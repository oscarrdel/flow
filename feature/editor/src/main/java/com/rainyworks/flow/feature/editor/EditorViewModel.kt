package com.rainyworks.flow.feature.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainyworks.flow.core.data.AutoSaver
import com.rainyworks.flow.core.data.ProjectRepository
import com.rainyworks.flow.core.engine.MediaProbe
import com.rainyworks.flow.core.engine.PreviewController
import com.rainyworks.flow.core.engine.ThumbnailProvider
import com.rainyworks.flow.core.model.EditHistory
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EditorEvent {
    data object PickMedia : EditorEvent
    data object Closed : EditorEvent
    data class OpenExport(val projectId: String) : EditorEvent
}

class EditorViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    val preview: PreviewController,
    private val probe: MediaProbe,
    private val thumbnails: ThumbnailProvider,
    private val strings: EditorStrings,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    private var history: EditHistory? = null
    private val autoSaver = AutoSaver(repository, viewModelScope)
    private var scrubbing = false
    private var coverKey: String? = null

    /** Project as it was when the current drag gesture started; drag updates are applied to it. */
    private var gestureBase: Project? = null
    private var gestureKey: String? = null

    init {
        viewModelScope.launch {
            val project = repository.load(projectId) ?: repository.create(strings.untitledProject)
            history = EditHistory(project)
            preview.setProject(project)
            _state.update { it.copy(project = project, loading = false) }
        }
        viewModelScope.launch {
            preview.positionUs.collect { position ->
                if (!scrubbing) _state.update { it.copy(playheadUs = position) }
            }
        }
        viewModelScope.launch {
            preview.isPlaying.collect { playing -> _state.update { it.copy(isPlaying = playing) } }
        }
    }

    fun onAction(action: EditorAction) {
        val current = history?.present
        when (action) {
            EditorAction.Close -> viewModelScope.launch {
                preview.pause()
                autoSaver.flush()
                _events.send(EditorEvent.Closed)
            }
            EditorAction.Export -> viewModelScope.launch {
                preview.pause()
                if (current == null || current.isEmpty) {
                    notify(strings.nothingToExport)
                } else {
                    autoSaver.flush()
                    _events.send(EditorEvent.OpenExport(projectId))
                }
            }
            EditorAction.Undo -> history?.let { setHistory(it.undo()) }
            EditorAction.Redo -> history?.let { setHistory(it.redo()) }
            EditorAction.TogglePlay -> if (_state.value.isPlaying) preview.pause() else preview.play()
            EditorAction.ToggleFullscreen -> _state.update { it.copy(fullscreenPreview = !it.fullscreenPreview) }
            EditorAction.AddMedia -> viewModelScope.launch { _events.send(EditorEvent.PickMedia) }
            EditorAction.AddMarker -> current?.let { apply(Edits.addMarker(it, _state.value.playheadUs)) }
            is EditorAction.Seek -> seek(action.timeUs, action.final)
            is EditorAction.SelectClip -> _state.update { it.copy(selectedClipId = action.clipId, activeSheet = null) }
            is EditorAction.Zoom -> _state.update {
                it.copy(
                    pixelsPerSecond = action.pixelsPerSecond.coerceIn(
                        EditorUiState.MIN_PIXELS_PER_SECOND,
                        EditorUiState.MAX_PIXELS_PER_SECOND,
                    ),
                )
            }
            is EditorAction.GlobalToolClicked -> onGlobalTool(action.tool)
            is EditorAction.ClipToolClicked -> onClipTool(action.tool)
            is EditorAction.TrimStart -> drag("trim-start-${action.clipId}", action.final) {
                Edits.trimStart(it, action.clipId, action.newStartUs)
            }
            is EditorAction.TrimEnd -> drag("trim-end-${action.clipId}", action.final) {
                Edits.trimEnd(it, action.clipId, action.newEndUs)
            }
            is EditorAction.MoveInMain -> current?.let { apply(Edits.moveInMain(it, action.clipId, action.toIndex)) }
            is EditorAction.MoveToLayer -> current?.let {
                apply(Edits.moveToLayer(it, action.clipId, action.trackId, action.startUs))
            }
            is EditorAction.SetSpeed -> drag("speed-${action.clipId}", action.final) {
                Edits.setSpeed(it, action.clipId, action.speed)
            }
            is EditorAction.SetVolume -> drag("volume-${action.clipId}", action.final) { project ->
                Edits.updateClip(project, action.clipId) { it.copy(volume = action.volume.coerceIn(0f, 2f)) }
            }
            is EditorAction.SetAspectRatio -> current?.let { apply(Edits.setAspectRatio(it, action.ratio)) }
            is EditorAction.SetBackground -> current?.let { apply(Edits.setBackground(it, action.background)) }
            is EditorAction.OpenSheet -> _state.update { it.copy(activeSheet = action.sheet) }
            EditorAction.DismissSheet -> _state.update { it.copy(activeSheet = null) }
            EditorAction.NoticeShown -> _state.update { it.copy(notice = null) }
        }
    }

    fun onMediaPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val media = uris.mapNotNull { probe.probe(it) }
            if (media.size < uris.size) notify(strings.someMediaFailed)
            var project = history?.present ?: return@launch
            val playhead = _state.value.playheadUs
            val visual = media.filter { it.type != MediaType.AUDIO }.map { ProjectFactory.clipFor(it) }
            if (visual.isNotEmpty()) {
                val atUs = if (project.mainTrack.clips.isEmpty()) null else playhead
                project = Edits.insertIntoMain(project, visual, atUs)
            }
            media.filter { it.type == MediaType.AUDIO }.forEach {
                project = Edits.addToLayer(project, ProjectFactory.clipFor(it).copy(startUs = playhead), TrackKind.AUDIO)
            }
            apply(project)
        }
    }

    /** Pauses playback and persists immediately; called when the app goes to the background. */
    fun onStop() {
        preview.pause()
        viewModelScope.launch { autoSaver.flush() }
    }

    private fun onGlobalTool(tool: GlobalTool) {
        when (tool) {
            GlobalTool.Edit -> onAction(EditorAction.AddMedia)
            GlobalTool.Format -> _state.update { it.copy(activeSheet = EditorSheet.AspectRatio) }
            GlobalTool.Canvas -> _state.update { it.copy(activeSheet = EditorSheet.Background) }
            else -> notify(strings.comingSoon)
        }
    }

    private fun onClipTool(tool: ClipTool) {
        val project = history?.present ?: return
        val clipId = _state.value.selectedClipId ?: return
        when (tool) {
            ClipTool.Split -> {
                val split = Edits.split(project, clipId, _state.value.playheadUs)
                if (split == project) notify(strings.cannotSplitHere) else apply(split)
            }
            ClipTool.Speed -> _state.update { it.copy(activeSheet = EditorSheet.Speed) }
            ClipTool.Volume -> _state.update { it.copy(activeSheet = EditorSheet.Volume) }
            ClipTool.Duplicate -> apply(Edits.duplicate(project, clipId))
            ClipTool.Delete -> {
                apply(Edits.delete(project, clipId))
                _state.update { it.copy(selectedClipId = null, activeSheet = null) }
            }
            else -> notify(strings.comingSoon)
        }
    }

    private fun seek(timeUs: Long, final: Boolean) {
        scrubbing = !final
        _state.update { it.copy(playheadUs = timeUs.coerceIn(0L, it.project?.durationUs ?: 0L)) }
        if (_state.value.isPlaying) preview.pause()
        preview.seekTo(timeUs)
    }

    private inline fun drag(key: String, final: Boolean, edit: (Project) -> Project) {
        var h = history ?: return
        if (gestureKey != key) {
            h = h.commitGroup()
            gestureKey = key
            gestureBase = h.present
        }
        val base = gestureBase ?: h.present
        var next = h.push(edit(base), mergeKey = key)
        if (final) {
            next = next.commitGroup()
            gestureBase = null
            gestureKey = null
        }
        setHistory(next)
    }

    private fun apply(project: Project) {
        val h = history ?: return
        gestureBase = null
        gestureKey = null
        setHistory(h.commitGroup().push(project))
    }

    private fun setHistory(next: EditHistory) {
        history = next
        val project = next.present
        _state.update { state ->
            state.copy(
                project = project,
                canUndo = next.canUndo,
                canRedo = next.canRedo,
                selectedClipId = state.selectedClipId?.takeIf { project.findClip(it) != null },
                playheadUs = state.playheadUs.coerceIn(0L, project.durationUs),
            )
        }
        preview.setProject(project)
        autoSaver.submit(project)
        refreshCover(project)
    }

    private fun refreshCover(project: Project) {
        val first = project.mainTrack.clips.firstOrNull()
        val media = first?.media ?: return
        val key = "${media.uri}@${first.trimInUs}"
        if (key == coverKey) return
        coverKey = key
        viewModelScope.launch {
            val bitmap = thumbnails.frameAt(media, first.trimInUs, COVER_HEIGHT_PX) ?: return@launch
            withContext(Dispatchers.IO) {
                repository.thumbnailFile(project.id).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
            }
            repository.onThumbnailUpdated(project.id)
        }
    }

    private fun notify(message: String) {
        _state.update { it.copy(notice = message) }
    }

    override fun onCleared() {
        preview.release()
    }

    private companion object {
        const val COVER_HEIGHT_PX = 360
    }
}

/** Localized strings the view model needs. */
data class EditorStrings(
    val untitledProject: String,
    val nothingToExport: String,
    val someMediaFailed: String,
    val cannotSplitHere: String,
    val comingSoon: String,
)

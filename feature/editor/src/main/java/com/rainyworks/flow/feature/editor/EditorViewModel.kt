package com.rainyworks.flow.feature.editor

import android.graphics.Bitmap
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainyworks.flow.core.data.AutoSaver
import com.rainyworks.flow.core.data.FontStore
import com.rainyworks.flow.core.data.ProjectRepository
import com.rainyworks.flow.core.engine.MediaProbe
import com.rainyworks.flow.core.engine.PreviewController
import com.rainyworks.flow.core.engine.ThumbnailProvider
import com.rainyworks.flow.core.model.ChromaKey
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.CubeLut
import com.rainyworks.flow.core.model.Srt
import com.rainyworks.flow.core.model.SubtitleEdits
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.EditHistory
import com.rainyworks.flow.core.model.EffectEdits
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.TrackKind
import com.rainyworks.flow.core.audio.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PickPurpose { Append, Overlay, Replace, Audio }

sealed interface EditorEvent {
    data class PickMedia(val purpose: PickPurpose) : EditorEvent
    data object Closed : EditorEvent
    data class OpenExport(val projectId: String) : EditorEvent

    /** Subtle tick, e.g. when playback crosses a marker. */
    data object Tick : EditorEvent
    data object RequestMicPermission : EditorEvent
    data class OpenDocument(val kind: DocumentKind) : EditorEvent
    data class CreateSrt(val suggestedName: String) : EditorEvent
}

enum class DocumentKind { Lut, Font, Srt }

class EditorViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    val preview: PreviewController,
    private val probe: MediaProbe,
    private val thumbnails: ThumbnailProvider,
    private val strings: EditorStrings,
    private val recorder: VoiceRecorder,
    private val fonts: FontStore,
    private val contentResolver: ContentResolver,
    /** Generates a reversed copy of a video; null when reversing is unavailable. */
    private val reverser: (suspend (MediaRef, (Float) -> Unit) -> MediaRef?)? = null,
    /** Preview resolution (short side in pixels) chosen in settings. */
    private val previewShortSide: Flow<Int>? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    private var history: EditHistory? = null
    private val autoSaver = AutoSaver(repository, viewModelScope)
    private var scrubbing = false
    private var coverKey: String? = null
    private var pickPurpose = PickPurpose.Append
    private var replaceTargetId: String? = null
    private var lutTargetId: String? = null

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
            var previous = 0L
            preview.positionUs.collect { position ->
                if (!scrubbing) {
                    val markers = _state.value.project?.markers.orEmpty()
                    if (_state.value.isPlaying && position > previous &&
                        markers.any { it.timeUs > previous && it.timeUs <= position }
                    ) {
                        _events.trySend(EditorEvent.Tick)
                    }
                    _state.update { it.copy(playheadUs = position) }
                }
                previous = position
            }
        }
        viewModelScope.launch {
            val list = fonts.list()
            _state.update { it.copy(customFonts = list) }
        }
        previewShortSide?.let { flow ->
            viewModelScope.launch { flow.collect { preview.setPreviewResolution(it) } }
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
            EditorAction.AddMedia -> pick(PickPurpose.Append)
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
            is EditorAction.SetTransform -> drag("transform-${action.clipId}", action.final) {
                MediaEdits.setTransformAt(it, action.clipId, action.transform, _state.value.playheadUs)
            }
            is EditorAction.SetOpacity -> drag("opacity-${action.clipId}", action.final) {
                MediaEdits.setOpacityAt(it, action.clipId, action.opacity, _state.value.playheadUs)
            }
            is EditorAction.SetAspectRatio -> current?.let { apply(Edits.setAspectRatio(it, action.ratio)) }
            is EditorAction.SetBackground -> current?.let { apply(Edits.setBackground(it, action.background)) }
            is EditorAction.OpenSheet -> _state.update { it.copy(activeSheet = action.sheet) }
            EditorAction.DismissSheet -> _state.update { it.copy(activeSheet = null) }
            EditorAction.NoticeShown -> _state.update { it.copy(notice = null) }
            is EditorAction.SetColorGrade -> drag("grade-${action.clipId}", action.final) {
                MediaEdits.setColorGrade(it, action.clipId, action.grade)
            }
            is EditorAction.ApplyGradeToAll -> current?.let {
                apply(MediaEdits.applyGradeToMain(it, action.clipId))
                notify(strings.gradeAppliedToAll)
            }
            is EditorAction.ImportLut -> {
                lutTargetId = action.clipId
                viewModelScope.launch { _events.send(EditorEvent.OpenDocument(DocumentKind.Lut)) }
            }
            EditorAction.AddAdjustmentLayer -> current?.let {
                val (project, id) = MediaEdits.addAdjustmentLayer(it, _state.value.playheadUs)
                apply(project)
                _state.update { s -> s.copy(selectedClipId = id, activeSheet = EditorSheet.Adjust) }
            }
            is EditorAction.AddText -> current?.let {
                val content = action.template ?: TextContent(text = strings.defaultText)
                val (project, id) = MediaEdits.addText(it, _state.value.playheadUs, content)
                apply(project)
                _state.update { s -> s.copy(selectedClipId = id, activeSheet = EditorSheet.Text) }
            }
            is EditorAction.SetTextContent -> drag("text-${action.clipId}", action.final) {
                MediaEdits.setTextContent(it, action.clipId, action.content)
            }
            EditorAction.ImportFont -> viewModelScope.launch { _events.send(EditorEvent.OpenDocument(DocumentKind.Font)) }
            is EditorAction.AddSubtitle -> current?.let { apply(SubtitleEdits.addCue(it, action.cue)) }
            is EditorAction.UpdateSubtitle -> current?.let {
                val updated = MediaEdits.updateSubtitle(it, action.clipId, action.cue)
                // Typing merges into one undo step until another edit happens.
                if (updated == it) notify(strings.subtitleOverlaps) else drag("subtitle-${action.clipId}", final = false) { updated }
            }
            is EditorAction.RemoveSubtitle -> current?.let { apply(Edits.delete(it, action.clipId)) }
            EditorAction.Back -> back()
            EditorAction.CancelTask -> {
                taskJob?.cancel()
                taskJob = null
                _state.update { it.copy(task = null) }
            }
            EditorAction.ImportSrt -> viewModelScope.launch { _events.send(EditorEvent.OpenDocument(DocumentKind.Srt)) }
            EditorAction.ExportSrt -> current?.let { project ->
                if (SubtitleEdits.cues(project).isEmpty()) {
                    notify(strings.noSubtitles)
                } else {
                    viewModelScope.launch { _events.send(EditorEvent.CreateSrt("${project.name}.srt")) }
                }
            }
            is EditorAction.SetChromaKey -> drag("chroma-${action.clipId}", action.final) { project ->
                Edits.updateClip(project, action.clipId) { it.copy(chromaKey = action.chroma) }
            }
            is EditorAction.PickChromaColor -> _state.update { it.copy(pickingChromaFor = action.clipId) }
            is EditorAction.ChromaColorPicked -> current?.let { project ->
                val clip = project.findClip(action.clipId)?.second
                val chroma = (clip?.chromaKey ?: ChromaKey()).copy(argb = action.argb)
                apply(Edits.updateClip(project, action.clipId) { it.copy(chromaKey = chroma) })
                _state.update { it.copy(pickingChromaFor = null) }
            }
            is EditorAction.SetBlendMode -> current?.let { project ->
                apply(Edits.updateClip(project, action.clipId) { it.copy(blendMode = action.mode) })
            }
            is EditorAction.SetMask -> drag("mask-${action.clipId}", action.final) { project ->
                Edits.updateClip(project, action.clipId) { it.copy(mask = action.mask) }
            }
            is EditorAction.ToggleKeyframe -> current?.let {
                val playhead = _state.value.playheadUs
                apply(
                    if (action.property in MediaEdits.MOTION_PROPERTIES) {
                        MediaEdits.toggleMotionKeyframe(it, action.clipId, playhead)
                    } else {
                        MediaEdits.toggleKeyframe(it, action.clipId, action.property, playhead)
                    },
                )
            }
            is EditorAction.SetKeyframeEasing -> current?.let {
                val clip = it.findClip(action.clipId)?.second ?: return@let
                val relative = action.timeUs - clip.startUs
                apply(
                    if (action.property in MediaEdits.MOTION_PROPERTIES) {
                        MediaEdits.setMotionEasing(it, action.clipId, relative, action.easing)
                    } else {
                        MediaEdits.setKeyframeEasing(it, action.clipId, action.property, relative, action.easing)
                    },
                )
            }
            is EditorAction.JumpToKeyframe -> current?.findClip(action.clipId)?.second?.let { clip ->
                val playhead = _state.value.playheadUs
                val times = MediaEdits.keyframeTimes(clip)
                val target = if (action.forward) times.firstOrNull { it > playhead + 1_000 } else times.lastOrNull { it < playhead - 1_000 }
                if (target != null) seek(target, final = true)
            }
            is EditorAction.SetAudioEffects -> drag("audiofx-${action.clipId}", action.final) { project ->
                Edits.updateClip(project, action.clipId) { it.copy(audioEffects = action.effects) }
            }
            is EditorAction.SetFades -> drag("fades-${action.clipId}", action.final) { project ->
                Edits.updateClip(project, action.clipId) {
                    val half = it.durationUs / 2
                    it.copy(fadeInUs = action.fadeInUs.coerceIn(0L, half), fadeOutUs = action.fadeOutUs.coerceIn(0L, half))
                }
            }
            is EditorAction.SetPreservePitch -> current?.let { project ->
                apply(Edits.updateClip(project, action.clipId) { it.copy(preservePitch = action.preserve) })
            }
            is EditorAction.SetMuted -> current?.let { project ->
                apply(Edits.updateClip(project, action.clipId) { it.copy(muted = action.muted) })
            }
            is EditorAction.AddEffect -> current?.let { project ->
                if (action.clipId == null) {
                    val added = EffectEdits.addEffectLayer(project, action.type, _state.value.playheadUs) ?: return@let
                    apply(added.first)
                    _state.update { it.copy(selectedClipId = added.second, activeSheet = EditorSheet.Effects) }
                } else {
                    apply(EffectEdits.addEffect(project, action.clipId, action.type).first)
                }
            }
            is EditorAction.SetEffectParam -> drag("effect-${action.effectId}-${action.key}", action.final) {
                EffectEdits.setEffectParam(it, action.clipId, action.effectId, action.key, action.value)
            }
            is EditorAction.RemoveEffect -> current?.let { apply(EffectEdits.removeEffect(it, action.clipId, action.effectId)) }
            is EditorAction.OpenTransition -> _state.update {
                it.copy(transitionClipId = action.clipId, selectedClipId = null, activeSheet = EditorSheet.Transition)
            }
            is EditorAction.SetTransition -> drag("transition-${action.clipId}", action.final) {
                EffectEdits.setTransition(it, action.clipId, action.transition)
            }
            is EditorAction.ApplyTransitionToAll -> current?.let {
                apply(EffectEdits.setTransitionEverywhere(it, action.transition))
                notify(strings.transitionAppliedToAll)
            }
            is EditorAction.SetEnterAnimation -> current?.let { apply(MediaEdits.setEnterAnimation(it, action.clipId, action.animation)) }
            is EditorAction.SetExitAnimation -> current?.let { apply(MediaEdits.setExitAnimation(it, action.clipId, action.animation)) }
            is EditorAction.SetAudioPanel -> _state.update { it.copy(audioPanelOpen = action.open, activeSheet = null) }
            EditorAction.AddAudioFile -> {
                _state.update { it.copy(audioPanelOpen = false) }
                pick(PickPurpose.Audio)
            }
            EditorAction.ExtractAudio -> extractAudio()
            EditorAction.OpenVoiceOver -> openVoiceOver()
            is EditorAction.SetVoiceOverMute -> _state.update { s -> s.copy(voiceOver = s.voiceOver?.copy(mutePreview = action.mute)) }
            EditorAction.StartVoiceOver -> startVoiceOver()
            EditorAction.StopVoiceOver -> stopVoiceOver(keep = true)
            EditorAction.CancelVoiceOver -> stopVoiceOver(keep = false)
        }
    }

    fun onMediaPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val purpose = pickPurpose
        val replaceId = replaceTargetId
        viewModelScope.launch {
            val media = uris.mapNotNull { probe.probe(it) }
            if (media.size < uris.size) notify(strings.someMediaFailed)
            if (media.isEmpty()) return@launch
            var project = history?.present ?: return@launch
            val playhead = _state.value.playheadUs
            when (purpose) {
                PickPurpose.Append -> {
                    val visual = media.filter { it.type != MediaType.AUDIO }.map { ProjectFactory.clipFor(it) }
                    if (visual.isNotEmpty()) {
                        val atUs = if (project.mainTrack.clips.isEmpty()) null else playhead
                        project = Edits.insertIntoMain(project, visual, atUs)
                    }
                    media.filter { it.type == MediaType.AUDIO }.forEach {
                        project = Edits.addToLayer(project, ProjectFactory.clipFor(it).copy(startUs = playhead), TrackKind.AUDIO)
                    }
                }
                PickPurpose.Overlay -> media.forEach { project = MediaEdits.addOverlay(project, it, playhead) }
                PickPurpose.Audio -> media.forEach { project = MediaEdits.addAudio(project, it, playhead) }
                PickPurpose.Replace -> {
                    val target = replaceId ?: return@launch
                    val replaced = MediaEdits.replaceMedia(project, target, media.first())
                    if (replaced == project) notify(strings.cannotReplace)
                    project = replaced
                }
            }
            apply(project)
        }
    }

    private fun pick(purpose: PickPurpose, replaceTarget: String? = null) {
        pickPurpose = purpose
        replaceTargetId = replaceTarget
        viewModelScope.launch { _events.send(EditorEvent.PickMedia(purpose)) }
    }

    private fun freezeFrame(clipId: String) {
        val project = history?.present ?: return
        val (_, clip) = project.findClip(clipId) ?: return
        val media = clip.media ?: return
        val playhead = _state.value.playheadUs
        if (clip.kind != ClipKind.VIDEO || !clip.contains(playhead)) {
            notify(strings.cannotFreezeHere)
            return
        }
        viewModelScope.launch {
            val sourceUs = clip.sourceTimeAt(playhead)
            val bitmap = thumbnails.frameAt(media, sourceUs, media.displayHeight.coerceAtLeast(MIN_FRAME_HEIGHT_PX))
            if (bitmap == null) {
                notify(strings.cannotFreezeHere)
                return@launch
            }
            val file = repository.newAssetFile(projectId, "png")
            withContext(Dispatchers.IO) {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val still = MediaRef(
                uri = Uri.fromFile(file).toString(),
                type = MediaType.IMAGE,
                width = bitmap.width,
                height = bitmap.height,
                displayName = media.displayName,
                sizeBytes = file.length(),
                mimeType = "image/png",
            )
            val current = history?.present ?: return@launch
            apply(MediaEdits.insertFreezeFrame(current, clipId, playhead, still, FREEZE_DURATION_US))
        }
    }

    /** Result of [EditorEvent.OpenDocument]. */
    fun onDocumentPicked(kind: DocumentKind, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            when (kind) {
                DocumentKind.Font -> {
                    val font = fonts.import(uri)
                    if (font == null) {
                        notify(strings.invalidFont)
                    } else {
                        _state.update { it.copy(customFonts = (it.customFonts + font).distinct()) }
                    }
                }
                DocumentKind.Lut -> importLut(uri)
                DocumentKind.Srt -> {
                    val text = readText(uri) ?: return@launch notify(strings.invalidSrt)
                    val cues = Srt.parse(text)
                    if (cues.isEmpty()) return@launch notify(strings.invalidSrt)
                    val project = history?.present ?: return@launch
                    apply(SubtitleEdits.import(project, cues))
                    notify(strings.subtitlesImported.format(cues.size))
                }
            }
        }
    }

    /** Result of [EditorEvent.CreateSrt]. */
    fun onSrtTarget(uri: Uri?) {
        val project = history?.present ?: return
        if (uri == null) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(SubtitleEdits.export(project).toByteArray()) } != null
                }.getOrDefault(false)
            }
            notify(if (ok) strings.subtitlesExported else strings.invalidSrt)
        }
    }

    private suspend fun importLut(uri: Uri) {
        val clipId = lutTargetId ?: return
        val text = readText(uri, MAX_LUT_BYTES) ?: return notify(strings.invalidLut)
        val error = runCatching { CubeLut.parse(text) }.exceptionOrNull()
        if (error != null) return notify(error.message ?: strings.invalidLut)
        val file = repository.newAssetFile(projectId, "cube")
        withContext(Dispatchers.IO) { file.writeText(text) }
        val project = history?.present ?: return
        val clip = project.findClip(clipId)?.second ?: return
        apply(MediaEdits.setColorGrade(project, clipId, clip.color.copy(lutUri = Uri.fromFile(file).toString(), lutIntensity = 1f)))
    }

    private suspend fun readText(uri: Uri, maxBytes: Int = MAX_TEXT_BYTES): String? = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(maxBytes + 1)
                if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private var taskJob: Job? = null

    private fun reverseClip(clipId: String) {
        val project = history?.present ?: return
        val clip = project.findClip(clipId)?.second ?: return
        val original = clip.originalMedia
        if (original != null) {
            apply(MediaEdits.swapReversed(project, clipId, original))
            notify(strings.reverseRemoved)
            return
        }
        val media = clip.media
        val reverse = reverser
        if (clip.kind != ClipKind.VIDEO || media == null || reverse == null) {
            notify(strings.cannotReverse)
            return
        }
        if (taskJob?.isActive == true) return
        preview.pause()
        taskJob = viewModelScope.launch {
            _state.update { it.copy(task = BackgroundTask(strings.reversing, 0f)) }
            val result = try {
                reverse(media) { p -> _state.update { s -> s.copy(task = s.task?.copy(progress = p.coerceIn(0f, 1f))) } }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                _state.update { it.copy(task = null) }
            }
            if (result == null) {
                notify(strings.reverseFailed)
            } else {
                val current = history?.present ?: return@launch
                if (current.findClip(clipId) != null) apply(MediaEdits.swapReversed(current, clipId, result))
            }
        }
    }

    private fun back() {
        val state = _state.value
        when {
            state.voiceOver != null -> stopVoiceOver(keep = false)
            state.pickingChromaFor != null -> _state.update { it.copy(pickingChromaFor = null) }
            state.fullscreenPreview -> _state.update { it.copy(fullscreenPreview = false) }
            state.activeSheet != null || state.audioPanelOpen -> _state.update { it.copy(activeSheet = null, audioPanelOpen = false) }
            state.selectedClipId != null -> _state.update { it.copy(selectedClipId = null) }
            else -> onAction(EditorAction.Close)
        }
    }

    private fun extractAudio() {
        val project = history?.present ?: return
        val clipId = _state.value.selectedClipId
        val result = clipId?.let { MediaEdits.extractAudio(project, it) }
        if (result == null || result == project) notify(strings.noAudioToExtract) else apply(result)
        _state.update { it.copy(audioPanelOpen = false) }
    }

    private fun openVoiceOver() {
        if (!recorder.hasPermission()) {
            viewModelScope.launch { _events.send(EditorEvent.RequestMicPermission) }
            return
        }
        preview.pause()
        _state.update { it.copy(audioPanelOpen = false, voiceOver = VoiceOverUiState(startUs = it.playheadUs)) }
    }

    /** Called after the user answers the microphone permission prompt. */
    fun onMicPermissionResult(granted: Boolean) {
        if (granted) openVoiceOver() else notify(strings.micPermissionDenied)
    }

    private var voiceJob: Job? = null

    private fun startVoiceOver() {
        val voice = _state.value.voiceOver ?: return
        if (voice.recording || voice.countdown != null) return
        voiceJob = viewModelScope.launch {
            for (n in VOICE_COUNTDOWN downTo 1) {
                _state.update { it.copy(voiceOver = it.voiceOver?.copy(countdown = n)) }
                delay(1_000)
            }
            val file = repository.newAssetFile(projectId, "wav")
            if (!recorder.start(viewModelScope, file)) {
                _state.update { it.copy(voiceOver = null) }
                notify(strings.micUnavailable)
                return@launch
            }
            val startUs = _state.value.playheadUs
            _state.update { it.copy(voiceOver = it.voiceOver?.copy(countdown = null, recording = true, startUs = startUs)) }
            if (voice.mutePreview) preview.player.volume = 0f
            preview.play()
            launch { recorder.level.collect { l -> _state.update { it.copy(voiceOver = it.voiceOver?.copy(level = l)) } } }
            launch { recorder.elapsedUs.collect { e -> _state.update { it.copy(voiceOver = it.voiceOver?.copy(elapsedUs = e)) } } }
        }
    }

    private fun stopVoiceOver(keep: Boolean) {
        val voice = _state.value.voiceOver ?: return
        voiceJob?.cancel()
        voiceJob = null
        viewModelScope.launch {
            preview.pause()
            preview.player.volume = 1f
            val recording = if (keep) recorder.stop() else recorder.cancel().let { null }
            _state.update { it.copy(voiceOver = null) }
            if (recording != null) {
                val media = MediaRef(
                    uri = Uri.fromFile(recording.file).toString(),
                    type = MediaType.AUDIO,
                    durationUs = recording.durationUs,
                    hasVideo = false,
                    hasAudio = true,
                    displayName = strings.voiceOverName,
                    sizeBytes = recording.file.length(),
                    mimeType = "audio/wav",
                )
                val project = history?.present ?: return@launch
                apply(MediaEdits.addAudio(project, media, voice.startUs))
                preview.seekTo(voice.startUs)
            } else if (keep) {
                notify(strings.recordingTooShort)
            }
        }
    }

    /** Pauses playback and persists immediately; called when the app goes to the background. */
    fun onStop() {
        if (_state.value.voiceOver != null) stopVoiceOver(keep = true)
        preview.pause()
        viewModelScope.launch { autoSaver.flush() }
    }

    private fun onGlobalTool(tool: GlobalTool) {
        when (tool) {
            GlobalTool.Edit -> pick(PickPurpose.Append)
            GlobalTool.Overlay -> pick(PickPurpose.Overlay)
            GlobalTool.Audio -> _state.update { it.copy(audioPanelOpen = true, activeSheet = null) }
            GlobalTool.Text -> onAction(EditorAction.AddText())
            GlobalTool.Effects -> _state.update { it.copy(selectedClipId = null, activeSheet = EditorSheet.Effects) }
            GlobalTool.Filters -> {
                val target = _state.value.selectedClipId ?: history?.present?.mainTrack?.clipAt(_state.value.playheadUs)?.id
                if (target == null) notify(strings.selectClipFirst)
                else _state.update { it.copy(selectedClipId = target, activeSheet = EditorSheet.Filters) }
            }
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
            ClipTool.Transform -> _state.update { it.copy(activeSheet = EditorSheet.Transform) }
            ClipTool.Opacity -> _state.update { it.copy(activeSheet = EditorSheet.Opacity) }
            ClipTool.Crop -> _state.update { it.copy(activeSheet = EditorSheet.Crop) }
            ClipTool.Replace -> pick(PickPurpose.Replace, clipId)
            ClipTool.Freeze -> freezeFrame(clipId)
            ClipTool.Filters -> _state.update { it.copy(activeSheet = EditorSheet.Filters) }
            ClipTool.Adjust -> _state.update { it.copy(activeSheet = EditorSheet.Adjust) }
            ClipTool.EditText -> _state.update { it.copy(activeSheet = EditorSheet.Text) }
            ClipTool.Chroma -> _state.update { it.copy(activeSheet = EditorSheet.Chroma) }
            ClipTool.Mask -> _state.update { it.copy(activeSheet = EditorSheet.Mask) }
            ClipTool.Blend -> _state.update { it.copy(activeSheet = EditorSheet.Blend) }
            ClipTool.Animation -> _state.update { it.copy(activeSheet = EditorSheet.Keyframes) }
            ClipTool.AudioEffects -> _state.update { it.copy(activeSheet = EditorSheet.AudioEffects) }
            ClipTool.Fades -> _state.update { it.copy(activeSheet = EditorSheet.Fades) }
            ClipTool.Extract -> extractAudio()
            ClipTool.Effects -> _state.update { it.copy(activeSheet = EditorSheet.Effects) }
            ClipTool.InOut -> _state.update { it.copy(activeSheet = EditorSheet.InOut) }
            ClipTool.Reverse -> reverseClip(clipId)
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
        const val MIN_FRAME_HEIGHT_PX = 720
        const val FREEZE_DURATION_US = 3_000_000L
        const val VOICE_COUNTDOWN = 3
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        const val MAX_LUT_BYTES = 64 * 1024 * 1024
    }
}

/** Localized strings the view model needs. */
data class EditorStrings(
    val untitledProject: String,
    val nothingToExport: String,
    val someMediaFailed: String,
    val cannotSplitHere: String,
    val comingSoon: String,
    val cannotReplace: String,
    val cannotFreezeHere: String,
    val noAudioToExtract: String,
    val micPermissionDenied: String,
    val micUnavailable: String,
    val recordingTooShort: String,
    val voiceOverName: String,
    val defaultText: String,
    val selectClipFirst: String,
    val gradeAppliedToAll: String,
    val invalidLut: String,
    val invalidFont: String,
    val invalidSrt: String,
    val noSubtitles: String,
    val subtitleOverlaps: String,
    /** Format string with one %d for the number of blocks. */
    val subtitlesImported: String,
    val subtitlesExported: String,
    val transitionAppliedToAll: String,
    val reversing: String,
    val reverseFailed: String,
    val reverseRemoved: String,
    val cannotReverse: String,
)

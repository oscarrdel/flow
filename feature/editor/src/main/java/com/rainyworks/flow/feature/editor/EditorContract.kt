package com.rainyworks.flow.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.CanvasBackground
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.Transform

data class EditorUiState(
    val project: Project? = null,
    val loading: Boolean = true,
    val playheadUs: Long = 0L,
    val isPlaying: Boolean = false,
    val selectedClipId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Timeline zoom: how many dp one second of media occupies. */
    val pixelsPerSecond: Float = DEFAULT_PIXELS_PER_SECOND,
    val activeSheet: EditorSheet? = null,
    val fullscreenPreview: Boolean = false,
    val fps: Int = 30,
    val audioPanelOpen: Boolean = false,
    val voiceOver: VoiceOverUiState? = null,
    /** Short transient message (snackbar). Cleared with [EditorAction.NoticeShown]. */
    val notice: String? = null,
) {
    companion object {
        const val DEFAULT_PIXELS_PER_SECOND = 64f
        const val MIN_PIXELS_PER_SECOND = 8f
        const val MAX_PIXELS_PER_SECOND = 1200f
    }
}

data class VoiceOverUiState(
    /** Seconds left before recording starts, or null once recording. */
    val countdown: Int? = null,
    val recording: Boolean = false,
    val level: Float = 0f,
    val elapsedUs: Long = 0L,
    val startUs: Long = 0L,
    val mutePreview: Boolean = true,
)

enum class EditorSheet { Speed, Volume, AspectRatio, Background, Transform, Crop, Opacity }

/** Global tools shown in the bottom bar when no clip is selected. */
enum class GlobalTool { Edit, Audio, Text, Overlay, Effects, Filters, Format, Canvas }

/** Tools shown in the bottom bar when a clip is selected. */
enum class ClipTool {
    Split, Speed, Volume, Transform, Opacity, Duplicate, Delete, Replace, Freeze, Reverse, Crop, Chroma, Mask, Animation,
}

sealed interface EditorAction {
    data object Close : EditorAction
    data object Export : EditorAction
    data object Undo : EditorAction
    data object Redo : EditorAction
    data object TogglePlay : EditorAction
    data object ToggleFullscreen : EditorAction
    data object AddMedia : EditorAction
    data object AddMarker : EditorAction

    /** Playhead moved by the user. [final] is true when the gesture ends. */
    data class Seek(val timeUs: Long, val final: Boolean) : EditorAction
    data class SelectClip(val clipId: String?) : EditorAction
    data class Zoom(val pixelsPerSecond: Float) : EditorAction

    data class GlobalToolClicked(val tool: GlobalTool) : EditorAction
    data class ClipToolClicked(val tool: ClipTool) : EditorAction

    /** Edge drags. Intermediate updates use final=false and merge into one undo step. */
    data class TrimStart(val clipId: String, val newStartUs: Long, val final: Boolean) : EditorAction
    data class TrimEnd(val clipId: String, val newEndUs: Long, val final: Boolean) : EditorAction
    data class MoveInMain(val clipId: String, val toIndex: Int) : EditorAction
    data class MoveToLayer(val clipId: String, val trackId: String, val startUs: Long) : EditorAction

    data class SetSpeed(val clipId: String, val speed: Float, val final: Boolean) : EditorAction
    data class SetVolume(val clipId: String, val volume: Float, val final: Boolean) : EditorAction
    /** Direct manipulation on the preview or exact values from the transform panel. */
    data class SetTransform(val clipId: String, val transform: Transform, val final: Boolean) : EditorAction
    data class SetOpacity(val clipId: String, val opacity: Float, val final: Boolean) : EditorAction
    data class SetAspectRatio(val ratio: AspectRatio) : EditorAction
    data class SetBackground(val background: CanvasBackground) : EditorAction

    data class OpenSheet(val sheet: EditorSheet) : EditorAction
    data object DismissSheet : EditorAction
    data object NoticeShown : EditorAction

    data class SetAudioPanel(val open: Boolean) : EditorAction
    data object AddAudioFile : EditorAction
    data object ExtractAudio : EditorAction
    data object OpenVoiceOver : EditorAction
    data class SetVoiceOverMute(val mute: Boolean) : EditorAction
    data object StartVoiceOver : EditorAction
    data object StopVoiceOver : EditorAction
    data object CancelVoiceOver : EditorAction
}

/** Supplies timeline visuals. Implementations cache aggressively; calls may suspend. */
interface TimelineMediaSource {
    suspend fun thumbnail(media: MediaRef, sourceTimeUs: Long, heightPx: Int): ImageBitmap?

    /** Normalized peaks (0..1), [bucketsPerSecond] values per second of source audio. */
    suspend fun waveform(media: MediaRef, bucketsPerSecond: Int): FloatArray?
}

package com.rainyworks.flow.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.CanvasBackground
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.AudioEffects
import com.rainyworks.flow.core.model.BlendMode
import com.rainyworks.flow.core.model.ChromaKey
import com.rainyworks.flow.core.model.ClipAnimation
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.Easing
import com.rainyworks.flow.core.model.Mask
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.SubtitleCue
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.Transition
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
    /** When set, the next tap on the preview samples a color for this clip's chroma key. */
    val pickingChromaFor: String? = null,
    /** A long-running job such as generating a reversed clip; shows a progress card. */
    val task: BackgroundTask? = null,
    /** Clip being dragged, drawn by the editor itself until the gesture ends. */
    val liveClipId: String? = null,
    /** Main-track clip whose incoming transition is being edited. */
    val transitionClipId: String? = null,
    /** Fonts the user imported, as (display name, font id/URI) pairs. */
    val customFonts: List<Pair<String, String>> = emptyList(),
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

data class BackgroundTask(val label: String, val progress: Float)

enum class EditorSheet {
    Speed, Volume, AspectRatio, Background, Transform, Crop, Opacity,
    Filters, Adjust, Curves, Hsl, Text, Subtitles,
    Chroma, Blend, Mask, Keyframes, AudioEffects, Fades, Effects, Transition, InOut, Stickers,
}

/** Global tools shown in the bottom bar when no clip is selected. */
enum class GlobalTool { Edit, Audio, Text, Overlay, Stickers, Effects, Filters, Format, Canvas }

/** Tools shown in the bottom bar when a clip is selected. */
enum class ClipTool {
    Split, Speed, Volume, Transform, Opacity, Duplicate, Delete, Replace, Freeze, Reverse, Crop, Chroma, Mask, Animation,
    Filters, Adjust, EditText, Blend, AudioEffects, Fades, Extract, Effects, InOut,
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
    /** Drop of a dragged layer clip: [trackId] is preferred, another track of its kind is used if busy. */
    /** Adds a shape or emoji sticker as an overlay at the playhead and selects it. */
    data class AddSticker(val spec: com.rainyworks.flow.core.model.StickerSpec) : EditorAction
    data class PlaceInLayers(val clipId: String, val trackId: String?, val startUs: Long) : EditorAction

    data class SetSpeed(val clipId: String, val speed: Float, val final: Boolean) : EditorAction
    /** A speed ramp for the clip, or null to go back to its constant speed. */
    data class SetSpeedCurve(val clipId: String, val curve: com.rainyworks.flow.core.model.SpeedCurve?) : EditorAction
    data class SetVolume(val clipId: String, val volume: Float, val final: Boolean) : EditorAction
    /** Direct manipulation on the preview or exact values from the transform panel. */
    data class SetTransform(val clipId: String, val transform: Transform, val final: Boolean) : EditorAction
    data class SetOpacity(val clipId: String, val opacity: Float, val final: Boolean) : EditorAction
    data class SetAspectRatio(val ratio: AspectRatio) : EditorAction
    data class SetBackground(val background: CanvasBackground) : EditorAction

    data class OpenSheet(val sheet: EditorSheet) : EditorAction
    data object DismissSheet : EditorAction
    data object NoticeShown : EditorAction

    /** Color: filter, manual adjustments, curves, HSL and LUT of a clip (or adjustment layer). */
    data class SetColorGrade(val clipId: String, val grade: ColorGrade, val final: Boolean) : EditorAction
    /** Copies the grade of [clipId] to every visual clip of the main track. */
    data class ApplyGradeToAll(val clipId: String) : EditorAction
    data class ImportLut(val clipId: String) : EditorAction
    /** Adds an adjustment layer at the playhead that grades everything below it. */
    data object AddAdjustmentLayer : EditorAction

    /** Creates a text clip at the playhead and opens the text editor. */
    data class AddText(val template: TextContent? = null) : EditorAction
    data class SetTextContent(val clipId: String, val content: TextContent, val final: Boolean) : EditorAction
    data object ImportFont : EditorAction

    data class AddSubtitle(val cue: SubtitleCue) : EditorAction
    data class UpdateSubtitle(val clipId: String, val cue: SubtitleCue) : EditorAction
    data class RemoveSubtitle(val clipId: String) : EditorAction

    data object CancelTask : EditorAction

    /** System back: closes the open panel, then the selection, then the editor. */
    data object Back : EditorAction
    data object ImportSrt : EditorAction
    data object ExportSrt : EditorAction

    /** Null removes the chroma key. */
    data class SetChromaKey(val clipId: String, val chroma: ChromaKey?, val final: Boolean) : EditorAction
    /** Starts picking the key color by tapping the preview; the result arrives as SetChromaKey. */
    data class PickChromaColor(val clipId: String) : EditorAction
    data class ChromaColorPicked(val clipId: String, val argb: Long) : EditorAction
    data class SetBlendMode(val clipId: String, val mode: BlendMode) : EditorAction
    /** Null removes the mask. */
    data class SetMask(val clipId: String, val mask: Mask?, val final: Boolean) : EditorAction

    /** Adds or replaces a keyframe for [property] at the playhead with the clip's current value. */
    data class ToggleKeyframe(val clipId: String, val property: String) : EditorAction
    data class SetKeyframeEasing(val clipId: String, val property: String, val timeUs: Long, val easing: Easing) : EditorAction
    /** Jumps the playhead to the previous/next keyframe of the clip. */
    data class JumpToKeyframe(val clipId: String, val forward: Boolean) : EditorAction

    data class SetAudioEffects(val clipId: String, val effects: AudioEffects, val final: Boolean) : EditorAction
    data class SetFades(val clipId: String, val fadeInUs: Long, val fadeOutUs: Long, val final: Boolean) : EditorAction
    data class SetPreservePitch(val clipId: String, val preserve: Boolean) : EditorAction
    data class SetMuted(val clipId: String, val muted: Boolean) : EditorAction

    /** Adds an effect to a clip; with clipId = null adds an effect layer at the playhead. */
    data class AddEffect(val clipId: String?, val type: String) : EditorAction
    data class SetEffectParam(val clipId: String, val effectId: String, val key: String, val value: Float, val final: Boolean) : EditorAction
    /** Adds or removes a keyframe for an effect parameter at the playhead. */
    data class ToggleEffectKeyframe(val clipId: String, val effectId: String, val key: String) : EditorAction
    data class RemoveEffect(val clipId: String, val effectId: String) : EditorAction

    /** Opens the transition panel for the cut before [clipId] (a main-track clip that is not the first). */
    data class OpenTransition(val clipId: String) : EditorAction
    data class SetTransition(val clipId: String, val transition: Transition?, val final: Boolean) : EditorAction
    data class ApplyTransitionToAll(val transition: Transition?) : EditorAction

    /** Entrance/exit animation of a visual clip; null removes it. */
    data class SetEnterAnimation(val clipId: String, val animation: ClipAnimation?) : EditorAction
    data class SetExitAnimation(val clipId: String, val animation: ClipAnimation?) : EditorAction

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

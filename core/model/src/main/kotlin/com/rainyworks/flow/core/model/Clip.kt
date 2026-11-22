package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

enum class ClipKind { VIDEO, IMAGE, AUDIO, TEXT, ADJUSTMENT, EFFECT }

/**
 * A placed piece of media on a track.
 *
 * Source time is the time inside the media file, timeline time is the time inside the
 * project. For media without an intrinsic duration (images, text, adjustment layers)
 * [trimInUs] is 0 and [trimOutUs] is the displayed duration.
 */
@Serializable
data class Clip(
    val id: String,
    val kind: ClipKind,
    val media: MediaRef? = null,
    val startUs: Long = 0L,
    val trimInUs: Long = 0L,
    val trimOutUs: Long,
    val speed: Float = 1f,
    val reversed: Boolean = false,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    /** Background music: lowers itself while other clips with sound (voice, video) play. */
    val duckUnderVoice: Boolean = false,
    val opacity: Float = 1f,
    val transform: Transform = Transform(),
    /** Variable speed; when set it replaces [speed] for timing. */
    val speedCurve: SpeedCurve? = null,
    /** Keeps the original audio pitch when the speed changes. */
    val preservePitch: Boolean = true,
    /** Animated property values keyed by [AnimatedProperty] ids. */
    val keyframes: Map<String, List<Keyframe>> = emptyMap(),
    val blendMode: BlendMode = BlendMode.NORMAL,
    val chromaKey: ChromaKey? = null,
    val mask: Mask? = null,
    val color: ColorGrade = ColorGrade(),
    val effects: List<EffectInstance> = emptyList(),
    /** Main track only: transition from the previous clip into this one. */
    val transitionIn: Transition? = null,
    /** TEXT clips only. */
    val text: TextContent? = null,
    val audioEffects: AudioEffects = AudioEffects(),
    /** Source frame shown for the whole clip (freeze frame), in source time. */
    val freezeAtUs: Long? = null,
    /** Entrance animation of a visual clip (photo, video, overlay, text, subtitle). */
    val enterAnimation: ClipAnimation? = null,
    /** Exit animation of a visual clip. */
    val exitAnimation: ClipAnimation? = null,
    /**
     * Set when [media] is a reversed copy generated from this original. Kept so reversing
     * again restores the original instead of reversing the copy.
     */
    val originalMedia: MediaRef? = null,
) {
    init {
        require(trimOutUs > trimInUs) { "Clip $id has a non-positive source range" }
        require(speed > 0f) { "Clip $id speed must be positive" }
    }

    val sourceDurationUs: Long get() = trimOutUs - trimInUs
    internal val speedRamp by lazy(LazyThreadSafetyMode.PUBLICATION) {
        speedCurve?.let { SpeedRamp.mapping(sourceDurationUs, it) }
    }
    val durationUs: Long
        get() = speedRamp?.durationUs ?: (sourceDurationUs / speed.toDouble()).roundToLong().coerceAtLeast(1L)
    val endUs: Long get() = startUs + durationUs

    /** True when the clip can be extended beyond its current source range indefinitely. */
    val hasUnboundedSource: Boolean
        get() = kind == ClipKind.IMAGE || kind == ClipKind.TEXT ||
            kind == ClipKind.ADJUSTMENT || kind == ClipKind.EFFECT

    /** Maps a timeline time inside this clip to the corresponding time inside the source. */
    fun sourceTimeAt(timelineUs: Long): Long {
        if (timelineUs <= startUs) return if (reversed) trimOutUs else trimInUs
        val timelineOffset = timelineUs - startUs
        if (timelineOffset < 0L || timelineOffset >= durationUs) return if (reversed) trimInUs else trimOutUs
        speedRamp?.let { return trimInUs + it.sourceOffsetAt(timelineOffset, reversed) }
        val offset = (timelineOffset * speed.toDouble()).roundToLong()
        val sourceUs = if (reversed) trimOutUs - offset else trimInUs + offset
        return sourceUs.coerceIn(trimInUs, trimOutUs)
    }

    /**
     * Where a time of the source file plays on the timeline (following trim, speed, speed
     * ramp and reverse), or null when that part of the source is not in the clip.
     */
    fun timelineTimeOfSource(sourceUs: Long): Long? {
        if (sourceUs !in trimInUs..trimOutUs) return null
        val played = if (reversed) trimOutUs - sourceUs else sourceUs - trimInUs
        val offset = speedRamp?.timelineOffsetAt(played)
            ?: (played / speed.toDouble()).toLong()
        return (startUs + offset).takeIf { it in startUs..endUs }
    }

    fun contains(timelineUs: Long): Boolean = timelineUs in startUs until endUs
}

@Serializable
data class Transform(
    /** Center offset relative to the canvas, in canvas widths/heights (0 = centered). */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val crop: CropRect = CropRect.Full,
)

/** Normalized crop rectangle inside the source frame (0..1). */
@Serializable
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }

    companion object {
        val Full = CropRect(0f, 0f, 1f, 1f)
    }
}

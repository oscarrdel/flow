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
    val opacity: Float = 1f,
    val transform: Transform = Transform(),
) {
    init {
        require(trimOutUs > trimInUs) { "Clip $id has a non-positive source range" }
        require(speed > 0f) { "Clip $id speed must be positive" }
    }

    val sourceDurationUs: Long get() = trimOutUs - trimInUs
    val durationUs: Long get() = (sourceDurationUs / speed.toDouble()).roundToLong().coerceAtLeast(1L)
    val endUs: Long get() = startUs + durationUs

    /** True when the clip can be extended beyond its current source range indefinitely. */
    val hasUnboundedSource: Boolean
        get() = kind == ClipKind.IMAGE || kind == ClipKind.TEXT ||
            kind == ClipKind.ADJUSTMENT || kind == ClipKind.EFFECT

    /** Maps a timeline time inside this clip to the corresponding time inside the source. */
    fun sourceTimeAt(timelineUs: Long): Long {
        val offset = ((timelineUs - startUs).coerceIn(0L, durationUs) * speed.toDouble()).roundToLong()
        return if (reversed) trimOutUs - offset else trimInUs + offset
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

package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable

enum class MediaType { VIDEO, IMAGE, GIF, AUDIO }

/** Reference to an original file on the device (content:// or file:// URI). */
@Serializable
data class MediaRef(
    val uri: String,
    val type: MediaType,
    val durationUs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val hasVideo: Boolean = type != MediaType.AUDIO,
    val hasAudio: Boolean = false,
    val displayName: String = "",
    val sizeBytes: Long = 0L,
    val mimeType: String = "",
    /**
     * Animated GIFs: a video copy with the color in the left half and the transparency in
     * the right half, which the engine plays instead of the still first frame.
     */
    val animationUri: String? = null,
    /** Length of [animationUri] (whole loops of the animation). */
    val animationDurationUs: Long = 0L,
) {
    /** Width and height as displayed, after applying the container rotation. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width
}

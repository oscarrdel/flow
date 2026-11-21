package com.rainyworks.flow.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Canvas(
    val aspectRatio: AspectRatio = AspectRatio.R9_16,
    /** When true, the aspect ratio follows the first clip imported into the main track. */
    val followFirstClip: Boolean = true,
    val background: CanvasBackground = CanvasBackground.Solid(0xFF000000),
)

@Serializable
data class AspectRatio(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Aspect ratio sides must be positive" }
    }

    val value: Float get() = width.toFloat() / height.toFloat()
    val label: String get() = "$width:$height"

    companion object {
        val R9_16 = AspectRatio(9, 16)
        val R16_9 = AspectRatio(16, 9)
        val R1_1 = AspectRatio(1, 1)
        val R4_5 = AspectRatio(4, 5)
        val R21_9 = AspectRatio(21, 9)
        val Presets = listOf(R9_16, R16_9, R1_1, R4_5, R21_9)

        fun fromSize(width: Int, height: Int): AspectRatio {
            require(width > 0 && height > 0)
            val g = gcd(width, height)
            return AspectRatio(width / g, height / g)
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

@Serializable
sealed interface CanvasBackground {
    @Serializable
    @SerialName("solid")
    data class Solid(val argb: Long) : CanvasBackground

    @Serializable
    @SerialName("gradient")
    data class Gradient(val startArgb: Long, val endArgb: Long, val angleDegrees: Float = 90f) : CanvasBackground

    @Serializable
    @SerialName("image")
    data class Image(val uri: String) : CanvasBackground

    /** Blurred, scaled-to-fill copy of the clip currently on screen. */
    @Serializable
    @SerialName("blur")
    data class Blur(val radius: Float = 0.5f) : CanvasBackground
}

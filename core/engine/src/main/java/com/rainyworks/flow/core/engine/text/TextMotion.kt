package com.rainyworks.flow.core.engine.text

import com.rainyworks.flow.core.model.TextAnimationType
import com.rainyworks.flow.core.model.TextAnimationType.*
import com.rainyworks.flow.core.model.TextContent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class TextMotion(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val reveal: Float = 1f,
    val cursor: Boolean = false,
) {
    fun visibleCharacters(total: Int): Int = (total * reveal.toDouble()).toInt().coerceIn(0, total)

    operator fun times(other: TextMotion) = TextMotion(
        alpha * other.alpha, scale * other.scale, dx + other.dx, dy + other.dy,
        minOf(reveal, other.reveal), cursor || other.cursor,
    )
}

internal object TextAnimations {
    private val rest = TextMotion()

    // progress runs from 0 to 1 in playback order for both entrance and exit.
    fun transition(type: TextAnimationType, progress: Float, exiting: Boolean = false): TextMotion {
        val p = progress.coerceIn(0f, 1f)
        val visible = if (exiting) 1f - p else p
        val distance = (1f - visible) * if (exiting) -0.2f else 0.2f
        return when (type) {
            NONE -> rest
            TYPEWRITER -> TextMotion(reveal = visible)
            FADE -> TextMotion(alpha = visible)
            BOUNCE -> {
                val t = visible - 1f
                TextMotion(scale = 1f + t * t * (2.70158f * t + 1.70158f))
            }
            SLIDE_UP -> TextMotion(alpha = visible, dy = distance)
            SLIDE_DOWN -> TextMotion(alpha = visible, dy = -distance)
            SLIDE_LEFT -> TextMotion(alpha = visible, dx = distance)
            SLIDE_RIGHT -> TextMotion(alpha = visible, dx = -distance)
            SCALE -> TextMotion(scale = visible)
        }
    }

    fun loop(type: TextAnimationType, progress: Float): TextMotion {
        val phase = progress.toDouble() - kotlin.math.floor(progress.toDouble())
        val angle = 2.0 * PI * phase
        val wave = sin(angle).toFloat()
        val pulse = ((1.0 - cos(angle)) * 0.5).toFloat()
        return when (type) {
            NONE -> rest
            TYPEWRITER -> TextMotion(cursor = phase < 0.5)
            FADE -> TextMotion(alpha = 1f - 0.5f * pulse)
            BOUNCE -> TextMotion(scale = 1f + 0.12f * pulse * pulse * pulse)
            SLIDE_UP -> TextMotion(dy = -0.03f * wave)
            SLIDE_DOWN -> TextMotion(dy = 0.03f * wave)
            SLIDE_LEFT -> TextMotion(dx = -0.03f * wave)
            SLIDE_RIGHT -> TextMotion(dx = 0.03f * wave)
            SCALE -> TextMotion(scale = 1f + 0.06f * pulse)
        }
    }

    fun at(content: TextContent, elapsedUs: Long, durationUs: Long): TextMotion {
        var motion = rest
        val enter = content.enter
        if (enter.type != NONE && elapsedUs < enter.durationUs && enter.durationUs > 0) {
            motion = transition(enter.type, (elapsedUs.toDouble() / enter.durationUs).toFloat())
        }
        val exit = content.exit
        val remainingUs = durationUs - elapsedUs
        if (exit.type != NONE && remainingUs < exit.durationUs && exit.durationUs > 0) {
            motion *= transition(exit.type, (1.0 - remainingUs.toDouble() / exit.durationUs).toFloat(), exiting = true)
        }
        val loop = content.loop
        if (loop.type != NONE && loop.durationUs > 0) {
            motion *= loop(loop.type, ((elapsedUs % loop.durationUs).toDouble() / loop.durationUs).toFloat())
        }
        return motion
    }
}

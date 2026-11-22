package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

enum class ClipAnimationType {
    FADE,
    /** Grows from smaller while fading in (or shrinks away on exit). */
    ZOOM_IN,
    /** Shrinks from larger while fading in (or grows away on exit). */
    ZOOM_OUT,
    SLIDE_UP,
    SLIDE_DOWN,
    SLIDE_LEFT,
    SLIDE_RIGHT,
    SPIN,
    POP,
    BLUR_IN,
}

/** An entrance or exit animation that plays at the start or end of a visual clip. */
@Serializable
data class ClipAnimation(val type: ClipAnimationType, val durationUs: Long = 500_000L)

/** Multipliers and offsets an entrance/exit animation applies on top of the clip transform. */
data class MotionSample(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
) {
    companion object {
        val Identity = MotionSample()
    }
}

object ClipAnimations {
    const val MIN_DURATION_US = 100_000L
    const val MAX_DURATION_US = 3_000_000L

    /**
     * The combined effect of the clip's entrance and exit animations at [timelineUs].
     * Outside both windows this is the identity.
     */
    fun sample(clip: Clip, timelineUs: Long): MotionSample {
        val local = timelineUs - clip.startUs
        val duration = clip.durationUs
        var result = MotionSample.Identity
        clip.enterAnimation?.let { anim ->
            val window = effectiveDuration(anim, duration)
            if (window > 0 && local < window) {
                result = combine(result, shape(anim.type, (local.toFloat() / window).coerceIn(0f, 1f), entering = true))
            }
        }
        clip.exitAnimation?.let { anim ->
            val window = effectiveDuration(anim, duration)
            val remaining = duration - local
            if (window > 0 && remaining < window) {
                result = combine(result, shape(anim.type, (remaining.toFloat() / window).coerceIn(0f, 1f), entering = false))
            }
        }
        return result
    }

    /** Entrance and exit together never take more than the whole clip. */
    private fun effectiveDuration(anim: ClipAnimation, clipDurationUs: Long): Long =
        min(anim.durationUs.coerceIn(MIN_DURATION_US, MAX_DURATION_US), clipDurationUs / 2)

    /**
     * [progress] goes 0 → 1 as the element becomes fully visible: during an entrance it is the
     * elapsed fraction; during an exit it is the remaining fraction, so exits mirror entrances.
     */
    fun shape(type: ClipAnimationType, progress: Float, entering: Boolean): MotionSample {
        val p = progress.coerceIn(0f, 1f)
        val e = easeOutCubic(p)
        val hidden = 1f - e
        return when (type) {
            ClipAnimationType.FADE -> MotionSample(opacity = e)
            ClipAnimationType.ZOOM_IN -> MotionSample(scale = 0.6f + 0.4f * e, opacity = e)
            ClipAnimationType.ZOOM_OUT -> MotionSample(scale = 1f + 0.5f * hidden, opacity = e)
            // Slides travel a quarter of the canvas; exits leave in the same direction they name.
            ClipAnimationType.SLIDE_UP -> MotionSample(offsetY = (if (entering) 0.25f else -0.25f) * hidden, opacity = e)
            ClipAnimationType.SLIDE_DOWN -> MotionSample(offsetY = (if (entering) -0.25f else 0.25f) * hidden, opacity = e)
            ClipAnimationType.SLIDE_LEFT -> MotionSample(offsetX = (if (entering) 0.25f else -0.25f) * hidden, opacity = e)
            ClipAnimationType.SLIDE_RIGHT -> MotionSample(offsetX = (if (entering) -0.25f else 0.25f) * hidden, opacity = e)
            ClipAnimationType.SPIN -> MotionSample(rotationDegrees = (if (entering) -180f else 180f) * hidden, scale = 0.5f + 0.5f * e, opacity = e)
            ClipAnimationType.POP -> MotionSample(scale = easeOutBack(p), opacity = min(1f, p * 3f))
            ClipAnimationType.BLUR_IN -> MotionSample(scale = 1f + 0.08f * hidden, opacity = e)
        }
    }

    private fun combine(a: MotionSample, b: MotionSample) = MotionSample(
        offsetX = a.offsetX + b.offsetX,
        offsetY = a.offsetY + b.offsetY,
        scale = a.scale * b.scale,
        rotationDegrees = a.rotationDegrees + b.rotationDegrees,
        opacity = a.opacity * b.opacity,
    )

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = t - 1f
        return max(0f, 1f + c3 * u * u * u + c1 * u * u)
    }

    /** Clip transform with keyframes and entrance/exit animations applied, as rendered. */
    fun renderedTransform(clip: Clip, timelineUs: Long): Pair<Transform, Float> {
        val motion = sample(clip, timelineUs)
        val t = clip.transform
        val transform = t.copy(
            offsetX = clip.animatedValue(AnimatedProperty.OFFSET_X, timelineUs, t.offsetX) + motion.offsetX,
            offsetY = clip.animatedValue(AnimatedProperty.OFFSET_Y, timelineUs, t.offsetY) + motion.offsetY,
            scale = clip.animatedValue(AnimatedProperty.SCALE, timelineUs, t.scale) * motion.scale,
            rotationDegrees = clip.animatedValue(AnimatedProperty.ROTATION, timelineUs, t.rotationDegrees) + motion.rotationDegrees,
        )
        val opacity = (clip.animatedValue(AnimatedProperty.OPACITY, timelineUs, clip.opacity) * motion.opacity).coerceIn(0f, 1f)
        return transform to opacity
    }

}

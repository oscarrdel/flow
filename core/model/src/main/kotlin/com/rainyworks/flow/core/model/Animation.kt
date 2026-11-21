package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

enum class Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, BEZIER, HOLD }

/** Control points of a CSS-style cubic bezier from (0,0) to (1,1). */
@Serializable
data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/**
 * A value at a point in time. [timeUs] is relative to the clip's start on the timeline.
 * [easing] shapes the curve from this keyframe to the next one.
 */
@Serializable
data class Keyframe(
    val timeUs: Long,
    val value: Float,
    val easing: Easing = Easing.LINEAR,
    val bezier: CubicBezier? = null,
)

object Animator {
    private const val FRAME_US = 33_333uL
    private const val BEZIER_PRECISION = 1e-7
    private val easeIn = CubicBezier(0.42f, 0f, 1f, 1f)
    private val easeOut = CubicBezier(0f, 0f, 0.58f, 1f)
    private val easeInOut = CubicBezier(0.42f, 0f, 0.58f, 1f)

    fun valueAt(keyframes: List<Keyframe>, timeUs: Long, default: Float): Float {
        if (keyframes.isEmpty()) return default
        val ordered = keyframes.sortedBy { it.timeUs }
        if (timeUs < ordered.first().timeUs) return ordered.first().value
        val rightIndex = ordered.indexOfFirst { it.timeUs > timeUs }
        if (rightIndex == -1) return ordered.last().value

        val left = ordered[rightIndex - 1]
        if (timeUs == left.timeUs || left.easing == Easing.HOLD) return left.value
        val right = ordered[rightIndex]
        val progress = timeUs.distanceFrom(left.timeUs).toDouble() / right.timeUs.distanceFrom(left.timeUs).toDouble()
        val bezier = when (left.easing) {
            Easing.EASE_IN -> easeIn
            Easing.EASE_OUT -> easeOut
            Easing.EASE_IN_OUT -> easeInOut
            Easing.BEZIER -> left.bezier
            Easing.LINEAR, Easing.HOLD -> null
        }
        val eased = bezier?.valueAt(progress) ?: progress
        return (left.value + (right.value.toDouble() - left.value) * eased).toFloat()
    }

    /** Inserts in time order or replaces the nearest keyframe strictly within one frame. */
    fun setKeyframe(list: List<Keyframe>, keyframe: Keyframe): List<Keyframe> {
        val index = nearestIndex(list, keyframe.timeUs)
        val updated = list.toMutableList()
        if (index >= 0 && list[index].timeUs.distanceFrom(keyframe.timeUs) < FRAME_US) {
            updated[index] = keyframe
        } else {
            updated.add(keyframe)
        }
        return updated.sortedBy { it.timeUs }
    }

    /** Removes the nearest keyframe within the inclusive tolerance. */
    fun removeKeyframeNear(list: List<Keyframe>, timeUs: Long, toleranceUs: Long): List<Keyframe> {
        if (toleranceUs < 0L) return list
        val index = nearestIndex(list, timeUs)
        if (index < 0 || list[index].timeUs.distanceFrom(timeUs) > toleranceUs.toULong()) return list
        return list.toMutableList().apply { removeAt(index) }.sortedBy { it.timeUs }
    }

    private fun nearestIndex(list: List<Keyframe>, timeUs: Long): Int =
        list.indices.minWithOrNull(compareBy({ list[it].timeUs.distanceFrom(timeUs) }, { list[it].timeUs })) ?: -1

    private fun Long.distanceFrom(other: Long): ULong =
        if (this >= other) toULong() - other.toULong() else other.toULong() - toULong()

    private fun CubicBezier.valueAt(x: Double): Double {
        val firstX = x1.coerceIn(0f, 1f).toDouble()
        val secondX = x2.coerceIn(0f, 1f).toDouble()
        if (firstX.isNaN() || secondX.isNaN() || !y1.isFinite() || !y2.isFinite()) return x

        var parameter = x
        for (iteration in 0 until 8) {
            val remaining = 1.0 - parameter
            val slope = 3.0 * (firstX * remaining * remaining +
                2.0 * (secondX - firstX) * remaining * parameter + (1.0 - secondX) * parameter * parameter)
            val error = coordinate(parameter, firstX, secondX) - x
            if (error == 0.0) return coordinate(parameter, y1.toDouble(), y2.toDouble())
            if (slope < BEZIER_PRECISION) break
            val next = parameter - error / slope
            if (next !in 0.0..1.0) break
            if (abs(next - parameter) <= BEZIER_PRECISION) return coordinate(next, y1.toDouble(), y2.toDouble())
            parameter = next
        }

        var lower = 0.0
        var upper = 1.0
        while (upper - lower > BEZIER_PRECISION) {
            val middle = (lower + upper) / 2.0
            if (coordinate(middle, firstX, secondX) < x) lower = middle else upper = middle
        }
        return coordinate((lower + upper) / 2.0, y1.toDouble(), y2.toDouble())
    }

    private fun coordinate(t: Double, first: Double, second: Double): Double {
        val remaining = 1.0 - t
        return 3.0 * remaining * t * (remaining * first + t * second) + t * t * t
    }
}

fun Clip.animatedValue(property: String, timelineUs: Long, default: Float): Float {
    val frames = keyframes[property] ?: return default
    val relativeUs = when {
        startUs > 0L && timelineUs < Long.MIN_VALUE + startUs -> Long.MIN_VALUE
        startUs < 0L && timelineUs > Long.MAX_VALUE + startUs -> Long.MAX_VALUE
        else -> timelineUs - startUs
    }
    return Animator.valueAt(frames, relativeUs, default)
}

/** Well-known animatable property keys. Effect parameters use "effect.<effectId>.<param>". */
object AnimatedProperty {
    const val OFFSET_X = "transform.offsetX"
    const val OFFSET_Y = "transform.offsetY"
    const val SCALE = "transform.scale"
    const val ROTATION = "transform.rotation"
    const val OPACITY = "opacity"
    const val VOLUME = "volume"
    const val MASK_OFFSET_X = "mask.offsetX"
    const val MASK_OFFSET_Y = "mask.offsetY"
    const val MASK_SCALE = "mask.scale"
    const val MASK_ROTATION = "mask.rotation"
    const val MASK_FEATHER = "mask.feather"

    fun effectParam(effectId: String, param: String) = "effect.$effectId.$param"
}

/** One point of a speed ramp. [position] is 0..1 along the clip's source range. */
@Serializable
data class SpeedPoint(val position: Float, val speed: Float)

@Serializable
data class SpeedCurve(val points: List<SpeedPoint>, val presetId: String? = null)

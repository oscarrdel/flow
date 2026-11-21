package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatorTest {
    @Test
    fun emptySingleAndUnsortedKeyframesUseTheExpectedEndpoints() {
        val first = Keyframe(100L, 2f)
        val middle = Keyframe(200L, 4f)
        val last = Keyframe(400L, 10f)
        val frames = listOf(last, first, middle)

        assertEquals(99f, Animator.valueAt(emptyList(), 0L, 99f), 0f)
        assertEquals(2f, Animator.valueAt(listOf(first), Long.MAX_VALUE, 99f), 0f)
        assertEquals(2f, Animator.valueAt(frames, Long.MIN_VALUE, 99f), 0f)
        assertEquals(2f, Animator.valueAt(frames, 100L, 99f), 0f)
        assertEquals(3f, Animator.valueAt(frames, 150L, 99f), 0f)
        assertEquals(4f, Animator.valueAt(frames, 200L, 99f), 0f)
        assertEquals(7f, Animator.valueAt(frames, 300L, 99f), 0f)
        assertEquals(10f, Animator.valueAt(frames, 400L, 99f), 0f)
        assertEquals(10f, Animator.valueAt(frames, Long.MAX_VALUE, 99f), 0f)
        assertEquals(listOf(last, first, middle), frames)
    }

    @Test
    fun interpolationWorksAcrossTheLongTimeRange() {
        val frames = listOf(Keyframe(Long.MIN_VALUE, 0f), Keyframe(Long.MAX_VALUE, 1f))
        assertEquals(0.5f, Animator.valueAt(frames, 0L, 99f), 1e-5f)
    }

    @Test
    fun eachSegmentUsesTheLeftEasingAndHoldSwitchesAtTheNextKeyframe() {
        val frames = listOf(
            Keyframe(0L, 0f, Easing.HOLD),
            Keyframe(100L, 10f, Easing.LINEAR),
            Keyframe(200L, 20f, Easing.HOLD),
        )

        assertEquals(0f, Animator.valueAt(frames, 99L, -1f), 0f)
        assertEquals(10f, Animator.valueAt(frames, 100L, -1f), 0f)
        assertEquals(15f, Animator.valueAt(frames, 150L, -1f), 0f)
        assertEquals(20f, Animator.valueAt(frames, 200L, -1f), 0f)
    }

    @Test
    fun standardEasingsMatchCssValuesAndStayMonotonic() {
        val midpoint = mapOf(
            Easing.LINEAR to 0.5f,
            Easing.EASE_IN to 0.3153568f,
            Easing.EASE_OUT to 0.6846432f,
            Easing.EASE_IN_OUT to 0.5f,
        )
        for ((easing, expected) in midpoint) {
            val frames = listOf(Keyframe(0L, 0f, easing), Keyframe(1_000L, 1f))
            assertEquals(easing.name, expected, Animator.valueAt(frames, 500L, -1f), 1e-5f)
            val values = (0L..1_000L).map { Animator.valueAt(frames, it, -1f) }
            assertTrue(easing.name, values.zipWithNext().all { (left, right) -> left <= right })
        }
        val easeInOut = listOf(Keyframe(0L, 0f, Easing.EASE_IN_OUT), Keyframe(1_000L, 1f))
        assertEquals(0.1291619f, Animator.valueAt(easeInOut, 250L, -1f), 1e-5f)
    }

    @Test
    fun customBezierInvertsXWithAFlatDerivativeAndAllowsOvershoot() {
        val flatX = CubicBezier(0f, 1f / 3f, 0f, 2f / 3f)
        val overshoot = CubicBezier(0.25f, 2f, 0.75f, 2f)

        val frames = listOf(Keyframe(0L, 0f, Easing.BEZIER, flatX), Keyframe(1_000_000L, 1f))
        assertEquals(0.01f, Animator.valueAt(frames, 1L, -1f), 1e-5f)
        assertEquals(0.5f, Animator.valueAt(frames, 125_000L, -1f), 1e-5f)
        assertEquals(1.625f, Animator.valueAt(listOf(frames.first().copy(bezier = overshoot), frames.last()), 500_000L, -1f), 1e-5f)
        assertEquals(0.25f, Animator.valueAt(listOf(frames.first().copy(bezier = null), frames.last()), 250_000L, -1f), 1e-5f)
    }

    @Test
    fun clipEvaluatesPropertyInTimelineTimeRelativeToItsStart() {
        val clip = testVideo("clip", startUs = 500_000L, trimInUs = 200_000L).copy(
            speed = 2f,
            reversed = true,
            keyframes = mapOf(AnimatedProperty.OPACITY to listOf(Keyframe(0L, 0f), Keyframe(100_000L, 1f))),
        )

        assertEquals(0f, clip.animatedValue(AnimatedProperty.OPACITY, Long.MIN_VALUE, 0.9f), 0f)
        assertEquals(0.5f, clip.animatedValue(AnimatedProperty.OPACITY, 550_000L, 0.9f), 0f)
        assertEquals(1f, clip.animatedValue(AnimatedProperty.OPACITY, 650_000L, 0.9f), 0f)
        assertEquals(0.9f, clip.animatedValue(AnimatedProperty.SCALE, 550_000L, 0.9f), 0f)
    }

    @Test
    fun setKeyframeReplacesTheClosestWithinOneFrameAndInsertsAtExactlyOneFrame() {
        val first = Keyframe(0L, 0f)
        val last = Keyframe(50_000L, 1f)
        val frames = listOf(last, first)
        val replacement = Keyframe(30_000L, 2f, Easing.HOLD)
        val newFrame = Keyframe(83_333L, 3f)

        assertEquals(listOf(replacement), Animator.setKeyframe(emptyList(), replacement))
        assertEquals(listOf(first, replacement), Animator.setKeyframe(frames, replacement))
        assertEquals(listOf(first, last, newFrame), Animator.setKeyframe(frames, newFrame))
        assertEquals(listOf(first, last.copy(value = 4f)), Animator.setKeyframe(frames, last.copy(value = 4f)))
        assertEquals(listOf(last, first), frames)
    }

    @Test
    fun removeKeyframeUsesInclusiveToleranceAndOnlyRemovesTheClosest() {
        val first = Keyframe(0L, 0f)
        val last = Keyframe(100L, 1f)
        val frames = listOf(last, first)

        assertEquals(listOf(first), Animator.removeKeyframeNear(frames, 60L, 40L))
        assertEquals(listOf(last), Animator.removeKeyframeNear(frames, 50L, 50L))
        assertEquals(listOf(last), Animator.removeKeyframeNear(frames, 0L, 0L))
        assertEquals(frames, Animator.removeKeyframeNear(frames, 60L, 39L))
        assertEquals(frames, Animator.removeKeyframeNear(frames, 0L, -1L))
        val distant = listOf(Keyframe(Long.MIN_VALUE, 0f))
        assertEquals(distant, Animator.removeKeyframeNear(distant, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}

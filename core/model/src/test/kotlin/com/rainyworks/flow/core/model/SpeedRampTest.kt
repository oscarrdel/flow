package com.rainyworks.flow.core.model

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRampTest {
    @Test
    fun constantCurvesMatchConstantSpeedClipsInBothDirections() {
        for (speed in listOf(Edits.MIN_SPEED, 0.5f, 0.75f, 1f, 1.25f, 2f, 2.5f, 3f, Edits.MAX_SPEED)) {
            val curve = SpeedCurve(listOf(SpeedPoint(0.25f, speed), SpeedPoint(0.75f, speed)))
            for (reversed in listOf(false, true)) {
                val constant = testVideo("clip", startUs = 100_000L, trimInUs = 250_000L, trimOutUs = 1_250_000L)
                    .copy(speed = speed, reversed = reversed)
                val ramped = constant.copy(speed = 1f, speedCurve = curve)
                assertEquals(constant.durationUs, ramped.durationUs)
                for (offset in listOf(-1L, 0L, 1L, 123L, constant.durationUs / 2, constant.durationUs)) {
                    val time = constant.startUs + offset
                    assertEquals("speed=$speed reversed=$reversed offset=$offset", constant.sourceTimeAt(time), ramped.sourceTimeAt(time))
                }
                assertEquals((123_456L / speed.toDouble()).roundToLong(), SpeedRamp.timelineOffsetAt(123_456L, 1_000_000L, curve))
            }
        }
    }

    @Test
    fun increasingAndDecreasingRampsHaveLogarithmicDurations() {
        val ascending = curve(0f to 1f, 1f to 2f)
        val descending = curve(0f to 2f, 1f to 1f)
        val source = 1_000_000L

        assertEquals((ln(2.0) * source).roundToLong(), SpeedRamp.timelineDurationUs(source, ascending))
        assertEquals(SpeedRamp.timelineDurationUs(source, ascending), SpeedRamp.timelineDurationUs(source, descending))
        assertEquals((ln(1.5) * source).roundToLong(), SpeedRamp.timelineOffsetAt(source / 2, source, ascending))
        assertEquals((ln(2.0 / 1.5) * source).roundToLong(), SpeedRamp.timelineOffsetAt(source / 2, source, descending))
        assertEquals(source / 2, SpeedRamp.sourceOffsetAt(405_465L, source, ascending))
        assertEquals(source / 2, SpeedRamp.sourceOffsetAt(287_682L, source, descending))
    }

    @Test
    fun nearlyFlatSegmentRetainsPrecision() {
        val speed = 1.0000001f
        val delta = speed.toDouble() - 1.0
        val source = 20_000_000L
        val ramp = curve(0f to 1f, 1f to speed)

        assertEquals((source * ln1p(delta) / delta).roundToLong(), SpeedRamp.timelineDurationUs(source, ramp))
        val elapsed = (source * ln1p(delta * 0.9) / delta).roundToLong()
        assertNear(18_000_000L, SpeedRamp.sourceOffsetAt(elapsed, source, ramp))
    }

    @Test
    fun unsortedInteriorPointsExtendTheirNearestEndpoint() {
        val ramp = curve(0.75f to 2f, 0.25f to 1f)
        val source = 1_000_000L

        assertEquals(((0.25 + 0.5 * ln(2.0) + 0.125) * source).roundToLong(), SpeedRamp.timelineDurationUs(source, ramp))
        assertEquals(100_000L, SpeedRamp.timelineOffsetAt(100_000L, source, ramp))
        assertEquals(50_000L, SpeedRamp.timelineDurationUs(source, ramp) - SpeedRamp.timelineOffsetAt(900_000L, source, ramp))
    }

    @Test
    fun boundsEmptySingletonAndDuplicatePointsHaveDeterministicMappings() {
        val source = 1_000_000L
        val ramp = curve(0f to 0f, 0.25f to -1f, 0.75f to 120f, 1f to 200f)
        val duration = SpeedRamp.timelineDurationUs(source, ramp)

        assertEquals(2_500_000L, SpeedRamp.timelineOffsetAt(250_000L, source, ramp))
        assertEquals(2_500L, duration - SpeedRamp.timelineOffsetAt(750_000L, source, ramp))
        assertEquals(source, SpeedRamp.timelineDurationUs(source, SpeedCurve(emptyList())))
        assertEquals(source / 4, SpeedRamp.timelineDurationUs(source, curve(0.3f to 4f)))
        assertEquals(source / 2, SpeedRamp.timelineDurationUs(source, curve(0.5f to 4f, 0.5f to 2f)))
        assertEquals(0L, SpeedRamp.timelineDurationUs(0L, ramp))
        assertEquals(0L, SpeedRamp.sourceOffsetAt(9L, 0L, ramp))
        assertEquals(0L, SpeedRamp.timelineOffsetAt(9L, 0L, ramp))
        assertEquals(0L, SpeedRamp.sourceOffsetAt(Long.MIN_VALUE, source, ramp))
        assertEquals(source, SpeedRamp.sourceOffsetAt(duration, source, ramp))
        assertEquals(source, SpeedRamp.sourceOffsetAt(Long.MAX_VALUE, source, ramp))
        assertEquals(0L, SpeedRamp.timelineOffsetAt(Long.MIN_VALUE, source, ramp))
        assertEquals(duration, SpeedRamp.timelineOffsetAt(source, source, ramp))
        assertEquals(duration, SpeedRamp.timelineOffsetAt(Long.MAX_VALUE, source, ramp))
    }

    @Test
    fun variableSpeedOffsetsAreMonotonicAndRoundTripWithinOneMicrosecond() {
        val ramp = curve(0.4f to 1.75f, 1f to 1.25f, 0f to 0.65f, 0.7f to 0.8f)
        val source = 1_234_567L
        val duration = SpeedRamp.timelineDurationUs(source, ramp)
        var previousTimeline = -1L
        var previousSource = -1L
        for (index in 0L..1_000L) {
            val sourceOffset = source * index / 1_000L
            val timeline = SpeedRamp.timelineOffsetAt(sourceOffset, source, ramp)
            val timelineOffset = duration * index / 1_000L
            val sourceAt = SpeedRamp.sourceOffsetAt(timelineOffset, source, ramp)
            assertTrue(previousTimeline <= timeline)
            assertTrue(previousSource <= sourceAt)
            assertNear(sourceOffset, SpeedRamp.sourceOffsetAt(timeline, source, ramp))
            assertNear(timelineOffset, SpeedRamp.timelineOffsetAt(sourceAt, source, ramp))
            previousTimeline = timeline
            previousSource = sourceAt
        }
    }

    @Test
    fun extremeSpeedsRoundToTheNearestRepresentableSample() {
        val source = 1_000_000L
        val offset = 123_456L
        val fast = curve(0f to Edits.MAX_SPEED)
        val slow = curve(0f to Edits.MIN_SPEED)

        val fastTimeline = SpeedRamp.timelineOffsetAt(offset, source, fast)
        val slowSource = SpeedRamp.sourceOffsetAt(offset, source, slow)

        assertEquals(1_235L, fastTimeline)
        assertEquals(123_500L, SpeedRamp.sourceOffsetAt(fastTimeline, source, fast))
        assertEquals(12_346L, slowSource)
        assertEquals(123_460L, SpeedRamp.timelineOffsetAt(slowSource, source, slow))
    }

    @Test
    fun slicingInterpolatesTheNewBoundariesAndRenormalizesInteriorPoints() {
        val ramp = curve(0f to 1f, 0.5f to 3f, 1f to 2f).copy(presetId = "original")
        val sliced = SpeedRamp.slice(ramp, 0.25f, 0.75f)
        val extended = SpeedRamp.slice(ramp, -0.5, 0.5)
        val source = 1_000_000L
        val originalDuration = SpeedRamp.timelineOffsetAt(750_000L, source, ramp) -
            SpeedRamp.timelineOffsetAt(250_000L, source, ramp)

        assertEquals(listOf(SpeedPoint(0f, 2f), SpeedPoint(0.5f, 3f), SpeedPoint(1f, 2.5f)), sliced.points)
        assertEquals(listOf(SpeedPoint(0f, 1f), SpeedPoint(0.5f, 1f), SpeedPoint(1f, 3f)), extended.points)
        assertNull(sliced.presetId)
        assertEquals(ramp, SpeedRamp.slice(ramp, 0.0, 1.0))
        assertNear(originalDuration, SpeedRamp.timelineDurationUs(500_000L, sliced))
    }

    @Test
    fun everyPresetHasAUniqueIdAndAValidNonconstantShape() {
        val presets = SpeedRamp.presets
        assertEquals(listOf("montage", "hero", "bullet", "jump_cut", "flash_in", "flash_out"), presets.map { it.presetId })
        for (preset in presets) {
            assertEquals(0f, preset.points.first().position, 0f)
            assertEquals(1f, preset.points.last().position, 0f)
            assertTrue(preset.points.zipWithNext().all { (left, right) -> left.position < right.position })
            assertTrue(preset.points.all { it.speed in Edits.MIN_SPEED..Edits.MAX_SPEED })
            assertTrue(preset.points.map { it.speed }.distinct().size > 1)
            assertTrue(SpeedRamp.timelineDurationUs(1_000_000L, preset) > 0L)
        }
    }

    private fun curve(vararg points: Pair<Float, Float>): SpeedCurve =
        SpeedCurve(points.map { (position, speed) -> SpeedPoint(position, speed) })

    private fun assertNear(expected: Long, actual: Long) {
        assertTrue("expected $expected ± 1 us, was $actual", abs(expected - actual) <= 1L)
    }
}

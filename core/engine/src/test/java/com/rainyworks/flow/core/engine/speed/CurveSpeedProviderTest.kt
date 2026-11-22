package com.rainyworks.flow.core.engine.speed

import androidx.media3.common.C
import com.rainyworks.flow.core.model.SpeedCurve
import com.rainyworks.flow.core.model.SpeedPoint
import com.rainyworks.flow.core.model.SpeedRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CurveSpeedProviderTest {
    @Test
    fun staircaseKeepsTheCurveDuration() {
        val source = 6_000_000L
        for (curve in SpeedRamp.presets + SpeedCurve(listOf(SpeedPoint(0f, 0.3f), SpeedPoint(1f, 4f)))) {
            val provider = CurveSpeedProvider(source, curve, timeBaseUs = 0)
            val expected = SpeedRamp.timelineDurationUs(source, curve)
            assertTrue("${curve.presetId}: ${provider.timelineDurationUs} vs $expected", abs(provider.timelineDurationUs - expected) < 66_666)
        }
    }

    @Test
    fun speedChangesAreReportedFromTheTimeBase() {
        val curve = SpeedCurve(listOf(SpeedPoint(0f, 1f), SpeedPoint(1f, 3f)))
        val provider = CurveSpeedProvider(1_000_000, curve, timeBaseUs = 2_000_000)
        assertTrue(provider.getSpeed(2_000_000) < provider.getSpeed(2_990_000))
        val next = provider.getNextSpeedChangeTimeUs(2_000_000)
        assertTrue(next in 2_000_001..2_100_000)
        assertEquals(C.TIME_UNSET, provider.getNextSpeedChangeTimeUs(2_999_999))
    }

    @Test
    fun constantCurveIsConstantSpeed() {
        val provider = CurveSpeedProvider(3_000_000, SpeedCurve(listOf(SpeedPoint(0f, 2f), SpeedPoint(1f, 2f))), 0)
        assertEquals(2f, provider.getSpeed(0), 1e-4f)
        assertEquals(2f, provider.getSpeed(2_900_000), 1e-4f)
        assertTrue(abs(provider.timelineDurationUs - 1_500_000L) < 2_000L)
    }

    @Test
    fun equalNeighborStepsAreOneSegment() {
        val provider = CurveSpeedProvider(12_000_000, SpeedCurve(listOf(SpeedPoint(0f, 0.5f), SpeedPoint(1f, 0.5f))), 0)
        assertEquals(C.TIME_UNSET, provider.getNextSpeedChangeTimeUs(0))
    }

    @Test
    fun lastPartialStepIsCountedExactly() {
        val provider = CurveSpeedProvider(1_000_000, SpeedCurve(listOf(SpeedPoint(0f, 1f), SpeedPoint(1f, 1f))), 0)
        assertTrue(abs(provider.timelineDurationUs - 1_000_000) <= 2)
    }
}

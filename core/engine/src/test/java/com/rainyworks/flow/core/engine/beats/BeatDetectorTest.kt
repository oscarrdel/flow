package com.rainyworks.flow.core.engine.beats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BeatDetectorTest {
    private val rate = 11_025

    /** Kick drums (decaying 60 Hz bursts) every [periodUs] over quiet noise. */
    private fun kicks(periodUs: Long, seconds: Int): FloatArray {
        val random = Random(7)
        val samples = FloatArray(rate * seconds) { (random.nextFloat() - 0.5f) * 0.02f }
        var t = 250_000L
        while (t < seconds * 1_000_000L) {
            val start = (t * rate / 1_000_000L).toInt()
            for (i in 0 until rate / 8) {
                if (start + i >= samples.size) break
                val time = i.toDouble() / rate
                samples[start + i] += (0.8 * exp(-time * 25) * sin(2 * PI * 60 * time)).toFloat()
            }
            t += periodUs
        }
        return samples
    }

    @Test
    fun findsEveryKickOnTime() {
        val beats = BeatDetector.onsets(kicks(500_000, 8), rate)
        val expected = (0 until 16).map { 250_000L + it * 500_000L }
        assertEquals(expected.size, beats.size)
        expected.zip(beats.toList()).forEach { (want, got) -> assertTrue("$want vs $got", abs(want - got) < 40_000) }
    }

    @Test
    fun silenceHasNoBeats() {
        assertEquals(0, BeatDetector.onsets(FloatArray(rate * 3), rate).size)
    }
}

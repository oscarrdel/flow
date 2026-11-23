package com.rainyworks.flow.core.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class LoudnessTest {
    private val rate = 16_000

    private fun tone(amplitude: Float, seconds: Double = 3.0, silentHalf: Boolean = false) =
        FloatArray((rate * seconds).toInt()) { i ->
            if (silentHalf && i > rate * seconds / 2) 0f else (amplitude * sin(2 * PI * 300 * i / rate)).toFloat()
        }

    @Test
    fun quietClipIsRaisedAndLoudOneLowered() {
        val quiet = Loudness.volumeFor(tone(0.02f), rate)
        val loud = Loudness.volumeFor(tone(0.9f), rate)
        assertTrue("quiet $quiet", quiet > 1.5f)
        assertTrue("loud $loud", loud < 0.3f)
    }

    @Test
    fun normalizedLevelHitsTheTargetAndSilenceDoesNotCount() {
        val samples = tone(0.1f, silentHalf = true)
        val volume = Loudness.volumeFor(samples, rate)
        val level = Loudness.levelDb(samples.map { it * volume }.toFloatArray(), rate)!!
        assertEquals(Loudness.TARGET_DB, level, 0.5)
        assertEquals(-23.0, 20 * log10(0.1 / kotlin.math.sqrt(2.0)), 0.1)
    }

    @Test
    fun peaksNeverClipWhenRaising() {
        // A loud click in otherwise quiet audio: raising to the target would clip it.
        val samples = tone(0.02f).also { it[100] = 0.9f }
        val volume = Loudness.volumeFor(samples, rate)
        assertTrue("volume $volume", volume * 0.9f <= 1.0f)
    }
}

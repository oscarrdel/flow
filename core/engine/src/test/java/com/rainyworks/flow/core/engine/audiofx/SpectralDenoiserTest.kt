package com.rainyworks.flow.core.engine.audiofx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class SpectralDenoiserTest {
    private val rate = 48_000

    private fun energy(x: DoubleArray, from: Int, to: Int) = (from until to).sumOf { x[it] * x[it] }

    @Test
    fun steadyHissDropsWhileATonePasses() {
        val random = Random(3)
        val seconds = 4
        val noise = DoubleArray(rate * seconds) { (random.nextDouble() - 0.5) * 0.04 }
        // Hiss alone for 2 s, then a 440 Hz tone over the hiss.
        val tone = DoubleArray(noise.size) { i -> if (i < 2 * rate) 0.0 else 0.3 * sin(2 * PI * 440 * i / rate) }
        val input = DoubleArray(noise.size) { noise[it] + tone[it] }
        val denoiser = SpectralDenoiser(1f)
        val raw = DoubleArray(input.size) { denoiser.process(input[it]) }
        // Undo the latency to compare like with like.
        val out = DoubleArray(input.size) { i -> raw.getOrElse(i + SpectralDenoiser.LATENCY) { 0.0 } }

        val hissBefore = energy(input, rate, 2 * rate - 4096)
        val hissAfter = energy(out, rate, 2 * rate - 4096)
        val reductionDb = 10 * log10(hissBefore / hissAfter)
        assertTrue("hiss reduced by $reductionDb dB", reductionDb > 10)

        val toneBefore = energy(tone, 3 * rate, 4 * rate - 4096)
        val toneAfter = energy(out, 3 * rate, 4 * rate - 4096)
        assertEquals("tone kept", 0.0, 10 * log10(toneAfter / toneBefore), 1.5)
    }

    @Test
    fun offIsTransparent() {
        val denoiser = SpectralDenoiser(0f)
        val input = DoubleArray(rate) { 0.5 * sin(2 * PI * 1000 * it / rate) }
        val raw = DoubleArray(input.size) { denoiser.process(input[it]) }
        for (i in rate / 4 until rate / 2) {
            assertEquals(input[i], raw[i + SpectralDenoiser.LATENCY], 1e-3)
        }
    }
}

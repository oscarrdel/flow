package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoColorTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Simulates the grade shader on one channel value (before saturation). */
    private fun graded(v: Double, a: ColorAdjustments): Double {
        val c = v + a.brightness * 0.25
        return c + (c - 0.5) * a.contrast
    }

    @Test
    fun flatDarkPictureGetsStretchedAndBrightened() {
        // Values between 20 and 100 out of 255: dark and low contrast.
        val pixels = IntArray(10_000) { i -> (20 + i % 81).let { rgb(it, it, it) } }
        val a = AutoColor.analyze(pixels)
        assertTrue("contrast ${a.contrast}", a.contrast > 0.3f)
        assertTrue("brightness ${a.brightness}", a.brightness > 0.3f)
        val low = graded(22 / 255.0, a)
        val high = graded(98 / 255.0, a)
        assertTrue("range $low..$high", low < 0.25 && high > 0.6)
    }

    @Test
    fun warmCastIsCooledDown() {
        val pixels = IntArray(10_000) { i -> rgb(180 - i % 40, 130 - i % 40, 90 - i % 40) }
        val a = AutoColor.analyze(pixels)
        assertTrue("temperature ${a.temperature}", a.temperature < -0.2f)
    }

    @Test
    fun wellExposedNeutralPictureIsLeftAlone() {
        val pixels = IntArray(10_000) { i -> (5 + (i * 245L / 10_000).toInt()).let { v -> rgb(v, (v * 0.9).toInt(), (v * 0.6).toInt().coerceAtLeast(0)) } }
        val gray = IntArray(10_000) { i -> (8 + (i * 240L / 10_000).toInt()).let { rgb(it, it, it) } }
        val a = AutoColor.analyze(gray)
        assertEquals(0f, a.temperature, 0.001f)
        assertTrue("contrast ${a.contrast}", kotlin.math.abs(a.contrast) < 0.1f)
        assertTrue("brightness ${a.brightness}", kotlin.math.abs(a.brightness) < 0.1f)
        assertTrue(pixels.isNotEmpty())
    }
}

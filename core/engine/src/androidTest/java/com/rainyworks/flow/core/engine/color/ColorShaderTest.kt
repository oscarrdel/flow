package com.rainyworks.flow.core.engine.color

import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.model.ColorAdjustments
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.CubeLut
import com.rainyworks.flow.core.model.CurvePoint
import com.rainyworks.flow.core.model.HslAdjustment
import com.rainyworks.flow.core.model.HslRange
import com.rainyworks.flow.core.model.ToneCurves
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class ColorShaderTest {
    private fun withGl(version: Int, test: () -> Unit) {
        val display = GlUtil.getDefaultEglDisplay()
        val context = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, version, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        try {
            val surface = GlUtil.createFocusedPlaceholderEglSurface(context, display)
            try {
                assertTrue(GlUtil.getContextMajorVersion() >= version)
                test()
            } finally {
                GlUtil.destroyEglSurface(display, surface)
            }
        } finally {
            GlUtil.destroyEglContext(display, context)
            GlUtil.terminate(display)
        }
    }

    private fun render(effect: GlEffect, width: Int, height: Int, rgba: ByteArray, timeUs: Long = 0): ByteArray {
        require(rgba.size == width * height * 4)
        val input = GlUtil.createTexture(width, height, false)
        try {
            GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, input, GLES20.GL_LINEAR)
            val upload = ByteBuffer.allocateDirect(rgba.size).apply { put(rgba).flip() }
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, upload)
            val program = effect.toGlShaderProgram(InstrumentationRegistry.getInstrumentation().targetContext, false)
            try {
                var output: GlTextureInfo? = null
                program.setErrorListener({ it.run() }) { throw AssertionError("shader failed", it) }
                program.setOutputListener(object : GlShaderProgram.OutputListener {
                    override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                        assertEquals(timeUs, presentationTimeUs)
                        output = outputTexture
                    }
                })
                program.queueInputFrame(DefaultGlObjectsProvider(),
                    GlTextureInfo(input, C.INDEX_UNSET, C.INDEX_UNSET, width, height), timeUs)
                val frame = checkNotNull(output)
                GlUtil.focusFramebufferUsingCurrentContext(frame.fboId, width, height)
                val bytes = ByteBuffer.allocateDirect(rgba.size)
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
                GlUtil.checkGlError()
                program.releaseOutputFrame(frame)
                return ByteArray(rgba.size).also { bytes.get(it) }
            } finally {
                program.release()
            }
        } finally {
            GlUtil.deleteTexture(input)
        }
    }

    private fun grade(grade: ColorGrade) = GradeShader(resolveGrade(grade))
    private fun adjustments(a: ColorAdjustments) = grade(ColorGrade(adjustments = a))
    private fun byteValue(b: Byte) = b.toInt() and 255
    private fun pattern(count: Int) = ByteArray(count * 4) { i -> ((i / 4) * 53 + (i % 4) * 71).toByte() }

    @Test fun identityPreservesEveryByteAndBrightnessPreservesAlpha() = withGl(3) {
        // Every byte value occurs in each channel. Force a draw even though the public API skips identity.
        val input = pattern(256)
        assertArrayEquals(input, render(grade(ColorGrade()), 32, 8, input))
        val brighter = render(adjustments(ColorAdjustments(brightness = 1f)), 32, 8, input)
        for (i in input.indices) {
            val v = byteValue(input[i])
            if (i % 4 == 3) assertEquals(v, byteValue(brighter[i]))
            else assertTrue(abs(minOf(255, v + 64) - byteValue(brighter[i])) <= 1)
        }
    }

    @Test fun selectiveHueSmoothlyRotatesRedAndLeavesGreenAndGrayIntact() = withGl(2) {
        val input = byteArrayOf(-1, 0, 0, 41, 0, -1, 0, 42, 99, 99, 99, 43, -1, 64, 0, 44)
        val effect = grade(ColorGrade(hsl = mapOf(HslRange.RED to HslAdjustment(hue = 1f))))
        val out = render(effect, 4, 1, input)
        assertEquals(255, byteValue(out[0]))
        assertEquals(128.0, byteValue(out[1]).toDouble(), 1.0)
        assertEquals(0, byteValue(out[2]))
        assertArrayEquals(input.copyOfRange(3, 12), out.copyOfRange(3, 12))
        // The fourth pixel starts at 15 degrees, halfway from the red center to orange.
        assertEquals(128.0, byteValue(out[13]).toDouble(), 2.0)
    }

    @Test fun allEightRangesReachTheirSignedThirtyDegreeShift() = withGl(3) {
        val centers = listOf(0, 30, 60, 120, 180, 240, 270, 300)
        val hsl = HslRange.entries.associateWith { HslAdjustment(hue = if (it.ordinal % 2 == 0) 1f else -1f) }
        fun pixels(shifted: Boolean): ByteArray {
            val buffer = ByteBuffer.allocate(4 * centers.size)
            for ((i, center) in centers.withIndex()) {
                val degrees = if (shifted) center + 30 * hsl.getValue(HslRange.entries[i]).hue else center.toFloat()
                val rgb = Color.HSVToColor(floatArrayOf((degrees + 360) % 360, 1f, 1f))
                buffer.put(Color.red(rgb).toByte()).put(Color.green(rgb).toByte()).put(Color.blue(rgb).toByte()).put(-1)
            }
            return buffer.array()
        }
        val out = render(grade(ColorGrade(hsl = hsl)), 8, 1, pixels(false))
        val expected = pixels(true)
        for (i in out.indices) assertTrue(abs(byteValue(expected[i]) - byteValue(out[i])) <= 2)
    }

    @Test fun masterPrecedesChannelsAndSharpenUsesAlreadyGradedNeighbors() = withGl(3) {
        val input = ByteArray(3 * 3 * 4) { i ->
            when {
                i % 4 == 3 -> 23
                i / 4 == 4 -> 128.toByte()
                else -> 64
            }
        }
        val curve = listOf(CurvePoint(0.5f, 0.25f))
        val curves = ToneCurves(master = curve, red = listOf(CurvePoint(0f, 0.2f), CurvePoint(1f, 0.8f)))
        val base = ColorGrade(curves = curves)
        val plain = render(grade(base), 3, 3, input)
        val pos = 4 * 4
        assertEquals(90.0, byteValue(plain[pos]).toDouble(), 2.0)
        assertEquals(64.0, byteValue(plain[pos + 1]).toDouble(), 2.0)
        val sharp = render(grade(base.copy(adjustments = ColorAdjustments(sharpness = 0.5f))), 3, 3, input)
        for (c in 0..2) {
            val center = byteValue(plain[pos + c])
            val neighbor = byteValue(plain[c])
            assertEquals(center + 0.5 * (center - neighbor), byteValue(sharp[pos + c]).toDouble(), 2.0)
        }
        assertEquals(23, byteValue(sharp[pos + 3]))
    }

    @Test fun grainRepeatsAtTheSameTimestampAndVignetteUsesPixelDistances() = withGl(3) {
        val width = 21
        val height = 13
        val input = ByteArray(width * height * 4) { 100 }
        val grain = adjustments(ColorAdjustments(grain = 1f))
        val first = render(grain, width, height, input, 9_000_000_000L)
        assertArrayEquals(first, render(grain, width, height, input, 9_000_000_000L))
        assertFalse(first.contentEquals(render(grain, width, height, input, 9_000_033_333L)))
        val shaded = render(adjustments(ColorAdjustments(vignette = 1f)), width, height, input)
        fun pixel(x: Int, y: Int) = byteValue(shaded[(y * width + x) * 4])
        assertEquals(100, pixel(10, 6))
        assertTrue(pixel(0, 0) < pixel(10, 0))
        assertTrue(pixel(10, 0) < pixel(10, 6))
        assertEquals(pixel(10, 0), pixel(4, 6))
    }

    private fun compareLut(lut: CubeLut, version: () -> Long = GlUtil::getContextMajorVersion) {
        val input = pattern(256)
        val k = 0.625f
        val out = render(CubeEffect(lut, k, version), 16, 16, input)
        for (i in input.indices step 4) {
            val rgb = FloatArray(3) { byteValue(input[i + it]) / 255f }
            val sample = lut.sample(rgb[0], rgb[1], rgb[2])
            for (c in 0..2) {
                val expected = ((rgb[c] + k * (sample[c] - rgb[c])).coerceIn(0f, 1f) * 255).roundToInt()
                val actual = byteValue(out[i + c])
                assertTrue("pixel ${i / 4} channel $c expected $expected got $actual", abs(expected - actual) <= 2)
            }
            assertEquals(input[i + 3], out[i + 3])
        }
    }

    private fun volume(): CubeLut {
        val size = 5
        // Cross terms exercise interpolation in all three dimensions. Outputs extend past black and white.
        val values = FloatArray(size * size * size * 3) { i ->
            val r = (i / 3 % size) / 4f
            val g = (i / 3 / size % size) / 4f
            val b = (i / 3 / size / size) / 4f
            when (i % 3) {
                0 -> 1.2f * b * (1f - g)
                1 -> r * b - 0.1f
                else -> g * (1f - r)
            }
        }
        return CubeLut("cross", size, true, floatArrayOf(0.1f, -0.1f, 0.2f), floatArrayOf(0.9f, 0.8f, 1.2f), values)
    }

    @Test fun nativeVolumeMatchesTrilinearCpuReference() = withGl(3) { compareLut(volume()) }

    @Test fun tiledVolumeMatchesTrilinearCpuReference() = withGl(2) {
        // EGL may return GLES3 even when GLES2 was requested. Exercise the ES2 layout explicitly.
        compareLut(volume()) { 2L }
    }

    @Test fun largeOneDimensionalLutSamplesEachChannelIndependently() = withGl(3) {
        val n = CubeLut.MAX_1D_SIZE
        val values = FloatArray(n * 3) {
            val x = (it / 3).toFloat() / (n - 1)
            when (it % 3) {
                0 -> x * x
                1 -> 1f - x
                else -> 0.5f * x - 0.1f
            }
        }
        compareLut(CubeLut("1D", n, false, floatArrayOf(0f, 0.2f, -0.1f), floatArrayOf(0.8f, 1f, 1.1f), values))
    }
}

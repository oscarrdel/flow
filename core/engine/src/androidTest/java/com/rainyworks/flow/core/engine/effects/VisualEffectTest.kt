package com.rainyworks.flow.core.engine.effects

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rainyworks.flow.core.engine.compositing.Pixels
import com.rainyworks.flow.core.engine.compositing.frames
import com.rainyworks.flow.core.engine.compositing.withGl
import com.rainyworks.flow.core.model.EffectCatalog
import com.rainyworks.flow.core.model.EffectInstance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualEffectTest {
    /** A colorful, detailed frame so every effect has something to change. */
    private fun pattern(w: Int = 96, h: Int = 64): Pixels {
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = 4 * (y * w + x)
            val checker = ((x / 4) + (y / 4)) % 2
            rgba[i] = (x * 255 / (w - 1)).toByte()
            rgba[i + 1] = (if (checker == 0) 40 else 220).toByte()
            rgba[i + 2] = (y * 255 / (h - 1)).toByte()
            rgba[i + 3] = 255.toByte()
        }
        return Pixels(w, h, rgba)
    }

    private fun strong(type: String): EffectInstance {
        val definition = EffectCatalog.find(type)!!
        val params = definition.defaults().toMutableMap()
        params["intensity"]?.let { params["intensity"] = 1f }
        if (type == "pixelate") params["size"] = 0.6f
        if (type == "frame.retro") params["size"] = 1f
        return EffectInstance("e", type, params)
    }

    @Test
    fun everyEffectCompilesAndChangesTheFrame() = withGl {
        val input = pattern()
        for (definition in EffectCatalog.all) {
            val outputs = frames(VisualEffect(strong(definition.type)), input, 0L, 250_000L, 1_330_000L)
            val changed = outputs.any { !it.rgba.contentEquals(input.rgba) }
            assertTrue("${definition.type} did not change the frame", changed)
            outputs.forEach { out ->
                for (i in 3 until out.rgba.size step 4) assertTrue(definition.type, (out.rgba[i].toInt() and 255) == 255)
            }
        }
    }

    @Test
    fun effectsPassThroughOutsideTheirTimeRange() = withGl {
        val input = pattern()
        for (definition in EffectCatalog.all) {
            val gated = VisualEffect(strong(definition.type), activeFromUs = 1_000_000L, activeUntilUs = 2_000_000L)
            val (before, after) = frames(gated, input, 500_000L, 2_500_000L)
            assertArrayEquals(definition.type, input.rgba, before.rgba)
            assertArrayEquals(definition.type, input.rgba, after.rgba)
        }
    }

    @Test
    fun animatedEffectsVaryOverTime() = withGl {
        val input = pattern()
        for (type in listOf("glitch", "shake", "zoom.dynamic", "particles")) {
            val outputs = frames(VisualEffect(strong(type)), input, 0L, 370_000L, 810_000L, 1_520_000L)
            val distinct = outputs.map { it.rgba.contentHashCode() }.toSet().size
            assertTrue("$type looks static", distinct > 1)
        }
    }
}

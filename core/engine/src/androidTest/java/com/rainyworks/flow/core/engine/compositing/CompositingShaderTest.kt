package com.rainyworks.flow.core.engine.compositing

import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import com.rainyworks.flow.core.model.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class CompositingShaderTest {
    private val clip = Clip("test", ClipKind.IMAGE, MediaRef("image", MediaType.IMAGE),
        startUs = 5_000_000, trimInUs = 600_000, trimOutUs = 1_600_000)

    @Test fun chromaPreservesInputAlphaAndRetainsNeutralShadows() = withGl {
        val input = Pixels(5, 1, byteArrayOf(0,-1,0,-1, 0,64,0,-1, -1,0,0,100, 76,-1,76,-1, -1,42,64,0))
        val key = ChromaKey(tolerance = 0.02f, softness = 0.22f, spillSuppression = 0f, shadows = 1f)
        val result = frames(ChromaKeyEffect(key), input, 0).single()
        assertEquals(0, result.value(0, 0, 3))
        assertEquals(191, result.value(1, 0, 3))
        assertEquals(0, result.value(1, 0, 1))
        assertEquals(100, result.value(2, 0, 0))
        assertEquals(100, result.value(2, 0, 3))
        assertTrue(result.value(3, 0, 3) in 1..254)
        for (c in 0..3) assertEquals(0, result.value(4, 0, c))
        val suppressed = frames(ChromaKeyEffect(key.copy(spillSuppression = 1f)), input, 0).single()
        assertEquals(result.value(3, 0, 3), suppressed.value(3, 0, 3))
        assertTrue(suppressed.value(3, 0, 1) < result.value(3, 0, 1))
        assertTrue(suppressed.value(3, 0, 0) > result.value(3, 0, 0))
        val hard = frames(ChromaKeyEffect(key.copy(softness = 0f, shadows = 0f)), input, 0).single()
        assertEquals(0, hard.value(1, 0, 3))
        assertEquals(255, hard.value(3, 0, 3))
    }

    @Test fun maskShapesMatchLocalDistancesWithRotationFeatherOffsetScaleAndInvert() = withGl {
        val w = 47
        val h = 33
        val input = Pixels(w, h, ByteArray(w * h * 4) { if (it % 4 == 3) 120 else 60 })
        for (shape in MaskShape.entries) {
            val m = Mask(shape, 0.08f, -0.12f, 0.86f, 32f, 0.24f, true, 0.6f)
            val result = frames(MaskEffect(clip.copy(mask = m), inputPremultiplied = true), input, 0).single()
            val angle = Math.toRadians(m.rotationDegrees.toDouble())
            val radius = 0.35f * h * m.scale
            val edge = maxOf(0.5f / radius, 0.6f * m.feather)
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x + 0.5f - (0.5f + m.offsetX) * w
                val dy = (0.5f - m.offsetY) * h - y - 0.5f
                val px = ((cos(angle) * dx + sin(angle) * dy) / radius).toFloat()
                val py = ((-sin(angle) * dx + cos(angle) * dy) / radius).toFloat()
                val dist = maskDistance(shape, px, py, w.toFloat() / h, 1f, m.cornerRadius)
                val t = ((dist + edge) / (2f * edge)).coerceIn(0f, 1f)
                val coverage = t * t * (3f - 2f * t)
                for ((channel, value) in listOf(0 to 60, 3 to 120)) {
                    val expected = (coverage * value).roundToInt()
                    val actual = result.value(x, y, channel)
                    assertTrue("$shape ($x,$y), expected $expected, got $actual", abs(expected - actual) <= 2)
                }
            }
        }
    }

    @Test fun allMaskKeysUseItemTimeAndMasksCanChainWithChroma() = withGl {
        val start = Mask(MaskShape.RECTANGLE, -0.2f, -0.15f, 0.6f, -25f, 0.05f)
        val end = start.copy(offsetX = 0.15f, offsetY = 0.2f, scale = 1.2f, rotationDegrees = 45f, feather = 0.6f)
        fun keys(a: Float, b: Float) = listOf(Keyframe(0, a), Keyframe(800_000, b))
        val animated = clip.copy(mask = start, chromaKey = ChromaKey(), keyframes = mapOf(
            AnimatedProperty.MASK_OFFSET_X to keys(start.offsetX, end.offsetX),
            AnimatedProperty.MASK_OFFSET_Y to keys(start.offsetY, end.offsetY),
            AnimatedProperty.MASK_SCALE to keys(start.scale, end.scale),
            AnimatedProperty.MASK_ROTATION to keys(start.rotationDegrees, end.rotationDegrees),
            AnimatedProperty.MASK_FEATHER to keys(start.feather, end.feather)))
        val source = Pixels(40, 30, ByteArray(40 * 30 * 4) { when (it % 4) { 0 -> -1; 3 -> 96; else -> 0 } })
        val effects = clipMatteEffects(animated, itemStartPtsUs = 0)
        val keyed = frames(effects[0], source, 0).single()
        val output = frames(effects[1], keyed, 0, 800_000)
        assertTrue(!output.first().rgba.contentEquals(output.last().rgba))
        for ((i, mask) in listOf(start, end).withIndex()) {
            val expected = frames(MaskEffect(clip.copy(mask = mask)), source, 0).single()
            assertTrue(output[i].rgba.contentEquals(expected.rgba))
        }
        val straight = frames(StraightAlphaForMedia3, keyed, 0).single()
        assertEquals(255, straight.value(0, 0, 0))
        assertEquals(96, straight.value(0, 0, 3))
    }

    @Test fun animatedOverlayUsesTimelineTimeAndPreservesFlips() {
        fun keys(a: Float, b: Float) = listOf(Keyframe(0, a), Keyframe(1000, b))
        val layer = clip.copy(transform = Transform(flipHorizontal = true), keyframes = mapOf(
            AnimatedProperty.OFFSET_X to keys(0f, 0.3f), AnimatedProperty.OFFSET_Y to keys(0f, -0.4f),
            AnimatedProperty.SCALE to keys(1f, 2f), AnimatedProperty.OPACITY to keys(1f, 0f),
            AnimatedProperty.ROTATION to keys(10f, 70f)))
        val settings = animatedOverlaySettings(layer, layer.startUs + 500)
        assertEquals(0.3f, settings.backgroundFrameAnchor.first, 0.00001f)
        assertEquals(0.4f, settings.backgroundFrameAnchor.second, 0.00001f)
        assertEquals(-1.5f, settings.scale.first, 0f)
        assertEquals(1.5f, settings.scale.second, 0f)
        assertEquals(-40f, settings.rotationDegrees, 0f)
        assertEquals(0.5f, settings.alphaScale, 0f)
    }

    @Test fun allGpuBlendModesMatchPartiallyTransparentLayersAndGaps() = withGl {
        val canvas = Size(8, 4)
        val foreground = BlendMode.entries.mapIndexed { i, mode ->
            clip.copy(startUs = i * 1000L, trimInUs = 0, trimOutUs = 1000, blendMode = mode, opacity = 0.6f)
        }
        val bg = clip.copy(startUs = 0)
        val comp = LayerBlendCompositor(canvas, listOf(Track("over", TrackKind.OVERLAY, foreground),
            Track.main().copy(clips = listOf(bg))))
        val atlas = comp.getOutputSize(listOf(canvas, canvas))
        assertEquals(Size(8, 8), atlas)
        val data = Pixels(8, 8, ByteArray(8 * 8 * 4) { i ->
            val pixel = if (i / 4 / 8 < 4) intArrayOf(96, 48, 12, 128) else intArrayOf(24, 60, 120, 192)
            pixel[i % 4].toByte()
        })
        val times = LongArray(foreground.size + 1) { it * 1000L }
        val out = frames(requireNotNull(comp.effect), data, *times)
        for ((i, c) in foreground.withIndex()) {
            val asrc = 128 / 255f
            val ab = 192 / 255f
            for (k in 0..2) {
                val b = data.value(1, 6, k) / 192f
                val s = data.value(1, 1, k) / 128f
                val expected = compositeChannel(c.blendMode, b, ab, s, asrc, c.opacity)
                assertEquals(c.blendMode.name, expected * 255.0, out[i].value(1, 1, k).toDouble(), 2.0)
            }
            assertEquals((asrc * c.opacity + ab * (1 - asrc * c.opacity)) * 255.0,
                out[i].value(1, 1, 3).toDouble(), 1.0)
        }
        assertEquals(192, out.last().value(0, 0, 3))
        assertEquals(24, out.last().value(0, 0, 0))
    }
}

package com.rainyworks.flow.core.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.abs

class GifEncoderTest {
    private fun gradient(w: Int, h: Int, shift: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val r = (x * 255 / (w - 1) + shift) % 256
        val g = y * 255 / (h - 1)
        val b = 128
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun decode(bytes: ByteArray) = ImageIO.getImageReadersByFormatName("gif").next().apply {
        input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
    }

    @Test
    fun writesReadableAnimationWithDelays() {
        val out = ByteArrayOutputStream()
        GifEncoder(out, 64, 48).use { gif ->
            gif.addFrame(gradient(64, 48, 0), 100)
            gif.addFrame(gradient(64, 48, 40), 100)
            gif.addFrame(gradient(64, 48, 80), 250)
        }
        val reader = decode(out.toByteArray())
        assertEquals(3, reader.getNumImages(true))
        val image = reader.read(0)
        assertEquals(64, image.width)
        assertEquals(48, image.height)
        val gce = (reader.getImageMetadata(2).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode)
            .getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
        assertEquals("25", gce.getAttribute("delayTime"))
    }

    @Test
    fun colorsStayCloseToSource() {
        val src = gradient(80, 60, 0)
        val out = ByteArrayOutputStream()
        GifEncoder(out, 80, 60, dither = false).use { it.addFrame(src, 50) }
        val image = decode(out.toByteArray()).read(0)
        var total = 0L
        for (i in src.indices) {
            val a = src[i]
            val b = image.getRGB(i % 80, i / 80)
            total += abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
        }
        val meanError = total.toDouble() / (src.size * 3)
        assertTrue("mean error $meanError", meanError < 6.0)
    }

    @Test
    fun fewColorsAreExact() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val src = IntArray(32 * 32) { colors[(it / 7) % 4] }
        val out = ByteArrayOutputStream()
        GifEncoder(out, 32, 32).use { it.addFrame(src, 100) }
        val image = decode(out.toByteArray()).read(0)
        for (i in src.indices) assertEquals(src[i] and 0xFFFFFF, image.getRGB(i % 32, i / 32) and 0xFFFFFF)
    }

    @Test
    fun largeNoisyFrameExercisesDictionaryReset() {
        val rnd = java.util.Random(7)
        val colors = intArrayOf(0xFF102030.toInt(), 0xFFF0E0D0.toInt(), 0xFF80FF00.toInt(), 0xFF0000FF.toInt())
        val src = IntArray(300 * 200) { colors[rnd.nextInt(4)] }
        val out = ByteArrayOutputStream()
        GifEncoder(out, 300, 200, dither = false).use { it.addFrame(src, 100) }
        val image = decode(out.toByteArray()).read(0)
        for (i in src.indices) {
            val expected = src[i] and 0xFFFFFF
            val actual = image.getRGB(i % 300, i / 300) and 0xFFFFFF
            // Palette entries are rebuilt from 5-bit buckets, so allow tiny rounding differences.
            for (shift in intArrayOf(16, 8, 0)) {
                assertTrue(abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= 8)
            }
        }
    }
}

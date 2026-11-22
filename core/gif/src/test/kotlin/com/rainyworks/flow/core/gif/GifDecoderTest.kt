package com.rainyworks.flow.core.gif

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifDecoderTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()

    @Test
    fun decodesWhatTheEncoderWrites() {
        val bytes = ByteArrayOutputStream().also { out ->
            GifEncoder(out, 4, 3, dither = false).use { gif ->
                gif.addFrame(IntArray(12) { red }, 100)
                gif.addFrame(IntArray(12) { if (it % 4 < 2) blue else green }, 250)
            }
        }.toByteArray()

        val frames = mutableListOf<Pair<IntArray, Int>>()
        val info = GifDecoder.decode(bytes) { argb, delay -> frames += argb.copyOf() to delay }

        assertEquals(GifDecoder.Info(4, 3, 2, 350), info)
        assertEquals(GifDecoder.info(bytes), info)
        assertEquals(List(12) { red }, frames[0].first.toList())
        assertEquals(100, frames[0].second)
        assertEquals(List(12) { if (it % 4 < 2) blue else green }, frames[1].first.toList())
        assertEquals(250, frames[1].second)
    }

    @Test
    fun appliesTransparencyOffsetsAndDisposal() {
        // 3x2 canvas. Frame 1: full red, disposed to background (cleared). Frame 2: a 1x1
        // blue pixel at (2, 1) whose index 1 would be green but index 0 is transparent.
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(byteArrayOf(3, 0, 2, 0))
        out.write(byteArrayOf(0x81.toByte(), 0, 0)) // Global table of 4 colors.
        out.write(byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte(), 0, 0xFF.toByte(), 0, 0, 0, 0))
        fun control(disposal: Int, transparentIndex: Int?) {
            val flags = (disposal shl 2) or (if (transparentIndex != null) 1 else 0)
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, flags.toByte(), 5, 0, (transparentIndex ?: 0).toByte(), 0))
        }
        fun image(left: Int, top: Int, w: Int, h: Int, indices: ByteArray) {
            out.write(byteArrayOf(0x2C, left.toByte(), 0, top.toByte(), 0, w.toByte(), 0, h.toByte(), 0, 0))
            // Lzw.encode writes the minimum code size, the sub-blocks and the terminator.
            val data = ByteArrayOutputStream().also { Lzw.encode(indices, 2, it) }.toByteArray()
            out.write(data)
        }
        control(disposal = 2, transparentIndex = null)
        image(0, 0, 3, 2, ByteArray(6) { 0 })
        control(disposal = 1, transparentIndex = 2)
        image(1, 1, 2, 1, byteArrayOf(2, 1))
        out.write(0x3B)

        val frames = mutableListOf<IntArray>()
        val info = GifDecoder.decode(out.toByteArray()) { argb, delay ->
            assertEquals(50, delay)
            frames += argb.copyOf()
        }
        assertEquals(2, info.frameCount)
        assertEquals(List(6) { red }, frames[0].toList())
        // Frame 1 was cleared; (1,1) is transparent in frame 2, (2,1) is blue.
        assertEquals(listOf(0, 0, 0, 0, 0, blue), frames[1].toList())
    }
}

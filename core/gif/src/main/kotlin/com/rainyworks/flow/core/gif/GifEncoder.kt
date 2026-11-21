package com.rainyworks.flow.core.gif

import java.io.OutputStream

/**
 * Writes an animated GIF89a. Each frame gets its own 256-color palette built with
 * median cut, optionally with Floyd–Steinberg dithering.
 *
 * Frames are opaque ARGB pixels (alpha is ignored), row-major, [width] x [height].
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    /** 0 loops forever; otherwise the number of extra repetitions. */
    loopCount: Int = 0,
    private val dither: Boolean = true,
) : AutoCloseable {
    private var finished = false

    init {
        require(width in 1..65535 && height in 1..65535) { "Invalid GIF size ${width}x$height" }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        out.write(0) // No global color table.
        out.write(0)
        out.write(0)
        // NETSCAPE2.0 looping extension.
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1)
        writeShort(loopCount.coerceIn(0, 65535))
        out.write(0)
    }

    /** Adds a frame shown for [delayMs] (rounded to GIF's 10 ms resolution, minimum 20 ms). */
    fun addFrame(argb: IntArray, delayMs: Int) {
        check(!finished) { "Encoder already finished" }
        require(argb.size == width * height) { "Frame has ${argb.size} pixels, expected ${width * height}" }
        val palette = Palette.build(argb)
        val indices = if (dither) ditherToPalette(argb, palette) else mapToPalette(argb, palette)
        val tableBits = tableBits(palette.size)

        // Graphic control extension: delay, no transparency, dispose "do not dispose".
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0b0000_0100)
        writeShort(((delayMs + 5) / 10).coerceAtLeast(2))
        out.write(0)
        out.write(0)

        // Image descriptor with a local color table.
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        out.write(0x80 or (tableBits - 1))
        val entries = 1 shl tableBits
        for (i in 0 until entries) {
            val c = if (i < palette.size) palette.colors[i] else 0
            out.write(c shr 16 and 0xFF); out.write(c shr 8 and 0xFF); out.write(c and 0xFF)
        }
        Lzw.encode(indices, maxOf(2, tableBits), out)
    }

    override fun close() {
        if (finished) return
        finished = true
        out.write(0x3B)
        out.flush()
    }

    private fun mapToPalette(argb: IntArray, palette: Palette): ByteArray =
        ByteArray(argb.size) { i ->
            val p = argb[i]
            palette.indexOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF).toByte()
        }

    private fun ditherToPalette(argb: IntArray, palette: Palette): ByteArray {
        val result = ByteArray(argb.size)
        // Error rows for the current and next line, three channels each.
        var current = IntArray((width + 2) * 3)
        var next = IntArray((width + 2) * 3)
        for (y in 0 until height) {
            next.fill(0)
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val e = (x + 1) * 3
                val r = ((p shr 16 and 0xFF) + current[e] / 16).coerceIn(0, 255)
                val g = ((p shr 8 and 0xFF) + current[e + 1] / 16).coerceIn(0, 255)
                val b = ((p and 0xFF) + current[e + 2] / 16).coerceIn(0, 255)
                val index = palette.indexOf(r, g, b)
                result[y * width + x] = index.toByte()
                val c = palette.colors[index]
                val er = r - (c shr 16 and 0xFF)
                val eg = g - (c shr 8 and 0xFF)
                val eb = b - (c and 0xFF)
                current[e + 3] += er * 7; current[e + 4] += eg * 7; current[e + 5] += eb * 7
                next[e - 3] += er * 3; next[e - 2] += eg * 3; next[e - 1] += eb * 3
                next[e] += er * 5; next[e + 1] += eg * 5; next[e + 2] += eb * 5
                next[e + 3] += er; next[e + 4] += eg; next[e + 5] += eb
            }
            val swap = current
            current = next
            next = swap
        }
        return result
    }

    private fun tableBits(colors: Int): Int {
        var bits = 1
        while ((1 shl bits) < colors) bits++
        return bits
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write(v shr 8 and 0xFF)
    }
}

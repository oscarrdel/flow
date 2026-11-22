package com.rainyworks.flow.core.gif

import java.io.IOException

/**
 * Decodes animated GIFs (87a and 89a) into full-canvas ARGB frames, applying each frame's
 * position, transparency and disposal. Frames are delivered one at a time through a reused
 * buffer, so large animations do not need to fit in memory.
 */
object GifDecoder {
    /** Browsers show frames with no (or a tiny) delay at 10 fps; so do we. */
    private const val DEFAULT_DELAY_MS = 100
    private const val MIN_DELAY_MS = 20

    data class Info(val width: Int, val height: Int, val frameCount: Int, val durationMs: Long)

    /** Reads only the structure: size, frame count and total duration. */
    fun info(bytes: ByteArray): Info {
        var frames = 0
        var duration = 0L
        val reader = Reader(bytes)
        val (width, height) = readHeader(reader)
        walk(reader, width, height, decodePixels = false) { _, delayMs ->
            frames++
            duration += delayMs
        }
        return Info(width, height, frames, duration)
    }

    /**
     * Calls [onFrame] for every frame with the composed canvas (ARGB, row-major,
     * width * height) and how long it stays on screen. The array is reused between calls.
     */
    fun decode(bytes: ByteArray, onFrame: (argb: IntArray, delayMs: Int) -> Unit): Info {
        var frames = 0
        var duration = 0L
        val reader = Reader(bytes)
        val (width, height) = readHeader(reader)
        walk(reader, width, height, decodePixels = true) { argb, delayMs ->
            frames++
            duration += delayMs
            onFrame(argb!!, delayMs)
        }
        return Info(width, height, frames, duration)
    }

    private fun readHeader(reader: Reader): Pair<Int, Int> {
        val signature = String(ByteArray(6) { reader.u8().toByte() }, Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") throw IOException("Not a GIF")
        val width = reader.u16()
        val height = reader.u16()
        if (width <= 0 || height <= 0) throw IOException("Empty GIF")
        return width to height
    }

    private fun walk(
        reader: Reader,
        width: Int,
        height: Int,
        decodePixels: Boolean,
        emit: (IntArray?, Int) -> Unit,
    ) {
        val packed = reader.u8()
        reader.u8() // Background color index: modern viewers start from transparent.
        reader.u8() // Pixel aspect ratio.
        val global = if (packed and 0x80 != 0) readTable(reader, 2 shl (packed and 7)) else null

        val canvas = if (decodePixels) IntArray(width * height) else null
        var saved: IntArray? = null
        var delayMs = DEFAULT_DELAY_MS
        var transparent = -1
        var disposal = 0

        while (reader.hasMore()) {
            when (reader.u8()) {
                0x21 -> {
                    val label = reader.u8()
                    if (label == 0xF9) {
                        val size = reader.u8()
                        val flags = reader.u8()
                        val delay = reader.u16() * 10
                        val index = reader.u8()
                        reader.skip(size - 4)
                        reader.skipSubBlocks()
                        disposal = (flags shr 2) and 7
                        transparent = if (flags and 1 != 0) index else -1
                        delayMs = if (delay < MIN_DELAY_MS) DEFAULT_DELAY_MS else delay
                    } else {
                        reader.skipSubBlocks()
                    }
                }
                0x2C -> {
                    val left = reader.u16()
                    val top = reader.u16()
                    val w = reader.u16()
                    val h = reader.u16()
                    val flags = reader.u8()
                    val local = if (flags and 0x80 != 0) readTable(reader, 2 shl (flags and 7)) else null
                    val interlaced = flags and 0x40 != 0
                    val minCodeSize = reader.u8()
                    if (canvas == null) {
                        reader.skipSubBlocks()
                    } else {
                        val table = local ?: global ?: throw IOException("GIF frame without colors")
                        if (disposal == 3) saved = canvas.copyOf()
                        val indices = decodeLzw(reader, minCodeSize, w * h)
                        draw(canvas, width, height, indices, table, transparent, left, top, w, h, interlaced)
                    }
                    emit(canvas, delayMs)
                    if (canvas != null) {
                        when (disposal) {
                            2 -> clearRect(canvas, width, height, left, top, w, h)
                            3 -> saved?.copyInto(canvas)
                        }
                    }
                    saved = null
                    // Graphic control applies to the next image only.
                    delayMs = DEFAULT_DELAY_MS
                    transparent = -1
                    disposal = 0
                }
                0x3B -> return
                0x00 -> Unit // Stray padding some encoders leave.
                else -> throw IOException("Damaged GIF")
            }
        }
    }

    private fun readTable(reader: Reader, size: Int): IntArray = IntArray(size) {
        val r = reader.u8()
        val g = reader.u8()
        val b = reader.u8()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun draw(
        canvas: IntArray, width: Int, height: Int, indices: ByteArray, table: IntArray, transparent: Int,
        left: Int, top: Int, w: Int, h: Int, interlaced: Boolean,
    ) {
        var row = 0
        var pass = 0
        var step = if (interlaced) 8 else 1
        for (i in 0 until h) {
            val y = top + row
            if (y in 0 until height) {
                for (x in 0 until w) {
                    val cx = left + x
                    if (cx !in 0 until width) continue
                    val index = indices[i * w + x].toInt() and 0xFF
                    if (index == transparent || index >= table.size) continue
                    canvas[y * width + cx] = table[index]
                }
            }
            row += step
            if (interlaced && row >= h) {
                pass++
                row = when (pass) { 1 -> 4; 2 -> 2; else -> 1 }
                step = when (pass) { 1 -> 8; 2 -> 4; else -> 2 }
            }
        }
    }

    private fun clearRect(canvas: IntArray, width: Int, height: Int, left: Int, top: Int, w: Int, h: Int) {
        for (y in maxOf(0, top) until minOf(height, top + h)) {
            for (x in maxOf(0, left) until minOf(width, left + w)) canvas[y * width + x] = 0
        }
    }

    /** Standard GIF LZW; missing data leaves the remaining pixels at index 0. */
    private fun decodeLzw(reader: Reader, minCodeSize: Int, pixelCount: Int): ByteArray {
        if (minCodeSize !in 1..11) throw IOException("Damaged GIF")
        val out = ByteArray(pixelCount)
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val prefix = ShortArray(4096)
        val suffix = ByteArray(4096)
        val stack = ByteArray(4097)
        for (i in 0 until clear) suffix[i] = i.toByte()

        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1
        var next = clear + 2
        var old = -1
        var first = 0
        var bits = 0
        var datum = 0
        var written = 0
        var blockLeft = 0
        var terminated = false

        loop@ while (written < pixelCount) {
            while (bits < codeSize) {
                if (blockLeft == 0) {
                    blockLeft = reader.u8()
                    if (blockLeft == 0) {
                        terminated = true
                        break@loop
                    }
                }
                datum = datum or (reader.u8() shl bits)
                bits += 8
                blockLeft--
            }
            val code = datum and codeMask
            datum = datum ushr codeSize
            bits -= codeSize

            if (code == clear) {
                codeSize = minCodeSize + 1
                codeMask = (1 shl codeSize) - 1
                next = clear + 2
                old = -1
                continue
            }
            if (code == end) break
            if (old == -1) {
                out[written++] = suffix[code]
                old = code
                first = code
                continue
            }
            var top = 0
            var current = code
            if (code >= next) {
                stack[top++] = first.toByte()
                current = old
            }
            while (current >= clear) {
                stack[top++] = suffix[current]
                current = prefix[current].toInt()
            }
            first = suffix[current].toInt() and 0xFF
            stack[top++] = first.toByte()
            if (next < 4096) {
                prefix[next] = old.toShort()
                suffix[next] = first.toByte()
                next++
                if (next and codeMask == 0 && next < 4096) {
                    codeSize++
                    codeMask = (1 shl codeSize) - 1
                }
            }
            old = code
            while (top > 0 && written < pixelCount) out[written++] = stack[--top]
        }
        // Skip whatever is left of the image data, including the terminator.
        if (!terminated) {
            if (blockLeft > 0) reader.skip(blockLeft)
            reader.skipSubBlocks()
        }
        return out
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        fun hasMore() = position < bytes.size
        fun u8(): Int {
            if (position >= bytes.size) throw IOException("Truncated GIF")
            return bytes[position++].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun skip(count: Int) {
            position = (position + count.coerceAtLeast(0)).coerceAtMost(bytes.size)
        }
        fun skipSubBlocks() {
            while (hasMore()) {
                val size = u8()
                if (size == 0) return
                skip(size)
            }
        }
    }
}

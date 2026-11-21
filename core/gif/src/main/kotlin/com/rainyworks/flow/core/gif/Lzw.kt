package com.rainyworks.flow.core.gif

import java.io.OutputStream

/** GIF-flavored LZW compression with variable code width and data sub-blocks. */
internal object Lzw {
    private const val MAX_CODE = 4095

    fun encode(indices: ByteArray, minCodeSize: Int, out: OutputStream) {
        out.write(minCodeSize)
        val blocks = SubBlockWriter(out)
        val clear = 1 shl minCodeSize
        val end = clear + 1
        var codeSize = minCodeSize + 1
        var next = end + 1
        // Dictionary keyed by (prefix code << 8 | byte).
        val table = HashMap<Int, Int>(8192)

        var bitBuffer = 0L
        var bitCount = 0
        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code.toLong() shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                blocks.write((bitBuffer and 0xFF).toInt())
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        emit(clear)
        if (indices.isEmpty()) {
            emit(end)
        } else {
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val existing = table[key]
                if (existing != null) {
                    prefix = existing
                    continue
                }
                emit(prefix)
                if (next <= MAX_CODE) {
                    table[key] = next++
                    if (next > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emit(clear)
                    table.clear()
                    codeSize = minCodeSize + 1
                    next = end + 1
                }
                prefix = k
            }
            emit(prefix)
            emit(end)
        }
        if (bitCount > 0) blocks.write((bitBuffer and 0xFF).toInt())
        blocks.finish()
    }

    private class SubBlockWriter(private val out: OutputStream) {
        private val block = ByteArray(255)
        private var size = 0

        fun write(b: Int) {
            block[size++] = b.toByte()
            if (size == 255) flush()
        }

        private fun flush() {
            if (size == 0) return
            out.write(size)
            out.write(block, 0, size)
            size = 0
        }

        fun finish() {
            flush()
            out.write(0)
        }
    }
}

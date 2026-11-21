package com.rainyworks.flow.core.audio

import java.io.File
import java.io.RandomAccessFile

/** Streams 16-bit PCM into a WAV file and patches the header sizes on [close]. */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L

    init {
        out.write(header(0))
    }

    fun write(buffer: ByteArray, length: Int) {
        out.write(buffer, 0, length)
        dataBytes += length
    }

    val durationUs: Long get() = dataBytes * 1_000_000L / (sampleRate.toLong() * channels * BYTES_PER_SAMPLE)

    override fun close() {
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    private fun header(dataSize: Long): ByteArray {
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val b = ByteArray(HEADER_SIZE)
        fun ascii(offset: Int, text: String) = text.forEachIndexed { i, ch -> b[offset + i] = ch.code.toByte() }
        fun int(offset: Int, value: Long) { for (i in 0 until 4) b[offset + i] = (value shr (8 * i) and 0xFF).toByte() }
        fun short(offset: Int, value: Int) { b[offset] = (value and 0xFF).toByte(); b[offset + 1] = (value shr 8 and 0xFF).toByte() }
        ascii(0, "RIFF"); int(4, 36 + dataSize); ascii(8, "WAVE")
        ascii(12, "fmt "); int(16, 16); short(20, 1); short(22, channels)
        int(24, sampleRate.toLong()); int(28, byteRate.toLong()); short(32, channels * BYTES_PER_SAMPLE); short(34, 16)
        ascii(36, "data"); int(40, dataSize)
        return b
    }

    companion object {
        const val HEADER_SIZE = 44
        const val BYTES_PER_SAMPLE = 2
    }
}

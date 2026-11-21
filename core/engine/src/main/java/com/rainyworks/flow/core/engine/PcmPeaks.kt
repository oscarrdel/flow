package com.rainyworks.flow.core.engine

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

internal class PcmPeaks(private val bucketsPerSecond: Int, durationUs: Long) {
    private val expected = checkedCount(ceil(durationUs.coerceAtLeast(0) * (bucketsPerSecond / 1_000_000.0)))
    private var values = FloatArray(expected.coerceIn(1, 4096))
    private var count = 0

    fun append(buffer: ByteBuffer, timeUs: Long, sampleRate: Int, channels: Int, encoding: Int) {
        require(sampleRate > 0 && channels > 0)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw IllegalArgumentException("Formato de audio PCM incompatible.")
        }
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = buffer.remaining() / bytesPerSample / channels
        val startBucket = timeUs * (bucketsPerSecond / 1_000_000.0)
        val step = bucketsPerSecond.toDouble() / sampleRate
        if (frames > 0) {
            val end = checkedCount(kotlin.math.floor(startBucket + (frames - 1) * step) + 1)
            if (end > values.size) values = values.copyOf(max(end, (values.size * 2).coerceAtMost(MAX_BUCKETS)))
        }
        for (frame in 0 until frames) {
            var peak = 0f
            repeat(channels) {
                val sample = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val low = buffer.get().toInt() and 255
                        val middle = buffer.get().toInt() and 255
                        (low or (middle shl 8) or (buffer.get().toInt() shl 16)) / 8388608f
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                    else -> buffer.float
                }
                if (sample.isFinite()) peak = max(peak, abs(sample).coerceAtMost(1f))
            }
            val at = startBucket + frame * step
            if (at >= 0) {
                val index = at.toInt()
                values[index] = max(values[index], peak)
                count = max(count, index + 1)
            }
        }
    }

    fun finish(): FloatArray? = if (count == 0) null else values.copyOf(max(expected, count))

    private fun checkedCount(value: Double): Int {
        require(value <= MAX_BUCKETS) { "La forma de onda supera la memoria disponible." }
        return value.toInt().coerceAtLeast(0)
    }

    companion object {
        // 32 MiB de amplitudes bastan para más de 22 horas a 100 muestras por segundo.
        const val MAX_BUCKETS = 8 * 1024 * 1024
    }
}

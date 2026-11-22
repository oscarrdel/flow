package com.rainyworks.flow.core.engine.audiofx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ peaking EQ, with one independent pair of delay registers per stereo channel. */
class EqualizerProcessor(gainsDb: List<Float>) : StereoPcmProcessor() {
    private val gains = gainsDb.take(FREQUENCIES.size).map { it.coerceIn(-12f, 12f) }
    private var filters = emptyArray<Biquad>()

    override fun enabled(sampleRate: Int) = gains.indices.any { gains[it] != 0f && FREQUENCIES[it] < sampleRate / 2.0 }

    override fun startStream(sampleRate: Int, positionOffsetUs: Long) {
        filters = gains.mapIndexedNotNull { index, db ->
            val hz = FREQUENCIES[index]
            if (db == 0f || hz >= sampleRate / 2.0) null else Biquad.peaking(hz, sampleRate, db)
        }.toTypedArray()
    }

    override fun sample(input: Double, channel: Int): Double {
        var value = input
        for (filter in filters) value = filter.process(value, channel)
        return value
    }

    internal companion object {
        val FREQUENCIES = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

internal class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)

    fun process(input: Double, channel: Int): Double {
        val output = b0 * input + z1[channel]
        z1[channel] = b1 * input - a1 * output + z2[channel]
        z2[channel] = b2 * input - a2 * output
        return output
    }

    companion object {
        fun peaking(hz: Int, rate: Int, db: Float): Biquad {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * 1.4)
            val a = 10.0.pow(db / 40.0)
            val a0 = 1 + alpha / a
            val b1 = -2 * cos(w) / a0
            return Biquad((1 + alpha * a) / a0, b1, (1 - alpha * a) / a0, b1, (1 - alpha / a) / a0)
        }

        fun cutoff(hz: Int, rate: Int, highPass: Boolean): Biquad {
            val w = 2 * PI * minOf(hz.toDouble(), rate * 0.49) / rate
            val cosine = cos(w)
            val alpha = sin(w) / sqrt(2.0)
            val a0 = 1 + alpha
            val b0 = (if (highPass) 1 + cosine else 1 - cosine) / (2 * a0)
            return Biquad(b0, (if (highPass) -2 else 2) * b0, b0, -2 * cosine / a0, (1 - alpha) / a0)
        }
    }
}

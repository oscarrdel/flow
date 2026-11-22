package com.rainyworks.flow.core.engine.audiofx

import kotlin.math.roundToInt

/** Each cell stores one sample. A read and write advances exactly one frame for this channel. */
private class Delay(frames: Int) {
    private val buffer = DoubleArray(frames.coerceAtLeast(1))
    private var cursor = 0
    val delayed: Double get() = buffer[cursor]

    fun push(value: Double) {
        buffer[cursor] = value
        if (++cursor == buffer.size) cursor = 0
    }
}

/** 300 ms echo. Feedback is independent of the dry/wet control. */
class EchoProcessor(amount: Float) : StereoPcmProcessor() {
    private val wet = amount.coerceIn(0f, 1f).toDouble()
    private var lines = emptyArray<Delay>()

    override fun enabled(sampleRate: Int) = wet > 0
    override fun startStream(sampleRate: Int, positionOffsetUs: Long) {
        lines = Array(2) { Delay((sampleRate * 0.3).roundToInt()) }
    }
    override fun onReset() { lines = emptyArray() }

    override fun sample(input: Double, channel: Int): Double {
        val line = lines[channel]
        val delayed = line.delayed
        line.push(input + delayed * 0.4)
        return input * (1 - wet) + delayed * wet
    }
}

/** Schroeder network with four parallel feedback combs and two serial all-pass diffusers. */
class ReverbProcessor(amount: Float, private val shortRoom: Boolean = false) : StereoPcmProcessor() {
    private val wet = amount.coerceIn(0f, 1f).toDouble()
    private val feedback = if (shortRoom) 0.47 else 0.73
    private var combs = emptyArray<Array<Delay>>()
    private var diffusers = emptyArray<Array<Delay>>()

    override fun enabled(sampleRate: Int) = wet > 0
    override fun startStream(sampleRate: Int, positionOffsetUs: Long) {
        fun bank(vararg milliseconds: Double) = Array(2) { channel ->
            Array(milliseconds.size) { i ->
                val timeMs = (milliseconds[i] + channel * 0.52) * (if (shortRoom) 0.55 else 1.0)
                Delay((timeMs * sampleRate / 1000).roundToInt())
            }
        }
        combs = bank(29.7, 37.1, 41.1, 43.7)
        diffusers = bank(5.0, 1.7)
    }

    override fun onReset() {
        combs = emptyArray()
        diffusers = emptyArray()
    }

    override fun sample(input: Double, channel: Int): Double {
        var sum = 0.0
        for (line in combs[channel]) {
            val delayed = line.delayed
            line.push(input + feedback * delayed)
            sum += delayed
        }
        var value = sum * 0.25
        for (line in diffusers[channel]) {
            val output = line.delayed - 0.5 * value
            line.push(value + 0.5 * output)
            value = output
        }
        return input * (1 - wet) + value * wet
    }
}

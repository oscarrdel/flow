package com.rainyworks.flow.core.engine.audiofx

import com.rainyworks.flow.core.model.AnimatedProperty
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.Easing
import com.rainyworks.flow.core.model.animatedValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.withSign

/** Runs after Media3 trims and retimes the item. One stereo frame advances one playout sample. */
class ClipGainProcessor(private val clip: Clip) : StereoPcmProcessor() {
    private val keys = clip.keyframes[AnimatedProperty.VOLUME].orEmpty().sortedBy { it.timeUs }
    private val durationUs = clip.durationUs
    private var keyIndex = -1
    private var curvedSegment: Clip? = null
    private var rate = 0
    private var offsetUs = 0L
    private var frames = 0L
    private var gain = 1.0

    override fun enabled(sampleRate: Int) = needsGain(clip)

    override fun startStream(sampleRate: Int, positionOffsetUs: Long) {
        rate = sampleRate
        offsetUs = positionOffsetUs
        frames = 0
        keyIndex = -1
        curvedSegment = null
    }

    private fun volumeAt(elapsedUs: Long): Float {
        if (clip.muted) return 0f
        if (keys.isEmpty()) return clip.volume
        // Cache the current linear segment. The model evaluator sorts all keys on every call.
        val previous = keyIndex
        while (keyIndex + 1 < keys.size && keys[keyIndex + 1].timeUs <= elapsedUs) keyIndex++
        if (keyIndex != previous) curvedSegment = null
        if (keyIndex < 0) return keys.first().value
        val left = keys[keyIndex]
        if (keyIndex == keys.lastIndex || left.easing == Easing.HOLD || elapsedUs == left.timeUs) return left.value
        if (left.easing == Easing.LINEAR) {
            val right = keys[keyIndex + 1]
            val fraction = (elapsedUs - left.timeUs).toDouble() / (right.timeUs - left.timeUs)
            return (left.value + (right.value.toDouble() - left.value) * fraction).toFloat()
        }
        // Only these two keys contribute to this curve. Limit the model evaluator's work to them.
        val segment = curvedSegment ?: clip.copy(
            startUs = 0,
            keyframes = mapOf(AnimatedProperty.VOLUME to listOf(left, keys[keyIndex + 1])),
        ).also { curvedSegment = it }
        return segment.animatedValue(AnimatedProperty.VOLUME, elapsedUs, clip.volume)
    }

    override fun sample(input: Double, channel: Int): Double {
        if (channel == 0) {
            val elapsedUs = offsetUs + frames * 1_000_000L / rate
            frames++
            gain = volumeAt(elapsedUs).coerceIn(0f, 2f).toDouble() *
                fade(elapsedUs, clip.fadeInUs) * fade(durationUs - elapsedUs, clip.fadeOutUs)
        }
        val amplified = input * gain
        if (gain <= 1.0) return amplified
        // Unity slope at the knee. The headroom and the knee move continuously with automation.
        val knee = 1.0 / gain
        val excess = abs(amplified) - knee
        if (excess <= 0.0) return amplified
        val headroom = 1.0 - knee
        return (knee + headroom * excess / (headroom + excess)).withSign(amplified)
    }

    private fun fade(timeUs: Long, lengthUs: Long): Double = when {
        lengthUs <= 0 || timeUs >= lengthUs -> 1.0
        timeUs <= 0 -> 0.0
        else -> sin(PI * 0.5 * timeUs / lengthUs)
    }

    internal companion object {
        fun needsGain(clip: Clip): Boolean {
            val keys = clip.keyframes[AnimatedProperty.VOLUME].orEmpty()
            val volumeChanges = if (keys.isEmpty()) clip.volume != 1f else keys.any { it.value != 1f }
            return clip.muted || volumeChanges || clip.fadeInUs > 0 || clip.fadeOutUs > 0
        }
    }
}

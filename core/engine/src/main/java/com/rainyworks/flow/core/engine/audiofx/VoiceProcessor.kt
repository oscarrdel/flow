package com.rainyworks.flow.core.engine.audiofx

import com.rainyworks.flow.core.model.VoicePreset
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.tanh

/** Voice-band filtering followed by the coloration of the selected transducer. */
internal class VoiceProcessor(private val preset: VoicePreset) : StereoPcmProcessor() {
    private lateinit var highPass: Biquad
    private lateinit var lowPass: Biquad
    private val envelope = DoubleArray(2)
    private var attack = 0.0
    private var release = 0.0

    override fun startStream(sampleRate: Int, positionOffsetUs: Long) {
        val (lo, hi) = when (preset) {
            VoicePreset.RADIO -> 400 to 4000
            VoicePreset.PHONE -> 300 to 3400
            VoicePreset.MEGAPHONE -> 500 to 3000
            else -> error("Unsupported voice filter: $preset")
        }
        highPass = Biquad.cutoff(lo, sampleRate, highPass = true)
        lowPass = Biquad.cutoff(hi, sampleRate, highPass = false)
        attack = exp(-1.0 / (sampleRate * 0.003))
        release = exp(-1.0 / (sampleRate * 0.080))
        envelope.fill(0.0)
    }

    override fun sample(input: Double, channel: Int): Double {
        val value = lowPass.process(highPass.process(input, channel), channel)
        return when (preset) {
            VoicePreset.RADIO -> tanh(1.25 * value) / 1.25
            VoicePreset.PHONE -> round(value * 127) / 127
            VoicePreset.MEGAPHONE -> {
                val peak = abs(value)
                val coefficient = if (peak > envelope[channel]) attack else release
                envelope[channel] = peak + coefficient * (envelope[channel] - peak)
                val level = envelope[channel]
                val gain = if (level > 0.25) (0.25 + (level - 0.25) / 4) / level else 1.0
                tanh(3.0 * value * gain)
            }
            else -> error("Unsupported voice filter: $preset")
        }
    }
}

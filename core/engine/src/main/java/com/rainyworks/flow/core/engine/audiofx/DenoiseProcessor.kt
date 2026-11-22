package com.rainyworks.flow.core.engine.audiofx

/** Background noise reduction for both channels; see [SpectralDenoiser]. */
class DenoiseProcessor(private val strength: Float) : StereoPcmProcessor() {
    private var channels = arrayOf(SpectralDenoiser(strength), SpectralDenoiser(strength))

    override fun enabled(sampleRate: Int) = strength > 0f

    override fun startStream(sampleRate: Int, positionOffsetUs: Long) {
        channels = arrayOf(SpectralDenoiser(strength), SpectralDenoiser(strength))
    }

    override fun sample(input: Double, channel: Int): Double = channels[channel].process(input)
}

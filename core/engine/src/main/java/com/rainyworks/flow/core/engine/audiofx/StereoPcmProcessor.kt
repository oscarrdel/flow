package com.rainyworks.flow.core.engine.audiofx

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Frame-preserving interleaved stereo processing in the negotiated encoding. */
@OptIn(UnstableApi::class)
abstract class StereoPcmProcessor : BaseAudioProcessor() {
    protected open fun enabled(sampleRate: Int): Boolean = true
    protected abstract fun startStream(sampleRate: Int, positionOffsetUs: Long)
    protected abstract fun sample(input: Double, channel: Int): Double

    final override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.encoding !in SUPPORTED_ENCODINGS
        ) throw UnhandledAudioFormatException(inputAudioFormat)
        return if (enabled(inputAudioFormat.sampleRate)) inputAudioFormat else AudioFormat.NOT_SET
    }

    final override fun onFlush(streamMetadata: StreamMetadata) {
        if (outputAudioFormat != AudioFormat.NOT_SET) {
            startStream(inputAudioFormat.sampleRate, streamMetadata.positionOffsetUs)
        }
    }

    final override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        require(size % inputAudioFormat.bytesPerFrame == 0) { "Incomplete stereo PCM frame" }
        val output = replaceOutputBuffer(size)
        if (outputAudioFormat == AudioFormat.NOT_SET) {
            output.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.hasRemaining()) {
                for (channel in 0..1) {
                    output.putFloat(sample(inputBuffer.float.toDouble(), channel).coerceIn(-1.0, 1.0).toFloat())
                }
            }
        } else {
            while (inputBuffer.hasRemaining()) {
                for (channel in 0..1) {
                    val value = sample(inputBuffer.short / 32768.0, channel)
                    output.putShort((value * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        output.flip()
    }

    private companion object {
        val SUPPORTED_ENCODINGS = intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
    }
}

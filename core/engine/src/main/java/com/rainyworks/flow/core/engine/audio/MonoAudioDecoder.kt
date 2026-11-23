package com.rainyworks.flow.core.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.ByteOrder

/** Decodes part of a file's audio to mono float samples at a chosen rate. */
object MonoAudioDecoder {
    /**
     * Decodes [fromUs, toUs) of the source to mono at [targetRate] Hz. Returns the samples and the
     * source time of the first one.
     */
    suspend fun decode(context: Context, uri: Uri, targetRate: Int, fromUs: Long, toUs: Long): Pair<FloatArray, Long> {
        val app = context.applicationContext
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val out = FloatBuilder()
                var firstUs = -1L
                // Linear resampling: position of the next output sample in input samples.
                var nextInput = 0.0
                var inputIndex = 0L
                var previous = 0f
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val size = extractor.readSampleData(checkNotNull(decoder.getInputBuffer(index)), 0)
                            if (size < 0 || extractor.sampleTime > toUs) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (index >= 0) {
                        val buffer = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.asShortBuffer()
                        var frameUs = info.presentationTimeUs.toDouble()
                        val step = rate / targetRate.toDouble()
                        while (shorts.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += shorts.get() / 32768f }
                            val value = sum / channels
                            val sampleUs = frameUs.toLong()
                            frameUs += 1_000_000.0 / rate
                            if (sampleUs !in fromUs until toUs) continue
                            if (firstUs < 0) firstUs = sampleUs
                            while (nextInput <= inputIndex) {
                                val fraction = (nextInput - (inputIndex - 1)).toFloat().coerceIn(0f, 1f)
                                out.add(previous + (value - previous) * fraction)
                                nextInput += step
                            }
                            previous = value
                            inputIndex++
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                return out.toArray() to (if (firstUs < 0) fromUs else firstUs)
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private class FloatBuilder {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    private const val TIMEOUT_US = 10_000L
}

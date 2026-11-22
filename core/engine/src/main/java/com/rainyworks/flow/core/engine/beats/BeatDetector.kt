package com.rainyworks.flow.core.engine.beats

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.rainyworks.flow.core.model.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max

/**
 * Finds the beats of a song so clips can be cut to the music. Audio is decoded to mono at a
 * reduced rate and beats are the sudden rises of energy (with extra weight on the bass,
 * where kicks live) that stand out from the surrounding second.
 */
class BeatDetector(context: Context) {
    private val app = context.applicationContext

    /** Beat times in microseconds of the source file. */
    suspend fun detect(media: MediaRef): LongArray = withContext(Dispatchers.IO) {
        val (samples, rate) = decodeMono(Uri.parse(media.uri))
        onsets(samples, rate)
    }

    private suspend fun decodeMono(uri: Uri): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                // Averaging blocks of samples keeps the work small; beats live well below 2 kHz.
                var step = max(1, rate / TARGET_RATE)
                val out = FloatArrayBuilder()
                var accumulator = 0f
                var accumulated = 0
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val size = extractor.readSampleData(checkNotNull(decoder.getInputBuffer(index)), 0)
                            if (size < 0) {
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
                        step = max(1, rate / TARGET_RATE)
                    } else if (index >= 0) {
                        val buffer = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.asShortBuffer()
                        while (shorts.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += shorts.get() / 32768f }
                            accumulator += sum / channels
                            if (++accumulated == step) {
                                out.add(accumulator / step)
                                accumulator = 0f
                                accumulated = 0
                            }
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                return out.toArray() to rate / step
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    companion object {
        private const val TARGET_RATE = 11_025
        private const val TIMEOUT_US = 10_000L
        private const val MIN_GAP_US = 280_000L

        /** Beat times (microseconds) in mono [samples] at [rate] Hz. */
        fun onsets(samples: FloatArray, rate: Int): LongArray {
            if (rate <= 0 || samples.size < rate / 4) return LongArray(0)
            val hop = max(1, rate / 86) // About 11.6 ms.
            val window = hop * 2
            val frames = (samples.size - window) / hop
            if (frames < 8) return LongArray(0)

            // Log energy of the whole signal and of a low-passed copy (bass, ~150 Hz).
            val alpha = (2 * Math.PI * 150.0 / rate).toFloat().let { it / (it + 1f) }
            val low = FloatArray(samples.size)
            var y = 0f
            for (i in samples.indices) {
                y += alpha * (samples[i] - y)
                low[i] = y
            }
            fun logEnergy(signal: FloatArray, start: Int): Float {
                var e = 0.0
                for (i in start until start + window) e += signal[i] * signal[i]
                // A floor around -40 dB keeps near-silence from looking like a burst of energy.
                return ln(1e-4 + e / window).toFloat()
            }
            val strength = FloatArray(frames)
            var previousFull = logEnergy(samples, 0)
            var previousLow = logEnergy(low, 0)
            for (f in 1 until frames) {
                val full = logEnergy(samples, f * hop)
                val bass = logEnergy(low, f * hop)
                strength[f] = max(0f, bass - previousLow) + 0.6f * max(0f, full - previousFull)
                previousFull = full
                previousLow = bass
            }

            // A beat stands out from the surrounding second and is the peak of its neighborhood.
            val half = (rate / hop) / 2
            val peakReach = 3
            val minGapFrames = (MIN_GAP_US * rate / 1_000_000L / hop).toInt()
            val beats = ArrayList<Long>()
            var lastBeat = -minGapFrames
            var windowSum = 0.0
            var windowStart = 0
            var windowEnd = 0
            for (f in 0 until frames) {
                while (windowEnd < minOf(frames, f + half)) windowSum += strength[windowEnd++]
                while (windowStart < f - half) windowSum -= strength[windowStart++]
                val mean = windowSum / (windowEnd - windowStart)
                val s = strength[f]
                if (s <= mean * 1.6 + 0.15) continue
                var isPeak = true
                for (n in maxOf(0, f - peakReach)..minOf(frames - 1, f + peakReach)) {
                    if (strength[n] > s) { isPeak = false; break }
                }
                if (!isPeak || f - lastBeat < minGapFrames) continue
                beats += f.toLong() * hop * 1_000_000L / rate
                lastBeat = f
            }
            return beats.toLongArray()
        }
    }
}

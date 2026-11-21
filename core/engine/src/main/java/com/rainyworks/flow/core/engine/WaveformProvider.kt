package com.rainyworks.flow.core.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WaveformProvider(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.cacheDir, "waveforms")

    suspend fun peaks(media: MediaRef, bucketsPerSecond: Int): FloatArray? {
        require(bucketsPerSecond > 0) { "La resolución de la forma de onda debe ser positiva." }
        if (!media.hasAudio && media.type != MediaType.AUDIO) return null
        return withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${media.uri}\n$bucketsPerSecond".toByteArray(Charsets.UTF_8))
            val filename = digest.joinToString("") { "%02x".format(it) }
            val file = File(directory, filename)
            locks[(digest[0].toInt() and 255) % locks.size].withLock {
                readMediaOrNull { readCache(file, media) }?.let { return@withLock it }
                val decoded = readMediaOrNull { decode(media, bucketsPerSecond) } ?: return@withLock null
                currentCoroutineContext().ensureActive()
                readMediaOrNull { writeCache(file, media, decoded) }
                decoded
            }
        }
    }

    private suspend fun decode(media: MediaRef, bucketsPerSecond: Int): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, Uri.parse(media.uri), null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val accumulator = PcmPeaks(bucketsPerSecond, media.durationUs)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                decodeRaw(extractor, format, accumulator)
            } else {
                val decoder = MediaCodec.createDecoderByType(mime)
                try {
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    decodePackets(extractor, decoder, format, accumulator)
                } finally {
                    decoder.release()
                }
            }
            return accumulator.finish()
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeRaw(extractor: MediaExtractor, format: MediaFormat, peaks: PcmPeaks) {
        var buffer = ByteBuffer.allocateDirect(8192)
        val pcm = PcmFormat(format)
        while (extractor.sampleTime >= 0) {
            currentCoroutineContext().ensureActive()
            val size = Math.toIntExact(extractor.sampleSize)
            if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size)
            buffer.clear()
            val bytes = extractor.readSampleData(buffer, 0)
            if (bytes < 0) break
            buffer.position(0).limit(bytes)
            pcm.append(peaks, buffer, extractor.sampleTime)
            extractor.advance()
        }
    }

    private suspend fun decodePackets(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        sourceFormat: MediaFormat,
        peaks: PcmPeaks,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var pcm = PcmFormat(sourceFormat)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val input = requireNotNull(decoder.getInputBuffer(index))
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    inputEnded = size < 0
                    if (inputEnded) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 5_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) pcm = PcmFormat(decoder.outputFormat)
            if (index >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val output = requireNotNull(decoder.getOutputBuffer(index))
                        output.limit(info.offset + info.size).position(info.offset)
                        pcm.append(peaks, output, info.presentationTimeUs)
                    }
                } finally {
                    decoder.releaseOutputBuffer(index, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    private class PcmFormat(format: MediaFormat) {
        private val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        fun append(peaks: PcmPeaks, buffer: ByteBuffer, timeUs: Long) =
            peaks.append(buffer, timeUs, rate, channels, encoding)
    }

    private suspend fun readCache(file: File, media: MediaRef): FloatArray? {
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { data ->
            if (data.readInt() != CACHE_VERSION || data.readLong() != media.durationUs || data.readLong() != media.sizeBytes) return null
            val count = data.readInt()
            if (count !in 1..PcmPeaks.MAX_BUCKETS || file.length() != 24L + count * 4L) return null
            val context = currentCoroutineContext()
            return FloatArray(count) { index ->
                if (index % 4096 == 0) context.ensureActive()
                data.readFloat().also { if (it !in 0f..1f) return null }
            }
        }
    }

    private suspend fun writeCache(file: File, media: MediaRef, peaks: FloatArray) {
        directory.mkdirs()
        val temp = File.createTempFile("picos-", ".tmp", directory)
        try {
            val context = currentCoroutineContext()
            DataOutputStream(temp.outputStream().buffered()).use { data ->
                data.writeInt(CACHE_VERSION)
                data.writeLong(media.durationUs)
                data.writeLong(media.sizeBytes)
                data.writeInt(peaks.size)
                peaks.forEachIndexed { index, peak ->
                    if (index % 4096 == 0) context.ensureActive()
                    data.writeFloat(peak)
                }
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val CACHE_VERSION = 1
        val locks = Array(16) { Mutex() }
    }
}

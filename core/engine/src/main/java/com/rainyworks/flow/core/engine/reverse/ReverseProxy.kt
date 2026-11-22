package com.rainyworks.flow.core.engine.reverse

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.rainyworks.flow.core.engine.GoldfishAvc
import com.rainyworks.flow.core.engine.MediaProbe
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * Creates a reversed copy of a video for the "reverse" clip tool.
 *
 * Reversing compressed video directly needs every frame of a group of pictures in memory.
 * Instead the source is first transcoded so that every frame is a key frame; those frames
 * are then copied into a new MP4 in reverse order without decoding them again. Audio is
 * decoded to PCM placed on the video's timeline, reversed from a temporary file in blocks
 * and re-encoded as AAC.
 *
 * Copies are stored in app files (not the cache) because saved projects point at them, and
 * are shared between projects by source, so reversing the same video again is instant.
 */
@OptIn(UnstableApi::class)
class ReverseProxy(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "reverse")

    suspend fun create(media: MediaRef, onProgress: (Float) -> Unit): MediaRef = withContext(Dispatchers.IO) {
        require(media.type == MediaType.VIDEO) { "Only videos can be reversed" }
        dir.mkdirs()
        val key = cacheKey(media)
        val target = File(dir, "$key.mp4")
        locks.getOrPut(key) { Mutex() }.withLock {
            if (target.length() > 0) {
                readable(target, media)?.let { return@withContext it }
                target.delete() // A damaged copy is rebuilt below.
            }
            val work = File(dir, "work-$key-${System.nanoTime()}").apply { mkdirs() }
            try {
                val intra = File(work, "intra.mp4")
                val audio = File(work, "audio.m4a")
                val pcm = File(work, "pcm.raw")
                val partial = File(work, "out.mp4")
                transcodeAllKeyFrames(media, intra) { onProgress(it * TRANSCODE_SHARE) }
                val videoEndUs = videoEndUs(intra)
                val hasAudio = reverseAudio(intra, videoEndUs, pcm, audio) { onProgress(TRANSCODE_SHARE + it * AUDIO_SHARE) }
                muxReversed(intra, videoEndUs, if (hasAudio) audio else null, partial) {
                    onProgress(TRANSCODE_SHARE + AUDIO_SHARE + it * (1f - TRANSCODE_SHARE - AUDIO_SHARE))
                }
                val result = readable(partial, media) ?: throw IOException("Reversed file is unreadable")
                // Publish atomically so readers never see a half-written copy.
                if (!partial.renameTo(target)) throw IOException("Could not store the reversed copy")
                onProgress(1f)
                result.copy(uri = Uri.fromFile(target).toString(), sizeBytes = target.length())
            } finally {
                work.deleteRecursively()
            }
        }
    }

    private suspend fun readable(file: File, source: MediaRef): MediaRef? =
        MediaProbe(app).probe(Uri.fromFile(file))?.takeIf { it.durationUs > 0 }?.copy(displayName = source.displayName)

    private fun cacheKey(media: MediaRef): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${media.uri}|${media.sizeBytes}|${media.durationUs}|v2".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    // region step 1: every frame a key frame

    private suspend fun transcodeAllKeyFrames(media: MediaRef, output: File, progress: (Float) -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            val shortSide = minOf(media.displayWidth, media.displayHeight)
            val effects = if (shortSide > MAX_SHORT_SIDE) listOf(Presentation.createForShortSide(MAX_SHORT_SIDE)) else emptyList()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(media.uri))
                .setEffects(Effects(emptyList(), effects))
                .build()
            val pixels = (if (shortSide > MAX_SHORT_SIDE) MAX_SHORT_SIDE else shortSide.coerceAtLeast(1)).toLong()
            // Intra-only H.264 needs a generous bitrate to look like the source.
            val bitrate = (pixels * pixels * 16 / 9 * 30 * INTRA_BITS_PER_PIXEL).toInt().coerceIn(4_000_000, 60_000_000)
            val encoder = DefaultEncoderFactory.Builder(app)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setiFrameIntervalSeconds(0f)
                        .setMaxBFrames(0)
                        .setBitrate(bitrate)
                        .build(),
                )
                .setEnableFallback(true)
                .build()
            val decoder = DefaultDecoderFactory.Builder(app)
                .setMediaCodecSelector(GoldfishAvc.selector)
                .setEnableDecoderFallback(true)
                .build()
            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(app)
                .setLooper(Looper.getMainLooper())
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .setAssetLoaderFactory(DefaultAssetLoaderFactory(app, decoder, Clock.DEFAULT, null))
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done.completeExceptionally(exportException)
                    }
                })
                .build()
            val holder = ProgressHolder()
            try {
                transformer.start(item, output.absolutePath)
                while (withTimeoutOrNull(200L) { done.await() } == null) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
                }
            } finally {
                if (!done.isCompleted) transformer.cancel()
            }
        }

    /** End of the video track: last frame start plus its duration. */
    private fun videoEndUs(intra: File): Long = withExtractor(intra) { extractor ->
        val track = findTrack(extractor, "video/") ?: throw IOException("No video track")
        val format = extractor.getTrackFormat(track)
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
    }

    // endregion

    // region step 2: audio

    /** Returns false when the source has no audio track. */
    private suspend fun reverseAudio(
        source: File,
        videoEndUs: Long,
        pcmFile: File,
        output: File,
        progress: (Float) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val track = findTrack(extractor, "audio/") ?: return false
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val pcm = decodeToTimeline(extractor, format, pcmFile, videoEndUs)
            progress(0.4f)
            encodeReversedPcm(pcmFile, pcm.rate, pcm.channels, output) { progress(0.4f + 0.6f * it) }
            return true
        } finally {
            extractor.release()
        }
    }

    private data class PcmLayout(val rate: Int, val channels: Int)

    /**
     * Decodes audio to 16-bit PCM written at the position given by each buffer's timestamp,
     * filling gaps with silence and padding to the end of the video, so reversing keeps
     * audio and video aligned.
     */
    private suspend fun decodeToTimeline(extractor: MediaExtractor, format: MediaFormat, pcmFile: File, videoEndUs: Long): PcmLayout {
        val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            RandomAccessFile(pcmFile, "rw").use { raw ->
                raw.setLength(0)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var writtenFrames = 0L
                val zeros = ByteArray(64 * 1024)
                fun writeSilence(frames: Long) {
                    var bytes = frames * channels * 2
                    while (bytes > 0) {
                        val n = minOf(bytes, zeros.size.toLong()).toInt()
                        raw.write(zeros, 0, n)
                        bytes -= n
                    }
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val input = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (input >= 0) {
                            val size = extractor.readSampleData(checkNotNull(decoder.getInputBuffer(input)), 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val output = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // HE-AAC and similar can decode to a different rate or channel count.
                        val actual = decoder.outputFormat
                        rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (output >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val frameBytes = channels * 2
                            val data = checkNotNull(decoder.getOutputBuffer(output))
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            val startFrame = (info.presentationTimeUs.coerceAtLeast(0L) * rate / 1_000_000.0).roundToLong()
                            if (startFrame > writtenFrames) {
                                writeSilence(startFrame - writtenFrames)
                                writtenFrames = startFrame
                            } else if (startFrame < writtenFrames) {
                                // Overlap with what is already written: drop the repeated part.
                                val skip = minOf((writtenFrames - startFrame) * frameBytes, data.remaining().toLong()).toInt()
                                data.position(data.position() + skip)
                            }
                            val frames = data.remaining() / frameBytes
                            data.limit(data.position() + frames * frameBytes)
                            var position = writtenFrames * frameBytes
                            while (data.hasRemaining()) position += raw.channel.write(data, position)
                            raw.seek((writtenFrames + frames) * frameBytes)
                            writtenFrames += frames
                        }
                        decoder.releaseOutputBuffer(output, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                val endFrame = (videoEndUs * rate / 1_000_000.0).roundToLong()
                if (endFrame > writtenFrames) {
                    writeSilence(endFrame - writtenFrames)
                } else if (endFrame in 1 until writtenFrames) {
                    raw.setLength(endFrame * channels * 2)
                }
            }
            return PcmLayout(rate, channels)
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
    }

    /** Reads 16-bit PCM from the end of [pcmFile] backwards and encodes it as AAC into [output]. */
    private suspend fun encodeReversedPcm(pcmFile: File, rate: Int, channels: Int, output: File, progress: (Float) -> Unit) {
        val frameBytes = channels * 2
        val totalFrames = pcmFile.length() / frameBytes
        val format = MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK_FRAMES * frameBytes)
        }
        val encoder = MediaCodec.createEncoderByType(MimeTypes.AUDIO_AAC)
        try {
            val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrack = -1
            var finished = false
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                val info = MediaCodec.BufferInfo()
                val block = ByteArray(BLOCK_FRAMES * frameBytes)
                val reversed = ByteArray(BLOCK_FRAMES * frameBytes)
                var framesQueued = 0L
                var remaining = totalFrames
                var inputDone = false
                RandomAccessFile(pcmFile, "r").use { raw ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (!inputDone) {
                            val input = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (input >= 0) {
                                val buffer = checkNotNull(encoder.getInputBuffer(input))
                                if (remaining <= 0) {
                                    encoder.queueInputBuffer(input, 0, 0, framesToUs(framesQueued, rate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val frames = minOf(remaining, (buffer.capacity() / frameBytes).toLong(), BLOCK_FRAMES.toLong()).toInt()
                                    val start = remaining - frames
                                    raw.seek(start * frameBytes)
                                    raw.readFully(block, 0, frames * frameBytes)
                                    // Reverse the order of whole frames, keeping each frame's channels intact.
                                    for (i in 0 until frames) {
                                        System.arraycopy(block, (frames - 1 - i) * frameBytes, reversed, i * frameBytes, frameBytes)
                                    }
                                    buffer.clear()
                                    buffer.put(reversed, 0, frames * frameBytes)
                                    encoder.queueInputBuffer(input, 0, frames * frameBytes, framesToUs(framesQueued, rate), 0)
                                    framesQueued += frames
                                    remaining = start
                                    progress(framesQueued.toFloat() / totalFrames.coerceAtLeast(1))
                                }
                            }
                        }
                        val out = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            muxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                        } else if (out >= 0) {
                            val data = checkNotNull(encoder.getOutputBuffer(out))
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerTrack >= 0) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer.writeSampleData(muxerTrack, data, info)
                            }
                            encoder.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                if (muxerTrack < 0) throw IOException("The audio encoder produced no output")
                muxer.stop() // Errors here must fail the operation.
                finished = true
            } finally {
                if (!finished && muxerTrack >= 0) runCatching { muxer.stop() }
                muxer.release()
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    // endregion

    // region step 3: reversed video samples + reversed audio

    private suspend fun muxReversed(intra: File, videoEndUs: Long, audio: File?, output: File, progress: (Float) -> Unit) {
        val video = MediaExtractor()
        try {
            val sound = if (audio != null) MediaExtractor() else null
            try {
                val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var started = false
                var finished = false
                try {
                    video.setDataSource(intra.path)
                    val videoTrack = findTrack(video, "video/") ?: throw IOException("No video track")
                    val videoFormat = video.getTrackFormat(videoTrack)
                    video.selectTrack(videoTrack)
                    if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
                    }

                    // Collect sample times; every sample must be a key frame for the copy to decode.
                    val times = ArrayList<Long>()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val time = video.sampleTime
                        if (time < 0) break
                        if (video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) {
                            throw IOException("This device could not encode an all-key-frame copy")
                        }
                        times += time
                        video.advance()
                    }
                    if (times.isEmpty()) throw IOException("Empty video")
                    times.sort()
                    // Each frame is shown from its time until the next one; the last until the end.
                    val ends = LongArray(times.size) { i ->
                        if (i + 1 < times.size) times[i + 1] else maxOf(videoEndUs, times[i] + defaultFrameUs(times))
                    }
                    val endUs = ends.last()

                    val outVideo = muxer.addTrack(videoFormat)
                    var outAudio = -1
                    if (sound != null) {
                        sound.setDataSource(audio!!.path)
                        val track = findTrack(sound, "audio/")
                        if (track != null) {
                            sound.selectTrack(track)
                            outAudio = muxer.addTrack(sound.getTrackFormat(track))
                        }
                    }
                    muxer.start()
                    started = true

                    var buffer = ByteBuffer.allocateDirect(DEFAULT_MAX_SAMPLE)
                    val info = MediaCodec.BufferInfo()
                    for (index in times.indices.reversed()) {
                        currentCoroutineContext().ensureActive()
                        video.seekTo(times[index], MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        val needed = video.sampleSize
                        if (needed < 0) continue
                        if (needed > buffer.capacity()) buffer = ByteBuffer.allocateDirect(needed.toInt())
                        val size = video.readSampleData(buffer, 0)
                        if (size < 0) continue
                        // Mirror the whole interval the frame was shown, not just its start.
                        info.set(0, size, endUs - ends[index], MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(outVideo, buffer, info)
                        val done = times.size - index
                        if (done % 15 == 0) progress(done.toFloat() / times.size)
                    }
                    // An explicit end keeps the last frame's duration instead of guessing it.
                    info.set(0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    muxer.writeSampleData(outVideo, buffer, info)

                    if (sound != null && outAudio >= 0) {
                        val audioBuffer = ByteBuffer.allocateDirect(DEFAULT_MAX_SAMPLE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = sound.readSampleData(audioBuffer, 0)
                            if (size < 0) break
                            info.set(0, size, sound.sampleTime, sound.sampleFlags)
                            muxer.writeSampleData(outAudio, audioBuffer, info)
                            sound.advance()
                        }
                    }
                    muxer.stop() // Errors here must fail the operation.
                    finished = true
                    progress(1f)
                } finally {
                    if (started && !finished) runCatching { muxer.stop() }
                    muxer.release()
                }
            } finally {
                sound?.release()
            }
        } finally {
            video.release()
        }
    }

    // endregion

    private fun defaultFrameUs(times: List<Long>): Long =
        if (times.size < 2) 33_333L else ((times.last() - times.first()) / (times.size - 1)).coerceAtLeast(1L)

    private fun <T> withExtractor(file: File, block: (MediaExtractor) -> T): T {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return block(extractor)
        } finally {
            extractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        }

    private fun framesToUs(frames: Long, rate: Int) = frames * 1_000_000L / rate

    private companion object {
        const val MAX_SHORT_SIDE = 1080
        const val INTRA_BITS_PER_PIXEL = 0.5
        const val AUDIO_BITRATE = 192_000
        const val BLOCK_FRAMES = 4_096
        const val TIMEOUT_US = 10_000L
        const val DEFAULT_MAX_SAMPLE = 1024 * 1024
        const val TRANSCODE_SHARE = 0.7f
        const val AUDIO_SHARE = 0.1f

        /** One lock per source so two requests never build or delete the same copy at once. */
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

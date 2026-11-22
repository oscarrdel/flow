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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Creates a reversed copy of a video for the "reverse" clip tool.
 *
 * Reversing compressed video directly needs every frame of a group of pictures in memory.
 * Instead the source is first transcoded so that every frame is a key frame; those frames
 * can then be copied into a new MP4 in reverse order without decoding them again. Audio is
 * decoded to PCM, reversed from a temporary file in blocks and re-encoded as AAC.
 *
 * Results are cached by source, so reversing the same video again is instant.
 */
@OptIn(UnstableApi::class)
class ReverseProxy(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.cacheDir, "reverse")

    suspend fun create(media: MediaRef, onProgress: (Float) -> Unit): MediaRef = withContext(Dispatchers.IO) {
        require(media.type == MediaType.VIDEO) { "Only videos can be reversed" }
        dir.mkdirs()
        val target = File(dir, "${cacheKey(media)}.mp4")
        if (target.length() > 0) return@withContext describe(target, media)

        val intra = File.createTempFile("reverse-intra-", ".mp4", dir)
        val audio = File.createTempFile("reverse-audio-", ".m4a", dir)
        val pcm = File.createTempFile("reverse-pcm-", ".raw", dir)
        val partial = File.createTempFile("reverse-out-", ".mp4", dir)
        try {
            transcodeAllKeyFrames(media, intra) { onProgress(it * TRANSCODE_SHARE) }
            val hasAudio = reverseAudio(intra, pcm, audio) { onProgress(TRANSCODE_SHARE + it * AUDIO_SHARE) }
            muxReversed(intra, if (hasAudio) audio else null, partial) {
                onProgress(TRANSCODE_SHARE + AUDIO_SHARE + it * (1f - TRANSCODE_SHARE - AUDIO_SHARE))
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
            }
            onProgress(1f)
            describe(target, media)
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            intra.delete()
            audio.delete()
            pcm.delete()
            partial.delete()
        }
    }

    private suspend fun describe(file: File, source: MediaRef): MediaRef {
        val probed = MediaProbe(app).probe(Uri.fromFile(file)) ?: throw IOException("Reversed file is unreadable")
        return probed.copy(displayName = source.displayName, rotationDegrees = probed.rotationDegrees)
    }

    private fun cacheKey(media: MediaRef): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${media.uri}|${media.sizeBytes}|${media.durationUs}|v1".toByteArray())
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

    // endregion

    // region step 2: audio

    /** Returns false when the source has no audio track. */
    private suspend fun reverseAudio(source: File, pcmFile: File, output: File, progress: (Float) -> Unit): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            val track = findTrack(extractor, "audio/") ?: return false
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            RandomAccessFile(pcmFile, "rw").use { raw ->
                raw.setLength(0)
                decodeToPcm(extractor, format) { chunk -> raw.channel.write(chunk) }
            }
            progress(0.4f)
            encodeReversedPcm(pcmFile, rate, channels, output) { progress(0.4f + 0.6f * it) }
            return true
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeToPcm(extractor: MediaExtractor, format: MediaFormat, consume: (ByteBuffer) -> Unit) {
        val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
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
                if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = checkNotNull(decoder.getOutputBuffer(output))
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        consume(data)
                    }
                    decoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
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
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrack = -1
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
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            if (muxerTrack >= 0) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    // endregion

    // region step 3: reversed video samples + reversed audio

    private suspend fun muxReversed(intra: File, audio: File?, output: File, progress: (Float) -> Unit) {
        val video = MediaExtractor()
        val sound = if (audio != null) MediaExtractor() else null
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
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
            val last = times.last()

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

            val maxSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                DEFAULT_MAX_SAMPLE
            }
            var buffer = ByteBuffer.allocateDirect(maxSize)
            val info = MediaCodec.BufferInfo()
            for ((index, time) in times.asReversed().withIndex()) {
                currentCoroutineContext().ensureActive()
                video.seekTo(time, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var size = video.readSampleData(buffer, 0)
                if (size < 0) continue
                if (size > buffer.capacity()) {
                    buffer = ByteBuffer.allocateDirect(size)
                    size = video.readSampleData(buffer, 0)
                }
                info.set(0, size, last - time, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(outVideo, buffer, info)
                if (index % 15 == 0) progress(index.toFloat() / times.size)
            }

            if (sound != null && outAudio >= 0) {
                val audioBuffer = ByteBuffer.allocateDirect(DEFAULT_MAX_SAMPLE)
                while (true) {
                    val size = sound.readSampleData(audioBuffer, 0)
                    if (size < 0) break
                    info.set(0, size, sound.sampleTime, sound.sampleFlags)
                    muxer.writeSampleData(outAudio, audioBuffer, info)
                    sound.advance()
                }
            }
            progress(1f)
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release()
            video.release()
            sound?.release()
        }
    }

    // endregion

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
        const val DEFAULT_MAX_SAMPLE = 4 * 1024 * 1024
        const val TRANSCODE_SHARE = 0.7f
        const val AUDIO_SHARE = 0.1f
    }
}

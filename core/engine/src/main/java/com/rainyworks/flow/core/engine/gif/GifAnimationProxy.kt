package com.rainyworks.flow.core.engine.gif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.rainyworks.flow.core.gif.GifDecoder
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Media3 shows a GIF as its first frame only. This turns an animated GIF into an H.264 video
 * twice as wide as the animation: the color on the left and the transparency, as gray, on
 * the right ([UnpackAlphaEffect] puts them back together). The copy holds whole loops and
 * lasts at least [MIN_COPY_US], so most clips play from a single item.
 */
@OptIn(UnstableApi::class)
class GifAnimationProxy(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "gif")

    /** Returns [media] with [MediaRef.animationUri] set, or unchanged for a still GIF. */
    suspend fun withAnimation(media: MediaRef): MediaRef = withContext(Dispatchers.IO) {
        if (media.type != MediaType.GIF || media.animationUri != null) return@withContext media
        val bytes = app.contentResolver.openInputStream(Uri.parse(media.uri))?.use { it.readBytes() }
            ?: return@withContext media
        val info = GifDecoder.info(bytes)
        if (info.frameCount < 2 || info.durationMs <= 0) return@withContext media
        dir.mkdirs()
        val key = key(bytes)
        val target = File(dir, "$key.mp4")
        val loops = maxOf(1, ((MIN_COPY_US / 1_000 + info.durationMs - 1) / info.durationMs).toInt())
        val copyUs = info.durationMs * 1_000L * loops
        locks.getOrPut(key) { Mutex() }.withLock {
            if (target.length() <= 0) build(bytes, info, loops, target)
        }
        media.copy(
            durationUs = info.durationMs * 1_000L,
            animationUri = Uri.fromFile(target).toString(),
            animationDurationUs = copyUs,
        )
    }

    private suspend fun build(bytes: ByteArray, info: GifDecoder.Info, loops: Int, target: File) {
        val work = File(dir, "work-${System.nanoTime()}").apply { mkdirs() }
        try {
            val (width, height) = packedSize(info.width, info.height)
            val frames = mutableListOf<Pair<File, Int>>()
            val packed = Bitmap.createBitmap(width * 2, height, Bitmap.Config.ARGB_8888)
            val source = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(packed)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val color = IntArray(info.width * info.height)
            val alpha = IntArray(info.width * info.height)
            GifDecoder.decode(bytes) { argb, delayMs ->
                splitAlpha(argb, info.width, color, alpha)
                packed.eraseColor(android.graphics.Color.BLACK)
                source.setPixels(color, 0, info.width, 0, 0, info.width, info.height)
                canvas.drawBitmap(source, null, Rect(0, 0, width, height), paint)
                source.setPixels(alpha, 0, info.width, 0, 0, info.width, info.height)
                canvas.drawBitmap(source, null, Rect(width, 0, width * 2, height), paint)
                val file = File(work, "${frames.size}.png")
                file.outputStream().use { packed.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frames += file to delayMs
            }
            source.recycle()
            packed.recycle()
            currentCoroutineContext().ensureActive()

            val items = (0 until loops).flatMap { frames }.map { (file, delayMs) ->
                EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(file))
                        .setImageDurationMs(delayMs.toLong())
                        .build(),
                ).setFrameRate(FRAME_RATE).build()
            }
            val partial = File(work, "out.mp4")
            encode(items, partial)
            if (!partial.renameTo(target)) throw IOException("Could not store the GIF animation")
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun encode(items: List<EditedMediaItem>, output: File) = withContext(Dispatchers.Main.immediate) {
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(app)
            .setLooper(Looper.getMainLooper())
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(app)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
                    .setEnableFallback(true)
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItems(items).build()
        try {
            transformer.start(Composition.Builder(sequence).build(), output.absolutePath)
            done.await()
        } finally {
            if (!done.isCompleted) transformer.cancel()
        }
    }

    private fun key(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes + "v1".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)

    companion object {
        private const val MIN_COPY_US = 10_000_000L
        private const val FRAME_RATE = 30
        private const val BITRATE = 8_000_000
        /** Hardware encoders accept even sizes; keep the packed frame within 1920x1080. */
        private const val MAX_PACKED_WIDTH = 1920
        private const val MAX_HEIGHT = 1080
        private val locks = ConcurrentHashMap<String, Mutex>()

        internal fun packedSize(width: Int, height: Int): Pair<Int, Int> {
            val scale = minOf(1f, MAX_PACKED_WIDTH / 2f / width, MAX_HEIGHT.toFloat() / height)
            fun even(v: Float) = maxOf(2, (v.roundToInt() + 1) / 2 * 2)
            return even(width * scale) to even(height * scale)
        }

        /**
         * Splits ARGB into opaque color (transparent pixels take the color of the nearest
         * opaque pixel to their left, so compression does not bleed dark fringes) and alpha
         * as gray.
         */
        internal fun splitAlpha(argb: IntArray, width: Int, color: IntArray, alpha: IntArray) {
            var last = 0xFF000000.toInt()
            for (i in argb.indices) {
                if (i % width == 0) last = 0xFF000000.toInt()
                val pixel = argb[i]
                val a = pixel ushr 24
                if (a > 0) last = pixel or 0xFF000000.toInt()
                color[i] = if (a > 0) pixel or 0xFF000000.toInt() else last
                alpha[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
            }
        }
    }
}

package com.rainyworks.flow.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ThumbnailProvider(context: Context) {
    private val app = context.applicationContext
    private data class Key(val uri: String, val timeUs: Long, val height: Int)

    private val bitmaps = object : LruCache<Key, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceIn(1L shl 20, 24L shl 20).toInt(),
    ) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val decoderLock = Mutex()
    private val retrievers = object : LruCache<String, MediaMetadataRetriever>(3) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: MediaMetadataRetriever, newValue: MediaMetadataRetriever?) {
            readMediaOrNull { oldValue.release() }
        }
    }

    suspend fun frameAt(media: MediaRef, sourceTimeUs: Long, targetHeightPx: Int): Bitmap? {
        require(targetHeightPx > 0) { "La altura de la miniatura debe ser positiva." }
        if (media.type == MediaType.AUDIO) return null
        val image = media.type == MediaType.IMAGE || media.type == MediaType.GIF
        val timeUs = sourceTimeUs.coerceAtLeast(0L).let { time ->
            if (media.durationUs > 0L) minOf(time, media.durationUs - 1L) else time
        }
        val key = Key(media.uri, if (image) 0L else timeUs / 500_000L * 500_000L, targetHeightPx)
        bitmaps.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            decoderLock.withLock {
                bitmaps.get(key)?.let { return@withLock it }
                currentCoroutineContext().ensureActive()
                val bitmap = readMediaOrNull {
                    if (image) decodeImage(Uri.parse(media.uri), targetHeightPx)
                    else videoFrame(media, key)
                }
                if (bitmap != null) bitmaps.put(key, bitmap)
                bitmap
            }
        }
    }

    private fun decodeImage(uri: Uri, height: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            val width = scaledWidth(info.size.width, info.size.height, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(width, height)
        }

    private fun videoFrame(media: MediaRef, key: Key): Bitmap? {
        val retriever = retrievers.get(media.uri) ?: MediaMetadataRetriever().let { fresh ->
            try {
                fresh.setDataSource(app, Uri.parse(media.uri))
                retrievers.put(media.uri, fresh)
                fresh
            } catch (failure: Exception) {
                fresh.release()
                throw failure
            }
        }
        val width = scaledWidth(media.displayWidth, media.displayHeight, key.height)
        return retriever.getScaledFrameAtTime(key.timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, key.height)
    }

    private fun scaledWidth(width: Int, height: Int, targetHeight: Int): Int =
        (width.coerceAtLeast(1).toDouble() / height.coerceAtLeast(1) * targetHeight).roundToInt().coerceAtLeast(1)

    /** Libera los decodificadores sin invalidar mapas de bits que aún muestre la interfaz. */
    suspend fun release() = withContext(Dispatchers.IO) {
        decoderLock.withLock {
            retrievers.evictAll()
            bitmaps.evictAll()
        }
    }
}

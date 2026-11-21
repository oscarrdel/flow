package com.rainyworks.flow.core.engine

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaProbe(context: Context) {
    private val app = context.applicationContext
    private val resolver get() = app.contentResolver

    suspend fun probe(uri: Uri): MediaRef? = withContext(Dispatchers.IO) {
        readMediaOrNull {
            val (name, size) = fileInfo(uri)
            val extension = name.substringAfterLast('.', "").lowercase()
            val declared = readMediaOrNull { resolver.getType(uri) }?.substringBefore(';')?.lowercase()
            val mime = declared?.takeIf { it.startsWith("image/") || it.startsWith("audio/") || it.startsWith("video/") }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mime?.startsWith("image/") == true) {
                return@readMediaOrNull probeImage(uri, mime, name, size)
            }
            if (mime == null || mime == "application/octet-stream") {
                readMediaOrNull { probeImage(uri, "", name, size) }?.let { return@readMediaOrNull it }
            }
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(app, uri)
                fun metadata(key: Int) = retriever.extractMetadata(key)
                val hasVideo = metadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio = metadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val durationMs = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if ((!hasVideo && !hasAudio) || durationMs <= 0L) return@readMediaOrNull null
                MediaRef(
                    uri = uri.toString(),
                    type = if (hasVideo) MediaType.VIDEO else MediaType.AUDIO,
                    durationUs = durationMs * 1_000L,
                    width = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    height = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                    rotationDegrees = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                    hasAudio = hasAudio,
                    hasVideo = hasVideo,
                    displayName = name,
                    sizeBytes = size,
                    mimeType = mime ?: metadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty(),
                )
            }
        }
    }

    private fun fileInfo(uri: Uri): Pair<String, Long> {
        val file = uri.path?.takeIf { uri.scheme == ContentResolver.SCHEME_FILE }?.let(::File)
        var name = file?.name ?: uri.lastPathSegment.orEmpty()
        var size = file?.length() ?: 0L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            readMediaOrNull {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                        }
                    }
            }
            if (size <= 0) size = readMediaOrNull { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } } ?: 0L
        }
        return name to size.coerceAtLeast(0L)
    }

    private fun probeImage(uri: Uri, mime: String, name: String, size: Long): MediaRef? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val isGif = bounds.outMimeType == "image/gif" || mime == "image/gif"
        val rotation = if (isGif) 0 else readMediaOrNull {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE -> 90
                    ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE -> 270
                    else -> 0
                }
            }
        } ?: 0
        return MediaRef(
            uri = uri.toString(),
            type = if (isGif) MediaType.GIF else MediaType.IMAGE,
            durationUs = if (isGif) resolver.openInputStream(uri)?.use(::gifDurationUs) ?: 0L else 0L,
            width = bounds.outWidth,
            height = bounds.outHeight,
            rotationDegrees = rotation,
            displayName = name,
            sizeBytes = size,
            mimeType = bounds.outMimeType ?: mime,
        )
    }
}

internal inline fun <T> readMediaOrNull(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    null
}

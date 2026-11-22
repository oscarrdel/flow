package com.rainyworks.flow.core.engine.snapshot

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectSlice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves the picture at one moment of the project as a photo in the gallery. A fraction of a
 * second around that moment is rendered with the same engine as exports, so every layer,
 * grade, effect and title is included, and its first frame is kept.
 */
class FrameSnapshot(context: Context) {
    private val app = context.applicationContext

    /** Renders the frame at [timeUs] at [heightPx] (short side) and returns it. */
    suspend fun capture(project: Project, timeUs: Long, heightPx: Int = 1080): Bitmap {
        val start = timeUs.coerceIn(0L, (project.durationUs - 1).coerceAtLeast(0L))
        val end = minOf(project.durationUs, start + SLICE_US).coerceAtLeast(start + 1)
        val slice = ProjectSlice.slice(project, start, end)
        val video = File.createTempFile("frame-", ".mp4", app.cacheDir)
        try {
            Exporter(app).render(slice, ExportSettings(heightPx = heightPx, frameRate = 30), video) { _, _ -> }
            return withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.absolutePath)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: throw IOException("No frame")
                } finally {
                    retriever.release()
                }
            }
        } finally {
            video.delete()
        }
    }

    /** Captures the frame at [timeUs] and stores it as a JPEG in Pictures/Flow. */
    suspend fun saveToGallery(project: Project, timeUs: Long, name: String): Uri {
        val bitmap = capture(project, timeUs)
        return withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Flow")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the photo")
            try {
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    ?: throw IOException("Could not write the photo")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            } finally {
                bitmap.recycle()
            }
        }
    }

    private companion object {
        /** Long enough for the encoder to produce a frame, short enough to be quick. */
        const val SLICE_US = 200_000L
    }
}

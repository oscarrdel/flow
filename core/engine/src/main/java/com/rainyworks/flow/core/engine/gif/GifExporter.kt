package com.rainyworks.flow.core.engine.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.transformer.ExportException
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.ExportState
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.gif.GifEncoder
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectSlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

data class GifSettings(
    val startUs: Long,
    val endUs: Long,
    /** Width of the longer side in pixels. */
    val maxSidePx: Int = 480,
    val frameRate: Int = 12,
    val dither: Boolean = true,
) {
    init {
        require(endUs > startUs) { "Empty range" }
        require(maxSidePx in 64..1080 && frameRate in 1..30)
    }

    val durationUs: Long get() = endUs - startUs

    companion object {
        const val MAX_DURATION_US = 15_000_000L
    }
}

/** Exports a short range of a project as an animated GIF into Pictures/Flow. */
class GifExporter(context: Context, private val exporter: Exporter) {
    private val app = context.applicationContext

    /** Rough size: GIF frames compress to about a third of their 8-bit pixels. */
    fun estimateSizeBytes(project: Project, settings: GifSettings): Long {
        val (w, h) = frameSize(project, settings)
        val frames = settings.durationUs * settings.frameRate / 1_000_000L
        return (w.toLong() * h * frames) / 3
    }

    fun export(project: Project, settings: GifSettings, displayName: String): Flow<ExportState> = channelFlow {
        val slice = ProjectSlice.slice(project, settings.startUs, minOf(settings.endUs, settings.startUs + GifSettings.MAX_DURATION_US))
        if (slice.isEmpty) {
            send(ExportState.Failed("Elige un fragmento que contenga clips."))
            return@channelFlow
        }
        val video = File.createTempFile("flow-gif-", ".mp4", app.cacheDir)
        val gif = File.createTempFile("flow-gif-", ".gif", app.cacheDir)
        try {
            send(ExportState.Running(0f, null))
            val (width, height) = frameSize(slice, settings)
            val renderSettings = ExportSettings(heightPx = minOf(width, height), frameRate = settings.frameRate)
            exporter.render(slice, renderSettings, video) { p, eta -> trySend(ExportState.Running(p * RENDER_SHARE, eta)) }
            encodeFrames(video, gif, width, height, slice.durationUs, settings) { p ->
                trySend(ExportState.Running(RENDER_SHARE + p * (1 - RENDER_SHARE), null))
            }
            send(publish(gif, displayName))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ExportException) {
            send(ExportState.Failed("No se pudo generar el fragmento para el GIF."))
        } catch (_: IOException) {
            send(ExportState.Failed("No se pudo guardar el GIF. Comprueba el almacenamiento."))
        } catch (_: RuntimeException) {
            send(ExportState.Failed("No se pudo crear el GIF con estos ajustes."))
        } finally {
            video.delete()
            gif.delete()
        }
    }.flowOn(Dispatchers.IO).buffer(Channel.CONFLATED)

    private suspend fun encodeFrames(
        video: File,
        gif: File,
        width: Int,
        height: Int,
        durationUs: Long,
        settings: GifSettings,
        onProgress: (Float) -> Unit,
    ) {
        val frameCount = maxOf(1, (durationUs * settings.frameRate / 1_000_000L).toInt())
        val delayMs = 1000 / settings.frameRate
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val pixels = IntArray(width * height)
            gif.outputStream().buffered().use { out ->
                GifEncoder(out, width, height, dither = settings.dither).use { encoder ->
                    for (i in 0 until frameCount) {
                        coroutineContext.ensureActive()
                        val timeUs = i * 1_000_000L / settings.frameRate
                        val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                            ?: continue
                        val scaled = if (frame.width == width && frame.height == height) frame else
                            Bitmap.createScaledBitmap(frame, width, height, true).also { frame.recycle() }
                        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                        scaled.recycle()
                        encoder.addFrame(pixels, delayMs)
                        onProgress((i + 1f) / frameCount)
                    }
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun frameSize(project: Project, settings: GifSettings): Pair<Int, Int> {
        val ratio = project.canvas.aspectRatio.value
        val (w, h) = if (ratio >= 1f) {
            settings.maxSidePx to (settings.maxSidePx / ratio).roundToInt()
        } else {
            (settings.maxSidePx * ratio).roundToInt() to settings.maxSidePx
        }
        return (w / 2 * 2).coerceAtLeast(2) to (h / 2 * 2).coerceAtLeast(2)
    }

    private fun publish(file: File, displayName: String): ExportState.Success {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${displayName.ifBlank { "Flow" }}.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Flow")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Cannot open output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (error: IOException) {
            resolver.delete(uri, null, null)
            throw error
        }
        return ExportState.Success(uri, file.length())
    }

    private companion object {
        const val RENDER_SHARE = 0.5f
    }
}

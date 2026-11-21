package com.rainyworks.flow.core.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.rainyworks.flow.core.model.Project
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ExportState {
    data class Running(val progress: Float, val etaMs: Long?) : ExportState
    data class Success(val uri: Uri, val sizeBytes: Long) : ExportState
    data class Failed(val message: String) : ExportState
    data object Cancelled : ExportState
}

@OptIn(UnstableApi::class)
class Exporter(context: Context) {
    private val app = context.applicationContext
    private val workers = mutableSetOf<Job>()

    fun estimateSizeBytes(project: Project, settings: ExportSettings): Long =
        com.rainyworks.flow.core.engine.estimateSizeBytes(project, settings)

    /** Cada recolección inicia una exportación independiente. Cancelar la recolección libera sus recursos. */
    fun export(project: Project, settings: ExportSettings, displayName: String): Flow<ExportState> = channelFlow {
        var outcome: ExportState? = null
        val worker = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                if (project.isEmpty) {
                    outcome = ExportState.Failed("Agrega al menos un clip antes de exportar.")
                    return@launch
                }
                val composition = CompositionFactory.build(project, settings)
                val temp = File.createTempFile("flow-", ".mp4", app.cacheDir)
                try {
                    trySend(ExportState.Running(0f, null))
                    encode(composition, temp, settings, settings.bitrate(project)) { trySend(it) }
                    outcome = publish(temp, displayName)
                } finally {
                    temp.delete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ExportException) {
                outcome = ExportState.Failed("No se pudo codificar el proyecto (código ${error.errorCode}).")
            } catch (_: SecurityException) {
                outcome = ExportState.Failed("Falta permiso para leer el medio o guardar el video.")
            } catch (_: IOException) {
                outcome = ExportState.Failed("No se pudo leer el medio o guardar el video. Comprueba el almacenamiento.")
            } catch (_: RuntimeException) {
                outcome = ExportState.Failed("No se pudo exportar el proyecto con estos ajustes.")
            }
        }
        synchronized(workers) { workers.add(worker) }
        try {
            worker.start()
            worker.join()
            send(outcome ?: ExportState.Cancelled)
        } finally {
            synchronized(workers) { workers.remove(worker) }
        }
    }.buffer(Channel.CONFLATED)

    /** Cancela las exportaciones activas de esta instancia y avisa a los recolectores que sigan activos. */
    fun cancel() {
        val active = synchronized(workers) { workers.toList() }
        active.forEach { it.cancel() }
    }

    private suspend fun encode(
        composition: Composition,
        temp: File,
        settings: ExportSettings,
        bitrate: Int,
        onProgress: (ExportState.Running) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        val completed = CompletableDeferred<Unit>()
        val videoMime = if (settings.codec == VideoCodec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val encoder = DefaultEncoderFactory.Builder(app)
            .setEnableFallback(false)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(AUDIO_BITRATE).build())
            .build()
        val transformer = Transformer.Builder(app)
            .setLooper(Looper.getMainLooper())
            .setVideoMimeType(videoMime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completed.complete(Unit)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    completed.completeExceptionally(exportException)
                }
            })
            .build()
        val holder = ProgressHolder()
        val startMs = SystemClock.elapsedRealtime()
        try {
            transformer.start(composition, temp.absolutePath)
            while (withTimeoutOrNull(250L) { completed.await() } == null) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val percent = holder.progress.coerceIn(0, 100)
                    val etaMs = if (percent > 0) (SystemClock.elapsedRealtime() - startMs) * (100 - percent) / percent else null
                    onProgress(ExportState.Running(percent / 100f, etaMs))
                }
            }
        } finally {
            if (!completed.isCompleted) transformer.cancel()
        }
    }

    private suspend fun publish(temp: File, displayName: String): ExportState.Success {
        val context = currentCoroutineContext()
        context.ensureActive()
        val size = temp.length()
        if (size == 0L) throw IOException("El archivo de video está vacío.")
        val name = displayName.substringAfterLast('/').substringAfterLast('\\').trim().ifEmpty { "Flow" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, if (name.endsWith(".mp4", true)) name else "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Flow")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear el video.")
        var published = false
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("No se pudo abrir el video.")
            stream.use { output ->
                temp.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        context.ensureActive()
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        output.write(buffer, 0, bytes)
                    }
                }
            }
            context.ensureActive()
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("No se pudo publicar el video.")
            published = true
            return ExportState.Success(uri, size)
        } finally {
            if (!published) resolver.delete(uri, null, null)
        }
    }
}

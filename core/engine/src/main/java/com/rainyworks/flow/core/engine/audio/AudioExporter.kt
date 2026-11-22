package com.rainyworks.flow.core.engine.audio

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.rainyworks.flow.core.engine.ExportState
import com.rainyworks.flow.core.model.Project
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

@OptIn(UnstableApi::class)
class AudioExporter(context: Context) {
    private val app = context.applicationContext

    fun estimateSizeBytes(project: Project, settings: AudioExportSettings): Long =
        com.rainyworks.flow.core.engine.audio.estimateSizeBytes(project, settings)

    /** Cada recolección inicia una exportación. Cancelar su Job libera el render y los temporales. */
    fun export(project: Project, settings: AudioExportSettings, displayName: String): Flow<ExportState> = channelFlow {
        fun progress(value: Float) { trySend(ExportState.Running(value, null)) }
        var intermediate: File? = null
        var converted: File? = null
        try {
            require(project.durationUs > 0) { "Agrega al menos un clip antes de exportar." }
            require(settings.sampleRate in aacSampleRates) { "Frecuencia AAC incompatible: ${settings.sampleRate} Hz." }
            if (settings.format != AudioFormat.WAV) {
                require(settings.bitrateKbps in 1..(Int.MAX_VALUE / 1000)) { "Bitrate de audio inválido." }
            }
            progress(0f)
            val composition = audioComposition(project, settings.sampleRate)
            intermediate = File.createTempFile("flow-render-", ".m4a", app.cacheDir)
            renderAac(app, composition, intermediate, settings) { progress(it * 0.7f) }
            progress(0.7f)
            val output = if (settings.format == AudioFormat.M4A) intermediate else {
                converted = File.createTempFile("flow-audio-", ".${settings.format.extension}", app.cacheDir)
                convertAac(intermediate, converted, settings, project.durationUs) { progress(0.7f + it * 0.3f) }
                converted
            }
            val result = publish(output, displayName, settings.format)
            progress(1f)
            send(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExportException) {
            send(ExportState.Failed("No se pudo renderizar el audio AAC (código ${e.errorCode})."))
        } catch (e: Exception) {
            send(ExportState.Failed(e.localizedMessage ?: "No se pudo exportar el audio."))
        } finally {
            converted?.delete()
            intermediate?.delete()
        }
    }.flowOn(Dispatchers.IO).buffer(Channel.CONFLATED)

    private suspend fun publish(file: File, displayName: String, format: AudioFormat): ExportState.Success {
        val context = currentCoroutineContext()
        context.ensureActive()
        val size = file.length()
        if (size == 0L) throw IOException("El archivo de audio está vacío.")
        val name = displayName.substringAfterLast('/').substringAfterLast('\\').trim().ifEmpty { "Flow" }
        val suffix = ".${format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, if (name.endsWith(suffix, true)) name else name + suffix)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Flow")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear el archivo de audio.")
        var published = false
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("No se pudo abrir el audio.")
            stream.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            context.ensureActive()
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("No se pudo publicar el audio.")
            published = true
            return ExportState.Success(uri, size)
        } finally {
            if (!published) resolver.delete(uri, null, null)
        }
    }
}

private val aacSampleRates = setOf(
    7_350, 8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000, 64_000, 88_200, 96_000,
)

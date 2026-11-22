package com.rainyworks.flow.core.engine.audio

import com.rainyworks.flow.core.model.Project
import kotlin.math.roundToLong

enum class AudioFormat(internal val extension: String, internal val mimeType: String) {
    MP3("mp3", "audio/mpeg"),
    M4A("m4a", "audio/mp4"),
    WAV("wav", "audio/wav"),
}

data class AudioExportSettings(
    val format: AudioFormat,
    val bitrateKbps: Int = 192,
    val sampleRate: Int = 44_100,
)

/** El mezclador del proyecto entrega estéreo. La estimación comprimida excluye los metadatos. */
fun estimateSizeBytes(project: Project, settings: AudioExportSettings): Long {
    if (project.durationUs <= 0) return 0
    val seconds = project.durationUs / 1_000_000.0
    return when (settings.format) {
        AudioFormat.WAV -> 44 + (seconds * settings.sampleRate).roundToLong() * 4
        else -> (seconds * settings.bitrateKbps * 125).roundToLong()
    }
}

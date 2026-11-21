package com.rainyworks.flow.core.engine

import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.Track
import com.rainyworks.flow.core.model.TrackKind
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class VideoCodec { H264, HEVC }

/**
 * [heightPx] is the nominal resolution ("1080p"): the length of the canvas' short side,
 * so 1080p means 1920x1080 for 16:9 and 1080x1920 for 9:16.
 */
data class ExportSettings(
    val heightPx: Int = 1080,
    val frameRate: Int = 30,
    val videoBitrate: Int? = null,
    val codec: VideoCodec = VideoCodec.H264,
) {
    init {
        require(heightPx >= 2 && frameRate > 0) { "La altura o la frecuencia de fotogramas es inválida." }
        require(videoBitrate == null || videoBitrate > 0) { "La tasa de bits debe ser positiva." }
    }

    private val shortSide: Int get() = heightPx / 2 * 2

    private fun longSide(project: Project): Int {
        val ratio = project.canvas.aspectRatio.value.toDouble()
        val factor = if (ratio >= 1.0) ratio else 1.0 / ratio
        return (shortSide * factor / 2).roundToInt().coerceIn(1, Int.MAX_VALUE / 2) * 2
    }

    internal fun width(project: Project): Int =
        if (project.canvas.aspectRatio.value >= 1f) longSide(project) else shortSide

    internal fun height(project: Project): Int =
        if (project.canvas.aspectRatio.value >= 1f) shortSide else longSide(project)

    /** Bits per second the encoder targets when no explicit [videoBitrate] is set. */
    fun bitrate(project: Project): Int {
        val bitsPerPixel = if (codec == VideoCodec.H264) 0.13 else 0.085
        return videoBitrate ?: (width(project).toDouble() * height(project) * frameRate * bitsPerPixel)
            .roundToInt().coerceIn(256_000, 120_000_000)
    }
}

/** Estimación basada en las tasas de bits objetivo y un margen del 1 % para el contenedor MP4. */
fun estimateSizeBytes(project: Project, settings: ExportSettings): Long {
    if (project.durationUs <= 0) return 0L
    val audio = project.tracks.any { track ->
        !track.hidden && track.kind in mediaTracks && track.clips.any { track.isAudible(it) }
    }
    val bitsPerSecond = settings.bitrate(project).toLong() + if (audio) AUDIO_BITRATE else 0
    return (project.durationUs / 1_000_000.0 * bitsPerSecond / 8 * 1.01).roundToLong()
}

internal const val AUDIO_BITRATE = 128_000
internal val mediaTracks = setOf(TrackKind.MAIN, TrackKind.OVERLAY, TrackKind.AUDIO)

internal fun Track.isAudible(clip: Clip): Boolean =
    !muted && !clip.muted && clip.volume > 0f &&
        (clip.media?.hasAudio == true || clip.media?.type == MediaType.AUDIO)

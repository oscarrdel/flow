package com.rainyworks.flow.feature.export

import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainyworks.flow.core.data.ProjectRepository
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.VideoCodec
import com.rainyworks.flow.core.engine.audio.AudioExportSettings
import com.rainyworks.flow.core.engine.audio.AudioFormat
import com.rainyworks.flow.core.engine.gif.GifSettings
import com.rainyworks.flow.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class ExportResolution(val heightPx: Int, val label: String) {
    P480(480, "480p"),
    P720(720, "720p"),
    P1080(1080, "1080p"),
    K2(1440, "2K"),
    K4(2160, "4K"),
}

enum class ExportQuality(val factor: Double, val label: String) {
    Low(0.5, "Baja"),
    Medium(1.0, "Media"),
    High(1.6, "Alta"),
    Custom(1.0, "Personalizada"),
}

enum class ExportKind(val label: String) { Video("Video"), Gif("GIF"), Audio("Solo audio") }

enum class ExportPreset(
    val label: String,
    val resolution: ExportResolution?,
    val frameRate: Int?,
    val codec: VideoCodec?,
    val quality: ExportQuality?,
) {
    Custom(
        label = "Personalizado",
        resolution = null,
        frameRate = null,
        codec = null,
        quality = null,
    ),
    TikTokReels(
        label = "TikTok / Reels",
        resolution = ExportResolution.P1080,
        frameRate = 30,
        codec = VideoCodec.H264,
        quality = ExportQuality.High,
    ),
    YouTube(
        label = "YouTube",
        resolution = ExportResolution.P1080,
        frameRate = 30,
        codec = VideoCodec.H264,
        quality = ExportQuality.High,
    ),
    YouTube4K(
        label = "YouTube 4K",
        resolution = ExportResolution.K4,
        frameRate = 30,
        codec = VideoCodec.HEVC,
        quality = ExportQuality.High,
    ),
    InstagramFeed(
        label = "Instagram feed",
        resolution = ExportResolution.P1080,
        frameRate = 30,
        codec = VideoCodec.H264,
        quality = ExportQuality.Medium,
    ),
    WhatsApp(
        label = "WhatsApp",
        resolution = ExportResolution.P720,
        frameRate = 30,
        codec = VideoCodec.H264,
        quality = ExportQuality.Medium,
    ),
    HighQuality(
        label = "Alta calidad",
        resolution = ExportResolution.K4,
        frameRate = 60,
        codec = VideoCodec.HEVC,
        quality = ExportQuality.High,
    );

    val summary: String
        get() {
            val res = resolution ?: return "Ajustes manuales"
            val fps = frameRate ?: return "Ajustes manuales"
            val codecLabel = when (codec) {
                VideoCodec.H264 -> "H.264"
                VideoCodec.HEVC -> "H.265"
                null -> return "Ajustes manuales"
            }
            return "${res.label} · $fps fps · $codecLabel"
        }

    fun matches(
        resolution: ExportResolution,
        frameRate: Int,
        codec: VideoCodec,
        quality: ExportQuality,
    ): Boolean = this.resolution == resolution &&
        this.frameRate == frameRate &&
        this.codec == codec &&
        this.quality == quality

    companion object {
        val platformPresets: List<ExportPreset> = entries.filter { it != Custom }
    }
}

sealed interface ExportPhase {
    data object Configuring : ExportPhase
    data class Rendering(val progress: Float, val etaMs: Long?) : ExportPhase
    data class Done(val uri: Uri, val sizeBytes: Long) : ExportPhase
    data class Error(val message: String) : ExportPhase
}

data class ExportUiState(
    val project: Project? = null,
    val resolution: ExportResolution = ExportResolution.P1080,
    val frameRate: Int = 30,
    val quality: ExportQuality = ExportQuality.Medium,
    val customVideoBitrateMbps: Float = DEFAULT_CUSTOM_BITRATE_MBPS,
    val codec: VideoCodec = VideoCodec.H264,
    val kind: ExportKind = ExportKind.Video,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val audioBitrateKbps: Int = 192,
    val gifStartUs: Long = 0L,
    val gifEndUs: Long = 0L,
    val gifMaxSidePx: Int = 480,
    val gifFrameRate: Int = 12,
    val estimatedBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val phase: ExportPhase = ExportPhase.Configuring,
    internal val preferredPreset: ExportPreset? = null,
) {
    val hasEnoughSpace: Boolean get() = freeBytes == 0L || estimatedBytes * 11 / 10 < freeBytes

    val isProjectVertical: Boolean
        get() = project?.canvas?.aspectRatio?.let { it.height > it.width } ?: true

    val selectedPreset: ExportPreset
        get() {
            val matching = ExportPreset.platformPresets.filter {
                it.matches(resolution, frameRate, codec, quality)
            }
            return when {
                matching.isEmpty() -> ExportPreset.Custom
                matching.size == 1 -> matching.first()
                preferredPreset in matching -> preferredPreset!!
                else -> if (isProjectVertical) {
                    matching.firstOrNull { it == ExportPreset.TikTokReels } ?: matching.first()
                } else {
                    matching.firstOrNull { it == ExportPreset.YouTube } ?: matching.first()
                }
            }
        }

    val showVerticalAspectHint: Boolean
        get() = project != null && !isProjectVertical && selectedPreset == ExportPreset.TikTokReels

    val recommendedVideoBitrateMbps: Float?
        get() = project?.let {
            ExportSettings(heightPx = resolution.heightPx, frameRate = frameRate, codec = codec).bitrate(it) / 1_000_000f
        }
}

class ExportViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val manager: ExportManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val project = repository.load(projectId)
            val gifEnd = minOf(project?.durationUs ?: 0L, DEFAULT_GIF_US)
            _state.update { it.copy(project = project, freeBytes = freeBytes(), gifStartUs = 0L, gifEndUs = gifEnd) }
            recompute()
        }
        viewModelScope.launch {
            manager.job.collect { job ->
                if (job?.projectId == projectId) _state.update { it.copy(phase = job.phase) }
            }
        }
    }

    fun setResolution(value: ExportResolution) = updateSettings { it.copy(resolution = value, preferredPreset = null) }
    fun setFrameRate(value: Int) = updateSettings { it.copy(frameRate = value, preferredPreset = null) }
    fun setQuality(value: ExportQuality) = updateSettings { state ->
        if (value == ExportQuality.Custom && state.quality != ExportQuality.Custom) {
            val initialMbps = state.project?.let { project ->
                roundCustomBitrateMbps(state.settings(project).bitrate(project) / 1_000_000f)
            } ?: state.customVideoBitrateMbps
            state.copy(quality = value, customVideoBitrateMbps = initialMbps, preferredPreset = null)
        } else {
            state.copy(quality = value, preferredPreset = null)
        }
    }
    fun setCustomVideoBitrate(mbps: Float) = updateSettings {
        it.copy(customVideoBitrateMbps = roundCustomBitrateMbps(mbps), preferredPreset = null)
    }
    fun setCodec(value: VideoCodec) = updateSettings { it.copy(codec = value, preferredPreset = null) }

    fun applyPreset(preset: ExportPreset) {
        val resolution = preset.resolution ?: return
        val frameRate = preset.frameRate ?: return
        val codec = preset.codec ?: return
        val quality = preset.quality ?: return
        updateSettings {
            it.copy(
                resolution = resolution,
                frameRate = frameRate,
                codec = codec,
                quality = quality,
                preferredPreset = preset,
            )
        }
    }
    fun setKind(value: ExportKind) = updateSettings { it.copy(kind = value) }
    fun setAudioFormat(value: AudioFormat) = updateSettings { it.copy(audioFormat = value) }
    fun setAudioBitrate(kbps: Int) = updateSettings { it.copy(audioBitrateKbps = kbps) }
    fun setGifSize(maxSidePx: Int) = updateSettings { it.copy(gifMaxSidePx = maxSidePx) }
    fun setGifFrameRate(fps: Int) = updateSettings { it.copy(gifFrameRate = fps) }

    /** Moves the GIF range, keeping it non-empty and within the maximum GIF length. */
    fun setGifRange(startUs: Long, endUs: Long) = updateSettings { state ->
        val duration = state.project?.durationUs ?: 0L
        val start = startUs.coerceIn(0L, (duration - MIN_GIF_US).coerceAtLeast(0L))
        val end = endUs.coerceIn(start + MIN_GIF_US, minOf(duration, start + GifSettings.MAX_DURATION_US))
        state.copy(gifStartUs = start, gifEndUs = end)
    }

    fun start() {
        val current = _state.value
        val project = current.project ?: return
        when (current.kind) {
            ExportKind.Video -> manager.start(project, current.settings(project))
            ExportKind.Gif -> manager.start(project, current.gifSettings())
            ExportKind.Audio -> manager.start(project, current.audioSettings())
        }
    }

    /** Stops rendering; the project itself is untouched. */
    fun cancel() = manager.cancel()

    fun backToSettings() {
        manager.clear(projectId)
        _state.update { it.copy(phase = ExportPhase.Configuring) }
    }

    private fun updateSettings(transform: (ExportUiState) -> ExportUiState) {
        if (_state.value.phase is ExportPhase.Rendering) return
        _state.update(transform)
        recompute()
    }

    private fun recompute() {
        val state = _state.value
        val project = state.project ?: return
        val bytes = when (state.kind) {
            ExportKind.Video -> manager.estimateSizeBytes(project, state.settings(project))
            ExportKind.Gif -> if (state.gifEndUs > state.gifStartUs) manager.estimateSizeBytes(project, state.gifSettings()) else 0L
            ExportKind.Audio -> manager.estimateSizeBytes(project, state.audioSettings())
        }
        _state.update { it.copy(estimatedBytes = bytes) }
    }

    private fun ExportUiState.settings(project: Project): ExportSettings {
        val base = ExportSettings(heightPx = resolution.heightPx, frameRate = frameRate, codec = codec)
        val videoBitrate = if (quality == ExportQuality.Custom) {
            (customVideoBitrateMbps.toDouble() * 1_000_000).roundToInt().coerceAtLeast(256_000)
        } else {
            (base.bitrate(project) * quality.factor).toInt().coerceAtLeast(256_000)
        }
        return base.copy(videoBitrate = videoBitrate)
    }

    private fun ExportUiState.gifSettings() =
        GifSettings(startUs = gifStartUs, endUs = gifEndUs, maxSidePx = gifMaxSidePx, frameRate = gifFrameRate)

    private fun ExportUiState.audioSettings() = AudioExportSettings(format = audioFormat, bitrateKbps = audioBitrateKbps)

    private fun freeBytes(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(0L)

}

internal const val MIN_CUSTOM_BITRATE_MBPS = 1f
internal const val MAX_CUSTOM_BITRATE_MBPS = 100f
private const val DEFAULT_CUSTOM_BITRATE_MBPS = 8f

internal fun roundCustomBitrateMbps(mbps: Float): Float {
    val clamped = mbps.coerceIn(MIN_CUSTOM_BITRATE_MBPS, MAX_CUSTOM_BITRATE_MBPS)
    return if (clamped < 10f) {
        (clamped * 2f).roundToInt() / 2f
    } else {
        clamped.roundToInt().toFloat()
    }
}

private const val MIN_GIF_US = 500_000L
private const val DEFAULT_GIF_US = 5_000_000L

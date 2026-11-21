package com.rainyworks.flow.feature.export

import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainyworks.flow.core.data.ProjectRepository
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.VideoCodec
import com.rainyworks.flow.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val codec: VideoCodec = VideoCodec.H264,
    val estimatedBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val phase: ExportPhase = ExportPhase.Configuring,
) {
    val hasEnoughSpace: Boolean get() = freeBytes == 0L || estimatedBytes * 11 / 10 < freeBytes
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
            _state.update { it.copy(project = project, freeBytes = freeBytes()) }
            recompute()
        }
        viewModelScope.launch {
            manager.job.collect { job ->
                if (job?.projectId == projectId) _state.update { it.copy(phase = job.phase) }
            }
        }
    }

    fun setResolution(value: ExportResolution) = updateSettings { it.copy(resolution = value) }
    fun setFrameRate(value: Int) = updateSettings { it.copy(frameRate = value) }
    fun setQuality(value: ExportQuality) = updateSettings { it.copy(quality = value) }
    fun setCodec(value: VideoCodec) = updateSettings { it.copy(codec = value) }

    fun start() {
        val current = _state.value
        val project = current.project ?: return
        manager.start(project, current.settings(project))
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
        _state.update { it.copy(estimatedBytes = manager.estimateSizeBytes(project, state.settings(project))) }
    }

    private fun ExportUiState.settings(project: Project): ExportSettings {
        val base = ExportSettings(heightPx = resolution.heightPx, frameRate = frameRate, codec = codec)
        return base.copy(videoBitrate = (base.bitrate(project) * quality.factor).toInt().coerceAtLeast(256_000))
    }

    private fun freeBytes(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(0L)

}

package com.rainyworks.flow.feature.export

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.ExportState
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The export currently running (or last finished) in the app. */
data class ExportJob(val projectId: String, val projectName: String, val phase: ExportPhase)

/**
 * Runs exports in an application-wide scope so they survive leaving the export screen.
 * While a job renders, [ExportService] keeps the process in the foreground with a
 * progress notification.
 */
class ExportManager(context: Context, private val exporter: Exporter) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _job = MutableStateFlow<ExportJob?>(null)
    val job: StateFlow<ExportJob?> = _job.asStateFlow()
    private var running: Job? = null

    val isRendering: Boolean get() = _job.value?.phase is ExportPhase.Rendering

    fun estimateSizeBytes(project: Project, settings: ExportSettings): Long = exporter.estimateSizeBytes(project, settings)

    fun start(project: Project, settings: ExportSettings) {
        if (isRendering) return
        _job.value = ExportJob(project.id, project.name, ExportPhase.Rendering(0f, null))
        ContextCompat.startForegroundService(appContext, Intent(appContext, ExportService::class.java))
        running = scope.launch {
            exporter.export(project, settings, project.name).collect { state ->
                val phase = when (state) {
                    is ExportState.Running -> ExportPhase.Rendering(state.progress, state.etaMs)
                    is ExportState.Success -> ExportPhase.Done(state.uri, state.sizeBytes)
                    is ExportState.Failed -> ExportPhase.Error(state.message)
                    ExportState.Cancelled -> ExportPhase.Configuring
                }
                _job.value = _job.value?.copy(phase = phase)
            }
        }
    }

    fun cancel() {
        running?.cancel()
        running = null
        _job.value = _job.value?.copy(phase = ExportPhase.Configuring)
    }

    /** Forgets a finished job once its result has been shown. */
    fun clear(projectId: String) {
        if (_job.value?.projectId == projectId && !isRendering) _job.value = null
    }
}

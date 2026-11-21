package com.rainyworks.flow.feature.export

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.engine.VideoCodec
import com.rainyworks.flow.core.model.Timecode
import java.util.Locale

@Composable
fun ExportScreen(viewModel: ExportViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rendering = state.phase is ExportPhase.Rendering

    BackHandler(enabled = rendering) { viewModel.cancel() }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = FlowTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (rendering) viewModel.cancel() else onDone() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
            }
            Text("Exportar", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(FlowTheme.spacing.md))
        when (val phase = state.phase) {
            ExportPhase.Configuring -> ExportSettingsPanel(state, viewModel, Modifier.weight(1f))
            is ExportPhase.Rendering -> RenderingPanel(phase, viewModel::cancel, Modifier.weight(1f))
            is ExportPhase.Done -> DonePanel(phase, onDone, Modifier.weight(1f))
            is ExportPhase.Error -> ErrorPanel(phase.message, viewModel::backToSettings, Modifier.weight(1f))
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportSettingsPanel(state: ExportUiState, viewModel: ExportViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FlowTheme.spacing.lg),
        ) {
            state.project?.let {
                Text(
                    "${it.name} · ${Timecode.formatShort(it.durationUs)} · ${it.canvas.aspectRatio.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlowColors.TextSecondary,
                )
            }
            Section("Resolución") {
                ExportResolution.entries.forEach {
                    FilterChip(selected = state.resolution == it, onClick = { viewModel.setResolution(it) }, label = { Text(it.label) })
                }
            }
            Section("Fotogramas por segundo") {
                listOf(24, 25, 30, 50, 60).forEach { fps ->
                    FilterChip(selected = state.frameRate == fps, onClick = { viewModel.setFrameRate(fps) }, label = { Text("$fps") })
                }
            }
            Section("Calidad (tasa de bits)") {
                ExportQuality.entries.forEach {
                    FilterChip(selected = state.quality == it, onClick = { viewModel.setQuality(it) }, label = { Text(it.label) })
                }
            }
            Section("Códec") {
                FilterChip(
                    selected = state.codec == VideoCodec.H264,
                    onClick = { viewModel.setCodec(VideoCodec.H264) },
                    label = { Text("H.264 · compatible") },
                )
                FilterChip(
                    selected = state.codec == VideoCodec.HEVC,
                    onClick = { viewModel.setCodec(VideoCodec.HEVC) },
                    label = { Text("H.265 · menor peso") },
                )
            }
        }
        Column(Modifier.padding(vertical = FlowTheme.spacing.lg)) {
            Text(
                "Peso aproximado: ${formatBytes(state.estimatedBytes)}" +
                    if (state.freeBytes > 0) " · Libre: ${formatBytes(state.freeBytes)}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.hasEnoughSpace) FlowColors.TextSecondary else FlowColors.Error,
            )
            if (!state.hasEnoughSpace) {
                Text("No hay espacio suficiente en el dispositivo", color = FlowColors.Error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(FlowTheme.spacing.md))
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                viewModel.start()
            }
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.start()
                    }
                },
                enabled = state.project != null && state.hasEnoughSpace,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text("Exportar video") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FlowTheme.spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowTheme.spacing.sm)) { content() }
    }
}

@Composable
private fun RenderingPanel(phase: ExportPhase.Rendering, onCancel: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { phase.progress }, modifier = Modifier.size(140.dp), strokeWidth = 8.dp)
            Text("${(phase.progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(FlowTheme.spacing.xl))
        LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(FlowTheme.spacing.md))
        Text(
            phase.etaMs?.let { "Tiempo restante: ${Timecode.formatShort(it * 1_000)}" } ?: "Calculando tiempo restante…",
            color = FlowColors.TextSecondary,
        )
        Text("Puedes salir de la app: la exportación sigue en segundo plano", color = FlowColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(FlowTheme.spacing.xl))
        OutlinedButton(onClick = onCancel) { Text("Cancelar") }
    }
}

@Composable
private fun DonePanel(phase: ExportPhase.Done, onDone: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = FlowColors.Accent, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(FlowTheme.spacing.lg))
        Text("Guardado en la galería", style = MaterialTheme.typography.titleMedium)
        Text(formatBytes(phase.sizeBytes), color = FlowColors.TextSecondary)
        Spacer(Modifier.height(FlowTheme.spacing.xl))
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, phase.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Compartir video"))
        }) {
            Icon(Icons.Rounded.Share, contentDescription = null)
            Spacer(Modifier.size(FlowTheme.spacing.sm))
            Text("Compartir")
        }
        Spacer(Modifier.height(FlowTheme.spacing.sm))
        OutlinedButton(onClick = onDone) { Text("Volver al editor") }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = FlowColors.Error, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(FlowTheme.spacing.lg))
        Text("No se pudo exportar", style = MaterialTheme.typography.titleMedium)
        Text(message, color = FlowColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(FlowTheme.spacing.xl))
        Button(onClick = onRetry) { Text("Volver a intentar") }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", bytes / 1024)
}

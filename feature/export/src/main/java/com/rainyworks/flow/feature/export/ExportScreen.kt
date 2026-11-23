package com.rainyworks.flow.feature.export

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.rainyworks.flow.core.engine.audio.AudioFormat
import com.rainyworks.flow.core.engine.gif.GifSettings
import com.rainyworks.flow.core.model.Timecode
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportKind.entries.forEachIndexed { i, kind ->
                    SegmentedButton(
                        selected = state.kind == kind,
                        onClick = { viewModel.setKind(kind) },
                        shape = SegmentedButtonDefaults.itemShape(i, ExportKind.entries.size),
                    ) { Text(kind.label) }
                }
            }
            when (state.kind) {
                ExportKind.Video -> VideoOptions(state, viewModel)
                ExportKind.Gif -> GifOptions(state, viewModel)
                ExportKind.Audio -> AudioOptions(state, viewModel)
            }
            if (state.kind != ExportKind.Gif) {
                FragmentRangeSection(state, viewModel)
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
            ) {
                Text(
                    when (state.kind) {
                        ExportKind.Video -> "Exportar video"
                        ExportKind.Gif -> "Crear GIF"
                        ExportKind.Audio -> "Exportar audio"
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoOptions(state: ExportUiState, viewModel: ExportViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(FlowTheme.spacing.lg)) {
        PresetSection(
            selected = state.selectedPreset,
            showVerticalAspectHint = state.showVerticalAspectHint,
            onSelect = viewModel::applyPreset,
        )
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
        if (state.quality == ExportQuality.Custom) {
            CustomBitrateSlider(
                mbps = state.customVideoBitrateMbps,
                recommendedMbps = state.recommendedVideoBitrateMbps,
                onChange = viewModel::setCustomVideoBitrate,
            )
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GifOptions(state: ExportUiState, viewModel: ExportViewModel) {
    val duration = state.project?.durationUs ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(FlowTheme.spacing.lg)) {
        Text(
            "Fragmento: ${Timecode.format(state.gifStartUs)} – ${Timecode.format(state.gifEndUs)} " +
                "(máx. ${GifSettings.MAX_DURATION_US / 1_000_000} s)",
            style = MaterialTheme.typography.titleSmall,
        )
        if (duration > 0L) {
            RangeSlider(
                value = state.gifStartUs.toFloat()..state.gifEndUs.toFloat(),
                onValueChange = { viewModel.setGifRange(it.start.toLong(), it.endInclusive.toLong()) },
                valueRange = 0f..duration.toFloat(),
            )
        }
        Section("Tamaño") {
            listOf(320, 480, 640).forEach { px ->
                FilterChip(selected = state.gifMaxSidePx == px, onClick = { viewModel.setGifSize(px) }, label = { Text("$px px") })
            }
        }
        Section("Fotogramas por segundo") {
            listOf(8, 12, 15, 24).forEach { fps ->
                FilterChip(selected = state.gifFrameRate == fps, onClick = { viewModel.setGifFrameRate(fps) }, label = { Text("$fps") })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioOptions(state: ExportUiState, viewModel: ExportViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(FlowTheme.spacing.lg)) {
        Section("Formato") {
            AudioFormat.entries.forEach { format ->
                FilterChip(
                    selected = state.audioFormat == format,
                    onClick = { viewModel.setAudioFormat(format) },
                    label = {
                        Text(
                            when (format) {
                                AudioFormat.MP3 -> "MP3 · universal"
                                AudioFormat.M4A -> "M4A · mejor calidad/peso"
                                AudioFormat.WAV -> "WAV · sin pérdida"
                            },
                        )
                    },
                )
            }
        }
        if (state.audioFormat != AudioFormat.WAV) {
            Section("Calidad") {
                listOf(128, 192, 256, 320).forEach { kbps ->
                    FilterChip(
                        selected = state.audioBitrateKbps == kbps,
                        onClick = { viewModel.setAudioBitrate(kbps) },
                        label = { Text("$kbps kbps") },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetSection(
    selected: ExportPreset,
    showVerticalAspectHint: Boolean,
    onSelect: (ExportPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FlowTheme.spacing.sm)) {
        Text("Preajustes", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FlowTheme.spacing.sm),
        ) {
            ExportPreset.entries.forEach { preset ->
                val isSelected = selected == preset
                Surface(
                    onClick = { onSelect(preset) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, if (isSelected) FlowColors.Accent else FlowColors.Outline),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = FlowTheme.spacing.md, vertical = FlowTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else FlowColors.TextPrimary,
                            maxLines = 1,
                        )
                        Text(
                            text = preset.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) FlowColors.TextPrimary.copy(alpha = 0.85f) else FlowColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (showVerticalAspectHint) {
            Text(
                text = "TikTok y Reels se ven mejor en formato 9:16; cámbialo en el editor.",
                style = MaterialTheme.typography.bodySmall,
                color = FlowColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun FragmentRangeSection(state: ExportUiState, viewModel: ExportViewModel) {
    val duration = state.project?.durationUs ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(FlowTheme.spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Exportar solo un fragmento", style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = state.useRange,
                onCheckedChange = viewModel::setUseRange,
                enabled = duration > 0L,
            )
        }
        if (state.useRange && duration > 0L) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${Timecode.format(state.rangeStartUs)} – ${Timecode.format(state.rangeEndUs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Duración: ${Timecode.format(state.rangeDurationUs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowColors.TextSecondary,
                )
            }
            RangeSlider(
                value = state.rangeStartUs.toFloat()..state.rangeEndUs.toFloat(),
                onValueChange = { viewModel.setRange(it.start.toLong(), it.endInclusive.toLong()) },
                valueRange = 0f..duration.toFloat(),
            )
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
        Text("Guardado en tu dispositivo", style = MaterialTheme.typography.titleMedium)
        Text(formatBytes(phase.sizeBytes), color = FlowColors.TextSecondary)
        Spacer(Modifier.height(FlowTheme.spacing.xl))
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(phase.uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, phase.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Compartir"))
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

@Composable
private fun CustomBitrateSlider(
    mbps: Float,
    recommendedMbps: Float?,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FlowTheme.spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tasa de bits: ${formatMbps(mbps)}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (recommendedMbps != null) {
                Text(
                    "Recomendado: ${formatMbps(recommendedMbps)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowColors.TextSecondary,
                )
            }
        }
        Slider(
            value = mbpsToSlider(mbps),
            onValueChange = { onChange(sliderToMbps(it)) },
            valueRange = 0f..1f,
        )
    }
}

private fun mbpsToSlider(mbps: Float): Float =
    (log10(mbps.coerceIn(MIN_CUSTOM_BITRATE_MBPS, MAX_CUSTOM_BITRATE_MBPS).toDouble()) / 2.0).toFloat()

private fun sliderToMbps(position: Float): Float =
    10.0.pow(position.coerceIn(0f, 1f).toDouble() * 2.0).toFloat()

private fun formatMbps(mbps: Float): String =
    if (mbps >= 10f) {
        "${mbps.roundToInt()} Mbps"
    } else {
        val half = (mbps * 2f).roundToInt() / 2f
        if (half % 1f == 0f) "${half.toInt()} Mbps" else String.format(Locale.US, "%.1f Mbps", half)
    }

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", bytes / 1024)
}

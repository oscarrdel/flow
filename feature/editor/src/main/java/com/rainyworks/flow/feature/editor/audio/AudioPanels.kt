package com.rainyworks.flow.feature.editor.audio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.components.FlowToolButton
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.TimecodeStyle
import com.rainyworks.flow.core.model.Timecode
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.VoiceOverUiState

@Composable
fun AudioPanel(canExtract: Boolean, onAction: (EditorAction) -> Unit, modifier: Modifier = Modifier) {
    FlowBottomPanel(
        title = "Audio",
        onDismiss = { onAction(EditorAction.SetAudioPanel(false)) },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = FlowTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FlowToolButton(Icons.Rounded.LibraryMusic, "Música y sonidos", onClick = { onAction(EditorAction.AddAudioFile) })
            FlowToolButton(Icons.Rounded.Mic, "Voz en off", onClick = { onAction(EditorAction.OpenVoiceOver) })
            FlowToolButton(
                Icons.Rounded.GraphicEq,
                "Extraer audio",
                enabled = canExtract,
                onClick = { onAction(EditorAction.ExtractAudio) },
            )
        }
    }
}

@Composable
fun VoiceOverPanel(state: VoiceOverUiState, onAction: (EditorAction) -> Unit, modifier: Modifier = Modifier) {
    val idle = !state.recording && state.countdown == null
    FlowBottomPanel(
        title = "Voz en off",
        subtitle = when {
            state.countdown != null -> "Prepárate…"
            state.recording -> "Grabando desde ${Timecode.format(state.startUs)}"
            else -> "Se grabará desde el cabezal"
        },
        onDismiss = { onAction(EditorAction.CancelVoiceOver) },
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = FlowTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LevelMeter(level = state.level, active = state.recording)
            Spacer(Modifier.height(FlowTheme.spacing.sm))
            Text(Timecode.format(state.elapsedUs), style = TimecodeStyle, color = FlowColors.TextSecondary)
            Spacer(Modifier.height(FlowTheme.spacing.md))
            Box(contentAlignment = Alignment.Center) {
                when {
                    state.countdown != null -> Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(FlowColors.Slate),
                        contentAlignment = Alignment.Center,
                    ) { Text("${state.countdown}", style = MaterialTheme.typography.titleLarge) }
                    state.recording -> FilledIconButton(
                        onClick = { onAction(EditorAction.StopVoiceOver) },
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = FlowColors.Error),
                    ) { Icon(Icons.Rounded.Stop, contentDescription = "Detener", modifier = Modifier.size(36.dp)) }
                    else -> FilledIconButton(
                        onClick = { onAction(EditorAction.StartVoiceOver) },
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = FlowColors.Error),
                    ) { Icon(Icons.Rounded.Mic, contentDescription = "Grabar", modifier = Modifier.size(36.dp)) }
                }
            }
            Spacer(Modifier.height(FlowTheme.spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null, tint = FlowColors.TextSecondary)
                Spacer(Modifier.size(FlowTheme.spacing.sm))
                Text("Silenciar video mientras grabo", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(FlowTheme.spacing.sm))
                Switch(
                    checked = state.mutePreview,
                    enabled = idle,
                    onCheckedChange = { onAction(EditorAction.SetVoiceOverMute(it)) },
                )
            }
            if (!idle) {
                IconButton(onClick = { onAction(EditorAction.CancelVoiceOver) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Descartar grabación")
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float, active: Boolean) {
    val animated by animateFloatAsState(if (active) level else 0f, label = "level")
    Box(
        Modifier
            .fillMaxWidth(0.8f)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(FlowColors.Steel),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated.coerceIn(0f, 1f))
                .height(8.dp)
                .background(if (animated > 0.9f) FlowColors.Error else FlowColors.Accent),
        )
    }
}

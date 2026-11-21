package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowToolButton
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.feature.editor.ClipTool
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.GlobalTool

/**
 * Contextual bottom toolbar switching between [GlobalTool] (no clip selected)
 * and [ClipTool] (a clip is selected, with a back button to deselect).
 */
@Composable
internal fun EditorBottomToolbar(
    selectedClipId: String?,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(FlowColors.Graphite)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectedClipId != null) {
            FlowToolButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                label = "Volver",
                onClick = { onAction(EditorAction.SelectClip(null)) },
                tint = FlowColors.Accent,
                modifier = Modifier.padding(start = spacing.sm),
            )
            VerticalDivider(
                color = FlowColors.Outline,
                modifier = Modifier
                    .height(36.dp)
                    .padding(horizontal = spacing.xs),
            )
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                items(
                    items = ClipTool.entries,
                    key = { it.name },
                ) { tool ->
                    FlowToolButton(
                        icon = tool.icon(),
                        label = tool.spanishLabel(),
                        onClick = { onAction(EditorAction.ClipToolClicked(tool)) },
                        tint = if (tool == ClipTool.Delete) FlowColors.Error else null,
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                items(
                    items = GlobalTool.entries,
                    key = { it.name },
                ) { tool ->
                    FlowToolButton(
                        icon = tool.icon(),
                        label = tool.spanishLabel(),
                        onClick = { onAction(EditorAction.GlobalToolClicked(tool)) },
                    )
                }
            }
        }
    }
}

private fun GlobalTool.spanishLabel(): String = when (this) {
    GlobalTool.Edit -> "Editar"
    GlobalTool.Audio -> "Audio"
    GlobalTool.Text -> "Texto"
    GlobalTool.Overlay -> "Superposición"
    GlobalTool.Effects -> "Efectos"
    GlobalTool.Filters -> "Filtros"
    GlobalTool.Format -> "Formato"
    GlobalTool.Canvas -> "Lienzo"
}

private fun GlobalTool.icon(): ImageVector = when (this) {
    GlobalTool.Edit -> Icons.Rounded.ContentCut
    GlobalTool.Audio -> Icons.Rounded.MusicNote
    GlobalTool.Text -> Icons.Rounded.Title
    GlobalTool.Overlay -> Icons.Rounded.Layers
    GlobalTool.Effects -> Icons.Rounded.AutoAwesome
    GlobalTool.Filters -> Icons.Rounded.Tune
    GlobalTool.Format -> Icons.Rounded.AspectRatio
    GlobalTool.Canvas -> Icons.Rounded.Palette
}

private fun ClipTool.spanishLabel(): String = when (this) {
    ClipTool.Split -> "Dividir"
    ClipTool.Speed -> "Velocidad"
    ClipTool.Volume -> "Volumen"
    ClipTool.Duplicate -> "Duplicar"
    ClipTool.Delete -> "Eliminar"
    ClipTool.Replace -> "Reemplazar"
    ClipTool.Freeze -> "Congelar"
    ClipTool.Reverse -> "Reversa"
    ClipTool.Crop -> "Recortar"
    ClipTool.Chroma -> "Chroma"
    ClipTool.Mask -> "Máscara"
    ClipTool.Animation -> "Animación"
}

private fun ClipTool.icon(): ImageVector = when (this) {
    ClipTool.Split -> Icons.Rounded.ContentCut
    ClipTool.Speed -> Icons.Rounded.Speed
    ClipTool.Volume -> Icons.AutoMirrored.Rounded.VolumeUp
    ClipTool.Duplicate -> Icons.Rounded.ContentCopy
    ClipTool.Delete -> Icons.Rounded.DeleteOutline
    ClipTool.Replace -> Icons.Rounded.SwapHoriz
    ClipTool.Freeze -> Icons.Rounded.AcUnit
    ClipTool.Reverse -> Icons.Rounded.History
    ClipTool.Crop -> Icons.Rounded.Crop
    ClipTool.Chroma -> Icons.Rounded.Colorize
    ClipTool.Mask -> Icons.Rounded.Contrast
    ClipTool.Animation -> Icons.Rounded.Animation
}

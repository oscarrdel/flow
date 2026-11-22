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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowToolButton
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.TrackKind
import com.rainyworks.flow.feature.editor.ClipTool
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorSheet
import com.rainyworks.flow.feature.editor.GlobalTool

/**
 * Contextual bottom toolbar switching between [GlobalTool] (no clip selected)
 * and [ClipTool] (a clip is selected, with a back button to deselect). Clip tools are
 * filtered by [selectedClipKind] and [selectedTrackKind] so only the ones that apply to
 * that media and track are offered.
 */
@Composable
internal fun EditorBottomToolbar(
    selectedClipId: String?,
    selectedClipKind: ClipKind?,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
    selectedTrackKind: TrackKind? = null,
    selectedClipHasAudio: Boolean = selectedClipKind == ClipKind.AUDIO || selectedClipKind == ClipKind.VIDEO,
) {
    val spacing = FlowTheme.spacing
    val clipTools = remember(selectedClipKind, selectedTrackKind, selectedClipHasAudio) {
        clipToolsFor(selectedClipKind, selectedTrackKind, selectedClipHasAudio)
    }

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
                    items = clipTools,
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
                GlobalTool.entries.forEach { tool ->
                    item(key = tool.name) {
                        FlowToolButton(
                            icon = tool.icon(),
                            label = tool.spanishLabel(),
                            onClick = { onAction(EditorAction.GlobalToolClicked(tool)) },
                        )
                    }
                    if (tool == GlobalTool.Text) {
                        item(key = "Subtitles") {
                            FlowToolButton(
                                icon = Icons.Rounded.Subtitles,
                                label = "Subtítulos",
                                onClick = { onAction(EditorAction.OpenSheet(EditorSheet.Subtitles)) },
                            )
                        }
                    }
                    if (tool == GlobalTool.Filters) {
                        item(key = "Adjustment") {
                            FlowToolButton(
                                icon = Icons.Rounded.Exposure,
                                label = "Ajuste",
                                onClick = { onAction(EditorAction.AddAdjustmentLayer) },
                            )
                        }
                    }
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
    GlobalTool.Filters -> Icons.Rounded.AutoFixHigh
    GlobalTool.Format -> Icons.Rounded.AspectRatio
    GlobalTool.Canvas -> Icons.Rounded.Palette
}

private val VisualOnlyTools = setOf(
    ClipTool.Transform,
    ClipTool.Opacity,
    ClipTool.Crop,
    ClipTool.Chroma,
    ClipTool.Mask,
    ClipTool.Animation,
    ClipTool.Freeze,
    ClipTool.Filters,
    ClipTool.Adjust,
    ClipTool.EditText,
    ClipTool.Blend,
)

private val TimedOnlyTools = setOf(
    ClipTool.Speed,
    ClipTool.Reverse,
    ClipTool.Freeze,
    ClipTool.Volume,
    ClipTool.AudioEffects,
    ClipTool.Fades,
    ClipTool.Extract,
)

private val AudioClipTools = setOf(
    ClipTool.AudioEffects,
    ClipTool.Fades,
    ClipTool.Extract,
)

private val HiddenForTextTools = setOf(
    ClipTool.Speed,
    ClipTool.Reverse,
    ClipTool.Freeze,
    ClipTool.Volume,
    ClipTool.Replace,
    ClipTool.Chroma,
    ClipTool.Filters,
    ClipTool.Adjust,
    ClipTool.Crop,
    ClipTool.Mask,
    ClipTool.EditText,
    ClipTool.Blend,
    ClipTool.AudioEffects,
    ClipTool.Fades,
    ClipTool.Extract,
)

private val AdjustmentLayerTools = listOf(
    ClipTool.Filters,
    ClipTool.Adjust,
    ClipTool.Split,
    ClipTool.Duplicate,
    ClipTool.Delete,
)

internal fun clipToolsFor(
    kind: ClipKind?,
    trackKind: TrackKind? = null,
    hasAudio: Boolean = kind == ClipKind.AUDIO || kind == ClipKind.VIDEO,
): List<ClipTool> {
    val isOverlay = trackKind == TrackKind.OVERLAY
    val base = when (kind) {
        ClipKind.AUDIO -> ClipTool.entries.filterNot { it in VisualOnlyTools || it == ClipTool.Extract }
        ClipKind.IMAGE -> ClipTool.entries.filterNot { it in TimedOnlyTools || it == ClipTool.EditText }
        ClipKind.TEXT -> listOf(ClipTool.EditText) + ClipTool.entries.filterNot { it in HiddenForTextTools }
        ClipKind.ADJUSTMENT -> AdjustmentLayerTools
        ClipKind.EFFECT -> listOf(ClipTool.Split, ClipTool.Duplicate, ClipTool.Delete)
        else -> ClipTool.entries.filterNot {
            it == ClipTool.EditText ||
                (!hasAudio && it in AudioClipTools) ||
                (kind != ClipKind.VIDEO && it == ClipTool.Extract)
        }
    }
    val audioFiltered = if (hasAudio) {
        base.filterNot { kind != ClipKind.VIDEO && it == ClipTool.Extract }
    } else {
        base.filterNot { it in AudioClipTools }
    }
    return if (isOverlay) audioFiltered else audioFiltered.filterNot { it == ClipTool.Blend }
}

private fun ClipTool.spanishLabel(): String = when (this) {
    ClipTool.Split -> "Dividir"
    ClipTool.Speed -> "Velocidad"
    ClipTool.Volume -> "Volumen"
    ClipTool.Transform -> "Ajustar"
    ClipTool.Opacity -> "Opacidad"
    ClipTool.Duplicate -> "Duplicar"
    ClipTool.Delete -> "Eliminar"
    ClipTool.Replace -> "Reemplazar"
    ClipTool.Freeze -> "Congelar"
    ClipTool.Reverse -> "Reversa"
    ClipTool.Crop -> "Recortar"
    ClipTool.Chroma -> "Chroma"
    ClipTool.Mask -> "Máscara"
    ClipTool.Animation -> "Animación"
    ClipTool.Filters -> "Filtros"
    ClipTool.Adjust -> "Ajustes"
    ClipTool.EditText -> "Editar"
    ClipTool.Blend -> "Fusión"
    ClipTool.AudioEffects -> "Efectos de sonido"
    ClipTool.Fades -> "Fundidos"
    ClipTool.Extract -> "Extraer audio"
}

private fun ClipTool.icon(): ImageVector = when (this) {
    ClipTool.Split -> Icons.Rounded.ContentCut
    ClipTool.Speed -> Icons.Rounded.Speed
    ClipTool.Volume -> Icons.AutoMirrored.Rounded.VolumeUp
    ClipTool.Transform -> Icons.Rounded.OpenWith
    ClipTool.Opacity -> Icons.Rounded.Opacity
    ClipTool.Duplicate -> Icons.Rounded.ContentCopy
    ClipTool.Delete -> Icons.Rounded.DeleteOutline
    ClipTool.Replace -> Icons.Rounded.SwapHoriz
    ClipTool.Freeze -> Icons.Rounded.AcUnit
    ClipTool.Reverse -> Icons.Rounded.History
    ClipTool.Crop -> Icons.Rounded.Crop
    ClipTool.Chroma -> Icons.Rounded.Colorize
    ClipTool.Mask -> Icons.Rounded.Contrast
    ClipTool.Animation -> Icons.Rounded.Animation
    ClipTool.Filters -> Icons.Rounded.AutoFixHigh
    ClipTool.Adjust -> Icons.Rounded.Tune
    ClipTool.EditText -> Icons.Rounded.Edit
    ClipTool.Blend -> Icons.Rounded.Layers
    ClipTool.AudioEffects -> Icons.Rounded.Equalizer
    ClipTool.Fades -> Icons.Rounded.Timeline
    ClipTool.Extract -> Icons.Rounded.GraphicEq
}

package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.SplitLayout
import com.rainyworks.flow.core.model.SplitScreen
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorUiState

/** Arranges the pictures on screen at the playhead into a split-screen layout. */
@Composable
internal fun SplitScreenSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val count = remember(state.project, state.playheadUs) {
        state.project?.let { SplitScreen.picturesAt(it, state.playheadUs).size } ?: 0
    }
    FlowBottomPanel(
        title = "Pantalla dividida",
        subtitle = if (count >= 2) "$count imágenes en el cursor" else "Agrega una superposición que coincida con el cursor",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(vertical = spacing.xs),
        ) {
            items(SplitLayout.entries) { layout ->
                LayoutCard(layout, enabled = count >= 2) { onAction(EditorAction.ApplySplitLayout(layout)) }
            }
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = "Se usan el clip principal y las superposiciones en el cursor, en ese orden. Cada imagen se recorta para llenar su espacio.",
            style = FlowTypography.labelMedium,
            color = FlowColors.TextTertiary,
        )
    }
}

@Composable
private fun LayoutCard(layout: SplitLayout, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val tint = if (enabled) FlowColors.Accent else FlowColors.TextTertiary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(88.dp)
            .clip(shape)
            .background(FlowColors.Slate)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        Canvas(Modifier.size(width = 36.dp, height = 60.dp)) {
            val gap = 2.dp.toPx()
            val corner = CornerRadius(3.dp.toPx())
            fun cell(l: Float, t: Float, w: Float, h: Float, alpha: Float = 0.45f) = drawRoundRect(
                color = tint.copy(alpha = alpha),
                topLeft = Offset(l * size.width + gap / 2, t * size.height + gap / 2),
                size = Size(w * size.width - gap, h * size.height - gap),
                cornerRadius = corner,
            )
            when (layout) {
                SplitLayout.SIDE_BY_SIDE -> { cell(0f, 0f, 0.5f, 1f); cell(0.5f, 0f, 0.5f, 1f, 0.8f) }
                SplitLayout.STACKED -> { cell(0f, 0f, 1f, 0.5f); cell(0f, 0.5f, 1f, 0.5f, 0.8f) }
                SplitLayout.THREE_COLUMNS -> for (i in 0..2) cell(i / 3f, 0f, 1f / 3f, 1f, 0.35f + 0.2f * i)
                SplitLayout.GRID -> for (i in 0..3) cell((i % 2) * 0.5f, (i / 2) * 0.5f, 0.5f, 0.5f, 0.35f + 0.15f * i)
                SplitLayout.PICTURE_IN_PICTURE -> {
                    cell(0f, 0f, 1f, 1f, 0.3f)
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(size.width * 0.58f, size.height * 0.62f),
                        size = Size(size.width * 0.34f, size.height * 0.3f),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
            }
            drawRoundRect(color = tint, size = size, cornerRadius = corner, style = Stroke(1.dp.toPx()))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (layout) {
                SplitLayout.SIDE_BY_SIDE -> "Lado a lado"
                SplitLayout.STACKED -> "Arriba y abajo"
                SplitLayout.THREE_COLUMNS -> "Tres"
                SplitLayout.GRID -> "Cuadrícula"
                SplitLayout.PICTURE_IN_PICTURE -> "Recuadro"
            },
            style = FlowTypography.labelMedium,
            color = if (enabled) FlowColors.TextPrimary else FlowColors.TextTertiary,
            maxLines = 1,
        )
    }
}

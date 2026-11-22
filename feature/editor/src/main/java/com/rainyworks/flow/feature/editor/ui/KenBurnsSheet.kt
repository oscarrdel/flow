package com.rainyworks.flow.feature.editor.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.KenBurns
import com.rainyworks.flow.core.model.KenBurnsStyle
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorUiState

/** Ken Burns for photos: a slow zoom or pan across the whole clip, chosen from cards. */
@Composable
internal fun KenBurnsSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)
    val current = remember(clip) { clip?.let(KenBurns::styleOf) }
    val options: List<KenBurnsStyle?> = remember { listOf<KenBurnsStyle?>(null) + KenBurnsStyle.entries }

    FlowBottomPanel(
        title = "Ken Burns",
        subtitle = "Acercamiento o paneo lento durante toda la foto",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(vertical = spacing.xs),
        ) {
            items(options, key = { it?.name ?: "none" }) { style ->
                KenBurnsCard(
                    style = style,
                    selected = clip != null && current == style && (style != null || !hasMotion(clip)),
                    onClick = { clip?.let { onAction(EditorAction.SetKenBurns(it.id, style)) } },
                )
            }
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = "Reemplaza los keyframes de movimiento del clip. Puedes ajustar los puntos después en Animación.",
            style = FlowTypography.labelMedium,
            color = FlowColors.TextTertiary,
        )
    }
}

private fun hasMotion(clip: com.rainyworks.flow.core.model.Clip) =
    com.rainyworks.flow.core.model.MediaEdits.hasMotionKeyframes(clip)

@Composable
private fun KenBurnsCard(style: KenBurnsStyle?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val transition = rememberInfiniteTransition(label = "kenBurns")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "progress",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(92.dp)
            .clip(shape)
            .background(if (selected) FlowColors.AccentDim else FlowColors.Slate)
            .border(1.dp, if (selected) FlowColors.Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        Canvas(Modifier.size(width = 72.dp, height = 44.dp)) {
            val frame = Size(size.width, size.height)
            drawRect(FlowColors.Graphite, size = frame)
            // A picture moving inside the frame, as the clip would.
            val (scale, dx, dy) = when (style) {
                null -> Triple(1f, 0f, 0f)
                KenBurnsStyle.ZOOM_IN -> Triple(1f + 0.35f * progress, 0f, 0f)
                KenBurnsStyle.ZOOM_OUT -> Triple(1.35f - 0.35f * progress, 0f, 0f)
                KenBurnsStyle.PAN_LEFT -> Triple(1.3f, 0.12f - 0.24f * progress, 0f)
                KenBurnsStyle.PAN_RIGHT -> Triple(1.3f, -0.12f + 0.24f * progress, 0f)
                KenBurnsStyle.PAN_UP -> Triple(1.3f, 0f, 0.12f - 0.24f * progress)
                KenBurnsStyle.PAN_DOWN -> Triple(1.3f, 0f, -0.12f + 0.24f * progress)
            }
            clipRect {
                val w = size.width * scale
                val h = size.height * scale
                val left = (size.width - w) / 2f + dx * size.width
                val top = (size.height - h) / 2f + dy * size.height
                drawRect(FlowColors.TrackOverlay, Offset(left, top), Size(w, h))
                // Simple "landscape" so the motion is readable.
                drawCircle(FlowColors.Keyframe, radius = h * 0.12f, center = Offset(left + w * 0.72f, top + h * 0.3f))
                drawRect(FlowColors.TrackAudio, Offset(left, top + h * 0.68f), Size(w, h * 0.32f))
            }
            drawRect(
                if (selected) FlowColors.Accent else FlowColors.Outline,
                size = frame,
                style = Stroke(1.dp.toPx()),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label(style),
            style = FlowTypography.labelMedium,
            color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
            maxLines = 1,
        )
    }
}

private fun label(style: KenBurnsStyle?) = when (style) {
    null -> "Ninguno"
    KenBurnsStyle.ZOOM_IN -> "Acercar"
    KenBurnsStyle.ZOOM_OUT -> "Alejar"
    KenBurnsStyle.PAN_LEFT -> "Izquierda"
    KenBurnsStyle.PAN_RIGHT -> "Derecha"
    KenBurnsStyle.PAN_UP -> "Subir"
    KenBurnsStyle.PAN_DOWN -> "Bajar"
}

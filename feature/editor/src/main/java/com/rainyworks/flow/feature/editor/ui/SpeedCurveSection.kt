package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.SpeedCurve
import com.rainyworks.flow.core.model.SpeedPoint
import com.rainyworks.flow.core.model.SpeedRamp
import com.rainyworks.flow.feature.editor.EditorAction
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Speed range shown on the curve graph (log scale). */
private const val GRAPH_MIN_SPEED = 0.1f
private const val GRAPH_MAX_SPEED = 10f

/**
 * Speed ramps: ready-made curves plus a graph whose points can be dragged up (faster) or
 * down (slower). The clip's length on the timeline follows the curve.
 */
@Composable
internal fun SpeedCurveSection(
    clip: Clip,
    onAction: (EditorAction) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val current = clip.speedCurve

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(vertical = spacing.xs),
    ) {
        item(key = "none") {
            CurveChip(
                label = "Ninguna",
                curve = null,
                selected = current == null,
                onClick = { onAction(EditorAction.SetSpeedCurve(clip.id, null)) },
            )
        }
        items(SpeedRamp.presets, key = { it.presetId ?: "" }) { preset ->
            CurveChip(
                label = presetName(preset.presetId),
                curve = preset,
                selected = current?.presetId != null && current.presetId == preset.presetId,
                onClick = { onAction(EditorAction.SetSpeedCurve(clip.id, preset)) },
            )
        }
        if (current != null && current.presetId == null) {
            item(key = "custom") {
                CurveChip(label = "Personalizada", curve = current, selected = true, onClick = {})
            }
        }
    }

    if (current != null) {
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = "Arrastra los puntos: arriba es más rápido, abajo más lento.",
            style = FlowTypography.labelMedium,
            color = FlowColors.TextSecondary,
        )
        Spacer(Modifier.height(spacing.xs))
        CurveEditor(
            curve = current,
            onChange = { onAction(EditorAction.SetSpeedCurve(clip.id, it)) },
        )
    }

    Spacer(Modifier.height(spacing.sm))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Mantener tono", style = FlowTypography.labelLarge, color = FlowColors.TextPrimary)
            Text(
                "Evita que la voz suene aguda o grave al cambiar la velocidad",
                style = FlowTypography.labelMedium,
                color = FlowColors.TextSecondary,
            )
        }
        Switch(
            checked = clip.preservePitch,
            onCheckedChange = { onAction(EditorAction.SetPreservePitch(clip.id, it)) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = FlowColors.OnAccent,
                checkedTrackColor = FlowColors.Accent,
            ),
        )
    }
}

@Composable
private fun CurveChip(label: String, curve: SpeedCurve?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .clip(shape)
            .background(if (selected) FlowColors.AccentDim else FlowColors.Slate)
            .border(1.dp, if (selected) FlowColors.Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        Canvas(Modifier.size(width = 64.dp, height = 28.dp)) {
            drawBaseline()
            if (curve == null) {
                drawLine(FlowColors.TextSecondary, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 2.dp.toPx())
            } else {
                drawCurve(curve.points, if (selected) FlowColors.Accent else FlowColors.TextSecondary, 2.dp.toPx())
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = FlowTypography.labelMedium,
            color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun CurveEditor(curve: SpeedCurve, onChange: (SpeedCurve) -> Unit) {
    // Local copy while dragging; the clip is retimed once, when the finger lifts.
    var points by remember(curve) { mutableStateOf(curve.points.sortedBy { it.position }) }
    var dragging by remember { mutableIntStateOf(-1) }
    val latestPoints by rememberUpdatedState(points)
    val latestOnChange by rememberUpdatedState(onChange)
    val handleRadius = 9.dp

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FlowColors.Graphite)
            .pointerInput(Unit) {
                val pad = handleRadius.toPx()
                fun xOf(p: Float) = pad + p * (size.width - 2 * pad)
                fun yOf(s: Float) = pad + (1f - speedFraction(s)) * (size.height - 2 * pad)
                detectDragGestures(
                    onDragStart = { start ->
                        val nearest = latestPoints.indices.minByOrNull { i ->
                            val p = latestPoints[i]
                            (Offset(xOf(p.position), yOf(p.speed)) - start).getDistance()
                        }
                        dragging = nearest?.takeIf { i ->
                            val p = latestPoints[i]
                            (Offset(xOf(p.position), yOf(p.speed)) - start).getDistance() < 40.dp.toPx()
                        } ?: -1
                    },
                    onDrag = { change, _ ->
                        val index = dragging
                        if (index < 0) return@detectDragGestures
                        change.consume()
                        val fraction = 1f - ((change.position.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        val speed = snapToOne(fractionSpeed(fraction))
                        points = latestPoints.mapIndexed { i, p -> if (i == index) p.copy(speed = speed) else p }
                    },
                    onDragEnd = {
                        if (dragging >= 0) latestOnChange(SpeedCurve(latestPoints, presetId = null))
                        dragging = -1
                    },
                    onDragCancel = {
                        points = curve.points.sortedBy { it.position }
                        dragging = -1
                    },
                )
            },
    ) {
        val pad = handleRadius.toPx()
        val inner = androidx.compose.ui.geometry.Size(size.width - 2 * pad, size.height - 2 * pad)
        // 1x reference line and labels.
        val oneY = pad + (1f - speedFraction(1f)) * inner.height
        drawLine(
            FlowColors.Outline, Offset(pad, oneY), Offset(size.width - pad, oneY), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
        translateInner(pad) { drawCurve(points, FlowColors.Accent, 3.dp.toPx(), inner) }
        points.forEachIndexed { i, p ->
            val center = Offset(pad + p.position * inner.width, pad + (1f - speedFraction(p.speed)) * inner.height)
            drawCircle(if (i == dragging) FlowColors.Accent else FlowColors.TextPrimary, handleRadius.toPx() * 0.75f, center)
            drawCircle(FlowColors.Accent, handleRadius.toPx() * 0.75f, center, style = Stroke(2.dp.toPx()))
        }
    }
    val active = points.getOrNull(dragging)
    Text(
        text = if (active != null) formatSpeedLabel(active.speed) else "Escala: 0.1x (abajo) a 10x (arriba)",
        style = FlowTypography.labelMedium,
        color = if (active != null) FlowColors.Accent else FlowColors.TextTertiary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private inline fun DrawScope.translateInner(pad: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(pad, pad)
    try {
        block()
    } finally {
        drawContext.transform.translate(-pad, -pad)
    }
}

private fun DrawScope.drawBaseline() {
    val y = (1f - speedFraction(1f)) * size.height
    drawLine(FlowColors.Outline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
}

private fun DrawScope.drawCurve(
    points: List<SpeedPoint>,
    color: Color,
    width: Float,
    area: androidx.compose.ui.geometry.Size = size,
) {
    val sorted = points.sortedBy { it.position }
    if (sorted.isEmpty()) return
    val path = Path()
    sorted.forEachIndexed { i, p ->
        val x = p.position * area.width
        val y = (1f - speedFraction(p.speed)) * area.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

/** Log-scale position of [speed] between the graph's limits, 0 at the bottom. */
private fun speedFraction(speed: Float): Float {
    val s = speed.coerceIn(GRAPH_MIN_SPEED, GRAPH_MAX_SPEED)
    return (ln(s / GRAPH_MIN_SPEED) / ln(GRAPH_MAX_SPEED / GRAPH_MIN_SPEED))
}

private fun fractionSpeed(fraction: Float): Float =
    GRAPH_MIN_SPEED * (GRAPH_MAX_SPEED / GRAPH_MIN_SPEED).pow(fraction.coerceIn(0f, 1f))

/** Makes normal speed easy to hit and keeps values tidy. */
private fun snapToOne(speed: Float): Float =
    if (abs(speed - 1f) < 0.08f) 1f else (speed * 10f).let { kotlin.math.round(it) / 10f }.coerceAtLeast(GRAPH_MIN_SPEED)

private fun presetName(id: String?): String = when (id) {
    "montage" -> "Montaje"
    "hero" -> "Héroe"
    "bullet" -> "Bala"
    "jump_cut" -> "Salto"
    "flash_in" -> "Flash entrada"
    "flash_out" -> "Flash salida"
    else -> "Curva"
}

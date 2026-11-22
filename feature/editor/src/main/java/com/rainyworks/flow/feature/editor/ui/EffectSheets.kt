package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurLinear
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterFrames
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.EffectCatalog
import com.rainyworks.flow.core.model.EffectCategory
import com.rainyworks.flow.core.model.EffectDefinition
import com.rainyworks.flow.core.model.EffectInstance
import com.rainyworks.flow.core.model.EffectParam
import com.rainyworks.flow.core.model.Timecode
import com.rainyworks.flow.core.model.Transition
import com.rainyworks.flow.core.model.TransitionCatalog
import com.rainyworks.flow.core.model.TransitionDefinition
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorUiState
import java.util.Locale
import kotlin.math.roundToInt

private val EFFECTS_PANEL_MAX_HEIGHT = 270.dp
private val TRANSITION_PANEL_MAX_HEIGHT = 270.dp
private const val DEFAULT_TRANSITION_DURATION_US = 500_000L
private const val TRANSITION_GRID_COLUMNS = 4

// region 1. Effects Panel

private fun Clip.supportsVisualEffects(): Boolean =
    kind == ClipKind.VIDEO || kind == ClipKind.IMAGE || kind == ClipKind.EFFECT

internal fun effectIconFor(type: String): ImageVector = when (type) {
    "blur.gaussian" -> Icons.Rounded.BlurOn
    "blur.motion" -> Icons.Rounded.BlurLinear
    "pixelate" -> Icons.Rounded.GridOn
    "glitch" -> Icons.Rounded.BrokenImage
    "chromatic" -> Icons.Rounded.Contrast
    "frame.retro" -> Icons.Rounded.FilterFrames
    "shake" -> Icons.Rounded.Vibration
    "zoom.dynamic" -> Icons.Rounded.ZoomIn
    "flare" -> Icons.Rounded.Flare
    "particles" -> Icons.Rounded.AutoAwesome
    else -> Icons.Rounded.AutoAwesome
}

internal fun formatEffectParamValue(param: EffectParam, value: Float): String = when {
    param.key == "angle" || param.max == 360f -> "${value.roundToInt()}°"
    param.min == 0f && param.max == 1f -> "${(value * 100f).roundToInt()}%"
    param.key == "style" -> "${value.roundToInt()}"
    else -> String.format(Locale.US, "%.2f", value)
}

@Composable
internal fun EffectsSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)?.takeIf { it.supportsVisualEffects() }
    val clipId = clip?.id
    val appliedEffects = clip?.effects.orEmpty()

    var selectedCategory by remember { mutableStateOf(EffectCategory.entries.first()) }
    val categoryEffects = remember(selectedCategory) {
        EffectCatalog.all.filter { it.category == selectedCategory }
    }

    var expandedEffectIds by remember(clipId) {
        mutableStateOf(setOfNotNull(appliedEffects.firstOrNull()?.id))
    }
    var previousEffectIds by remember(clipId) {
        mutableStateOf(appliedEffects.map { it.id }.toSet())
    }

    LaunchedEffect(appliedEffects) {
        val currentIds = appliedEffects.map { it.id }.toSet()
        val addedIds = currentIds - previousEffectIds
        if (addedIds.isNotEmpty()) {
            expandedEffectIds = (expandedEffectIds intersect currentIds) + addedIds
        } else {
            expandedEffectIds = expandedEffectIds intersect currentIds
        }
        previousEffectIds = currentIds
    }

    val subtitle = when {
        clip == null -> "Toca un efecto para agregar una capa en el cabezal"
        clip.kind == ClipKind.EFFECT -> {
            if (appliedEffects.isEmpty()) {
                "Capa de efecto · Toca un efecto para agregarlo"
            } else {
                "Capa de efecto · ${appliedEffects.size} aplicado${if (appliedEffects.size == 1) "" else "s"}"
            }
        }
        appliedEffects.isEmpty() -> "Toca un efecto para aplicarlo al clip"
        else -> "Efectos del clip · ${appliedEffects.size} aplicado${if (appliedEffects.size == 1) "" else "s"}"
    }

    FlowBottomPanel(
        title = "Efectos",
        subtitle = subtitle,
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = EFFECTS_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SheetChipRow(
                options = EffectCategory.entries,
                selected = selectedCategory,
                label = { it.label },
                onSelect = { selectedCategory = it },
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(categoryEffects, key = { it.type }) { definition ->
                    val appliedCount = appliedEffects.count { it.type == definition.type }
                    EffectCatalogCard(
                        definition = definition,
                        appliedCount = appliedCount,
                        onClick = {
                            onAction(EditorAction.AddEffect(clipId, definition.type))
                        },
                    )
                }
            }

            if (clip != null) {
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(color = FlowColors.Steel.copy(alpha = 0.7f), thickness = 1.dp)
                Spacer(Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Aplicados",
                        style = FlowTypography.labelLarge,
                        color = FlowColors.TextPrimary,
                    )
                    if (appliedEffects.isNotEmpty()) {
                        Text(
                            text = "${appliedEffects.size}",
                            style = FlowTypography.labelMedium,
                            color = FlowColors.Accent,
                        )
                    }
                }

                if (appliedEffects.isEmpty()) {
                    SheetEmptyMessage("Ningún efecto aplicado a este clip.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        appliedEffects.forEach { instance ->
                            val isExpanded = instance.id in expandedEffectIds
                            AppliedEffectItem(
                                clipId = clip.id,
                                instance = instance,
                                expanded = isExpanded,
                                onToggleExpand = {
                                    expandedEffectIds = if (isExpanded) {
                                        expandedEffectIds - instance.id
                                    } else {
                                        expandedEffectIds + instance.id
                                    }
                                },
                                onAction = onAction,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectCatalogCard(
    definition: EffectDefinition,
    appliedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val shape = RoundedCornerShape(10.dp)
    val isApplied = appliedCount > 0

    Column(
        modifier = modifier
            .width(88.dp)
            .clip(shape)
            .background(
                if (isApplied) FlowColors.AccentDim.copy(alpha = 0.4f) else FlowColors.Slate
            )
            .border(
                width = if (isApplied) 1.5.dp else 1.dp,
                color = if (isApplied) FlowColors.Accent else FlowColors.Outline,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.xs, vertical = spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (isApplied) FlowColors.Accent.copy(alpha = 0.18f) else FlowColors.Graphite
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = effectIconFor(definition.type),
                contentDescription = definition.name,
                tint = if (isApplied) FlowColors.Accent else FlowColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = definition.name,
            style = FlowTypography.labelSmall,
            color = if (isApplied) FlowColors.Accent else FlowColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AppliedEffectItem(
    clipId: String,
    instance: EffectInstance,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val definition = remember(instance.type) { EffectCatalog.find(instance.type) }
    val name = definition?.name ?: instance.type
    val params = definition?.params.orEmpty()
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FlowColors.Slate)
            .border(
                width = 1.dp,
                color = if (expanded) FlowColors.Accent.copy(alpha = 0.65f) else FlowColors.Outline,
                shape = shape,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(start = spacing.sm, end = spacing.xs, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = effectIconFor(instance.type),
                contentDescription = null,
                tint = FlowColors.Accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = name,
                style = FlowTypography.labelLarge,
                color = FlowColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Contraer" else "Expandir",
                tint = FlowColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            IconButton(
                onClick = { onAction(EditorAction.RemoveEffect(clipId, instance.id)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Eliminar $name",
                    tint = FlowColors.Error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded && params.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                params.forEach { param ->
                    val currentValue = EffectCatalog.value(instance, param.key)
                        .coerceIn(param.min, param.max)
                    ResettableSlider(
                        label = param.label,
                        value = currentValue,
                        valueRange = param.min..param.max,
                        valueText = { v -> formatEffectParamValue(param, v) },
                        resetValue = param.default,
                        onValueChange = { v, final ->
                            onAction(
                                EditorAction.SetEffectParam(
                                    clipId = clipId,
                                    effectId = instance.id,
                                    key = param.key,
                                    value = v.coerceIn(param.min, param.max),
                                    final = final,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

// endregion

// region 2. Transition Panel

@Composable
internal fun TransitionSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val mainClips = state.project?.mainTrack?.clips.orEmpty()
    val clipId = state.transitionClipId
        ?: state.selectedClipId?.takeIf { id -> mainClips.indexOfFirst { it.id == id } > 0 }
        ?: mainClips.getOrNull(1)?.id
    val clip = remember(mainClips, clipId) {
        clipId?.let { id -> mainClips.firstOrNull { it.id == id } }
    }

    val currentTransition = clip?.transitionIn
    val selectedType = currentTransition?.type ?: "cut"
    val selectedName = remember(selectedType) {
        TransitionCatalog.find(selectedType)?.name ?: "Corte"
    }

    val minDurationUs = TransitionCatalog.MIN_DURATION_US
    val maxDurationUs = TransitionCatalog.MAX_DURATION_US
    val minSec = minDurationUs.toFloat() / Timecode.US_PER_SECOND.toFloat()
    val maxSec = maxDurationUs.toFloat() / Timecode.US_PER_SECOND.toFloat()

    var rememberedDurationUs by remember(clipId) {
        mutableLongStateOf(
            (currentTransition?.durationUs ?: DEFAULT_TRANSITION_DURATION_US)
                .coerceIn(minDurationUs, maxDurationUs)
        )
    }

    LaunchedEffect(currentTransition?.durationUs) {
        val activeUs = currentTransition?.durationUs
        if (activeUs != null) {
            rememberedDurationUs = activeUs.coerceIn(minDurationUs, maxDurationUs)
        }
    }

    val effectiveDurationUs = (currentTransition?.durationUs ?: rememberedDurationUs)
        .coerceIn(minDurationUs, maxDurationUs)
    val durationSec = (effectiveDurationUs.toFloat() / Timecode.US_PER_SECOND.toFloat())
        .coerceIn(minSec, maxSec)

    fun secondsToDurationUs(seconds: Float): Long {
        val tenths = (seconds * 10f).roundToInt()
        return (tenths * 100_000L).coerceIn(minDurationUs, maxDurationUs)
    }

    FlowBottomPanel(
        title = "Transición",
        subtitle = if (selectedType == "cut") {
            "Corte directo entre clips"
        } else {
            String.format(Locale.US, "%s · %.1f s", selectedName, durationSec)
        },
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            if (clipId != null) {
                SheetActionChip(
                    label = "Aplicar a todos",
                    icon = Icons.Rounded.DoneAll,
                    enabled = true,
                    onClick = {
                        val toApply = if (selectedType == "cut") {
                            null
                        } else {
                            currentTransition ?: Transition(selectedType, effectiveDurationUs)
                        }
                        onAction(EditorAction.ApplyTransitionToAll(toApply))
                    },
                )
            }
        },
        modifier = modifier,
    ) {
        if (clipId == null) {
            SheetEmptyMessage("Añade al menos dos clips en la pista principal para editar una transición.")
            return@FlowBottomPanel
        }

        val rows = remember { TransitionCatalog.all.chunked(TRANSITION_GRID_COLUMNS) }

        Column(
            modifier = Modifier
                .heightIn(max = TRANSITION_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    rowItems.forEach { definition ->
                        val isSelected = selectedType == definition.type
                        TransitionGridTile(
                            definition = definition,
                            selected = isSelected,
                            onClick = {
                                if (definition.type == "cut") {
                                    onAction(EditorAction.SetTransition(clipId, null, true))
                                } else {
                                    onAction(
                                        EditorAction.SetTransition(
                                            clipId = clipId,
                                            transition = Transition(
                                                type = definition.type,
                                                durationUs = effectiveDurationUs,
                                            ),
                                            final = true,
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(TRANSITION_GRID_COLUMNS - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            ResettableSlider(
                label = "Duración",
                value = durationSec,
                valueRange = minSec..maxSec,
                valueText = { sec -> String.format(Locale.US, "%.1f s", sec.coerceIn(minSec, maxSec)) },
                resetValue = DEFAULT_TRANSITION_DURATION_US.toFloat() / Timecode.US_PER_SECOND.toFloat(),
                enabled = selectedType != "cut",
                onValueChange = { sec, final ->
                    val updatedUs = secondsToDurationUs(sec)
                    rememberedDurationUs = updatedUs
                    if (selectedType != "cut") {
                        onAction(
                            EditorAction.SetTransition(
                                clipId = clipId,
                                transition = Transition(
                                    type = selectedType,
                                    durationUs = updatedUs,
                                ),
                                final = final,
                            ),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun TransitionGridTile(
    definition: TransitionDefinition,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val iconColor = if (selected) FlowColors.Accent else FlowColors.TextPrimary

    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) FlowColors.AccentDim.copy(alpha = 0.5f) else FlowColors.Slate
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) FlowColors.Accent else FlowColors.Outline,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            drawTransitionIcon(definition.type, iconColor)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = definition.name,
            style = FlowTypography.labelSmall,
            color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DrawScope.drawTransitionIcon(type: String, color: Color) {
    val strokeWidth = 1.75.dp.toPx()
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val pad = 2.5.dp.toPx()

    when (type) {
        "cut" -> {
            drawLine(
                color = color,
                start = Offset(cx, pad),
                end = Offset(cx, h - pad),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawRoundRect(
                color = color.copy(alpha = 0.5f),
                topLeft = Offset(pad, pad + 2.dp.toPx()),
                size = Size(cx - pad - 2.dp.toPx(), h - 2f * (pad + 2.dp.toPx())),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = stroke,
            )
            drawRoundRect(
                color = color.copy(alpha = 0.5f),
                topLeft = Offset(cx + 2.dp.toPx(), pad + 2.dp.toPx()),
                size = Size(cx - pad - 2.dp.toPx(), h - 2f * (pad + 2.dp.toPx())),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = stroke,
            )
        }
        "dissolve" -> {
            val boxSize = Size(w * 0.52f, h * 0.52f)
            drawRoundRect(
                color = color.copy(alpha = 0.35f),
                topLeft = Offset(pad, pad),
                size = boxSize,
                cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(w - pad - boxSize.width, h - pad - boxSize.height),
                size = boxSize,
                cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                style = stroke,
            )
        }
        "fade.black" -> {
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.75f),
                topLeft = Offset(pad, pad),
                size = Size(w - 2f * pad, h - 2f * pad),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(pad, pad),
                size = Size(w - 2f * pad, h - 2f * pad),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = stroke,
            )
        }
        "fade.white" -> {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(pad, pad),
                size = Size(w - 2f * pad, h - 2f * pad),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(pad, pad),
                size = Size(w - 2f * pad, h - 2f * pad),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = stroke,
            )
        }
        "slide.left" -> drawArrowIcon(color, strokeWidth, dx = -1f, dy = 0f)
        "slide.right" -> drawArrowIcon(color, strokeWidth, dx = 1f, dy = 0f)
        "slide.up" -> drawArrowIcon(color, strokeWidth, dx = 0f, dy = -1f)
        "slide.down" -> drawArrowIcon(color, strokeWidth, dx = 0f, dy = 1f)
        "wipe.left" -> {
            drawRect(
                color = color.copy(alpha = 0.28f),
                topLeft = Offset(cx, pad),
                size = Size(cx - pad, h - 2f * pad),
            )
            drawLine(color = color, start = Offset(cx, pad), end = Offset(cx, h - pad), strokeWidth = strokeWidth)
            drawArrowIcon(color, strokeWidth * 0.85f, dx = -1f, dy = 0f)
        }
        "wipe.right" -> {
            drawRect(
                color = color.copy(alpha = 0.28f),
                topLeft = Offset(pad, pad),
                size = Size(cx - pad, h - 2f * pad),
            )
            drawLine(color = color, start = Offset(cx, pad), end = Offset(cx, h - pad), strokeWidth = strokeWidth)
            drawArrowIcon(color, strokeWidth * 0.85f, dx = 1f, dy = 0f)
        }
        "zoom" -> {
            drawCircle(color = color.copy(alpha = 0.25f), radius = w * 0.18f, center = Offset(cx, cy))
            drawCircle(color = color, radius = w * 0.36f, center = Offset(cx, cy), style = stroke)
        }
        "spin" -> {
            drawArc(
                color = color,
                startAngle = -60f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(pad + 1.dp.toPx(), pad + 1.dp.toPx()),
                size = Size(w - 2f * (pad + 1.dp.toPx()), h - 2f * (pad + 1.dp.toPx())),
                style = stroke,
            )
        }
        "glitch" -> {
            val path = Path().apply {
                moveTo(pad, cy - 3.dp.toPx())
                lineTo(cx + 2.dp.toPx(), cy - 3.dp.toPx())
                lineTo(cx - 2.dp.toPx(), cy + 3.dp.toPx())
                lineTo(w - pad, cy + 3.dp.toPx())
            }
            drawPath(path = path, color = color, style = stroke)
        }
        "flash" -> {
            val r = w * 0.36f
            drawLine(color = color, start = Offset(cx, cy - r), end = Offset(cx, cy + r), strokeWidth = strokeWidth)
            drawLine(color = color, start = Offset(cx - r, cy), end = Offset(cx + r, cy), strokeWidth = strokeWidth)
            val d = r * 0.68f
            drawLine(color = color, start = Offset(cx - d, cy - d), end = Offset(cx + d, cy + d), strokeWidth = strokeWidth * 0.85f)
            drawLine(color = color, start = Offset(cx - d, cy + d), end = Offset(cx + d, cy - d), strokeWidth = strokeWidth * 0.85f)
        }
        else -> {
            val bowtie = Path().apply {
                moveTo(pad, pad)
                lineTo(w - pad, h - pad)
                lineTo(w - pad, pad)
                lineTo(pad, h - pad)
                close()
            }
            drawPath(path = bowtie, color = color, style = stroke)
        }
    }
}

private fun DrawScope.drawArrowIcon(color: Color, strokeWidth: Float, dx: Float, dy: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val len = size.minDimension * 0.28f
    val head = size.minDimension * 0.16f
    val start = Offset(cx - dx * len, cy - dy * len)
    val end = Offset(cx + dx * len, cy + dy * len)
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    if (dx != 0f) {
        drawLine(
            color = color,
            start = end,
            end = Offset(end.x - dx * head, end.y - head),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = end,
            end = Offset(end.x - dx * head, end.y + head),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    } else if (dy != 0f) {
        drawLine(
            color = color,
            start = end,
            end = Offset(end.x - head, end.y - dy * head),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = end,
            end = Offset(end.x + head, end.y - dy * head),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

// endregion

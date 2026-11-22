package com.rainyworks.flow.feature.editor.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.components.FlowLabeledSlider
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.CanvasBackground
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.CropRect
import com.rainyworks.flow.core.model.Transform
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorSheet
import com.rainyworks.flow.feature.editor.EditorUiState
import com.rainyworks.flow.feature.editor.TimelineMediaSource
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MIN_SHEET_SPEED = 0.1f
private const val MAX_SHEET_SPEED = 10f
private const val CROP_FRAME_QUANTUM_US = 100_000L
private val CROP_EDITOR_HEIGHT = 168.dp

/**
 * Anchored bottom panel for the currently active [EditorSheet].
 */
@Composable
internal fun EditorActiveSheet(
    sheet: EditorSheet,
    state: EditorUiState,
    mediaSource: TimelineMediaSource,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (sheet) {
        EditorSheet.Speed -> SpeedSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Volume -> VolumeSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.AspectRatio -> AspectRatioSheetPanel(
            currentRatio = state.project?.canvas?.aspectRatio ?: AspectRatio.R9_16,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Background -> BackgroundSheetPanel(
            currentBackground = state.project?.canvas?.background
                ?: CanvasBackground.Solid(0xFF000000),
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Transform -> TransformSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Crop -> CropSheetPanel(
            state = state,
            mediaSource = mediaSource,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Opacity -> OpacitySheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Filters -> FiltersSheetPanel(
            state = state,
            mediaSource = mediaSource,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Adjust -> AdjustSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Curves -> CurvesSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Hsl -> HslSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Text -> TextSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Subtitles -> SubtitlesSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Chroma -> ChromaSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Blend -> BlendSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Mask -> MaskSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Keyframes -> KeyframesSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.AudioEffects -> AudioEffectsSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Fades -> FadesSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Effects -> EffectsSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.Transition -> TransitionSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        EditorSheet.InOut -> InOutSheetPanel(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
    }
}

@Composable
internal fun rememberSelectedClip(state: EditorUiState): Clip? {
    val project = state.project
    val clipId = state.selectedClipId
    return remember(project, clipId) { clipId?.let { project?.findClip(it)?.second } }
}

/** Slider keeping its own value while dragging; reports `final = true` when released. */
@Composable
internal fun SheetSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    enabled: Boolean,
    onValueChange: (value: Float, final: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    FlowLabeledSlider(
        label = label,
        valueText = valueText(local),
        value = local,
        valueRange = valueRange,
        enabled = enabled,
        onValueChange = {
            local = it
            onValueChange(it, false)
        },
        onValueChangeFinished = { onValueChange(local, true) },
        modifier = modifier,
    )
}

@Composable
private fun TransformSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)
    val clipId = clip?.id
    val enabled = clipId != null
    val transform = clip?.animatedTransformAt(state.playheadUs) ?: Transform()
    val currentTransform by rememberUpdatedState(transform)

    fun emit(updated: Transform, final: Boolean) {
        val targetId = clipId ?: return
        onAction(EditorAction.SetTransform(targetId, updated, final))
    }

    FlowBottomPanel(
        title = "Ajustar",
        subtitle = "Posición, escala y rotación del clip",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            SheetSlider(
                label = "Posición X",
                value = transform.offsetX.coerceIn(-1f, 1f),
                valueRange = -1f..1f,
                valueText = ::formatSignedPercent,
                enabled = enabled,
                onValueChange = { v, final -> emit(currentTransform.copy(offsetX = v), final) },
                modifier = Modifier.weight(1f),
            )
            SheetSlider(
                label = "Posición Y",
                value = transform.offsetY.coerceIn(-1f, 1f),
                valueRange = -1f..1f,
                valueText = ::formatSignedPercent,
                enabled = enabled,
                onValueChange = { v, final -> emit(currentTransform.copy(offsetY = v), final) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            SheetSlider(
                label = "Escala",
                value = transform.scale.coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE),
                valueRange = MIN_TRANSFORM_SCALE..MAX_TRANSFORM_SCALE,
                valueText = { "${(it * 100f).roundToInt()}%" },
                enabled = enabled,
                onValueChange = { v, final -> emit(currentTransform.copy(scale = v), final) },
                modifier = Modifier.weight(1f),
            )
            SheetSlider(
                label = "Rotación",
                value = normalizeDegrees(transform.rotationDegrees),
                valueRange = -180f..180f,
                valueText = { "${it.roundToInt()}°" },
                enabled = enabled,
                onValueChange = { v, final -> emit(currentTransform.copy(rotationDegrees = v), final) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(spacing.xs))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetActionChip(
                label = "Girar 90°",
                icon = Icons.Rounded.Rotate90DegreesCw,
                enabled = enabled,
                onClick = {
                    val rotated = normalizeDegrees(currentTransform.rotationDegrees + 90f)
                    emit(currentTransform.copy(rotationDegrees = rotated), true)
                },
            )
            SheetActionChip(
                label = "Voltear H",
                icon = Icons.Rounded.Flip,
                enabled = enabled,
                selected = transform.flipHorizontal,
                onClick = {
                    emit(currentTransform.copy(flipHorizontal = !currentTransform.flipHorizontal), true)
                },
            )
            SheetActionChip(
                label = "Voltear V",
                icon = Icons.Rounded.Flip,
                iconRotation = 90f,
                enabled = enabled,
                selected = transform.flipVertical,
                onClick = {
                    emit(currentTransform.copy(flipVertical = !currentTransform.flipVertical), true)
                },
            )
            SheetActionChip(
                label = "Restablecer",
                icon = Icons.Rounded.RestartAlt,
                enabled = enabled,
                onClick = { emit(Transform(crop = currentTransform.crop), true) },
            )
        }
    }
}

private data class CropAspectOption(
    val label: String,
    val ratio: AspectRatio? = null,
    val original: Boolean = false,
) {
    /**
     * Width/height proportion in normalized frame space (the pixel ratio corrected by the
     * source aspect), or null for a free crop.
     */
    fun normalizedRatio(sourceAspect: Float): Float? = when {
        original -> 1f
        ratio != null -> ratio.value / sourceAspect
        else -> null
    }
}

private val CropAspectOptions = listOf(
    CropAspectOption("Libre"),
    CropAspectOption("Original", original = true),
    CropAspectOption("9:16", AspectRatio.R9_16),
    CropAspectOption("16:9", AspectRatio.R16_9),
    CropAspectOption("1:1", AspectRatio.R1_1),
    CropAspectOption("4:5", AspectRatio.R4_5),
    CropAspectOption("21:9", AspectRatio.R21_9),
)

@Composable
private fun CropSheetPanel(
    state: EditorUiState,
    mediaSource: TimelineMediaSource,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val density = LocalDensity.current
    val clip = rememberSelectedClip(state)
    val clipId = clip?.id
    val media = clip?.media
    val enabled = clipId != null
    val transform = clip?.transform ?: Transform()
    val currentTransform by rememberUpdatedState(transform)

    val sourceWidth = media?.displayWidth?.takeIf { it > 0 }?.toFloat() ?: 1f
    val sourceHeight = media?.displayHeight?.takeIf { it > 0 }?.toFloat() ?: 1f
    val sourceAspect = sourceWidth / sourceHeight

    var selectedOption by remember(clipId) { mutableStateOf(CropAspectOptions.first()) }
    val lockedRatio = selectedOption.normalizedRatio(sourceAspect)

    val sourceTimeUs = clip?.let { it.freezeAtUs ?: it.sourceTimeAt(state.playheadUs) } ?: 0L
    val quantizedSourceUs = (sourceTimeUs / CROP_FRAME_QUANTUM_US) * CROP_FRAME_QUANTUM_US
    val frameHeightPx = with(density) { CROP_EDITOR_HEIGHT.roundToPx() }
    val frame by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = media?.uri,
        key2 = quantizedSourceUs,
        key3 = frameHeightPx,
    ) {
        value = media?.takeIf { it.hasVideo }?.let { mediaSource.thumbnail(it, quantizedSourceUs, frameHeightPx) }
    }

    fun emitCrop(crop: CropRect, final: Boolean) {
        val targetId = clipId ?: return
        onAction(EditorAction.SetTransform(targetId, currentTransform.copy(crop = crop), final))
    }

    FlowBottomPanel(
        title = "Recortar",
        subtitle = "Arrastra las esquinas, los bordes o el centro",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            SheetActionChip(
                label = "Restablecer",
                icon = Icons.Rounded.RestartAlt,
                enabled = enabled && transform.crop != CropRect.Full,
                onClick = {
                    selectedOption = CropAspectOptions.first()
                    emitCrop(CropRect.Full, true)
                },
            )
        },
        modifier = modifier,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(CropAspectOptions, key = { it.label }) { option ->
                FilterChip(
                    selected = option == selectedOption,
                    enabled = enabled,
                    onClick = {
                        selectedOption = option
                        val ratio = option.normalizedRatio(sourceAspect)
                        if (ratio != null) {
                            emitCrop(fitCropToRatio(currentTransform.crop, ratio), true)
                        }
                    },
                    label = {
                        Text(
                            text = option.label,
                            style = FlowTypography.labelMedium,
                        )
                    },
                    colors = sheetChipColors(),
                )
            }
        }

        Spacer(Modifier.height(spacing.sm))

        CropFrameEditor(
            frame = frame,
            sourceAspect = sourceAspect,
            crop = transform.crop,
            lockedRatio = lockedRatio,
            enabled = enabled,
            onCropChange = ::emitCrop,
            height = CROP_EDITOR_HEIGHT,
        )
    }
}

@Composable
private fun OpacitySheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)
    val clipId = clip?.id
    val opacity = (clip?.opacity ?: 1f).coerceIn(0f, 1f)
    val presets = remember { listOf(0.25f, 0.5f, 0.75f, 1f) }

    fun emit(value: Float, final: Boolean) {
        val targetId = clipId ?: return
        onAction(EditorAction.SetOpacity(targetId, (value * 100f).roundToInt() / 100f, final))
    }

    FlowBottomPanel(
        title = "Opacidad",
        subtitle = "Transparencia del clip sobre las capas inferiores",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        SheetSlider(
            label = "Opacidad",
            value = opacity,
            valueRange = 0f..1f,
            valueText = { "${(it * 100f).roundToInt()}%" },
            enabled = clipId != null,
            onValueChange = ::emit,
        )

        Spacer(Modifier.height(spacing.xs))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(vertical = spacing.xs),
        ) {
            items(presets, key = { it }) { preset ->
                FilterChip(
                    selected = kotlin.math.abs(opacity - preset) < 0.01f,
                    enabled = clipId != null,
                    onClick = { emit(preset, true) },
                    label = {
                        Text(
                            text = "${(preset * 100f).roundToInt()}%",
                            style = FlowTypography.labelMedium,
                        )
                    },
                    colors = sheetChipColors(),
                )
            }
        }
    }
}

@Composable
internal fun SheetActionChip(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    iconRotation: Float = 0f,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(iconRotation),
            )
        },
        label = {
            Text(
                text = label,
                style = FlowTypography.labelMedium,
            )
        },
        colors = sheetChipColors(),
        modifier = modifier,
    )
}

@Composable
internal fun sheetChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = FlowColors.Slate,
    labelColor = FlowColors.TextPrimary,
    iconColor = FlowColors.TextSecondary,
    selectedContainerColor = FlowColors.AccentDim,
    selectedLabelColor = FlowColors.Accent,
    selectedLeadingIconColor = FlowColors.Accent,
)

internal fun formatSignedPercent(value: Float): String {
    val percent = (value * 100f).roundToInt()
    return if (percent > 0) "+$percent%" else "$percent%"
}

@Composable
private fun SpeedSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clipId = state.selectedClipId
        ?: state.project?.mainTrack?.clips?.firstOrNull()?.id
    val currentClip = remember(state.project, clipId) {
        clipId?.let { state.project?.findClip(it)?.second }
    }
    val externalSpeed = (currentClip?.speed ?: 1f).coerceIn(MIN_SHEET_SPEED, MAX_SHEET_SPEED)
    var sliderProgress by remember(clipId, externalSpeed) {
        mutableFloatStateOf(speedToLogFraction(externalSpeed))
    }
    val displayedSpeed = remember(sliderProgress) {
        logFractionToSpeed(sliderProgress)
    }

    val presets = remember { listOf(0.25f, 0.5f, 1f, 2f, 4f, 10f) }

    var curveTab by remember(clipId) { mutableStateOf(currentClip?.speedCurve != null) }
    val canRamp = currentClip?.media?.type == com.rainyworks.flow.core.model.MediaType.VIDEO

    FlowBottomPanel(
        title = "Velocidad",
        subtitle = if (curveTab) "Cambia la velocidad a lo largo del clip" else "Escala logarítmica (0.1x – 10x)",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        if (canRamp) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                FilterChip(
                    selected = !curveTab,
                    onClick = { curveTab = false },
                    label = { Text("Normal", style = FlowTypography.labelMedium) },
                    colors = speedTabColors(),
                )
                FilterChip(
                    selected = curveTab,
                    onClick = { curveTab = true },
                    label = { Text("Curva", style = FlowTypography.labelMedium) },
                    colors = speedTabColors(),
                )
            }
            Spacer(Modifier.height(spacing.xs))
        }
        if (curveTab && canRamp && currentClip != null) {
            SpeedCurveSection(clip = currentClip, onAction = onAction)
            return@FlowBottomPanel
        }
        FlowLabeledSlider(
            label = "Multiplicador",
            valueText = formatSpeedLabel(displayedSpeed),
            value = sliderProgress,
            valueRange = 0f..1f,
            enabled = clipId != null,
            onValueChange = { fraction ->
                sliderProgress = fraction
                val targetId = clipId ?: return@FlowLabeledSlider
                onAction(
                    EditorAction.SetSpeed(
                        clipId = targetId,
                        speed = logFractionToSpeed(fraction),
                        final = false,
                    )
                )
            },
            onValueChangeFinished = {
                val targetId = clipId ?: return@FlowLabeledSlider
                onAction(
                    EditorAction.SetSpeed(
                        clipId = targetId,
                        speed = logFractionToSpeed(sliderProgress),
                        final = true,
                    )
                )
            },
        )

        Spacer(Modifier.height(spacing.xs))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(vertical = spacing.xs),
        ) {
            items(presets, key = { it }) { preset ->
                val isSelected = kotlin.math.abs(displayedSpeed - preset) < 0.03f
                FilterChip(
                    selected = isSelected,
                    enabled = clipId != null,
                    onClick = {
                        val targetId = clipId ?: return@FilterChip
                        sliderProgress = speedToLogFraction(preset)
                        onAction(
                            EditorAction.SetSpeed(
                                clipId = targetId,
                                speed = preset,
                                final = true,
                            )
                        )
                    },
                    label = {
                        Text(
                            text = formatSpeedLabel(preset),
                            style = FlowTypography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = FlowColors.Slate,
                        labelColor = FlowColors.TextPrimary,
                        selectedContainerColor = FlowColors.AccentDim,
                        selectedLabelColor = FlowColors.Accent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun speedTabColors() = FilterChipDefaults.filterChipColors(
    containerColor = FlowColors.Slate,
    labelColor = FlowColors.TextPrimary,
    selectedContainerColor = FlowColors.AccentDim,
    selectedLabelColor = FlowColors.Accent,
)

@Composable
private fun VolumeSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clipId = state.selectedClipId
        ?: state.project?.mainTrack?.clips?.firstOrNull()?.id
    val currentClip = remember(state.project, clipId) {
        clipId?.let { state.project?.findClip(it)?.second }
    }
    val externalVolume = (currentClip?.volume ?: 1f).coerceIn(0f, 2f)
    var localVolume by remember(clipId, externalVolume) {
        mutableFloatStateOf(externalVolume)
    }
    val percent = (localVolume * 100f).roundToInt()
    val isAmplified = percent > 100
    val presets = remember { listOf(0f, 0.5f, 1f, 1.5f, 2f) }

    FlowBottomPanel(
        title = "Volumen",
        subtitle = "Ajusta la ganancia del clip (0% – 200%)",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            SheetActionChip(
                label = "Fundidos",
                icon = Icons.Rounded.Timeline,
                enabled = clipId != null,
                onClick = { onAction(EditorAction.OpenSheet(EditorSheet.Fades)) },
            )
        },
        modifier = modifier,
    ) {
        FlowLabeledSlider(
            label = "Nivel de audio",
            valueText = "$percent%",
            value = localVolume,
            valueRange = 0f..2f,
            enabled = clipId != null,
            onValueChange = { newVolume ->
                localVolume = newVolume
                val targetId = clipId ?: return@FlowLabeledSlider
                onAction(
                    EditorAction.SetVolume(
                        clipId = targetId,
                        volume = (newVolume * 100f).roundToInt() / 100f,
                        final = false,
                    )
                )
            },
            onValueChangeFinished = {
                val targetId = clipId ?: return@FlowLabeledSlider
                onAction(
                    EditorAction.SetVolume(
                        clipId = targetId,
                        volume = (localVolume * 100f).roundToInt() / 100f,
                        final = true,
                    )
                )
            },
        )

        if (isAmplified) {
            Spacer(Modifier.height(spacing.xs))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(FlowColors.Keyframe.copy(alpha = 0.16f))
                    .border(1.dp, FlowColors.Keyframe.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = spacing.sm, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = FlowColors.Keyframe,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Amplificado",
                    style = FlowTypography.labelMedium,
                    color = FlowColors.Keyframe,
                )
            }
        }

        Spacer(Modifier.height(spacing.xs))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(vertical = spacing.xs),
        ) {
            items(presets, key = { it }) { preset ->
                val presetPct = (preset * 100f).roundToInt()
                val isSelected = kotlin.math.abs(localVolume - preset) < 0.02f
                FilterChip(
                    selected = isSelected,
                    enabled = clipId != null,
                    onClick = {
                        val targetId = clipId ?: return@FilterChip
                        localVolume = preset
                        onAction(
                            EditorAction.SetVolume(
                                clipId = targetId,
                                volume = preset,
                                final = true,
                            )
                        )
                    },
                    label = {
                        Text(
                            text = if (presetPct == 0) "Silencio" else "$presetPct%",
                            style = FlowTypography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = FlowColors.Slate,
                        labelColor = FlowColors.TextPrimary,
                        selectedContainerColor = FlowColors.AccentDim,
                        selectedLabelColor = FlowColors.Accent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AspectRatioSheetPanel(
    currentRatio: AspectRatio,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing

    FlowBottomPanel(
        title = "Relación de aspecto",
        subtitle = "Selecciona el formato del lienzo",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            contentPadding = PaddingValues(vertical = spacing.xs),
        ) {
            items(
                items = AspectRatio.Presets,
                key = { it.label },
            ) { ratio ->
                val selected = ratio == currentRatio
                AspectRatioChip(
                    ratio = ratio,
                    selected = selected,
                    onClick = { onAction(EditorAction.SetAspectRatio(ratio)) },
                )
            }
        }
    }
}

@Composable
private fun AspectRatioChip(
    ratio: AspectRatio,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) FlowColors.Accent else FlowColors.Outline
    val containerColor = if (selected) FlowColors.AccentDim.copy(alpha = 0.45f) else FlowColors.Slate

    // Proportional preview frame icon inside a 32x32dp box
    val maxSide = 26f
    val r = ratio.value
    val frameWidth = if (r >= 1f) maxSide.dp else (maxSide * r).coerceAtLeast(10f).dp
    val frameHeight = if (r < 1f) maxSide.dp else (maxSide / r).coerceAtLeast(10f).dp

    Column(
        modifier = modifier
            .width(72.dp)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = spacing.sm, horizontal = spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = frameWidth, height = frameHeight)
                    .border(
                        width = 1.5.dp,
                        color = if (selected) FlowColors.Accent else FlowColors.TextSecondary,
                        shape = RoundedCornerShape(3.dp),
                    ),
            )
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = ratio.label,
            style = FlowTypography.labelLarge,
            color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
        )
    }
}

private data class GradientOption(
    val name: String,
    val startArgb: Long,
    val endArgb: Long,
    val angleDegrees: Float = 90f,
)

@Composable
private fun BackgroundSheetPanel(
    currentBackground: CanvasBackground,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val solidColors = remember {
        listOf(
            0xFF000000L to "Negro",
            0xFF1F2227L to "Pizarra",
            0xFFFFFFFFL to "Blanco",
            0xFF0E2954L to "Océano",
            0xFF114B3EL to "Esmeralda",
            0xFF331B4BL to "Violeta",
            0xFF521622L to "Carmesí",
            0xFF4F350EL to "Ámbar",
        )
    }
    val gradientPresets = remember {
        listOf(
            GradientOption("Aurora", 0xFF0F2027L, 0xFF4BE3C1L),
            GradientOption("Atardecer", 0xFF2B1055L, 0xFFFF6B8AL),
            GradientOption("Medianoche", 0xFF07080AL, 0xFF3B6FD9L),
        )
    }

    FlowBottomPanel(
        title = "Fondo del lienzo",
        subtitle = "Desenfoque, color sólido o degradado",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            val isBlur = currentBackground is CanvasBackground.Blur
            FilterChip(
                selected = isBlur,
                onClick = { onAction(EditorAction.SetBackground(CanvasBackground.Blur(0.5f))) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.BlurOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = {
                    Text(
                        text = "Desenfoque",
                        style = FlowTypography.labelLarge,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = FlowColors.Slate,
                    labelColor = FlowColors.TextPrimary,
                    iconColor = FlowColors.TextSecondary,
                    selectedContainerColor = FlowColors.AccentDim,
                    selectedLabelColor = FlowColors.Accent,
                    selectedLeadingIconColor = FlowColors.Accent,
                ),
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(
                    items = gradientPresets,
                    key = { it.name },
                ) { gradient ->
                    val isSelected = currentBackground is CanvasBackground.Gradient &&
                        currentBackground.startArgb == gradient.startArgb &&
                        currentBackground.endArgb == gradient.endArgb
                    val chipShape = RoundedCornerShape(10.dp)

                    Row(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(gradient.startArgb),
                                        Color(gradient.endArgb),
                                    ),
                                )
                            )
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) FlowColors.Accent else FlowColors.Outline,
                                shape = chipShape,
                            )
                            .clickable {
                                onAction(
                                    EditorAction.SetBackground(
                                        CanvasBackground.Gradient(
                                            startArgb = gradient.startArgb,
                                            endArgb = gradient.endArgb,
                                            angleDegrees = gradient.angleDegrees,
                                        )
                                    )
                                )
                            }
                            .padding(horizontal = spacing.md, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = gradient.name,
                            style = FlowTypography.labelMedium,
                            color = FlowColors.TextPrimary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(spacing.md))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(
                items = solidColors,
                key = { it.first },
            ) { (argb, name) ->
                val isSelected = currentBackground is CanvasBackground.Solid &&
                    currentBackground.argb == argb
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) FlowColors.Accent else FlowColors.Outline,
                            shape = CircleShape,
                        )
                        .clickable {
                            onAction(EditorAction.SetBackground(CanvasBackground.Solid(argb)))
                        },
                )
            }
        }
    }
}

/**
 * Maps a speed value in [0.1, 10.0] to a normalized logarithmic slider position in [0, 1]
 * such that 1.0x sits at the exact midpoint (0.5).
 */
private fun speedToLogFraction(speed: Float): Float {
    val clamped = speed.coerceIn(MIN_SHEET_SPEED, MAX_SHEET_SPEED)
    return ((log10(clamped) + 1f) / 2f).coerceIn(0f, 1f)
}

/**
 * Maps a normalized slider position in [0, 1] to a rounded speed multiplier in [0.1, 10.0].
 */
private fun logFractionToSpeed(fraction: Float): Float {
    val raw = 10f.pow(fraction.coerceIn(0f, 1f) * 2f - 1f)
    return ((raw * 100f).roundToInt() / 100f).coerceIn(MIN_SHEET_SPEED, MAX_SHEET_SPEED)
}

internal fun formatSpeedLabel(speed: Float): String {
    return if (speed >= 2f && kotlin.math.abs(speed - speed.roundToInt()) < 0.05f) {
        "${speed.roundToInt()}x"
    } else {
        String.format(Locale.US, "%.2fx", speed).replace(".00x", "x").replace("0x", "x")
    }
}

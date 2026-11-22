package com.rainyworks.flow.feature.editor.ui

import androidx.compose.material3.IconButton

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.FlipToBack
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.designsystem.theme.TimecodeStyle
import com.rainyworks.flow.core.model.ClipAnimations
import com.rainyworks.flow.core.model.AnimatedProperty
import com.rainyworks.flow.core.model.BlendMode
import com.rainyworks.flow.core.model.ChromaKey
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Easing
import com.rainyworks.flow.core.model.Keyframe
import com.rainyworks.flow.core.model.Mask
import com.rainyworks.flow.core.model.MaskShape
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.TrackKind
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorUiState
import com.rainyworks.flow.feature.editor.TimelineMediaSource
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val CHROMA_GREEN_ARGB = 0xFF00FF00L
private const val CHROMA_BLUE_ARGB = 0xFF0000FFL
private const val MIN_CHROMA_SAMPLE_HEIGHT_PX = 720
private val COMPOSITING_PANEL_MAX_HEIGHT = 250.dp
private val KEYFRAMES_LIST_MAX_HEIGHT = 220.dp

// region 1. Chroma Key Panel & Eyedropper

@Composable
internal fun ChromaSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)?.takeIf {
        it.kind == ClipKind.VIDEO || it.kind == ClipKind.IMAGE
    }
    val clipId = clip?.id
    val chroma = clip?.chromaKey
    val active = chroma != null
    val displayed = chroma ?: ChromaKey()
    val currentChroma by rememberUpdatedState(displayed)
    val isPicking = clipId != null && state.pickingChromaFor == clipId

    fun emit(updated: ChromaKey?, final: Boolean) {
        val targetId = clipId ?: return
        onAction(EditorAction.SetChromaKey(targetId, updated, final))
    }

    FlowBottomPanel(
        title = "Chroma key",
        subtitle = if (isPicking) "Toca el color a eliminar en la vista previa" else "Elimina un color de fondo del clip",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        if (clip == null) {
            SheetEmptyMessage("Selecciona un clip de video o imagen para aplicar chroma key.")
            return@FlowBottomPanel
        }

        Column(
            modifier = Modifier
                .heightIn(max = COMPOSITING_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SheetSwitchRow(
                label = "Activar chroma key",
                checked = active,
                onCheckedChange = { enabled ->
                    emit(if (enabled) ChromaKey() else null, true)
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                // Key color sample swatch
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(displayed.argb))
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) FlowColors.Accent else FlowColors.Outline,
                            shape = CircleShape,
                        ),
                )

                val currentRgb = displayed.argb and 0xFFFFFFL
                FilterChip(
                    selected = active && currentRgb == (CHROMA_GREEN_ARGB and 0xFFFFFFL),
                    onClick = { emit(currentChroma.copy(argb = CHROMA_GREEN_ARGB), true) },
                    label = { Text(text = "Verde", style = FlowTypography.labelMedium) },
                    colors = sheetChipColors(),
                )

                FilterChip(
                    selected = active && currentRgb == (CHROMA_BLUE_ARGB and 0xFFFFFFL),
                    onClick = { emit(currentChroma.copy(argb = CHROMA_BLUE_ARGB), true) },
                    label = { Text(text = "Azul", style = FlowTypography.labelMedium) },
                    colors = sheetChipColors(),
                )

                SheetActionChip(
                    label = "Cuentagotas",
                    icon = Icons.Rounded.Colorize,
                    enabled = true,
                    selected = isPicking,
                    onClick = { onAction(EditorAction.PickChromaColor(clip.id)) },
                )
            }

            ResettableSlider(
                label = "Intensidad / Tolerancia",
                value = displayed.tolerance.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                valueText = ::formatHundred,
                resetValue = 0.3f,
                enabled = active,
                onValueChange = { v, final ->
                    emit(currentChroma.copy(tolerance = (v * 100f).roundToInt() / 100f), final)
                },
            )

            ResettableSlider(
                label = "Suavizado de borde",
                value = displayed.softness.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                valueText = ::formatHundred,
                resetValue = 0.1f,
                enabled = active,
                onValueChange = { v, final ->
                    emit(currentChroma.copy(softness = (v * 100f).roundToInt() / 100f), final)
                },
            )

            ResettableSlider(
                label = "Derrame de color",
                value = displayed.spillSuppression.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                valueText = ::formatHundred,
                resetValue = 0.5f,
                enabled = active,
                onValueChange = { v, final ->
                    emit(currentChroma.copy(spillSuppression = (v * 100f).roundToInt() / 100f), final)
                },
            )

            ResettableSlider(
                label = "Sombras",
                value = displayed.shadows.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                valueText = ::formatHundred,
                resetValue = 0f,
                enabled = active,
                onValueChange = { v, final ->
                    emit(currentChroma.copy(shadows = (v * 100f).roundToInt() / 100f), final)
                },
            )
        }
    }
}

/**
 * Overlaid over the preview canvas while [EditorUiState.pickingChromaFor] is active.
 * Displays the prompt "Toca el color a eliminar" and samples the frame of the target clip
 * at the next tap location, mapping canvas coordinates back to image pixel coordinates.
 */
@Composable
internal fun ChromaEyedropperOverlay(
    clipId: String,
    project: Project?,
    playheadUs: Long,
    mediaSource: TimelineMediaSource,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val scope = rememberCoroutineScope()
    val clip = remember(project, clipId) { project?.findClip(clipId)?.second }
    val media = clip?.media
    val sourceTimeUs = remember(clip, playheadUs) {
        clip?.let { it.freezeAtUs ?: it.sourceTimeAt(playheadUs) } ?: 0L
    }
    val sampleHeightPx = remember(media) {
        (media?.displayHeight?.takeIf { it > 0 } ?: MIN_CHROMA_SAMPLE_HEIGHT_PX)
            .coerceAtLeast(MIN_CHROMA_SAMPLE_HEIGHT_PX)
    }

    val preloadedFrame by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = media?.uri,
        key2 = sourceTimeUs,
        key3 = sampleHeightPx,
    ) {
        value = media?.takeIf { it.hasVideo }?.let {
            mediaSource.thumbnail(it, sourceTimeUs, sampleHeightPx)
        }
    }

    val latestClip by rememberUpdatedState(clip)
    val latestPlayheadUs by rememberUpdatedState(playheadUs)
    val latestPreloadedFrame by rememberUpdatedState(preloadedFrame)
    val latestMediaSource by rememberUpdatedState(mediaSource)
    val latestOnAction by rememberUpdatedState(onAction)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(clipId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val up = waitForUpOrCancellation()
                    up?.consume()
                    val tapPosition = up?.position ?: down.position
                    val canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                    val targetClip = latestClip
                    val currentPlayhead = latestPlayheadUs

                    scope.launch {
                        val targetMedia = targetClip?.media
                        val heightPx = (targetMedia?.displayHeight?.takeIf { it > 0 } ?: MIN_CHROMA_SAMPLE_HEIGHT_PX)
                            .coerceAtLeast(MIN_CHROMA_SAMPLE_HEIGHT_PX)
                        val sampleUs = targetClip?.let { it.freezeAtUs ?: it.sourceTimeAt(currentPlayhead) } ?: 0L
                        val bitmap = latestPreloadedFrame
                            ?: targetMedia?.takeIf { it.hasVideo }?.let {
                                latestMediaSource.thumbnail(it, sampleUs, heightPx)
                            }
                        val pickedArgb = sampleChromaColorAtTap(
                            bitmap = bitmap,
                            clip = targetClip,
                            playheadUs = currentPlayhead,
                            tap = tapPosition,
                            canvasSize = canvasSize,
                        )
                        latestOnAction(EditorAction.ChromaColorPicked(clipId, pickedArgb))
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = spacing.sm)
                .clip(RoundedCornerShape(20.dp))
                .background(FlowColors.Scrim)
                .border(1.dp, FlowColors.Accent, RoundedCornerShape(20.dp))
                .padding(horizontal = spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Colorize,
                contentDescription = null,
                tint = FlowColors.Accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Toca el color a eliminar",
                style = FlowTypography.labelLarge,
                color = FlowColors.TextPrimary,
            )
        }
    }
}

/**
 * Maps a canvas tap point [tap] into the source image pixel coordinates of [clip], respecting
 * its animated transform (offset, scale, rotation, flip, crop) and contain fitting inside
 * [canvasSize], and returns the sampled opaque ARGB color.
 */
internal fun sampleChromaColorAtTap(
    bitmap: ImageBitmap?,
    clip: Clip?,
    playheadUs: Long,
    tap: Offset,
    canvasSize: Size,
): Long {
    if (bitmap == null || clip == null || bitmap.width <= 0 || bitmap.height <= 0 ||
        canvasSize.width <= 0f || canvasSize.height <= 0f
    ) {
        return clip?.chromaKey?.argb ?: CHROMA_GREEN_ARGB
    }

    val transform = ClipAnimations.renderedTransform(clip, playheadUs).first
    val frame = clipFrameFor(canvasSize, clip.media, transform)

    val dx = tap.x - frame.center.x
    val dy = tap.y - frame.center.y
    val radians = -frame.rotationDegrees * (PI.toFloat() / 180f)
    val cosA = cos(radians)
    val sinA = sin(radians)
    val localX = dx * cosA - dy * sinA
    val localY = dx * sinA + dy * cosA

    var normX = if (frame.width > 0f) localX / frame.width else 0f
    var normY = if (frame.height > 0f) localY / frame.height else 0f
    if (transform.flipHorizontal) normX = -normX
    if (transform.flipVertical) normY = -normY

    val cropU = (normX + 0.5f).coerceIn(0f, 1f)
    val cropV = (normY + 0.5f).coerceIn(0f, 1f)
    val crop = transform.crop
    val imageU = (crop.left + cropU * (crop.right - crop.left)).coerceIn(0f, 1f)
    val imageV = (crop.top + cropV * (crop.bottom - crop.top)).coerceIn(0f, 1f)

    val px = (imageU * (bitmap.width - 1)).roundToInt().coerceIn(0, bitmap.width - 1)
    val py = (imageV * (bitmap.height - 1)).roundToInt().coerceIn(0, bitmap.height - 1)

    val pixelBuffer = IntArray(1)
    val colorInt = runCatching {
        bitmap.readPixels(pixelBuffer, startX = px, startY = py, width = 1, height = 1)
        pixelBuffer[0]
    }.getOrElse {
        val androidBitmap = bitmap.asAndroidBitmap()
        val softwareBitmap = if (androidBitmap.config == Bitmap.Config.HARDWARE) {
            androidBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            androidBitmap
        }
        softwareBitmap.getPixel(px, py)
    }

    return (colorInt.toLong() and 0xFFFFFFL) or 0xFF000000L
}

// endregion

// region 2. Blend (Fusión) Panel

internal fun BlendMode.spanishLabel(): String = when (this) {
    BlendMode.NORMAL -> "Normal"
    BlendMode.MULTIPLY -> "Multiplicar"
    BlendMode.SCREEN -> "Pantalla"
    BlendMode.OVERLAY -> "Superponer"
    BlendMode.DARKEN -> "Oscurecer"
    BlendMode.LIGHTEN -> "Aclarar"
    BlendMode.SOFT_LIGHT -> "Luz suave"
    BlendMode.DIFFERENCE -> "Diferencia"
}

@Composable
internal fun BlendSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val selection = remember(state.project, state.selectedClipId) {
        state.selectedClipId?.let { id -> state.project?.findClip(id) }
    }
    val track = selection?.first
    val clip = selection?.second
    val isOverlayTrack = track?.kind == TrackKind.OVERLAY && clip != null

    FlowBottomPanel(
        title = "Fusión",
        subtitle = if (isOverlayTrack) {
            "Modo: ${clip.blendMode.spanishLabel()}"
        } else {
            "Mezcla con las capas inferiores"
        },
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        if (!isOverlayTrack) {
            SheetEmptyMessage(
                text = "El modo de fusión solo aplica a clips en pistas de superposición.",
            )
            return@FlowBottomPanel
        }

        val modes = BlendMode.entries
        val rows = remember(modes) { modes.chunked(4) }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            rows.forEach { rowModes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    rowModes.forEach { mode ->
                        val selected = clip.blendMode == mode
                        val shape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(shape)
                                .background(
                                    if (selected) FlowColors.AccentDim.copy(alpha = 0.5f) else FlowColors.Slate
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) FlowColors.Accent else FlowColors.Outline,
                                    shape = shape,
                                )
                                .clickable {
                                    onAction(EditorAction.SetBlendMode(clip.id, mode))
                                }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.spanishLabel(),
                                style = FlowTypography.labelMedium,
                                color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(spacing.xs))

            SheetSlider(
                label = "Opacidad",
                value = clip.opacity.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                valueText = { "${(it * 100f).roundToInt()}%" },
                enabled = true,
                onValueChange = { v, final ->
                    onAction(EditorAction.SetOpacity(clip.id, (v * 100f).roundToInt() / 100f, final))
                },
            )
        }
    }
}

// endregion

// region 3. Mask Panel & Shape Geometry

private data class MaskShapeOption(
    val shape: MaskShape?,
    val label: String,
)

private val MaskShapeOptions = listOf(
    MaskShapeOption(null, "Ninguna"),
    MaskShapeOption(MaskShape.SPLIT, "Línea dividida"),
    MaskShapeOption(MaskShape.CIRCLE, "Círculo"),
    MaskShapeOption(MaskShape.RECTANGLE, "Rectángulo"),
    MaskShapeOption(MaskShape.HEART, "Corazón"),
    MaskShapeOption(MaskShape.STAR, "Estrella"),
)

@Composable
internal fun MaskSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)?.takeIf {
        it.kind == ClipKind.VIDEO || it.kind == ClipKind.IMAGE
    }
    val clipId = clip?.id
    val mask = clip?.animatedMaskAt(state.playheadUs)
    val currentMask by rememberUpdatedState(mask)

    fun emit(updated: Mask?, final: Boolean) {
        val targetId = clipId ?: return
        onAction(EditorAction.SetMask(targetId, updated, final))
    }

    FlowBottomPanel(
        title = "Máscara",
        subtitle = if (mask != null) {
            "Arrastra o pellizca sobre la vista previa para colocar la máscara"
        } else {
            "Recorta el clip con una forma geométrica"
        },
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            if (mask != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    SheetActionChip(
                        label = "Invertir",
                        icon = Icons.Rounded.FlipToBack,
                        enabled = true,
                        selected = mask.inverted,
                        onClick = {
                            val m = currentMask ?: return@SheetActionChip
                            emit(m.copy(inverted = !m.inverted), true)
                        },
                    )
                    SheetActionChip(
                        label = "Restablecer",
                        icon = Icons.Rounded.RestartAlt,
                        enabled = true,
                        onClick = {
                            val m = currentMask ?: return@SheetActionChip
                            emit(Mask(shape = m.shape), true)
                        },
                    )
                }
            }
        },
        modifier = modifier,
    ) {
        if (clip == null) {
            SheetEmptyMessage("Selecciona un clip de video o imagen para aplicar una máscara.")
            return@FlowBottomPanel
        }

        Column(
            modifier = Modifier
                .heightIn(max = COMPOSITING_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(MaskShapeOptions, key = { it.label }) { option ->
                    val selected = mask?.shape == option.shape
                    MaskShapeTile(
                        option = option,
                        selected = selected,
                        onClick = {
                            val targetShape = option.shape
                            if (targetShape == null) {
                                emit(null, true)
                            } else {
                                val existing = currentMask
                                emit(
                                    existing?.copy(shape = targetShape) ?: Mask(shape = targetShape),
                                    true,
                                )
                            }
                        },
                    )
                }
            }

            if (mask != null) {
                ResettableSlider(
                    label = "Difuminado",
                    value = mask.feather.coerceIn(0f, 1f),
                    valueRange = 0f..1f,
                    valueText = ::formatHundred,
                    resetValue = 0f,
                    onValueChange = { v, final ->
                        val m = currentMask ?: return@ResettableSlider
                        emit(m.copy(feather = (v * 100f).roundToInt() / 100f), final)
                    },
                )

                if (mask.shape == MaskShape.RECTANGLE) {
                    ResettableSlider(
                        label = "Redondeo",
                        value = mask.cornerRadius.coerceIn(0f, 1f),
                        valueRange = 0f..1f,
                        valueText = ::formatHundred,
                        resetValue = 0f,
                        onValueChange = { v, final ->
                            val m = currentMask ?: return@ResettableSlider
                            emit(m.copy(cornerRadius = (v * 100f).roundToInt() / 100f), final)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MaskShapeTile(
    option: MaskShapeOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val iconColor = if (selected) FlowColors.Accent else FlowColors.TextPrimary
    Column(
        modifier = Modifier
            .width(68.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(if (selected) FlowColors.AccentDim.copy(alpha = 0.45f) else FlowColors.Slate)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) FlowColors.Accent else FlowColors.Outline,
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(26.dp)) {
                drawMaskShapeIcon(option.shape, iconColor)
            }
        }
        Text(
            text = option.label,
            style = FlowTypography.labelSmall,
            color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun DrawScope.drawMaskShapeIcon(shape: MaskShape?, color: Color) {
    val stroke = Stroke(width = 2.dp.toPx())
    val r = size.minDimension * 0.42f
    val c = Offset(size.width / 2f, size.height / 2f)

    when (shape) {
        null -> {
            drawCircle(color = color, radius = r, center = c, style = stroke)
            val d = r * 0.7f
            drawLine(
                color = color,
                start = Offset(c.x - d, c.y + d),
                end = Offset(c.x + d, c.y - d),
                strokeWidth = 2.dp.toPx(),
            )
        }
        MaskShape.SPLIT -> {
            drawRect(
                color = color.copy(alpha = 0.25f),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = Size(size.width - 4.dp.toPx(), size.height / 2f - 2.dp.toPx()),
            )
            drawLine(
                color = color,
                start = Offset(0f, c.y),
                end = Offset(size.width, c.y),
                strokeWidth = 2.dp.toPx(),
            )
        }
        MaskShape.CIRCLE -> {
            drawCircle(color = color.copy(alpha = 0.22f), radius = r, center = c)
            drawCircle(color = color, radius = r, center = c, style = stroke)
        }
        MaskShape.RECTANGLE -> {
            val rectSize = Size(r * 2f, r * 1.6f)
            val topLeft = Offset(c.x - rectSize.width / 2f, c.y - rectSize.height / 2f)
            val cr = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            drawRoundRect(color = color.copy(alpha = 0.22f), topLeft = topLeft, size = rectSize, cornerRadius = cr)
            drawRoundRect(color = color, topLeft = topLeft, size = rectSize, cornerRadius = cr, style = stroke)
        }
        MaskShape.HEART -> {
            val path = buildHeartPath(c, r)
            drawPath(path = path, color = color.copy(alpha = 0.22f))
            drawPath(path = path, color = color, style = stroke)
        }
        MaskShape.STAR -> {
            val path = buildStarPath(c, r, r * 0.44f)
            drawPath(path = path, color = color.copy(alpha = 0.22f))
            drawPath(path = path, color = color, style = stroke)
        }
    }
}

internal fun buildHeartPath(center: Offset, radius: Float): Path = Path().apply {
    val w = radius * 1.05f
    val h = radius * 1.05f
    val topY = center.y - h * 0.35f
    val bottomY = center.y + h * 0.85f
    moveTo(center.x, bottomY)
    cubicTo(
        center.x - w * 1.25f, center.y + h * 0.2f,
        center.x - w * 0.95f, center.y - h * 0.95f,
        center.x, topY,
    )
    cubicTo(
        center.x + w * 0.95f, center.y - h * 0.95f,
        center.x + w * 1.25f, center.y + h * 0.2f,
        center.x, bottomY,
    )
    close()
}

internal fun buildStarPath(center: Offset, outerRadius: Float, innerRadius: Float, points: Int = 5): Path =
    Path().apply {
        val totalVertices = points * 2
        for (i in 0 until totalVertices) {
            val r = if (i % 2 == 0) outerRadius else innerRadius
            val angle = -PI / 2.0 + i * PI / points
            val x = center.x + (r * cos(angle)).toFloat()
            val y = center.y + (r * sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

internal fun buildRectangleMaskPath(center: Offset, width: Float, height: Float, cornerRadiusFraction: Float): Path =
    Path().apply {
        val hw = width / 2f
        val hh = height / 2f
        val r = cornerRadiusFraction.coerceIn(0f, 1f) * minOf(hw, hh)
        addRoundRect(
            RoundRect(
                rect = Rect(center.x - hw, center.y - hh, center.x + hw, center.y + hh),
                cornerRadius = CornerRadius(r, r),
            ),
        )
    }

// endregion

// region 4. Keyframes (Animación) Panel

private data class AnimatablePropertySpec(
    val property: String,
    val label: String,
    val formatValue: (Float) -> String,
)

private val BaseAnimatableSpecs = listOf(
    AnimatablePropertySpec(AnimatedProperty.OFFSET_X, "Posición X", ::formatSignedPercent),
    AnimatablePropertySpec(AnimatedProperty.OFFSET_Y, "Posición Y", ::formatSignedPercent),
    AnimatablePropertySpec(AnimatedProperty.SCALE, "Escala") { "${(it * 100f).roundToInt()}%" },
    AnimatablePropertySpec(AnimatedProperty.ROTATION, "Rotación") { "${it.roundToInt()}°" },
    AnimatablePropertySpec(AnimatedProperty.OPACITY, "Opacidad") { "${(it * 100f).roundToInt()}%" },
    AnimatablePropertySpec(AnimatedProperty.VOLUME, "Volumen") { "${(it * 100f).roundToInt()}%" },
)

private val MaskAnimatableSpecs = listOf(
    AnimatablePropertySpec(AnimatedProperty.MASK_OFFSET_X, "Máscara", ::formatSignedPercent),
    AnimatablePropertySpec(AnimatedProperty.MASK_OFFSET_Y, "Máscara Y", ::formatSignedPercent),
    AnimatablePropertySpec(AnimatedProperty.MASK_SCALE, "Escala de máscara") { "${(it * 100f).roundToInt()}%" },
    AnimatablePropertySpec(AnimatedProperty.MASK_ROTATION, "Rotación de máscara") { "${it.roundToInt()}°" },
    AnimatablePropertySpec(AnimatedProperty.MASK_FEATHER, "Difuminado de máscara", ::formatHundred),
)

private data class EasingOption(val easing: Easing, val label: String)

private val EasingOptions = listOf(
    EasingOption(Easing.LINEAR, "Lineal"),
    EasingOption(Easing.EASE_IN, "Entrada suave"),
    EasingOption(Easing.EASE_OUT, "Salida suave"),
    EasingOption(Easing.EASE_IN_OUT, "Suave"),
    EasingOption(Easing.HOLD, "Mantener"),
)

private fun Clip.keyframeAtPlayhead(property: String, playheadUs: Long): Keyframe? {
    val relativeUs = playheadUs - startUs
    return keyframes[property]
        ?.filter { abs(it.timeUs - relativeUs) <= MediaEdits.KEYFRAME_TOLERANCE_US }
        ?.minByOrNull { abs(it.timeUs - relativeUs) }
}

@Composable
internal fun KeyframesSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)
    val playheadUs = state.playheadUs

    val keyframeTimes = remember(clip) { clip?.let(MediaEdits::keyframeTimes).orEmpty() }
    val hasPrevKeyframe = keyframeTimes.any { it < playheadUs - 1_000L }
    val hasNextKeyframe = keyframeTimes.any { it > playheadUs + 1_000L }
    val motionKeyframe = clip?.let { c ->
        MediaEdits.MOTION_PROPERTIES.firstNotNullOfOrNull { c.keyframeAtPlayhead(it, playheadUs) }
    }
    val animated = clip != null && MediaEdits.hasMotionKeyframes(clip)
    val extraSpecs = remember(clip?.mask != null, clip?.media?.hasAudio) {
        buildList {
            if (clip?.media?.hasAudio == true) add(BaseAnimatableSpecs.last())
            if (clip?.mask != null) addAll(MaskAnimatableSpecs)
        }
    }

    FlowBottomPanel(
        title = "Animación",
        subtitle = when {
            motionKeyframe != null -> "Keyframe en el cabezal"
            animated -> "Cambia el clip aquí para crear otro keyframe"
            else -> "Agrega un keyframe para empezar a animar"
        },
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            if (clip != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        enabled = hasPrevKeyframe,
                        onClick = { onAction(EditorAction.JumpToKeyframe(clip.id, forward = false)) },
                    ) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Keyframe anterior") }
                    IconButton(
                        enabled = hasNextKeyframe,
                        onClick = { onAction(EditorAction.JumpToKeyframe(clip.id, forward = true)) },
                    ) { Icon(Icons.Rounded.SkipNext, contentDescription = "Keyframe siguiente") }
                }
            }
        },
        modifier = modifier,
    ) {
        if (clip == null) {
            SheetEmptyMessage("Selecciona un clip para animarlo.")
            return@FlowBottomPanel
        }

        Column(
            modifier = Modifier
                .heightIn(max = KEYFRAMES_LIST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (motionKeyframe != null) FlowColors.Keyframe.copy(alpha = 0.12f) else FlowColors.Slate)
                    .clickable { onAction(EditorAction.ToggleKeyframe(clip.id, AnimatedProperty.OFFSET_X)) }
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyframeDiamondButton(
                    filled = motionKeyframe != null,
                    contentDescription = if (motionKeyframe != null) "Quitar keyframe" else "Agregar keyframe",
                    onClick = { onAction(EditorAction.ToggleKeyframe(clip.id, AnimatedProperty.OFFSET_X)) },
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (motionKeyframe != null) "Quitar keyframe" else "Agregar keyframe",
                        style = FlowTypography.titleSmall,
                        color = FlowColors.TextPrimary,
                    )
                    Text(
                        text = "Guarda posición, tamaño, giro y opacidad en este momento.",
                        style = FlowTypography.bodySmall,
                        color = FlowColors.TextSecondary,
                    )
                }
            }

            if (motionKeyframe != null) {
                Text("Movimiento hacia el siguiente keyframe", style = FlowTypography.labelMedium, color = FlowColors.TextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    items(EasingOptions, key = { it.easing.name }) { option ->
                        FilterChip(
                            selected = motionKeyframe.easing == option.easing,
                            onClick = {
                                onAction(EditorAction.SetKeyframeEasing(clip.id, AnimatedProperty.OFFSET_X, playheadUs, option.easing))
                            },
                            label = { Text(option.label, style = FlowTypography.labelMedium) },
                            colors = sheetChipColors(),
                        )
                    }
                }
            } else if (animated) {
                Text(
                    text = "Mueve, escala o gira el clip en la vista previa y se creará un keyframe aquí.",
                    style = FlowTypography.bodySmall,
                    color = FlowColors.Keyframe,
                )
            } else {
                Text(
                    text = "Después mueve el cabezal a otro momento y cambia el clip en la vista previa: Flow anima el cambio.",
                    style = FlowTypography.bodySmall,
                    color = FlowColors.TextSecondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                val x = MediaEdits.propertyValue(clip, AnimatedProperty.OFFSET_X, playheadUs)
                val y = MediaEdits.propertyValue(clip, AnimatedProperty.OFFSET_Y, playheadUs)
                MotionValue("Posición", "${formatSignedPercent(x)} · ${formatSignedPercent(y)}")
                MotionValue("Tamaño", "${(MediaEdits.propertyValue(clip, AnimatedProperty.SCALE, playheadUs) * 100f).roundToInt()}%")
                MotionValue("Giro", "${MediaEdits.propertyValue(clip, AnimatedProperty.ROTATION, playheadUs).roundToInt()}°")
                MotionValue("Opacidad", "${(MediaEdits.propertyValue(clip, AnimatedProperty.OPACITY, playheadUs) * 100f).roundToInt()}%")
            }

            extraSpecs.forEach { spec ->
                val hasKeyframeHere = clip.keyframeAtPlayhead(spec.property, playheadUs) != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.label, style = FlowTypography.labelLarge, color = FlowColors.TextPrimary, modifier = Modifier.weight(1f))
                    Text(
                        spec.formatValue(MediaEdits.propertyValue(clip, spec.property, playheadUs)),
                        style = TimecodeStyle,
                        color = FlowColors.Accent,
                    )
                    Spacer(Modifier.width(spacing.sm))
                    KeyframeDiamondButton(
                        filled = hasKeyframeHere,
                        contentDescription = if (hasKeyframeHere) "Quitar keyframe de ${spec.label}" else "Añadir keyframe de ${spec.label}",
                        onClick = { onAction(EditorAction.ToggleKeyframe(clip.id, spec.property)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionValue(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(FlowColors.Slate)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = FlowTypography.labelSmall, color = FlowColors.TextSecondary)
        Text(value, style = TimecodeStyle, color = FlowColors.Accent)
    }
}

@Composable
private fun KeyframeDiamondButton(
    filled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                if (filled) FlowColors.Keyframe.copy(alpha = 0.18f) else FlowColors.Graphite
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val r = size.minDimension / 2f - 1.5.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val diamond = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            }
            if (filled) {
                drawPath(path = diamond, color = FlowColors.Keyframe)
            }
            drawPath(
                path = diamond,
                color = if (filled) FlowColors.Keyframe else FlowColors.TextSecondary,
                style = Stroke(width = 1.75.dp.toPx()),
            )
        }
    }
}

// endregion

package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.ColorAdjustments
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.CurvePoint
import com.rainyworks.flow.core.model.FilterCatalog
import com.rainyworks.flow.core.model.FilterCategory
import com.rainyworks.flow.core.model.HslAdjustment
import com.rainyworks.flow.core.model.HslRange
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.ToneCurves
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorSheet
import com.rainyworks.flow.feature.editor.EditorUiState
import com.rainyworks.flow.feature.editor.TimelineMediaSource
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val FILTER_THUMB_SIZE = 62.dp
private const val FILTER_FRAME_QUANTUM_US = 250_000L
private val COLOR_PANEL_MAX_CONTENT_HEIGHT = 300.dp
private val ADJUST_LIST_MAX_HEIGHT = 230.dp
private val CURVE_EDITOR_SIZE = 184.dp
private val CURVE_INSET = 10.dp
private val CURVE_HIT_RADIUS = 22.dp
private const val CURVE_MIN_GAP = 0.02f

// region Shared color helpers

/** True when the clip carries a color grade the panels can edit. */
private fun Clip.isGradable(): Boolean =
    kind == ClipKind.VIDEO || kind == ClipKind.IMAGE || kind == ClipKind.ADJUSTMENT

/** Selected clip plus a way to emit grade changes for it. */
private class GradeTarget(
    val clip: Clip?,
    val grade: ColorGrade,
    private val emitter: (ColorGrade, Boolean) -> Unit,
) {
    val enabled: Boolean get() = clip != null

    fun emit(grade: ColorGrade, final: Boolean) = emitter(grade, final)
}

@Composable
private fun rememberGradeTarget(state: EditorUiState, onAction: (EditorAction) -> Unit): GradeTarget {
    val clip = rememberSelectedClip(state)?.takeIf { it.isGradable() }
    val grade = clip?.color ?: ColorGrade()
    val clipId = clip?.id
    val latestOnAction by rememberUpdatedState(onAction)
    return remember(clip, grade) {
        GradeTarget(clip, grade) { updated, final ->
            if (clipId != null) latestOnAction(EditorAction.SetColorGrade(clipId, updated, final))
        }
    }
}

private enum class ColorSection(val label: String, val sheet: EditorSheet, val icon: ImageVector) {
    Filters("Filtros", EditorSheet.Filters, Icons.Rounded.AutoFixHigh),
    Adjust("Ajustes", EditorSheet.Adjust, Icons.Rounded.Tune),
    Curves("Curvas", EditorSheet.Curves, Icons.AutoMirrored.Rounded.ShowChart),
    Hsl("HSL", EditorSheet.Hsl, Icons.Rounded.Palette),
}

/** Quick switch between the color panels of the same clip. */
@Composable
private fun ColorSectionRow(
    current: ColorSection,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val spacing = FlowTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorSection.entries.forEach { section ->
            SheetActionChip(
                label = section.label,
                icon = section.icon,
                enabled = true,
                selected = section == current,
                onClick = { if (section != current) onAction(EditorAction.OpenSheet(section.sheet)) },
            )
        }
        trailing()
    }
}

@Composable
private fun NoGradableClipMessage() {
    SheetEmptyMessage("Selecciona un clip de video, imagen o una capa de ajuste para editar su color.")
}

// endregion

// region Filters

@Composable
internal fun FiltersSheetPanel(
    state: EditorUiState,
    mediaSource: TimelineMediaSource,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val target = rememberGradeTarget(state, onAction)
    val clip = target.clip
    val grade = target.grade
    val currentPreset = FilterCatalog.find(grade.filterId)
    var category by remember(clip?.id) {
        mutableStateOf(currentPreset?.category ?: FilterCategory.entries.first())
    }
    val presets = remember(category) { FilterCatalog.byCategory(category) }
    val sample = rememberFilterSample(state, clip, mediaSource)

    FlowBottomPanel(
        title = "Filtros",
        subtitle = currentPreset?.let { "${it.name} · ${it.category.label}" } ?: "Sin filtro",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        if (clip == null) {
            NoGradableClipMessage()
            return@FlowBottomPanel
        }
        Column(
            modifier = Modifier
                .heightIn(max = COLOR_PANEL_MAX_CONTENT_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            SheetChipRow(
                options = FilterCategory.entries,
                selected = category,
                label = { it.label },
                onSelect = { category = it },
            )

            Spacer(Modifier.height(spacing.sm))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                item(key = "none") {
                    FilterTile(
                        name = "Ninguno",
                        sample = sample,
                        adjustments = null,
                        selected = grade.filterId == null,
                        onClick = { target.emit(grade.copy(filterId = null), true) },
                    )
                }
                items(presets, key = { it.id }) { preset ->
                    FilterTile(
                        name = preset.name,
                        sample = sample,
                        adjustments = preset.adjustments,
                        selected = grade.filterId == preset.id,
                        onClick = { target.emit(grade.copy(filterId = preset.id), true) },
                    )
                }
            }

            if (grade.filterId != null) {
                Spacer(Modifier.height(spacing.xs))
                ResettableSlider(
                    label = "Intensidad",
                    value = grade.filterIntensity.coerceIn(0f, 1f),
                    valueRange = 0f..1f,
                    valueText = ::formatHundred,
                    resetValue = 1f,
                    onValueChange = { v, final -> target.emit(target.grade.copy(filterIntensity = v), final) },
                )
            }

            Spacer(Modifier.height(spacing.xs))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetActionChip(
                    label = "Ajustes",
                    icon = Icons.Rounded.Tune,
                    enabled = true,
                    onClick = { onAction(EditorAction.OpenSheet(EditorSheet.Adjust)) },
                )
                SheetActionChip(
                    label = "Curvas",
                    icon = Icons.AutoMirrored.Rounded.ShowChart,
                    enabled = true,
                    onClick = { onAction(EditorAction.OpenSheet(EditorSheet.Curves)) },
                )
                SheetActionChip(
                    label = "HSL",
                    icon = Icons.Rounded.Palette,
                    enabled = true,
                    onClick = { onAction(EditorAction.OpenSheet(EditorSheet.Hsl)) },
                )
                SheetActionChip(
                    label = "Aplicar a todos",
                    icon = Icons.Rounded.DoneAll,
                    enabled = true,
                    onClick = { onAction(EditorAction.ApplyGradeToAll(clip.id)) },
                )
                SheetActionChip(
                    label = "Importar LUT (.cube)",
                    icon = Icons.Rounded.FileOpen,
                    enabled = true,
                    onClick = { onAction(EditorAction.ImportLut(clip.id)) },
                )
            }

            if (grade.lutUri != null) {
                Spacer(Modifier.height(spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ResettableSlider(
                        label = "Intensidad del LUT",
                        value = grade.lutIntensity.coerceIn(0f, 1f),
                        valueRange = 0f..1f,
                        valueText = ::formatHundred,
                        resetValue = 1f,
                        onValueChange = { v, final -> target.emit(target.grade.copy(lutIntensity = v), final) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(spacing.sm))
                    SheetActionChip(
                        label = "Quitar LUT",
                        icon = Icons.Rounded.Close,
                        enabled = true,
                        onClick = { target.emit(grade.copy(lutUri = null, lutIntensity = 1f), true) },
                    )
                }
            }
        }
    }
}

/** Frame used as the sample image for the filter tiles: the selected clip or the main track. */
@Composable
private fun rememberFilterSample(
    state: EditorUiState,
    clip: Clip?,
    mediaSource: TimelineMediaSource,
): ImageBitmap? {
    val density = LocalDensity.current
    val heightPx = with(density) { FILTER_THUMB_SIZE.roundToPx() }
    val playhead = state.playheadUs
    val source: Pair<MediaRef, Long>? = remember(clip, state.project, playhead / FILTER_FRAME_QUANTUM_US) {
        fun of(c: Clip): Pair<MediaRef, Long>? {
            val media = c.media?.takeIf { it.hasVideo } ?: return null
            val time = c.freezeAtUs ?: c.sourceTimeAt(playhead.coerceIn(c.startUs, c.endUs - 1))
            return media to (time / FILTER_FRAME_QUANTUM_US) * FILTER_FRAME_QUANTUM_US
        }
        clip?.let(::of) ?: state.project?.mainTrack?.let { main ->
            (main.clipAt(playhead) ?: main.clips.firstOrNull())?.let(::of)
        }
    }
    val bitmap by produceState<ImageBitmap?>(null, source?.first?.uri, source?.second, heightPx) {
        value = source?.let { (media, time) -> mediaSource.thumbnail(media, time, heightPx) }
    }
    return bitmap
}

@Composable
private fun FilterTile(
    name: String,
    sample: ImageBitmap?,
    adjustments: ColorAdjustments?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val colorFilter = remember(adjustments) {
        adjustments?.let { ColorFilter.colorMatrix(ColorMatrix(approximateColorMatrix(it))) }
    }
    val vignette = adjustments?.vignette ?: 0f
    Column(
        modifier = Modifier
            .width(FILTER_THUMB_SIZE)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(FILTER_THUMB_SIZE)
                .clip(shape)
                .background(FlowColors.Slate)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) FlowColors.Accent else FlowColors.Outline,
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (adjustments == null) {
                if (sample != null) {
                    Image(
                        bitmap = sample,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.45f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Block,
                    contentDescription = null,
                    tint = if (selected) FlowColors.Accent else FlowColors.TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                val vignetteModifier = Modifier.drawWithContent {
                    drawContent()
                    if (vignette > 0f) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = (vignette * 1.6f).coerceAtMost(0.85f))),
                                center = center,
                                radius = size.maxDimension * 0.72f,
                            ),
                        )
                    }
                }
                if (sample != null) {
                    Image(
                        bitmap = sample,
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        colorFilter = colorFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(vignetteModifier),
                    )
                } else {
                    Canvas(modifier = Modifier.fillMaxSize().then(vignetteModifier)) {
                        drawFilterPlaceholder(colorFilter)
                    }
                }
            }
        }
        Text(
            text = name,
            style = FlowTypography.labelMedium,
            color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Generic colorful scene used when no frame is available. */
private fun DrawScope.drawFilterPlaceholder(colorFilter: ColorFilter?) {
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF6FA8DC), Color(0xFFF6C177), Color(0xFF3E7C4A))),
        colorFilter = colorFilter,
    )
    drawCircle(
        color = Color(0xFFFFE08A),
        radius = size.minDimension * 0.16f,
        center = Offset(size.width * 0.7f, size.height * 0.32f),
        colorFilter = colorFilter,
    )
    drawCircle(
        color = Color(0xFFD9826B),
        radius = size.minDimension * 0.2f,
        center = Offset(size.width * 0.3f, size.height * 0.7f),
        colorFilter = colorFilter,
    )
}

/**
 * Rough 4x5 color matrix that mimics the look of [a] for small previews: exposure,
 * brightness, contrast, saturation, temperature, tint and fade.
 */
internal fun approximateColorMatrix(a: ColorAdjustments): FloatArray {
    var m = identityMatrix()

    val exposure = 2f.pow(a.exposure)
    m = concat(m, scaleMatrix(exposure, exposure, exposure))

    val brightness = a.brightness * 0.22f * 255f + (a.shadows - a.highlights) * 0.04f * 255f
    m = concat(m, offsetMatrix(brightness, brightness, brightness))

    val contrast = (1f + a.contrast).coerceAtLeast(0f)
    val contrastOffset = 128f * (1f - contrast)
    m = concat(m, scaleMatrix(contrast, contrast, contrast, contrastOffset))

    m = concat(m, saturationMatrix((1f + a.saturation).coerceAtLeast(0f)))

    val warm = a.temperature * 0.18f
    val tint = a.tint * 0.12f
    m = concat(m, scaleMatrix(1f + warm + tint * 0.5f, 1f - tint, 1f - warm + tint * 0.5f))

    val fade = a.fade.coerceIn(0f, 1f) * 0.3f
    m = concat(m, scaleMatrix(1f - fade, 1f - fade, 1f - fade, fade * 160f))
    return m
}

private fun identityMatrix() = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

private fun scaleMatrix(r: Float, g: Float, b: Float, offset: Float = 0f) = floatArrayOf(
    r, 0f, 0f, 0f, offset,
    0f, g, 0f, 0f, offset,
    0f, 0f, b, 0f, offset,
    0f, 0f, 0f, 1f, 0f,
)

private fun offsetMatrix(r: Float, g: Float, b: Float) = scaleMatrix(1f, 1f, 1f).also {
    it[4] = r
    it[9] = g
    it[14] = b
}

private fun saturationMatrix(s: Float): FloatArray {
    val lr = 0.2126f * (1f - s)
    val lg = 0.7152f * (1f - s)
    val lb = 0.0722f * (1f - s)
    return floatArrayOf(
        lr + s, lg, lb, 0f, 0f,
        lr, lg + s, lb, 0f, 0f,
        lr, lg, lb + s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** Returns the matrix that applies [first] and then [then]. */
private fun concat(first: FloatArray, then: FloatArray): FloatArray {
    val out = FloatArray(20)
    for (r in 0 until 4) {
        for (c in 0 until 5) {
            var sum = 0f
            for (k in 0 until 4) sum += then[r * 5 + k] * first[k * 5 + c]
            if (c == 4) sum += then[r * 5 + 4]
            out[r * 5 + c] = sum
        }
    }
    return out
}

// endregion

// region Adjust

private data class AdjustmentSpec(
    val label: String,
    val signed: Boolean,
    val get: (ColorAdjustments) -> Float,
    val set: (ColorAdjustments, Float) -> ColorAdjustments,
)

private val AdjustmentSpecs = listOf(
    AdjustmentSpec("Brillo", true, { it.brightness }, { a, v -> a.copy(brightness = v) }),
    AdjustmentSpec("Contraste", true, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    AdjustmentSpec("Saturación", true, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    AdjustmentSpec("Exposición", true, { it.exposure }, { a, v -> a.copy(exposure = v) }),
    AdjustmentSpec("Nitidez", true, { it.sharpness }, { a, v -> a.copy(sharpness = v) }),
    AdjustmentSpec("Iluminaciones", true, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    AdjustmentSpec("Sombras", true, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    AdjustmentSpec("Temperatura", true, { it.temperature }, { a, v -> a.copy(temperature = v) }),
    AdjustmentSpec("Matiz", true, { it.tint }, { a, v -> a.copy(tint = v) }),
    AdjustmentSpec("Viñeta", false, { it.vignette }, { a, v -> a.copy(vignette = v) }),
    AdjustmentSpec("Grano", false, { it.grain }, { a, v -> a.copy(grain = v) }),
    AdjustmentSpec("Desvanecido", false, { it.fade }, { a, v -> a.copy(fade = v) }),
)

@Composable
internal fun AdjustSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val target = rememberGradeTarget(state, onAction)
    val grade = target.grade
    val currentGrade by rememberUpdatedState(grade)

    FlowBottomPanel(
        title = "Ajustes",
        subtitle = "Doble toque en un control para restablecerlo",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                // One tap: tones, color cast and saturation from a few frames of the clip.
                SheetActionChip(
                    label = "Auto",
                    icon = Icons.Rounded.AutoFixHigh,
                    enabled = target.enabled && target.clip?.media != null,
                    onClick = { target.clip?.let { onAction(EditorAction.AutoColor(it.id)) } },
                )
                SheetActionChip(
                    label = "Restablecer todo",
                    icon = Icons.Rounded.RestartAlt,
                    enabled = target.enabled && !grade.adjustments.isIdentity,
                    onClick = { target.emit(grade.copy(adjustments = ColorAdjustments()), true) },
                )
            }
        },
        modifier = modifier,
    ) {
        if (target.clip == null) {
            NoGradableClipMessage()
            return@FlowBottomPanel
        }
        ColorSectionRow(current = ColorSection.Adjust, onAction = onAction)
        Spacer(Modifier.height(spacing.sm))
        Column(
            modifier = Modifier
                .heightIn(max = ADJUST_LIST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AdjustmentSpecs.forEach { spec ->
                ResettableSlider(
                    label = spec.label,
                    value = spec.get(grade.adjustments).coerceIn(if (spec.signed) -1f else 0f, 1f),
                    valueRange = if (spec.signed) -1f..1f else 0f..1f,
                    valueText = if (spec.signed) ::formatSignedHundred else ::formatHundred,
                    onValueChange = { v, final ->
                        val updated = spec.set(currentGrade.adjustments, (v * 100f).roundToInt() / 100f)
                        target.emit(currentGrade.copy(adjustments = updated), final)
                    },
                )
            }
        }
    }
}

// endregion

// region Curves

private enum class CurveChannel(val label: String, val color: Color) {
    Master("RGB", Color(0xFFF2F3F5)),
    Red("R", Color(0xFFFF5A5F)),
    Green("G", Color(0xFF34C759)),
    Blue("B", Color(0xFF3B8BFF)),
}

private fun ToneCurves.points(channel: CurveChannel): List<CurvePoint> = when (channel) {
    CurveChannel.Master -> master
    CurveChannel.Red -> red
    CurveChannel.Green -> green
    CurveChannel.Blue -> blue
}

private fun ToneCurves.with(channel: CurveChannel, points: List<CurvePoint>): ToneCurves = when (channel) {
    CurveChannel.Master -> copy(master = points)
    CurveChannel.Red -> copy(red = points)
    CurveChannel.Green -> copy(green = points)
    CurveChannel.Blue -> copy(blue = points)
}

/** Ensures a usable curve: sorted, with end points at x = 0 and x = 1. */
private fun normalizedCurve(points: List<CurvePoint>): List<CurvePoint> {
    val sorted = points.sortedBy { it.x }.map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
    if (sorted.size < 2) return ToneCurves.IDENTITY
    val result = sorted.toMutableList()
    if (result.first().x > 0f) result[0] = result.first().copy(x = 0f)
    if (result.last().x < 1f) result[result.lastIndex] = result.last().copy(x = 1f)
    return result
}

/**
 * Monotone cubic interpolation (Fritsch-Carlson) through [points], which must be sorted by
 * x. The curve never overshoots between points, so it stays inside the unit square.
 */
internal fun monotoneCurve(points: List<CurvePoint>): (Float) -> Float {
    val n = points.size
    if (n < 2) return { it }
    val xs = FloatArray(n) { points[it].x }
    val ys = FloatArray(n) { points[it].y }
    val d = FloatArray(n - 1) { i -> (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]).coerceAtLeast(1e-5f) }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
    }
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
    }
    return { x ->
        when {
            x <= xs[0] -> ys[0]
            x >= xs[n - 1] -> ys[n - 1]
            else -> {
                var i = 0
                while (i < n - 2 && x > xs[i + 1]) i++
                val h = (xs[i + 1] - xs[i]).coerceAtLeast(1e-5f)
                val t = (x - xs[i]) / h
                val t2 = t * t
                val t3 = t2 * t
                val value = (2f * t3 - 3f * t2 + 1f) * ys[i] +
                    (t3 - 2f * t2 + t) * h * m[i] +
                    (-2f * t3 + 3f * t2) * ys[i + 1] +
                    (t3 - t2) * h * m[i + 1]
                value.coerceIn(0f, 1f)
            }
        }
    }
}

@Composable
internal fun CurvesSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val target = rememberGradeTarget(state, onAction)
    val grade = target.grade
    val currentGrade by rememberUpdatedState(grade)
    var channel by remember { mutableStateOf(CurveChannel.Master) }
    val points = remember(grade.curves, channel) { normalizedCurve(grade.curves.points(channel)) }

    fun emitPoints(updated: List<CurvePoint>, final: Boolean) {
        target.emit(currentGrade.copy(curves = currentGrade.curves.with(channel, updated)), final)
    }

    FlowBottomPanel(
        title = "Curvas",
        subtitle = "Toca para añadir, arrastra para mover, doble toque para quitar",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        if (target.clip == null) {
            NoGradableClipMessage()
            return@FlowBottomPanel
        }
        ColorSectionRow(current = ColorSection.Curves, onAction = onAction)
        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            CurveEditor(
                points = points,
                color = channel.color,
                guides = CurveChannel.entries
                    .filter { it != channel }
                    .map { it.color.copy(alpha = 0.35f) to normalizedCurve(grade.curves.points(it)) }
                    .filter { (_, pts) -> pts != ToneCurves.IDENTITY },
                onChange = ::emitPoints,
                modifier = Modifier.size(CURVE_EDITOR_SIZE),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    CurveChannel.entries.forEach { option ->
                        ChannelButton(
                            channel = option,
                            selected = option == channel,
                            edited = normalizedCurve(grade.curves.points(option)) != ToneCurves.IDENTITY,
                            onClick = { channel = option },
                        )
                    }
                }
                SheetActionChip(
                    label = "Restablecer canal",
                    icon = Icons.Rounded.RestartAlt,
                    enabled = points != ToneCurves.IDENTITY,
                    onClick = { emitPoints(ToneCurves.IDENTITY, true) },
                )
                SheetActionChip(
                    label = "Restablecer todo",
                    icon = Icons.Rounded.Gradient,
                    enabled = grade.curves != ToneCurves(),
                    onClick = { target.emit(currentGrade.copy(curves = ToneCurves()), true) },
                )
            }
        }
    }
}

@Composable
private fun ChannelButton(
    channel: CurveChannel,
    selected: Boolean,
    edited: Boolean,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(if (selected) channel.color.copy(alpha = 0.22f) else FlowColors.Slate)
            .border(if (selected) 2.dp else 1.dp, if (selected) channel.color else FlowColors.Outline, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = channel.label,
            style = FlowTypography.labelMedium,
            color = if (selected) channel.color else FlowColors.TextPrimary,
        )
        if (edited) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(FlowColors.Accent),
            )
        }
    }
}

/**
 * Square tone curve editor. Tapping adds a point, dragging moves one (the end points only
 * move vertically and inner points stay between their neighbors) and a double tap removes
 * an inner point.
 */
@Composable
private fun CurveEditor(
    points: List<CurvePoint>,
    color: Color,
    guides: List<Pair<Color, List<CurvePoint>>>,
    onChange: (List<CurvePoint>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val insetPx = with(density) { CURVE_INSET.toPx() }
    val hitRadiusPx = with(density) { CURVE_HIT_RADIUS.toPx() }
    val latestPoints by rememberUpdatedState(points)
    val latestOnChange by rememberUpdatedState(onChange)
    var dragPoints by remember { mutableStateOf<List<CurvePoint>?>(null) }
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    var lastTapIndex by remember { mutableStateOf<Int?>(null) }
    var lastTapMs by remember { mutableLongStateOf(0L) }

    val shape = RoundedCornerShape(10.dp)
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(FlowColors.Black)
            .border(1.dp, FlowColors.Outline, shape)
            .pointerInput(insetPx, hitRadiusPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val area = Size(size.width - insetPx * 2f, size.height - insetPx * 2f)
                    if (area.width <= 0f || area.height <= 0f) return@awaitEachGesture

                    fun toCurve(p: Offset) = CurvePoint(
                        x = ((p.x - insetPx) / area.width).coerceIn(0f, 1f),
                        y = (1f - (p.y - insetPx) / area.height).coerceIn(0f, 1f),
                    )
                    fun toPx(c: CurvePoint) = Offset(insetPx + c.x * area.width, insetPx + (1f - c.y) * area.height)

                    val start = latestPoints
                    val hitIndex = start.indices
                        .minByOrNull { (toPx(start[it]) - down.position).getDistance() }
                        ?.takeIf { (toPx(start[it]) - down.position).getDistance() <= hitRadiusPx }

                    var working = start
                    var index = hitIndex
                    var dragging = false
                    var released = false
                    activeIndex = hitIndex

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            if (index == null) {
                                val inserted = insertCurvePoint(working, toCurve(down.position))
                                if (inserted != null) {
                                    working = inserted.first
                                    index = inserted.second
                                }
                            }
                            activeIndex = index
                        }
                        val i = index
                        if (dragging && i != null) {
                            change.consume()
                            working = moveCurvePoint(working, i, toCurve(change.position))
                            dragPoints = working
                            latestOnChange(working, false)
                        }
                    }

                    if (dragging) {
                        if (index != null) latestOnChange(working, true)
                        lastTapIndex = null
                    } else if (released) {
                        val now = System.currentTimeMillis()
                        if (hitIndex != null) {
                            val isInner = hitIndex in 1 until start.lastIndex
                            val isDouble = lastTapIndex == hitIndex &&
                                now - lastTapMs < viewConfiguration.doubleTapTimeoutMillis
                            if (isDouble && isInner) {
                                latestOnChange(start.filterIndexed { j, _ -> j != hitIndex }, true)
                                lastTapIndex = null
                            } else {
                                lastTapIndex = hitIndex
                                lastTapMs = now
                            }
                        } else {
                            insertCurvePoint(start, toCurve(down.position))?.let { (updated, _) ->
                                latestOnChange(updated, true)
                            }
                            lastTapIndex = null
                        }
                    }
                    dragPoints = null
                    activeIndex = null
                }
            },
    ) {
        val area = Size(size.width - insetPx * 2f, size.height - insetPx * 2f)
        fun toPx(c: CurvePoint) = Offset(insetPx + c.x * area.width, insetPx + (1f - c.y) * area.height)

        val gridColor = FlowColors.Steel
        for (i in 0..4) {
            val x = insetPx + area.width * i / 4f
            val y = insetPx + area.height * i / 4f
            drawLine(gridColor, Offset(x, insetPx), Offset(x, insetPx + area.height), strokeWidth = 1.dp.toPx())
            drawLine(gridColor, Offset(insetPx, y), Offset(insetPx + area.width, y), strokeWidth = 1.dp.toPx())
        }
        drawLine(
            color = FlowColors.Outline,
            start = toPx(CurvePoint(0f, 0f)),
            end = toPx(CurvePoint(1f, 1f)),
            strokeWidth = 1.dp.toPx(),
        )

        guides.forEach { (guideColor, guidePoints) ->
            drawCurvePath(monotoneCurve(guidePoints), guideColor, 1.5.dp.toPx(), ::toPx)
        }

        val shown = dragPoints ?: points
        drawCurvePath(monotoneCurve(shown), color, 2.dp.toPx(), ::toPx)

        val radius = 5.dp.toPx()
        shown.forEachIndexed { i, p ->
            val center = toPx(p)
            val active = i == activeIndex
            drawCircle(Color.Black, radius = radius + 2.dp.toPx(), center = center)
            drawCircle(if (active) FlowColors.Accent else color, radius = if (active) radius * 1.3f else radius, center = center)
        }
    }
}

private fun DrawScope.drawCurvePath(
    curve: (Float) -> Float,
    color: Color,
    width: Float,
    toPx: (CurvePoint) -> Offset,
) {
    val steps = 64
    val path = Path()
    for (s in 0..steps) {
        val x = s / steps.toFloat()
        val p = toPx(CurvePoint(x, curve(x)))
        if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Inserts [point] keeping the list sorted; null when it is too close to an existing point. */
private fun insertCurvePoint(points: List<CurvePoint>, point: CurvePoint): Pair<List<CurvePoint>, Int>? {
    val index = points.indexOfFirst { it.x > point.x }.takeIf { it > 0 } ?: return null
    if (point.x - points[index - 1].x < CURVE_MIN_GAP || points[index].x - point.x < CURVE_MIN_GAP) return null
    val updated = points.toMutableList().apply { add(index, point) }
    return updated to index
}

/** Moves a point: the end points keep their x, inner points stay between their neighbors. */
private fun moveCurvePoint(points: List<CurvePoint>, index: Int, target: CurvePoint): List<CurvePoint> {
    val updated = points.toMutableList()
    val x = when (index) {
        0 -> points[0].x
        points.lastIndex -> points[index].x
        else -> target.x.coerceIn(points[index - 1].x + CURVE_MIN_GAP, points[index + 1].x - CURVE_MIN_GAP)
    }
    updated[index] = CurvePoint(x, target.y.coerceIn(0f, 1f))
    return updated
}

// endregion

// region HSL

private val HslRange.label: String
    get() = when (this) {
        HslRange.RED -> "Rojo"
        HslRange.ORANGE -> "Naranja"
        HslRange.YELLOW -> "Amarillo"
        HslRange.GREEN -> "Verde"
        HslRange.CYAN -> "Cian"
        HslRange.BLUE -> "Azul"
        HslRange.PURPLE -> "Morado"
        HslRange.MAGENTA -> "Magenta"
    }

private val HslRange.swatch: Color
    get() = when (this) {
        HslRange.RED -> Color(0xFFFF3B30)
        HslRange.ORANGE -> Color(0xFFFF9500)
        HslRange.YELLOW -> Color(0xFFFFD60A)
        HslRange.GREEN -> Color(0xFF34C759)
        HslRange.CYAN -> Color(0xFF32D2E6)
        HslRange.BLUE -> Color(0xFF0A6CFF)
        HslRange.PURPLE -> Color(0xFFAF52DE)
        HslRange.MAGENTA -> Color(0xFFFF2D92)
    }

@Composable
internal fun HslSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val target = rememberGradeTarget(state, onAction)
    val grade = target.grade
    val currentGrade by rememberUpdatedState(grade)
    var range by remember { mutableStateOf(HslRange.RED) }
    val adjustment = grade.hsl[range] ?: HslAdjustment()

    fun emit(update: (HslAdjustment) -> HslAdjustment, final: Boolean) {
        val map = currentGrade.hsl.toMutableMap()
        val updated = update(map[range] ?: HslAdjustment())
        if (updated == HslAdjustment()) map.remove(range) else map[range] = updated
        target.emit(currentGrade.copy(hsl = map), final)
    }

    FlowBottomPanel(
        title = "HSL",
        subtitle = "Tono, saturación y luminancia por color · ${range.label}",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            SheetActionChip(
                label = "Restablecer",
                icon = Icons.Rounded.RestartAlt,
                enabled = target.enabled && adjustment != HslAdjustment(),
                onClick = { emit({ HslAdjustment() }, true) },
            )
        },
        modifier = modifier,
    ) {
        if (target.clip == null) {
            NoGradableClipMessage()
            return@FlowBottomPanel
        }
        ColorSectionRow(current = ColorSection.Hsl, onAction = onAction)
        Spacer(Modifier.height(spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HslRange.entries.forEach { option ->
                val selected = option == range
                val edited = grade.hsl[option]?.let { it != HslAdjustment() } == true
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(option.swatch)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) FlowColors.TextPrimary else FlowColors.Outline,
                            shape = CircleShape,
                        )
                        .clickable { range = option },
                    contentAlignment = Alignment.Center,
                ) {
                    if (edited) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(spacing.sm))
        ResettableSlider(
            label = "Tono",
            value = adjustment.hue.coerceIn(-1f, 1f),
            valueRange = -1f..1f,
            valueText = ::formatSignedHundred,
            onValueChange = { v, final -> emit({ it.copy(hue = (v * 100f).roundToInt() / 100f) }, final) },
        )
        ResettableSlider(
            label = "Saturación",
            value = adjustment.saturation.coerceIn(-1f, 1f),
            valueRange = -1f..1f,
            valueText = ::formatSignedHundred,
            onValueChange = { v, final -> emit({ it.copy(saturation = (v * 100f).roundToInt() / 100f) }, final) },
        )
        ResettableSlider(
            label = "Luminancia",
            value = adjustment.luminance.coerceIn(-1f, 1f),
            valueRange = -1f..1f,
            valueText = ::formatSignedHundred,
            onValueChange = { v, final -> emit({ it.copy(luminance = (v * 100f).roundToInt() / 100f) }, final) },
        )
    }
}

// endregion

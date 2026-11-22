package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.AudioEffects
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Timecode
import com.rainyworks.flow.core.model.VoicePreset
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val EQ_BAND_COUNT = 10
private const val EQ_MIN_DB = -12f
private const val EQ_MAX_DB = 12f
private const val PITCH_MIN_SEMITONES = -12f
private const val PITCH_MAX_SEMITONES = 12f
private const val PITCH_SLIDER_STEPS = 23
private val AUDIO_PANEL_MAX_HEIGHT = 270.dp
private val EQ_GRAPH_HEIGHT = 124.dp

internal val EqualizerBandLabels: List<String> = listOf(
    "31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k",
)

internal data class EqualizerQuickPreset(
    val name: String,
    val gainsDb: List<Float>,
)

internal val EqualizerQuickPresets: List<EqualizerQuickPreset> = listOf(
    EqualizerQuickPreset(
        name = "Plano",
        gainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    ),
    EqualizerQuickPreset(
        name = "Graves",
        gainsDb = listOf(7f, 6f, 4.5f, 2f, 0f, -1f, -1.5f, -1f, 0f, 0f),
    ),
    EqualizerQuickPreset(
        name = "Voz",
        gainsDb = listOf(-4f, -3f, -1f, 1.5f, 3.5f, 4.5f, 4f, 2f, 0f, -2f),
    ),
    EqualizerQuickPreset(
        name = "Brillante",
        gainsDb = listOf(-1f, -1f, 0f, 0f, 0.5f, 1.5f, 3.5f, 5.5f, 6.5f, 6f),
    ),
    EqualizerQuickPreset(
        name = "Radio",
        gainsDb = listOf(-10f, -8f, -4f, 2f, 5f, 6f, 5f, -2f, -8f, -10f),
    ),
)

private enum class AudioEffectsTab(val label: String, val icon: ImageVector) {
    Equalizer("Ecualizador", Icons.Rounded.Equalizer),
    Voice("Voz", Icons.Rounded.RecordVoiceOver),
    Space("Espacio", Icons.Rounded.BlurOn),
}

/** True when the clip has an editable audio stream (VIDEO with audio or AUDIO clip). */
internal fun Clip.hasEditableAudio(): Boolean =
    kind == ClipKind.AUDIO || (kind == ClipKind.VIDEO && media?.hasAudio == true)

internal fun VoicePreset.spanishLabel(): String = when (this) {
    VoicePreset.NONE -> "Ninguno"
    VoicePreset.RADIO -> "Radio"
    VoicePreset.PHONE -> "Teléfono"
    VoicePreset.MEGAPHONE -> "Megáfono"
    VoicePreset.ROOM -> "Habitación"
}

internal fun formatSemitones(semitones: Float): String {
    val st = semitones.roundToInt()
    return if (st > 0) "+$st st" else "$st st"
}

private fun normalizedEqualizerBands(gains: List<Float>): List<Float> =
    List(EQ_BAND_COUNT) { index ->
        gains.getOrElse(index) { 0f }.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
    }

// region 1. AudioEffects ("Efectos de sonido") Panel

@Composable
internal fun AudioEffectsSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)?.takeIf { it.hasEditableAudio() }
    val clipId = clip?.id
    val effects = clip?.audioEffects ?: AudioEffects()
    val currentEffects by rememberUpdatedState(effects)
    var activeTab by remember { mutableStateOf(AudioEffectsTab.Equalizer) }

    fun emit(updated: AudioEffects, final: Boolean) {
        val targetId = clipId ?: return
        onAction(EditorAction.SetAudioEffects(targetId, updated, final))
    }

    val hasAnyEffect = !effects.isIdentity && (
        effects.equalizer.any { abs(it) > 0.01f } ||
            effects.pitchSemitones != 0f ||
            effects.echo != 0f ||
            effects.reverb != 0f ||
            effects.preset != VoicePreset.NONE
        )

    FlowBottomPanel(
        title = "Efectos de sonido",
        subtitle = "Ecualizador de 10 bandas, voz y espacio",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            if (clip != null) {
                SheetActionChip(
                    label = "Restablecer",
                    icon = Icons.Rounded.RestartAlt,
                    enabled = hasAnyEffect || effects.equalizer.isNotEmpty(),
                    onClick = {
                        when (activeTab) {
                            AudioEffectsTab.Equalizer -> {
                                if (currentEffects.equalizer.any { abs(it) > 0.01f } || currentEffects.equalizer.isNotEmpty()) {
                                    emit(currentEffects.copy(equalizer = emptyList()), true)
                                } else {
                                    emit(AudioEffects(), true)
                                }
                            }
                            AudioEffectsTab.Voice -> {
                                if (currentEffects.preset != VoicePreset.NONE || currentEffects.pitchSemitones != 0f) {
                                    emit(currentEffects.copy(preset = VoicePreset.NONE, pitchSemitones = 0f), true)
                                } else {
                                    emit(AudioEffects(), true)
                                }
                            }
                            AudioEffectsTab.Space -> {
                                if (currentEffects.echo != 0f || currentEffects.reverb != 0f) {
                                    emit(currentEffects.copy(echo = 0f, reverb = 0f), true)
                                } else {
                                    emit(AudioEffects(), true)
                                }
                            }
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) {
        if (clip == null) {
            SheetEmptyMessage("Selecciona un clip de video con audio o un clip de audio para aplicar efectos de sonido.")
            return@FlowBottomPanel
        }

        Column(
            modifier = Modifier
                .heightIn(max = AUDIO_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AudioEffectsTab.entries.forEach { tab ->
                    SheetActionChip(
                        label = tab.label,
                        icon = tab.icon,
                        enabled = true,
                        selected = tab == activeTab,
                        onClick = { activeTab = tab },
                    )
                }
            }

            when (activeTab) {
                AudioEffectsTab.Equalizer -> EqualizerTabContent(
                    equalizer = effects.equalizer,
                    onUpdateEqualizer = { gains, final ->
                        emit(currentEffects.copy(equalizer = gains), final)
                    },
                )
                AudioEffectsTab.Voice -> VoiceTabContent(
                    effects = effects,
                    onUpdate = ::emit,
                )
                AudioEffectsTab.Space -> SpaceTabContent(
                    effects = effects,
                    onUpdate = ::emit,
                )
            }
        }
    }
}

@Composable
private fun EqualizerTabContent(
    equalizer: List<Float>,
    onUpdateEqualizer: (gains: List<Float>, final: Boolean) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val externalBands = remember(equalizer) { normalizedEqualizerBands(equalizer) }
    val localBands = remember(externalBands) {
        mutableStateListOf<Float>().apply { addAll(externalBands) }
    }
    val latestOnUpdate by rememberUpdatedState(onUpdateEqualizer)

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(EqualizerQuickPresets, key = { it.name }) { preset ->
            val isSelected = preset.gainsDb.indices.all { i ->
                abs(localBands[i] - preset.gainsDb[i]) < 0.15f
            }
            FilterChip(
                selected = isSelected,
                onClick = {
                    for (i in 0 until EQ_BAND_COUNT) {
                        localBands[i] = preset.gainsDb[i]
                    }
                    latestOnUpdate(preset.gainsDb, true)
                },
                label = {
                    Text(
                        text = preset.name,
                        style = FlowTypography.labelMedium,
                    )
                },
                colors = sheetChipColors(),
            )
        }
    }

    EqualizerBandsGraph(
        bands = localBands,
        onBandChange = { index, gainDb, final ->
            localBands[index] = gainDb
            latestOnUpdate(localBands.toList(), final)
        },
    )
}

@Composable
private fun EqualizerBandsGraph(
    bands: List<Float>,
    onBandChange: (index: Int, gainDb: Float, final: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val latestOnBandChange by rememberUpdatedState(onBandChange)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FlowColors.Slate)
            .border(1.dp, FlowColors.Outline, shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(EQ_GRAPH_HEIGHT),
        ) {
            // Smooth response curve & dB grid drawn behind the 10 vertical sliders
            Canvas(modifier = Modifier.fillMaxSize()) {
                val padPx = 10.dp.toPx()
                val trackTop = padPx
                val trackBottom = (size.height - padPx).coerceAtLeast(trackTop + 1f)
                val trackSpan = trackBottom - trackTop
                val zeroY = trackTop + trackSpan * 0.5f

                // Reference lines for +12 dB, 0 dB, -12 dB
                drawLine(
                    color = FlowColors.Steel.copy(alpha = 0.6f),
                    start = Offset(0f, trackTop),
                    end = Offset(size.width, trackTop),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = FlowColors.Outline,
                    start = Offset(0f, zeroY),
                    end = Offset(size.width, zeroY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
                drawLine(
                    color = FlowColors.Steel.copy(alpha = 0.6f),
                    start = Offset(0f, trackBottom),
                    end = Offset(size.width, trackBottom),
                    strokeWidth = 1.dp.toPx(),
                )

                val count = bands.size.coerceAtLeast(1)
                val colWidth = size.width / count.toFloat()
                val points = List(count) { i ->
                    val gain = bands[i].coerceIn(EQ_MIN_DB, EQ_MAX_DB)
                    val norm = (gain - EQ_MIN_DB) / (EQ_MAX_DB - EQ_MIN_DB)
                    val x = colWidth * (i + 0.5f)
                    val y = trackBottom - norm * trackSpan
                    Offset(x, y)
                }

                if (points.size >= 2) {
                    val curvePath = buildSmoothEqPath(points, trackTop, trackBottom)
                    val fillPath = Path().apply {
                        addPath(curvePath)
                        lineTo(points.last().x, zeroY)
                        lineTo(points.first().x, zeroY)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                FlowColors.Accent.copy(alpha = 0.25f),
                                FlowColors.Accent.copy(alpha = 0.04f),
                                FlowColors.Accent.copy(alpha = 0.25f),
                            ),
                            startY = trackTop,
                            endY = trackBottom,
                        ),
                    )
                    drawPath(
                        path = curvePath,
                        color = FlowColors.Accent.copy(alpha = 0.85f),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }

            // 10 interactive vertical band sliders
            Row(modifier = Modifier.fillMaxSize()) {
                for (i in 0 until EQ_BAND_COUNT) {
                    val label = EqualizerBandLabels[i]
                    val gainDb = bands.getOrElse(i) { 0f }.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
                    VerticalEqBandSlider(
                        bandLabel = label,
                        gainDb = gainDb,
                        onGainChange = { db, final -> latestOnBandChange(i, db, final) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        // Frequency labels below the 10 vertical sliders
        Row(modifier = Modifier.fillMaxWidth()) {
            for (i in 0 until EQ_BAND_COUNT) {
                val gainDb = bands.getOrElse(i) { 0f }
                val isAdjusted = abs(gainDb) >= 0.1f
                Text(
                    text = EqualizerBandLabels[i],
                    style = FlowTypography.labelSmall,
                    color = if (isAdjusted) FlowColors.Accent else FlowColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VerticalEqBandSlider(
    bandLabel: String,
    gainDb: Float,
    onGainChange: (gainDb: Float, final: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnGainChange by rememberUpdatedState(onGainChange)
    val latestGainDb by rememberUpdatedState(gainDb)

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = "$bandLabel Hz"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = gainDb,
                    range = EQ_MIN_DB..EQ_MAX_DB,
                )
                setProgress { target ->
                    val clamped = ((target * 2f).roundToInt() / 2f).coerceIn(EQ_MIN_DB, EQ_MAX_DB)
                    latestOnGainChange(clamped, true)
                    true
                }
            }
            .observeDoubleTap {
                latestOnGainChange(0f, true)
            }
            .pointerInput(bandLabel) {
                fun yToDb(y: Float): Float {
                    val padPx = 10.dp.toPx()
                    val trackSpan = (size.height - 2f * padPx).coerceAtLeast(1f)
                    val fraction = (1f - ((y - padPx) / trackSpan)).coerceIn(0f, 1f)
                    val raw = EQ_MIN_DB + fraction * (EQ_MAX_DB - EQ_MIN_DB)
                    return ((raw * 2f).roundToInt() / 2f).coerceIn(EQ_MIN_DB, EQ_MAX_DB)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var latestDb = yToDb(down.position.y)
                    latestOnGainChange(latestDb, false)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        val updatedDb = yToDb(change.position.y)
                        if (updatedDb != latestDb) {
                            latestDb = updatedDb
                            latestOnGainChange(latestDb, false)
                        }
                    }
                    latestOnGainChange(latestGainDb, true)
                }
            },
    ) {
        val padPx = 10.dp.toPx()
        val trackTop = padPx
        val trackBottom = (size.height - padPx).coerceAtLeast(trackTop + 1f)
        val trackSpan = trackBottom - trackTop
        val centerX = size.width * 0.5f
        val zeroY = trackTop + trackSpan * 0.5f
        val norm = ((gainDb - EQ_MIN_DB) / (EQ_MAX_DB - EQ_MIN_DB)).coerceIn(0f, 1f)
        val thumbY = trackBottom - norm * trackSpan

        // Vertical track bar
        drawLine(
            color = FlowColors.Steel,
            start = Offset(centerX, trackTop),
            end = Offset(centerX, trackBottom),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Active vertical bar from 0 dB center to current thumb position
        if (abs(thumbY - zeroY) > 1f) {
            drawLine(
                color = FlowColors.Accent,
                start = Offset(centerX, zeroY),
                end = Offset(centerX, thumbY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Slider thumb
        val thumbRadius = 5.5.dp.toPx()
        drawCircle(
            color = FlowColors.OnAccent,
            radius = thumbRadius + 1.5.dp.toPx(),
            center = Offset(centerX, thumbY),
        )
        drawCircle(
            color = FlowColors.Accent,
            radius = thumbRadius,
            center = Offset(centerX, thumbY),
        )
    }
}

/** Builds a smooth monotone cubic Bezier path through the 10 equalizer band points. */
private fun buildSmoothEqPath(
    points: List<Offset>,
    minY: Float,
    maxY: Float,
): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    if (points.size == 1) return@apply

    val n = points.size
    val tangentsY = FloatArray(n)
    for (i in 0 until n) {
        tangentsY[i] = when (i) {
            0 -> points[1].y - points[0].y
            n - 1 -> points[n - 1].y - points[n - 2].y
            else -> {
                val d0 = points[i].y - points[i - 1].y
                val d1 = points[i + 1].y - points[i].y
                if (d0 * d1 <= 0f) 0f else (d0 + d1) * 0.5f
            }
        }
    }

    for (i in 0 until n - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val dx = (p1.x - p0.x) / 3f
        val c1x = p0.x + dx
        val c1y = (p0.y + tangentsY[i] / 3f).coerceIn(minY, maxY)
        val c2x = p1.x - dx
        val c2y = (p1.y - tangentsY[i + 1] / 3f).coerceIn(minY, maxY)
        cubicTo(c1x, c1y, c2x, c2y, p1.x, p1.y)
    }
}

@Composable
private fun VoiceTabContent(
    effects: AudioEffects,
    onUpdate: (AudioEffects, Boolean) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val currentEffects by rememberUpdatedState(effects)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SheetChipRow(
            options = VoicePreset.entries,
            selected = effects.preset,
            label = { it.spanishLabel() },
            onSelect = { preset ->
                onUpdate(currentEffects.copy(preset = preset), true)
            },
        )

        ResettableSlider(
            label = "Tono",
            value = effects.pitchSemitones.roundToInt().toFloat().coerceIn(PITCH_MIN_SEMITONES, PITCH_MAX_SEMITONES),
            valueRange = PITCH_MIN_SEMITONES..PITCH_MAX_SEMITONES,
            steps = PITCH_SLIDER_STEPS,
            valueText = ::formatSemitones,
            resetValue = 0f,
            onValueChange = { v, final ->
                val snapped = v.roundToInt().toFloat().coerceIn(PITCH_MIN_SEMITONES, PITCH_MAX_SEMITONES)
                onUpdate(currentEffects.copy(pitchSemitones = snapped), final)
            },
        )
    }
}

@Composable
private fun SpaceTabContent(
    effects: AudioEffects,
    onUpdate: (AudioEffects, Boolean) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val currentEffects by rememberUpdatedState(effects)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        ResettableSlider(
            label = "Eco",
            value = effects.echo.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            valueText = ::formatHundred,
            resetValue = 0f,
            onValueChange = { v, final ->
                val rounded = (v * 100f).roundToInt() / 100f
                onUpdate(currentEffects.copy(echo = rounded), final)
            },
        )

        ResettableSlider(
            label = "Reverberación",
            value = effects.reverb.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            valueText = ::formatHundred,
            resetValue = 0f,
            onValueChange = { v, final ->
                val rounded = (v * 100f).roundToInt() / 100f
                onUpdate(currentEffects.copy(reverb = rounded), final)
            },
        )
    }
}

// endregion

// region 2. Fades ("Fundidos") Panel

@Composable
internal fun FadesSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)?.takeIf { it.hasEditableAudio() }
    val clipId = clip?.id
    val maxFadeUs = ((clip?.durationUs ?: 0L) / 2L).coerceAtLeast(0L)
    val maxFadeSec = (maxFadeUs.toFloat() / Timecode.US_PER_SECOND.toFloat())
    val sliderMaxSec = maxFadeSec.coerceAtLeast(0.1f)
    val slidersEnabled = clipId != null && maxFadeUs > 0L

    val fadeInUs = (clip?.fadeInUs ?: 0L).coerceIn(0L, maxFadeUs)
    val fadeOutUs = (clip?.fadeOutUs ?: 0L).coerceIn(0L, maxFadeUs)
    val currentFadeInUs by rememberUpdatedState(fadeInUs)
    val currentFadeOutUs by rememberUpdatedState(fadeOutUs)

    fun formatFadeSeconds(seconds: Float): String =
        String.format(Locale.US, "%.1f s", seconds.coerceIn(0f, maxFadeSec))

    fun secondsToFadeUs(seconds: Float): Long {
        val quantizedSec = (seconds * 10f).roundToInt() / 10f
        return (quantizedSec * Timecode.US_PER_SECOND).roundToLong().coerceIn(0L, maxFadeUs)
    }

    FlowBottomPanel(
        title = "Fundidos",
        subtitle = "Fundido de entrada, salida y opciones de audio",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        headerTrailing = {
            if (clip != null) {
                SheetActionChip(
                    label = "Restablecer",
                    icon = Icons.Rounded.RestartAlt,
                    enabled = fadeInUs > 0L || fadeOutUs > 0L,
                    onClick = {
                        onAction(
                            EditorAction.SetFades(
                                clipId = clip.id,
                                fadeInUs = 0L,
                                fadeOutUs = 0L,
                                final = true,
                            ),
                        )
                    },
                )
            }
        },
        modifier = modifier,
    ) {
        if (clip == null) {
            SheetEmptyMessage("Selecciona un clip de video con audio o un clip de audio para ajustar sus fundidos.")
            return@FlowBottomPanel
        }

        Column(
            modifier = Modifier
                .heightIn(max = AUDIO_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            ResettableSlider(
                label = "Fundido de entrada",
                value = (fadeInUs.toFloat() / Timecode.US_PER_SECOND.toFloat()).coerceIn(0f, sliderMaxSec),
                valueRange = 0f..sliderMaxSec,
                valueText = ::formatFadeSeconds,
                resetValue = 0f,
                enabled = slidersEnabled,
                onValueChange = { sec, final ->
                    onAction(
                        EditorAction.SetFades(
                            clipId = clip.id,
                            fadeInUs = secondsToFadeUs(sec),
                            fadeOutUs = currentFadeOutUs,
                            final = final,
                        ),
                    )
                },
            )

            ResettableSlider(
                label = "Fundido de salida",
                value = (fadeOutUs.toFloat() / Timecode.US_PER_SECOND.toFloat()).coerceIn(0f, sliderMaxSec),
                valueRange = 0f..sliderMaxSec,
                valueText = ::formatFadeSeconds,
                resetValue = 0f,
                enabled = slidersEnabled,
                onValueChange = { sec, final ->
                    onAction(
                        EditorAction.SetFades(
                            clipId = clip.id,
                            fadeInUs = currentFadeInUs,
                            fadeOutUs = secondsToFadeUs(sec),
                            final = final,
                        ),
                    )
                },
            )

            SheetSwitchRow(
                label = "Silenciar clip",
                checked = clip.muted,
                onCheckedChange = { muted ->
                    onAction(EditorAction.SetMuted(clip.id, muted))
                },
            )

            // For background music: it lowers itself under voice and video sound.
            SheetSwitchRow(
                label = "Bajar cuando hay voz",
                checked = clip.duckUnderVoice,
                onCheckedChange = { duck ->
                    onAction(EditorAction.SetDuckUnderVoice(clip.id, duck))
                },
            )

            if (clip.speed != 1f || clip.speedCurve != null) {
                SheetSwitchRow(
                    label = "Mantener tono original",
                    checked = clip.preservePitch,
                    onCheckedChange = { preserve ->
                        onAction(EditorAction.SetPreservePitch(clip.id, preserve))
                    },
                )
            }
        }
    }
}

// endregion

package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.components.FlowLabeledSlider
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography

/** Time window after a double tap during which the slider's own release is ignored. */
private const val RESET_RELEASE_WINDOW_MS = 400L

/**
 * Labeled slider that reports `final = false` while dragging and `final = true` on release.
 * A double tap anywhere on it restores [resetValue].
 */
@Composable
internal fun ResettableSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    onValueChange: (value: Float, final: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    resetValue: Float = 0f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    var resetAtMs by remember { mutableLongStateOf(0L) }
    val latestOnChange by rememberUpdatedState(onValueChange)

    FlowLabeledSlider(
        label = label,
        valueText = valueText(local),
        value = local,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        onValueChange = {
            local = it
            latestOnChange(it, false)
        },
        onValueChangeFinished = {
            val justReset = System.currentTimeMillis() - resetAtMs < RESET_RELEASE_WINDOW_MS
            if (justReset) {
                resetAtMs = 0L
            } else {
                latestOnChange(local, true)
            }
        },
        modifier = modifier.then(
            if (enabled) {
                Modifier.observeDoubleTap {
                    local = resetValue
                    resetAtMs = System.currentTimeMillis()
                    latestOnChange(resetValue, true)
                }
            } else {
                Modifier
            },
        ),
    )
}

/**
 * Detects a double tap without consuming any event, so children (such as a slider) keep
 * receiving the whole gesture. [onDoubleTap] runs before the children see the second release.
 */
internal fun Modifier.observeDoubleTap(onDoubleTap: () -> Unit): Modifier = pointerInput(Unit) {
    var lastTapUpMs = 0L
    var lastTapPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val isSecondTap = down.uptimeMillis - lastTapUpMs < viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastTapPosition).getDistance() < slop * 4f
        var moved = false
        var releasedAtMs: Long? = null
        var releasedAt = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) moved = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > slop) moved = true
            if (!change.pressed) {
                releasedAtMs = change.uptimeMillis
                releasedAt = change.position
                break
            }
        }
        val upMs = releasedAtMs
        when {
            upMs == null || moved -> lastTapUpMs = 0L
            isSecondTap -> {
                lastTapUpMs = 0L
                onDoubleTap()
            }
            else -> {
                lastTapUpMs = upMs
                lastTapPosition = releasedAt
            }
        }
    }
}

/** Horizontal row of selectable filter chips. */
@Composable
internal fun <T> SheetChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val spacing = FlowTheme.spacing
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(options, key = { label(it) }) { option ->
            FilterChip(
                selected = option == selected,
                enabled = enabled,
                onClick = { onSelect(option) },
                label = { Text(text = label(option), style = FlowTypography.labelMedium) },
                colors = sheetChipColors(),
            )
        }
    }
}

/** Row of round color swatches. Colors are compared by RGB so the alpha can be edited separately. */
@Composable
internal fun ColorSwatchRow(
    colors: List<Long>,
    selectedArgb: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    swatchSize: Dp = 28.dp,
) {
    val spacing = FlowTheme.spacing
    val selectedRgb = selectedArgb?.let { it and 0xFFFFFFL }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(colors, key = { it }) { argb ->
            val isSelected = selectedRgb == (argb and 0xFFFFFFL)
            Box(
                modifier = Modifier
                    .size(swatchSize)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (isSelected) 2.5.dp else 1.dp,
                        color = if (isSelected) FlowColors.Accent else FlowColors.Outline,
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled) { onSelect(argb) },
            )
        }
    }
}

/** Label with a trailing switch, used to enable optional style blocks. */
@Composable
internal fun SheetSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = FlowTypography.labelLarge,
            color = if (enabled) FlowColors.TextPrimary else FlowColors.TextTertiary,
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FlowColors.OnAccent,
                checkedTrackColor = FlowColors.Accent,
                uncheckedThumbColor = FlowColors.TextSecondary,
                uncheckedTrackColor = FlowColors.Slate,
                uncheckedBorderColor = FlowColors.Outline,
            ),
        )
    }
}

/** Message shown by clip panels when the selection does not support them. */
@Composable
internal fun SheetEmptyMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = FlowTypography.bodyMedium,
        color = FlowColors.TextSecondary,
        modifier = modifier,
    )
}

internal fun formatSignedHundred(value: Float): String {
    val v = Math.round(value * 100f)
    return if (v > 0) "+$v" else "$v"
}

internal fun formatHundred(value: Float): String = "${Math.round(value * 100f)}"

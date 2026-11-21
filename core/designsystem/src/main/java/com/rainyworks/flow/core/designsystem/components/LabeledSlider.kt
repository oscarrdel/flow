package com.rainyworks.flow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.designsystem.theme.TimecodeStyle

/**
 * Slider control with a title label and a monospace value badge on the right.
 */
@Composable
fun FlowLabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val spacing = FlowTheme.spacing

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = FlowTypography.labelLarge,
                color = if (enabled) FlowColors.TextSecondary else FlowColors.TextTertiary,
            )
            Text(
                text = valueText,
                style = TimecodeStyle,
                color = if (enabled) FlowColors.Accent else FlowColors.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(FlowColors.Slate)
                    .padding(horizontal = spacing.sm, vertical = 2.dp),
            )
        }

        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = FlowColors.Accent,
                activeTrackColor = FlowColors.Accent,
                inactiveTrackColor = FlowColors.Steel,
                activeTickColor = FlowColors.OnAccent,
                inactiveTickColor = FlowColors.Outline,
                disabledThumbColor = FlowColors.TextTertiary,
                disabledActiveTrackColor = FlowColors.Outline,
                disabledInactiveTrackColor = FlowColors.Slate,
            ),
        )
    }
}

package com.rainyworks.flow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography

/**
 * Compact, semi-transparent panel anchored at the bottom of the editor so the
 * upper preview area remains unobstructed at all times.
 */
@Composable
fun FlowBottomPanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = FlowTheme.spacing
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FlowColors.Graphite.copy(alpha = 0.94f))
            .border(
                width = 1.dp,
                color = FlowColors.Outline.copy(alpha = 0.6f),
                shape = shape,
            )
            // Consume taps inside the panel so they don't fall through to the timeline behind it.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .padding(bottom = spacing.md),
    ) {
        // Subtle drag handle pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.sm),
            horizontalArrangement = Arrangement.Center,
        ) {
            Spacer(
                modifier = Modifier
                    .width(32.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(FlowColors.Outline),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = FlowTypography.titleSmall,
                    color = FlowColors.TextPrimary,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = FlowTypography.labelMedium,
                        color = FlowColors.TextSecondary,
                    )
                }
            }
            if (headerTrailing != null) {
                headerTrailing()
                Spacer(Modifier.width(spacing.sm))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Cerrar panel",
                    tint = FlowColors.Accent,
                )
            }
        }

        HorizontalDivider(
            color = FlowColors.Steel.copy(alpha = 0.8f),
            thickness = 1.dp,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            content = content,
        )
    }
}

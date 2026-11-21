package com.rainyworks.flow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography

/**
 * Compact vertical icon + label button used in contextual toolbars.
 */
@Composable
fun FlowToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val spacing = FlowTheme.spacing
    val shape = RoundedCornerShape(12.dp)
    val contentColor = when {
        !enabled -> FlowColors.TextTertiary
        selected -> FlowColors.Accent
        tint != null -> tint
        else -> FlowColors.TextPrimary
    }
    val containerColor = when {
        selected -> FlowColors.AccentDim.copy(alpha = 0.45f)
        else -> Color.Transparent
    }

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = 60.dp, minHeight = 56.dp)
            .clip(shape)
            .background(containerColor)
            .then(
                if (selected) {
                    Modifier.border(1.dp, FlowColors.Accent.copy(alpha = 0.7f), shape)
                } else {
                    Modifier
                }
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = spacing.sm, vertical = spacing.xs + 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = FlowTypography.labelMedium,
            color = if (selected) FlowColors.Accent else contentColor.copy(alpha = if (enabled) 0.92f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.xs),
        )
    }
}

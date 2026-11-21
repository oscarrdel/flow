package com.rainyworks.flow.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val FlowColorScheme = darkColorScheme(
    primary = FlowColors.Accent,
    onPrimary = FlowColors.OnAccent,
    primaryContainer = FlowColors.AccentDim,
    onPrimaryContainer = FlowColors.TextPrimary,
    secondary = FlowColors.Keyframe,
    onSecondary = FlowColors.Black,
    secondaryContainer = FlowColors.AccentDim,
    onSecondaryContainer = FlowColors.TextPrimary,
    tertiary = FlowColors.Marker,
    background = FlowColors.Carbon,
    onBackground = FlowColors.TextPrimary,
    surface = FlowColors.Graphite,
    onSurface = FlowColors.TextPrimary,
    surfaceVariant = FlowColors.Slate,
    onSurfaceVariant = FlowColors.TextSecondary,
    surfaceContainerLowest = FlowColors.Black,
    surfaceContainerLow = FlowColors.Carbon,
    surfaceContainer = FlowColors.Graphite,
    surfaceContainerHigh = FlowColors.Slate,
    surfaceContainerHighest = FlowColors.Steel,
    outline = FlowColors.Outline,
    outlineVariant = FlowColors.Steel,
    error = FlowColors.Error,
    scrim = FlowColors.Scrim,
)

data class FlowSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

val LocalFlowSpacing = staticCompositionLocalOf { FlowSpacing() }

@Composable
fun FlowTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFlowSpacing provides FlowSpacing()) {
        MaterialTheme(
            colorScheme = FlowColorScheme,
            typography = FlowTypography,
            content = content,
        )
    }
}

object FlowTheme {
    val spacing: FlowSpacing
        @Composable get() = LocalFlowSpacing.current
}

package com.rainyworks.flow.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Cempasúchil: a deep violet night lit by marigold, rosa mexicano and turquoise, with
 * surfaces of glass. Names keep their roles (backgrounds, surfaces, text, accent).
 */
object FlowColors {
    /** Behind the video itself. */
    val Black = Color(0xFF0D0517)
    /** Screen ground under the aurora. */
    val Carbon = Color(0xFF1A0B2B)
    /** Solid surfaces where glass is not possible (menus, dialogs). */
    val Graphite = Color(0xFF26123A)
    val Slate = Color(0xFF331B4C)
    val Steel = Color(0xFF42295E)
    val Outline = Color(0xFF5C4280)

    val TextPrimary = Color(0xFFFFF4E8)
    val TextSecondary = Color(0xFFD2BCE3)
    val TextTertiary = Color(0xFF9580AE)

    /** Cempasúchil. */
    val Accent = Color(0xFFFFB01F)
    val AccentDim = Color(0xFF5B2B5E)
    val OnAccent = Color(0xFF2A1040)

    /** Rosa mexicano. */
    val Rosa = Color(0xFFFF2D92)
    /** Turquesa. */
    val Turquesa = Color(0xFF12D3C2)

    val Keyframe = Color(0xFFFF5FAE)
    val Marker = Color(0xFF12D3C2)
    val Error = Color(0xFFFF5A6E)

    val TrackVideo = Color(0xFF7B5CFF)
    val TrackOverlay = Color(0xFFB36BFF)
    val TrackText = Rosa
    val TrackAudio = Turquesa
    val TrackEffect = Color(0xFFFF7A3D)
    val TrackAdjustment = Color(0xFF6FA8FF)

    val Scrim = Color(0xCC0D0517)

    /** Glass: fill at the lit top and at the bottom, and the light caught on its rim. */
    val GlassTop = Color(0x2EFFFFFF)
    val GlassBottom = Color(0x12FFFFFF)
    val GlassRim = Color(0x80FFFFFF)
    val GlassShadow = Color(0x66060010)
}

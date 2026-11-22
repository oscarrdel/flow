package com.rainyworks.flow.feature.editor.ui

import com.rainyworks.flow.core.model.TextAnimation
import com.rainyworks.flow.core.model.TextAnimationType
import com.rainyworks.flow.core.model.TextBackground
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.TextAlign
import com.rainyworks.flow.core.model.TextFill
import com.rainyworks.flow.core.model.TextShadow
import com.rainyworks.flow.core.model.TextStroke

internal data class TextStylePreset(
    val id: String,
    val name: String,
    val apply: (TextContent) -> TextContent,
)

private fun TextContent.withStyle(
    font: String,
    bold: Boolean = false,
    italic: Boolean = false,
    letterSpacing: Float = 0f,
    fill: TextFill,
    stroke: TextStroke? = null,
    shadow: TextShadow? = null,
    background: TextBackground? = null,
    enter: TextAnimation = TextAnimation(),
): TextContent = copy(
    font = font,
    bold = bold,
    italic = italic,
    letterSpacing = letterSpacing,
    fill = fill,
    stroke = stroke,
    shadow = shadow,
    background = background,
    enter = enter,
    exit = TextAnimation(),
    loop = TextAnimation(),
)

internal val TextStylePresets: List<TextStylePreset> = listOf(
    TextStylePreset(
        id = "classic",
        name = "Clásico",
        apply = { current ->
            current.withStyle(
                font = "sans-serif",
                fill = TextFill(argb = 0xFFFFFFFFL),
                shadow = TextShadow(argb = 0x80000000L, offsetX = 0.03f, offsetY = 0.04f, blur = 0.08f),
            )
        },
    ),
    TextStylePreset(
        id = "headline",
        name = "Titular",
        apply = { current ->
            current.withStyle(
                font = "sans-serif-black",
                bold = true,
                letterSpacing = 0.02f,
                fill = TextFill(argb = 0xFFFFFFFFL),
                stroke = TextStroke(argb = 0xFF000000L, width = 0.10f),
                shadow = TextShadow(argb = 0x99000000L, offsetX = 0.03f, offsetY = 0.05f, blur = 0.06f),
                enter = TextAnimation(type = TextAnimationType.SCALE, durationUs = 400_000L),
            )
        },
    ),
    TextStylePreset(
        id = "neon",
        name = "Neón",
        apply = { current ->
            current.withStyle(
                font = "sans-serif-black",
                bold = true,
                letterSpacing = 0.04f,
                fill = TextFill(argb = 0xFF32F5FFL, gradientEndArgb = 0xFF00C8FFL),
                shadow = TextShadow(argb = 0xE600E5FFL, offsetX = 0f, offsetY = 0f, blur = 0.22f),
                enter = TextAnimation(type = TextAnimationType.FADE, durationUs = 450_000L),
            )
        },
    ),
    TextStylePreset(
        id = "badge",
        name = "Etiqueta",
        apply = { current ->
            current.withStyle(
                font = "sans-serif-condensed",
                bold = true,
                letterSpacing = 0.03f,
                fill = TextFill(argb = 0xFF111111L),
                background = TextBackground(argb = 0xFFFFD60AL, cornerRadius = 0.45f, padding = 0.28f),
                enter = TextAnimation(type = TextAnimationType.SLIDE_UP, durationUs = 400_000L),
            )
        },
    ),
    TextStylePreset(
        id = "cinema",
        name = "Cine",
        apply = { current ->
            current.withStyle(
                font = "sans-serif",
                letterSpacing = 0.22f,
                fill = TextFill(argb = 0xFFFFFFFFL),
                enter = TextAnimation(type = TextAnimationType.FADE, durationUs = 650_000L),
            )
        },
    ),
    TextStylePreset(
        id = "typewriter",
        name = "Máquina de escribir",
        apply = { current ->
            current.withStyle(
                font = "monospace",
                letterSpacing = 0.04f,
                fill = TextFill(argb = 0xFFE8F5E9L),
                background = TextBackground(argb = 0xCC121417L, cornerRadius = 0.14f, padding = 0.24f),
                enter = TextAnimation(type = TextAnimationType.TYPEWRITER, durationUs = 800_000L),
            )
        },
    ),
    TextStylePreset(
        id = "comic",
        name = "Cómic",
        apply = { current ->
            current.withStyle(
                font = "sans-serif-black",
                bold = true,
                letterSpacing = 0.02f,
                fill = TextFill(argb = 0xFFFFE600L, gradientEndArgb = 0xFFFFA800L),
                stroke = TextStroke(argb = 0xFF000000L, width = 0.15f),
                shadow = TextShadow(argb = 0xE6000000L, offsetX = 0.06f, offsetY = 0.06f, blur = 0.02f),
                enter = TextAnimation(type = TextAnimationType.BOUNCE, durationUs = 450_000L),
            )
        },
    ),
    TextStylePreset(
        id = "elegant",
        name = "Elegante",
        apply = { current ->
            current.withStyle(
                font = "serif",
                italic = true,
                letterSpacing = 0.06f,
                fill = TextFill(argb = 0xFFFFFFFFL),
                shadow = TextShadow(argb = 0x66000000L, offsetX = 0f, offsetY = 0.03f, blur = 0.08f),
                enter = TextAnimation(type = TextAnimationType.FADE, durationUs = 550_000L),
            )
        },
    ),
    TextStylePreset(
        id = "box",
        name = "Caja",
        apply = { current ->
            current.withStyle(
                font = "sans-serif",
                bold = true,
                letterSpacing = 0.02f,
                fill = TextFill(argb = 0xFFFFFFFFL),
                background = TextBackground(argb = 0xB3000000L, cornerRadius = 0.16f, padding = 0.28f),
                enter = TextAnimation(type = TextAnimationType.SLIDE_LEFT, durationUs = 450_000L),
            )
        },
    ),
    TextStylePreset(
        id = "pop",
        name = "Pop",
        apply = { current ->
            current.withStyle(
                font = "sans-serif-black",
                bold = true,
                letterSpacing = 0.02f,
                fill = TextFill(argb = 0xFFFF2D92L, gradientEndArgb = 0xFFFF6EC7L),
                stroke = TextStroke(argb = 0xFFFFFFFFL, width = 0.11f),
                shadow = TextShadow(argb = 0x99000000L, offsetX = 0.03f, offsetY = 0.05f, blur = 0.06f),
                enter = TextAnimation(type = TextAnimationType.BOUNCE, durationUs = 500_000L),
            )
        },
    ),
    TextStylePreset(
        id = "long_shadow",
        name = "Sombra larga",
        apply = { current ->
            current.withStyle(
                font = "sans-serif-black",
                bold = true,
                letterSpacing = 0.02f,
                fill = TextFill(argb = 0xFFFFFFFFL),
                shadow = TextShadow(argb = 0xE6000000L, offsetX = 0.12f, offsetY = 0.12f, blur = 0.03f),
                enter = TextAnimation(type = TextAnimationType.SLIDE_DOWN, durationUs = 450_000L),
            )
        },
    ),
    TextStylePreset(
        id = "minimal",
        name = "Minimal",
        apply = { current ->
            current.withStyle(
                font = "sans-serif",
                letterSpacing = 0.05f,
                fill = TextFill(argb = 0xFFD0D5DDL),
            )
        },
    ),
    TextStylePreset(
        id = "impact",
        name = "Impacto",
        apply = layout(
            TextContent(
                text = "",
                font = "sans-serif-black",
                size = 0.08f,
                bold = true,
                fill = TextFill(argb = 0xFFFFD60AL, gradientEndArgb = 0xFFFF9500L),
                stroke = TextStroke(argb = 0xFF000000L, width = 0.12f),
                shadow = TextShadow(argb = 0xCC000000L, offsetX = 0.04f, offsetY = 0.06f, blur = 0.06f),
                enter = TextAnimation(type = TextAnimationType.BOUNCE, durationUs = 500_000L),
            ),
        ),
    ),
    TextStylePreset(
        id = "lower.minimal",
        name = "Tercio inferior",
        apply = layout(
            TextContent(
                text = "",
                font = "sans-serif",
                size = 0.048f,
                align = TextAlign.START,
                bold = true,
                fill = TextFill(argb = 0xFFFFFFFFL),
                background = TextBackground(argb = 0xD916181CL, cornerRadius = 0.22f, padding = 0.28f),
                enter = TextAnimation(type = TextAnimationType.SLIDE_LEFT, durationUs = 450_000L),
                exit = TextAnimation(type = TextAnimationType.FADE, durationUs = 350_000L),
            ),
        ),
    ),
    TextStylePreset(
        id = "lower.badge",
        name = "Tercio etiqueta",
        apply = layout(
            TextContent(
                text = "",
                font = "sans-serif-condensed",
                size = 0.046f,
                align = TextAlign.START,
                bold = true,
                letterSpacing = 0.05f,
                fill = TextFill(argb = 0xFF00201AL),
                background = TextBackground(argb = 0xEE4BE3C1L, cornerRadius = 0.28f, padding = 0.26f),
                enter = TextAnimation(type = TextAnimationType.SLIDE_UP, durationUs = 400_000L),
            ),
        ),
    ),
    TextStylePreset(
        id = "lower.editorial",
        name = "Tercio editorial",
        apply = layout(
            TextContent(
                text = "",
                font = "sans-serif-condensed",
                size = 0.052f,
                align = TextAlign.START,
                bold = true,
                fill = TextFill(argb = 0xFFFFFFFFL),
                background = TextBackground(argb = 0xE6FF2D92L, cornerRadius = 0.14f, padding = 0.25f),
                shadow = TextShadow(argb = 0x66000000L, offsetX = 0.02f, offsetY = 0.03f, blur = 0.05f),
                enter = TextAnimation(type = TextAnimationType.SLIDE_RIGHT, durationUs = 450_000L),
            ),
        ),
    ),
)

/**
 * Lower thirds and the impact title also set size and alignment (a lower third sits at the
 * left); the words and the subtitle flag stay.
 */
private fun layout(template: TextContent): (TextContent) -> TextContent = { current ->
    template.copy(text = current.text, isSubtitle = current.isSubtitle)
}

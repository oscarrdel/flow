package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable

enum class TextAlign { START, CENTER, END }

@Serializable
data class TextFill(
    val argb: Long = 0xFFFFFFFF,
    /** When set, the text is filled with a vertical gradient from [argb] to this color. */
    val gradientEndArgb: Long? = null,
)

@Serializable
data class TextStroke(val argb: Long = 0xFF000000, val width: Float = 0.08f)

@Serializable
data class TextShadow(
    val argb: Long = 0x99000000,
    val offsetX: Float = 0.04f,
    val offsetY: Float = 0.04f,
    val blur: Float = 0.08f,
)

@Serializable
data class TextBackground(val argb: Long = 0xCC000000, val cornerRadius: Float = 0.2f, val padding: Float = 0.25f)

enum class TextAnimationType { NONE, TYPEWRITER, FADE, BOUNCE, SLIDE_UP, SLIDE_DOWN, SLIDE_LEFT, SLIDE_RIGHT, SCALE }

@Serializable
data class TextAnimation(val type: TextAnimationType = TextAnimationType.NONE, val durationUs: Long = 500_000L)

/**
 * Content and style of a TEXT clip. Sizes are relative to the canvas height so a title
 * looks the same at every export resolution.
 */
@Serializable
data class TextContent(
    val text: String,
    /** Bundled font id or URI of an imported .ttf/.otf file. */
    val font: String = "default",
    val size: Float = 0.06f,
    val align: TextAlign = TextAlign.CENTER,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineHeight: Float = 1.2f,
    val fill: TextFill = TextFill(),
    val stroke: TextStroke? = null,
    val shadow: TextShadow? = null,
    val background: TextBackground? = null,
    val enter: TextAnimation = TextAnimation(),
    val exit: TextAnimation = TextAnimation(),
    val loop: TextAnimation = TextAnimation(),
    /** True for subtitle blocks so they can be exported back to .srt. */
    val isSubtitle: Boolean = false,
)

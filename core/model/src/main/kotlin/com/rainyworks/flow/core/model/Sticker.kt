package com.rainyworks.flow.core.model

/** Basic shapes offered as stickers. */
enum class ShapeKind { RECTANGLE, ROUNDED, CIRCLE, TRIANGLE, STAR, HEART, ARROW }

/** What a sticker draws. Stickers become image overlays, so they move, animate and blend like photos. */
sealed interface StickerSpec {
    /** A shape filled with [argb], or only outlined when [filled] is false. */
    data class Shape(val kind: ShapeKind, val argb: Long, val filled: Boolean = true) : StickerSpec

    /** An emoji (or any short text) drawn large with the system font. */
    data class Emoji(val text: String) : StickerSpec
}

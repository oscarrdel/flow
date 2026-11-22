package com.rainyworks.flow.core.engine.compositing

import com.rainyworks.flow.core.model.BlendMode
import kotlin.math.abs
import kotlin.math.sqrt

/** Separable blend functions over straight, display-referred SDR channels in [0, 1]. */
fun blendChannel(mode: BlendMode, backdrop: Float, source: Float): Float {
    val b = backdrop
    val s = source
    return when (mode) {
        BlendMode.NORMAL -> s
        BlendMode.MULTIPLY -> b * s
        BlendMode.SCREEN -> b + s - b * s
        BlendMode.OVERLAY -> if (b <= 0.5f) 2f * b * s else 1f - 2f * (1f - b) * (1f - s)
        BlendMode.DARKEN -> minOf(b, s)
        BlendMode.LIGHTEN -> maxOf(b, s)
        BlendMode.DIFFERENCE -> abs(b - s)
        BlendMode.SOFT_LIGHT -> if (s <= 0.5f) b - (1f - 2f * s) * b * (1f - b) else {
            val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b else sqrt(b)
            b + (2f * s - 1f) * (d - b)
        }
    }
}

/** One premultiplied channel of source-over. Both RGB arguments to this function are straight. */
fun compositeChannel(mode: BlendMode, backdrop: Float, backdropAlpha: Float,
                     source: Float, sourceAlpha: Float, opacity: Float): Float {
    val a = sourceAlpha * opacity.coerceIn(0f, 1f)
    return (1f - a) * backdrop * backdropAlpha + a * (1f - backdropAlpha) * source +
        a * backdropAlpha * blendChannel(mode, backdrop, source)
}

package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable

/** Manual adjustments; every value is -1..1 with 0 meaning "unchanged" unless noted. */
@Serializable
data class ColorAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val sharpness: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    /** 0..1 */
    val vignette: Float = 0f,
    /** 0..1 */
    val grain: Float = 0f,
    /** 0..1 */
    val fade: Float = 0f,
) {
    val isIdentity: Boolean get() = this == ColorAdjustments()
}

/** A point on a tone curve, both axes 0..1. */
@Serializable
data class CurvePoint(val x: Float, val y: Float)

@Serializable
data class ToneCurves(
    val master: List<CurvePoint> = IDENTITY,
    val red: List<CurvePoint> = IDENTITY,
    val green: List<CurvePoint> = IDENTITY,
    val blue: List<CurvePoint> = IDENTITY,
) {
    companion object {
        val IDENTITY = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

enum class HslRange { RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, MAGENTA }

/** Per-range hue shift, saturation and luminance, each -1..1. */
@Serializable
data class HslAdjustment(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)

@Serializable
data class ColorGrade(
    val filterId: String? = null,
    /** 0..1 strength of [filterId]. */
    val filterIntensity: Float = 1f,
    val adjustments: ColorAdjustments = ColorAdjustments(),
    val curves: ToneCurves = ToneCurves(),
    val hsl: Map<HslRange, HslAdjustment> = emptyMap(),
    /** URI of an imported .cube file copied into app storage. */
    val lutUri: String? = null,
    val lutIntensity: Float = 1f,
) {
    val isIdentity: Boolean get() = this == ColorGrade()
}

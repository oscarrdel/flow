package com.rainyworks.flow.core.engine.color

import com.rainyworks.flow.core.model.ColorAdjustments
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.FilterCatalog
import com.rainyworks.flow.core.model.HslAdjustment
import com.rainyworks.flow.core.model.HslRange
import com.rainyworks.flow.core.model.ToneCurves
import kotlin.math.roundToInt

internal class ResolvedGrade(
    val adjustments: ColorAdjustments,
    val hsl: Map<HslRange, HslAdjustment>,
    val curves: ByteArray?,
) {
    val isIdentity get() = adjustments.isIdentity && hsl.isEmpty() && curves == null
}

internal fun resolveGrade(grade: ColorGrade): ResolvedGrade {
    val strength = grade.filterIntensity.coerceIn(0f, 1f)
    val preset = FilterCatalog.find(grade.filterId)?.takeIf { strength > 0f }
    val zero = HslAdjustment()
    val hsl = buildMap {
        for (range in HslRange.entries) {
            val manual = grade.hsl[range] ?: zero
            val filter = preset?.hsl?.get(range) ?: zero
            fun sum(a: Float, b: Float) = (a + b * strength).coerceIn(-1f, 1f)
            val merged = HslAdjustment(
                sum(manual.hue, filter.hue),
                sum(manual.saturation, filter.saturation),
                sum(manual.luminance, filter.luminance),
            )
            if (merged != zero) put(range, merged)
        }
    }
    return ResolvedGrade(
        FilterCatalog.effectiveAdjustments(grade), hsl,
        curveTexture(grade.curves, preset?.curves, strength),
    )
}

/**
 * Samples 256 texels. A stores the first master, RGB the remaining per-channel transfer.
 * Reading A then RGB gives preset master → preset channel → user master → user channel.
 * Each preset curve is independently interpolated towards the diagonal before composition.
 */
internal fun curveTexture(user: ToneCurves, preset: ToneCurves?, intensity: Float): ByteArray? {
    val identity = ToneCurves()
    val filter = preset?.takeIf { it != identity && intensity > 0f }
    if (filter == null && user == identity) return null
    val first = filter ?: user
    val last = if (filter == null) identity else user
    val k = if (filter == null) 1.0 else intensity.toDouble().coerceIn(0.0, 1.0)
    val master = ToneSpline(first.master)
    fun channels(curves: ToneCurves) = listOf(curves.red, curves.green, curves.blue).map(::ToneSpline)
    val firstRgb = channels(first)
    val lastMaster = ToneSpline(last.master)
    val lastRgb = channels(last)
    val rgba = ByteArray(256 * 4)
    var changed = false
    fun write(i: Int, channel: Int, value: Double) {
        val byte = (value * 255).roundToInt()
        rgba[i * 4 + channel] = byte.toByte()
        if (byte != i) changed = true
    }
    for (i in 0..255) {
        val x = i / 255.0
        write(i, 3, x + k * (master.at(x) - x))
        for (c in 0..2) {
            val filtered = x + k * (firstRgb[c].at(x) - x)
            write(i, c, lastRgb[c].at(lastMaster.at(filtered)))
        }
    }
    return rgba.takeIf { changed }
}

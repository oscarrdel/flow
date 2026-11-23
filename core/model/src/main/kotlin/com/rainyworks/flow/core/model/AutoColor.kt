package com.rainyworks.flow.core.model

import kotlin.math.abs

/**
 * One-tap color fix from a few sample frames: stretches the tones to use the full range
 * (contrast and brightness), neutralizes a color cast (temperature) and livens up dull
 * pictures (saturation). The values match how the grade shader applies them:
 * `c = src + brightness * 0.25`, then `c += (c - 0.5) * contrast`, temperature scales red up
 * and blue down by 20 % per unit, and saturation pushes away from the luma.
 */
object AutoColor {
    private const val LOW_TARGET = 0.04
    private const val HIGH_TARGET = 0.96

    /** [pixels] are ARGB from one or more frames of the clip. */
    fun analyze(pixels: IntArray): ColorAdjustments {
        if (pixels.isEmpty()) return ColorAdjustments()
        val histogram = IntArray(256)
        var redSum = 0.0
        var blueSum = 0.0
        var greenSum = 0.0
        var midCount = 0
        var saturationSum = 0.0
        for (p in pixels) {
            val r = (p shr 16 and 0xFF) / 255.0
            val g = (p shr 8 and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            histogram[(y * 255).toInt().coerceIn(0, 255)]++
            saturationSum += maxOf(r, g, b) - minOf(r, g, b)
            // Mid tones show a color cast best; deep shadows and clipped highlights do not.
            if (y in 0.2..0.8) {
                redSum += r; greenSum += g; blueSum += b
                midCount++
            }
        }
        val low = percentile(histogram, pixels.size, 0.02)
        val high = percentile(histogram, pixels.size, 0.98)
        val span = (high - low).coerceAtLeast(0.05)

        // Contrast so the 2-98 % range covers most of the output, within a gentle limit.
        val contrast = ((HIGH_TARGET - LOW_TARGET) / span - 1.0).coerceIn(-0.2, 0.6)
        // Brightness centers that range: (mid + offset - 0.5) * (1 + contrast) + 0.5 = 0.5.
        val mid = (low + high) / 2
        val brightness = ((0.5 - mid) / 0.25).coerceIn(-0.9, 0.9)

        // Temperature that brings red and blue of the mid tones together, a bit less than fully.
        val temperature = if (midCount > pixels.size / 20 && redSum + blueSum > 0) {
            val r = redSum / midCount
            val b = blueSum / midCount
            ((b - r) / (0.2 * (r + b)) * 0.6).coerceIn(-0.5, 0.5)
        } else 0.0

        val saturation = when (val mean = saturationSum / pixels.size) {
            in 0.0..0.12 -> 0.25
            in 0.12..0.25 -> 0.15
            in 0.25..0.4 -> 0.05
            else -> 0.0
        }
        return ColorAdjustments(
            brightness = round(brightness),
            contrast = round(contrast),
            saturation = round(saturation),
            temperature = round(temperature),
        )
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Double {
        val target = total * fraction
        var seen = 0
        for (i in histogram.indices) {
            seen += histogram[i]
            if (seen >= target) return i / 255.0
        }
        return 1.0
    }

    /** Two decimals, as the sliders show; tiny values become exactly zero. */
    private fun round(value: Double): Float {
        val r = Math.round(value * 100) / 100f
        return if (abs(r) < 0.02f) 0f else r
    }
}

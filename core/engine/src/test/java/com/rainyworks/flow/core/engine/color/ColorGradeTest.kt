package com.rainyworks.flow.core.engine.color

import com.rainyworks.flow.core.model.ColorAdjustments
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.CubeLut
import com.rainyworks.flow.core.model.CurvePoint
import com.rainyworks.flow.core.model.FilterCatalog
import com.rainyworks.flow.core.model.HslAdjustment
import com.rainyworks.flow.core.model.HslRange
import com.rainyworks.flow.core.model.ToneCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class ColorGradeTest {
    @Test fun splineInterpolatesKnotsAndCubicMidpoint() {
        val spline = ToneSpline(listOf(CurvePoint(0.5f, 0.25f)))
        assertEquals(0.0, spline.at(-1.0), 0.0)
        assertEquals(0.09375, spline.at(0.25), 0.0)
        assertEquals(0.25, spline.at(0.5), 0.0)
        assertEquals(1.0, spline.at(2.0), 0.0)
    }

    @Test fun splineIsMonotoneWithinEveryIntervalIncludingFlatsAndReversals() {
        val points = listOf(
            CurvePoint(0f, 0.1f), CurvePoint(0.02f, 0.89f), CurvePoint(0.6f, 0.9f),
            CurvePoint(0.75f, 0.9f), CurvePoint(0.8f, 0.2f), CurvePoint(1f, 0.21f),
        )
        val spline = ToneSpline(points.reversed())
        for ((start, end) in points.zipWithNext()) {
            var prev = start.y.toDouble()
            for (i in 1..256) {
                val x = start.x + (end.x.toDouble() - start.x) * i / 256
                val v = spline.at(x)
                if (end.y >= start.y) assertTrue(v >= prev - 1e-12) else assertTrue(v <= prev + 1e-12)
                assertTrue(v in minOf(start.y, end.y) - 1e-12..maxOf(start.y, end.y) + 1e-12)
                prev = v
            }
        }
    }

    @Test fun defaultSparseAndExplicitlyNeutralGradesSkipGpuAndLoader() {
        val diagonal = listOf(CurvePoint(0.75f, 0.75f), CurvePoint(0.25f, 0.25f))
        val cancelled = ColorGrade(filterId = "landscape.sky", filterIntensity = 0.5f,
            adjustments = ColorAdjustments(highlights = 0.125f, contrast = -0.05f),
            hsl = mapOf(HslRange.BLUE to HslAdjustment(saturation = -0.15f, luminance = 0.075f),
                HslRange.CYAN to HslAdjustment(saturation = -0.075f)))
        val neutral = listOf(
            ColorGrade(), ColorGrade(filterId = "unknown"),
            ColorGrade(curves = ToneCurves(master = diagonal, blue = emptyList()),
                hsl = HslRange.entries.associateWith { HslAdjustment() }),
            ColorGrade(filterId = "cine.teal_orange", filterIntensity = 0f),
            ColorGrade(lutUri = "unused", lutIntensity = 0f), cancelled,
        )
        for (grade in neutral) {
            assertTrue(resolveGrade(grade).isIdentity)
            assertTrue(ColorEffects.forGrade(grade) { error("unexpected load") }.isEmpty())
        }
    }

    @Test fun scaledHslAddsClampsAndRetainsOtherRanges() {
        val grade = ColorGrade(filterId = "cine.teal_orange", filterIntensity = 0.5f,
            adjustments = ColorAdjustments(contrast = 0.95f, shadows = -0.3f),
            hsl = mapOf(
                HslRange.BLUE to HslAdjustment(hue = -0.99f, saturation = 0.98f, luminance = 0.5f),
                HslRange.GREEN to HslAdjustment(hue = 0.4f),
            ))
        val merged = resolveGrade(grade)
        assertEquals(FilterCatalog.effectiveAdjustments(grade), merged.adjustments)
        assertEquals(1f, merged.adjustments.contrast, 0f)
        assertEquals(HslAdjustment(-1f, 1f, 0.5f), merged.hsl[HslRange.BLUE])
        assertEquals(HslAdjustment(saturation = 0.1f), merged.hsl[HslRange.ORANGE])
        assertEquals(grade.hsl[HslRange.GREEN], merged.hsl[HslRange.GREEN])
        assertEquals(3, merged.hsl.size)
        val rgba = checkNotNull(merged.curves)
        // Catalog blue starts at .08, halfway from the diagonal at strength .5.
        assertEquals(10, rgba[2].toInt() and 255)
    }

    @Test fun presetMasterAndChannelsAreScaledIndependentlyThenUserCurvesApply() {
        fun line(lo: Float, hi: Float) = listOf(CurvePoint(0f, lo), CurvePoint(1f, hi))
        val preset = ToneCurves(master = line(0.2f, 1f), red = line(0f, 0.6f), green = line(0.3f, 0.7f))
        val user = ToneCurves(master = line(0f, 0.75f), red = line(0.1f, 1f))
        for (k in listOf(0f, 0.25f, 1f)) {
            val rgba = checkNotNull(curveTexture(user, preset, k))
            assertEquals(256 * 4, rgba.size)
            fun texel(i: Int, channel: Int) = (rgba[4 * i + channel].toInt() and 255) / 255.0
            for (i in 0..255) {
                val x = i / 255.0
                val master = x + k * (0.2 + 0.8 * x - x)
                val expected = listOf(
                    0.1 + 0.9 * 0.75 * (master + k * (0.6 * master - master)),
                    0.75 * (master + k * (0.3 + 0.4 * master - master)),
                    0.75 * master,
                )
                val pos = texel(i, 3) * 255
                val lo = floor(pos).toInt()
                for (c in 0..2) {
                    val result = texel(lo, c) + (pos - lo) * (texel(minOf(lo + 1, 255), c) - texel(lo, c))
                    assertEquals(expected[c], result, 1.0 / 255)
                }
            }
        }
        assertNull(curveTexture(ToneCurves(), preset, 0f))
    }

    @Test fun lutLoadsOncePerRequestAndFollowsManualPass() {
        val lut = CubeLut.parse("LUT_1D_SIZE 2\n1 1 1\n0 0 0")
        val grade = ColorGrade(lutUri = "look.cube", adjustments = ColorAdjustments(exposure = 0.1f))
        val loads = mutableListOf<String>()
        val effects = ColorEffects.forGrade(grade) { loads += it; lut }
        assertEquals(listOf("look.cube"), loads)
        assertEquals(2, effects.size)
        assertTrue(effects.first() is GradeShader)
        assertTrue(effects.last() is CubeEffect)
        assertTrue(ColorEffects.forGrade(grade) { null }.single() is GradeShader)
        assertTrue(ColorEffects.forGrade(ColorGrade(lutUri = "missing")) { null }.isEmpty())
        assertFalse(resolveGrade(grade).isIdentity)
    }
}

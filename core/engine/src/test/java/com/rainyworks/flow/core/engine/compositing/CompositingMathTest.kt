package com.rainyworks.flow.core.engine.compositing

import com.rainyworks.flow.core.model.BlendMode
import com.rainyworks.flow.core.model.MaskShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositingMathTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.00001f)

    @Test fun channelModesAndSoftLightBranches() {
        val expected = mapOf(BlendMode.NORMAL to 0.75f, BlendMode.MULTIPLY to 0.1875f,
            BlendMode.SCREEN to 0.8125f, BlendMode.OVERLAY to 0.375f, BlendMode.DARKEN to 0.25f,
            BlendMode.LIGHTEN to 0.75f, BlendMode.SOFT_LIGHT to 0.375f, BlendMode.DIFFERENCE to 0.5f)
        for (mode in BlendMode.entries) near(expected.getValue(mode), blendChannel(mode, 0.25f, 0.75f))
        near(0.625f, blendChannel(BlendMode.OVERLAY, 0.75f, 0.25f))
        near(0.15625f, blendChannel(BlendMode.SOFT_LIGHT, 0.25f, 0.25f))
        near(0.72f, blendChannel(BlendMode.SOFT_LIGHT, 0.64f, 0.75f))
        near(0.296f, blendChannel(BlendMode.SOFT_LIGHT, 0.1f, 1f))
    }

    @Test fun opacityOnlyAffectsSourceCoverage() {
        for (mode in BlendMode.entries) {
            near(0.24f, compositeChannel(mode, 0.6f, 0.4f, 0.8f, 1f, 0f))
            near(0.20f, compositeChannel(mode, 1f, 0f, 0.8f, 0.5f, 0.5f))
            near(0.24f, compositeChannel(mode, 0.6f, 0.4f, 1f, 0f, 1f))
        }
        near(0.43f, compositeChannel(BlendMode.MULTIPLY, 0.5f, 0.6f, 0.8f, 1f, 0.5f))
    }

    @Test fun chromaSeparatesHuesAndHandlesExposureAndNeutralKeys() {
        val green = KeyColor.fromRgb(0f, 1f, 0f)
        near(0f, green.distance(green))
        near(0f, green.distance(KeyColor.fromRgb(0f, 0.2f, 0f)))
        assertTrue(green.distance(KeyColor.fromRgb(1f, 0f, 0f)) > 0.6f)
        assertTrue(green.distance(KeyColor.fromRgb(0.2f, 1f, 0.2f)) in 0.04f..0.12f)
        val black = KeyColor.fromRgb(0f, 0f, 0f)
        near(0f, black.distance(black))
        near(1f, black.distance(KeyColor.fromRgb(1f, 1f, 1f)))
    }

    @Test fun splitEllipseAndRoundedRectangleContours() {
        near(-0.25f, maskDistance(MaskShape.SPLIT, 42f, -0.25f))
        near(0f, maskDistance(MaskShape.CIRCLE, 2f, 0f, aspectX = 2f))
        near(0f, maskDistance(MaskShape.CIRCLE, 0f, 1f, aspectX = 2f))
        val halfSize = 0.30f / 0.35f
        near(0f, maskDistance(MaskShape.RECTANGLE, halfSize, halfSize))
        assertTrue(maskDistance(MaskShape.RECTANGLE, halfSize, halfSize, cornerRadius = 0.5f) > 0f)
        near(0f, maskDistance(MaskShape.RECTANGLE, halfSize, 0f, cornerRadius = 1f))
    }

    @Test fun heartNotchLobesAndTip() {
        near(0f, maskDistance(MaskShape.HEART, 0f, 0.8f))
        near(0f, maskDistance(MaskShape.HEART, -0.5f, -0.90710678f))
        near(0f, maskDistance(MaskShape.HEART, 0f, -0.40710678f))
        assertTrue(maskDistance(MaskShape.HEART, 0f, -0.6f) > 0f)
        assertTrue(maskDistance(MaskShape.HEART, 0.5f, -0.6f) < 0f)
        assertTrue(maskDistance(MaskShape.HEART, 0f, 0f) < 0f)
    }

    @Test fun allStarTipsAndValleys() {
        repeat(10) { i ->
            val angle = i * PI / 5
            val radius = if (i % 2 == 0) 1f else 0.44f
            fun distance(r: Float) = maskDistance(MaskShape.STAR, r * sin(angle).toFloat(), -r * cos(angle).toFloat())
            near(0f, distance(radius))
            assertTrue(distance(radius - 0.05f) < 0f)
            assertTrue(distance(radius + 0.05f) > 0f)
        }
    }
}

package com.rainyworks.flow.core.engine.color

import com.rainyworks.flow.core.model.CurvePoint
import kotlin.math.hypot

/** Piecewise cubic Hermite interpolation with the Fritsch–Carlson monotonicity limiter. */
internal class ToneSpline(points: List<CurvePoint>) {
    // A missing endpoint keeps its original value. The last point wins at a repeated x.
    private val knots = (listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) + points)
        .filter { it.x.isFinite() && it.y.isFinite() }
        .associate { it.x.coerceIn(0f, 1f).toDouble() to it.y.coerceIn(0f, 1f).toDouble() }
        .toSortedMap()
    private val x = knots.keys.toDoubleArray()
    private val y = knots.values.toDoubleArray()
    val isIdentity = x.contentEquals(y)
    private val tangent = DoubleArray(x.size)

    init {
        val slopes = DoubleArray(x.lastIndex) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]) }
        tangent[0] = slopes.first()
        tangent[x.lastIndex] = slopes.last()
        for (i in 1 until x.lastIndex) {
            if (slopes[i - 1] * slopes[i] > 0.0) tangent[i] = (slopes[i - 1] + slopes[i]) / 2
        }
        for (i in slopes.indices) {
            val slope = slopes[i]
            if (slope == 0.0) {
                tangent[i] = 0.0
                tangent[i + 1] = 0.0
            } else {
                val length = hypot(tangent[i] / slope, tangent[i + 1] / slope)
                if (length > 3.0) {
                    tangent[i] *= 3.0 / length
                    tangent[i + 1] *= 3.0 / length
                }
            }
        }
    }

    fun at(input: Double): Double {
        val value = input.coerceIn(0.0, 1.0)
        if (isIdentity) return value
        val found = x.binarySearch(value)
        if (found >= 0) return y[found]
        val i = -found - 2
        val width = x[i + 1] - x[i]
        val t = (value - x[i]) / width
        val u = 1.0 - t
        return (u * u * ((1 + 2 * t) * y[i] + t * width * tangent[i]) +
            t * t * ((3 - 2 * t) * y[i + 1] - u * width * tangent[i + 1])).coerceIn(0.0, 1.0)
    }
}

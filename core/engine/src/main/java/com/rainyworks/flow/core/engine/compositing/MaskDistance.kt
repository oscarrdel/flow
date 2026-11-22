package com.rainyworks.flow.core.engine.compositing

import com.rainyworks.flow.core.model.MaskShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

/**
 * Signed distance in local mask units, negative inside. Y increases downward, one unit is
 * 35% of the short frame edge at scale 1. The ellipse uses radial distance in its unit disk.
 * aspectX/Y are the frame dimensions divided by the short edge.
 */
fun maskDistance(shape: MaskShape, x: Float, y: Float, aspectX: Float = 1f, aspectY: Float = 1f,
                 cornerRadius: Float = 0f): Float = when (shape) {
    MaskShape.SPLIT -> y
    MaskShape.CIRCLE -> hypot(x / aspectX, y / aspectY) - 1f
    MaskShape.RECTANGLE -> {
        val w = (0.30f / 0.35f) * aspectX
        val h = (0.30f / 0.35f) * aspectY
        val r = minOf(w, h) * cornerRadius.coerceIn(0f, 1f)
        val dx = abs(x) - w + r
        val dy = abs(y) - h + r
        hypot(maxOf(0f, dx), maxOf(0f, dy)) + minOf(0f, maxOf(dx, dy)) - r
    }
    MaskShape.HEART -> {
        val qx = abs(x) - 0.5f
        val qy = y + 0.407106781f
        if (qy <= 0f || qy <= qx) hypot(qx, qy) - 0.5f else {
            val tx = abs(x)
            val ty = y - 0.8f
            val along = (0.5f * (tx - ty)).coerceIn(0f, 0.853553391f)
            minOf(hypot(tx - along, ty + along), hypot(qx + 0.5f, qy)) * sign(tx + ty)
        }
    }
    MaskShape.STAR -> {
        val halfSector = (PI / 5).toFloat()
        val angle = abs((atan2(x, -y) + halfSector).mod(2 * halfSector) - halfSector)
        val radius = hypot(x, y)
        val qx = radius * sin(angle)
        val qy = radius * cos(angle) - 1f
        val ex = 0.44f * sin(halfSector)
        val ey = 0.44f * cos(halfSector) - 1f
        val t = ((qx * ex + qy * ey) / (ex * ex + ey * ey)).coerceIn(0f, 1f)
        hypot(qx - t * ex, qy - t * ey) * sign(qy * ex - qx * ey)
    }
}

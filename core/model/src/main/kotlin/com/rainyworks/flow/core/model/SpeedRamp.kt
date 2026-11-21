package com.rainyworks.flow.core.model

import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.roundToLong

/**
 * Maps between timeline time and position in the original source range.
 * Offsets are rounded to whole microseconds, so several offsets can map to the same sample.
 */
object SpeedRamp {
    fun timelineDurationUs(sourceDurationUs: Long, curve: SpeedCurve): Long =
        mapping(sourceDurationUs, curve).durationUs

    fun sourceOffsetAt(timelineOffsetUs: Long, sourceDurationUs: Long, curve: SpeedCurve): Long =
        mapping(sourceDurationUs, curve).sourceOffsetAt(timelineOffsetUs)

    fun timelineOffsetAt(sourceOffsetUs: Long, sourceDurationUs: Long, curve: SpeedCurve): Long =
        mapping(sourceDurationUs, curve).timelineOffsetAt(sourceOffsetUs)

    /** Re-normalizes a source interval. Positions outside 0..1 extend the nearest endpoint speed. */
    fun slice(curve: SpeedCurve, fromPos: Float, toPos: Float): SpeedCurve =
        slice(curve, fromPos.toDouble(), toPos.toDouble())

    fun slice(curve: SpeedCurve, fromPos: Double, toPos: Double): SpeedCurve {
        require(fromPos.isFinite() && toPos.isFinite() && fromPos < toPos)
        val points = normalizedPoints(curve)
        val width = toPos - fromPos
        val sliced = buildList {
            add(SpeedPoint(0f, speedAt(points, fromPos)))
            for (point in points) {
                val position = point.position.toDouble()
                if (position > fromPos && position < toPos) {
                    val normalized = ((position - fromPos) / width).toFloat()
                    if (normalized > 0f && normalized < 1f) add(point.copy(position = normalized))
                }
            }
            add(SpeedPoint(1f, speedAt(points, toPos)))
        }
        val presetId = curve.presetId.takeIf { fromPos == 0.0 && toPos == 1.0 }
        return SpeedCurve(sliced, presetId)
    }

    val presets: List<SpeedCurve> = listOf(
        // Montage alternates fast bursts with two slower beats.
        preset("montage", 0f to 1f, 0.2f to 5f, 0.4f to 0.5f, 0.65f to 5f, 0.85f to 0.5f, 1f to 1f),
        // Hero speeds into a sustained slow-motion moment and accelerates out of it.
        preset("hero", 0f to 1f, 0.18f to 3f, 0.38f to 0.4f, 0.65f to 0.4f, 0.83f to 3f, 1f to 1f),
        // Bullet isolates a brief moment with a deep slowdown between fast shoulders.
        preset("bullet", 0f to 1f, 0.35f to 4f, 0.47f to 0.15f, 0.53f to 0.15f, 0.65f to 4f, 1f to 1f),
        // Jump cut compresses the middle with steep ramps around a fast plateau.
        preset("jump_cut", 0f to 1f, 0.38f to 1f, 0.42f to 8f, 0.58f to 8f, 0.62f to 1f, 1f to 1f),
        // Flash in opens at high speed and quickly settles to normal.
        preset("flash_in", 0f to 8f, 0.12f to 4f, 0.35f to 1f, 1f to 1f),
        // Flash out holds normal speed before a fast exit.
        preset("flash_out", 0f to 1f, 0.65f to 1f, 0.88f to 4f, 1f to 8f),
    )

    internal fun mapping(sourceDurationUs: Long, curve: SpeedCurve): Mapping =
        Mapping(sourceDurationUs.coerceAtLeast(0L), normalizedPoints(curve))

    private fun preset(id: String, vararg points: Pair<Float, Float>): SpeedCurve =
        SpeedCurve(points.map { (position, speed) -> SpeedPoint(position, speed) }, presetId = id)

    private fun normalizedPoints(curve: SpeedCurve): List<SpeedPoint> {
        val points = curve.points.asSequence()
            .filter { !it.position.isNaN() && !it.speed.isNaN() }
            .map {
                val position = if (it.position <= 0f) 0f else it.position.coerceAtMost(1f)
                SpeedPoint(position, it.speed.coerceIn(Edits.MIN_SPEED, Edits.MAX_SPEED))
            }
            .associateBy { it.position }
            .values.sortedBy { it.position }
        if (points.isEmpty()) return listOf(SpeedPoint(0f, 1f), SpeedPoint(1f, 1f))
        return buildList {
            if (points.first().position > 0f) add(points.first().copy(position = 0f))
            addAll(points)
            if (points.last().position < 1f) add(points.last().copy(position = 1f))
        }
    }

    private fun speedAt(points: List<SpeedPoint>, position: Double): Float {
        if (position <= 0.0) return points.first().speed
        if (position >= 1.0) return points.last().speed
        val rightIndex = points.indexOfFirst { it.position >= position }
        val left = points[rightIndex - 1]
        val right = points[rightIndex]
        val fraction = (position - left.position) / (right.position.toDouble() - left.position)
        return (left.speed + fraction * (right.speed.toDouble() - left.speed)).toFloat()
    }

    internal class Mapping(private val sourceDurationUs: Long, points: List<SpeedPoint>) {
        private val segments = points.zipWithNext(::Segment)
        private val duration = segments.sumOf { it.duration }
        val startSpeed: Double = points.first().speed.toDouble()
        val endSpeed: Double = points.last().speed.toDouble()
        private val constant = points.all { it.speed.toDouble() == startSpeed }
        val durationUs: Long = if (sourceDurationUs == 0L) 0L else {
            val exactDurationUs = if (constant) sourceDurationUs / startSpeed else duration * sourceDurationUs
            exactDurationUs.roundToLong().coerceAtLeast(1L)
        }

        fun sourceOffsetAt(timelineOffsetUs: Long, reversed: Boolean = false): Long {
            if (timelineOffsetUs <= 0L) return if (reversed) sourceDurationUs else 0L
            if (timelineOffsetUs >= durationUs) return if (reversed) 0L else sourceDurationUs
            if (constant) {
                val offset = (timelineOffsetUs * startSpeed).roundToLong()
                return if (reversed) sourceDurationUs - offset else offset
            }

            val elapsed = timelineOffsetUs.toDouble() / sourceDurationUs
            var remaining = if (reversed) duration - elapsed else elapsed
            for (segment in segments) {
                if (remaining <= segment.duration) {
                    return (segment.positionAt(remaining) * sourceDurationUs).roundToLong().coerceIn(0L, sourceDurationUs)
                }
                remaining -= segment.duration
            }
            return sourceDurationUs
        }

        fun timelineOffsetAt(sourceOffsetUs: Long): Long {
            if (sourceOffsetUs <= 0L) return 0L
            if (sourceOffsetUs >= sourceDurationUs) return durationUs
            if (constant) return (sourceOffsetUs / startSpeed).roundToLong()

            val position = sourceOffsetUs.toDouble() / sourceDurationUs
            var elapsed = 0.0
            for (segment in segments) {
                if (position <= segment.endPosition) {
                    elapsed += segment.durationAt(position)
                    break
                }
                elapsed += segment.duration
            }
            return (elapsed * sourceDurationUs).roundToLong().coerceIn(0L, durationUs)
        }
    }

    private class Segment(left: SpeedPoint, right: SpeedPoint) {
        private val startPosition = left.position.toDouble()
        val endPosition = right.position.toDouble()
        private val width = endPosition - startPosition
        private val startSpeed = left.speed.toDouble()
        private val speedChange = right.speed.toDouble() - startSpeed
        val duration = durationAt(endPosition)

        // Integrate the reciprocal of the linear speed in normalized source units.
        fun durationAt(position: Double): Double {
            val distance = position - startPosition
            if (speedChange == 0.0) return distance / startSpeed
            // ln1p and expm1 keep nearly flat segments stable.
            return width / speedChange * ln1p(speedChange / startSpeed * (distance / width))
        }

        fun positionAt(duration: Double): Double {
            val distance = if (speedChange == 0.0) {
                duration * startSpeed
            } else {
                width * startSpeed / speedChange * expm1(duration * (speedChange / width))
            }
            return startPosition + distance
        }
    }
}

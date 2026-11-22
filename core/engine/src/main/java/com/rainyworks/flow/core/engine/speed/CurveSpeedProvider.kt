package com.rainyworks.flow.core.engine.speed

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.SpeedCurve
import com.rainyworks.flow.core.model.SpeedRamp
import kotlin.math.roundToLong

/**
 * Plays a speed curve as a staircase of constant speeds, which is what Media3 accepts.
 * Each step's speed is chosen so the step lasts exactly as long on the timeline as the
 * curve says, so the rendered clip matches the duration shown in the editor.
 *
 * [timeBaseUs] is the media time Media3 reports at the first frame of the clip.
 */
@OptIn(UnstableApi::class)
class CurveSpeedProvider(
    sourceDurationUs: Long,
    curve: SpeedCurve,
    private val timeBaseUs: Long,
) : SpeedProvider {
    private val stepUs: Long
    private val speeds: FloatArray

    init {
        require(sourceDurationUs > 0) { "Empty clip" }
        val steps = (sourceDurationUs / TARGET_STEP_US).toInt().coerceIn(1, MAX_STEPS)
        stepUs = (sourceDurationUs + steps - 1) / steps
        speeds = FloatArray(steps) { i ->
            val from = i * stepUs
            val to = minOf(sourceDurationUs, from + stepUs)
            val timelineFrom = SpeedRamp.timelineOffsetAt(from, sourceDurationUs, curve)
            val timelineTo = SpeedRamp.timelineOffsetAt(to, sourceDurationUs, curve)
            val timeline = (timelineTo - timelineFrom).coerceAtLeast(1L)
            ((to - from).toDouble() / timeline).toFloat()
        }
    }

    /** Timeline length of the whole clip with this staircase, for tests and checks. */
    val timelineDurationUs: Long
        get() = speeds.indices.sumOf { i -> (stepUs / speeds[i].toDouble()).roundToLong() }

    private fun step(timeUs: Long): Int =
        ((timeUs - timeBaseUs).coerceAtLeast(0L) / stepUs).toInt().coerceIn(0, speeds.lastIndex)

    override fun getSpeed(timeUs: Long): Float = speeds[step(timeUs)]

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        val next = step(timeUs) + 1
        return if (next > speeds.lastIndex) C.TIME_UNSET else timeBaseUs + next * stepUs
    }

    companion object {
        private const val TARGET_STEP_US = 33_333L
        private const val MAX_STEPS = 300

        fun forClip(clip: Clip, timeBaseUs: Long): CurveSpeedProvider? {
            val curve = clip.speedCurve ?: return null
            return CurveSpeedProvider(clip.sourceDurationUs, curve, timeBaseUs)
        }
    }
}

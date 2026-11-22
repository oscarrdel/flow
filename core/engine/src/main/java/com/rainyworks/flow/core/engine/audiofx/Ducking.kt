package com.rainyworks.flow.core.engine.audiofx

import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.Project
import kotlin.math.PI
import kotlin.math.sin

/**
 * When a clip ducks (background music), it drops to [LEVEL] while any other clip with sound
 * plays, easing down over [ATTACK_US] before the sound starts and back up over [RELEASE_US]
 * after it ends.
 */
class Ducking(
    /** Merged spans, relative to the ducking clip's start, where other sound plays. */
    private val spans: List<LongRange>,
) {
    val isEmpty: Boolean get() = spans.isEmpty()

    /** Gain factor at [clipRelativeUs]. */
    fun gainAt(clipRelativeUs: Long): Double {
        var amount = 0.0
        for (span in spans) {
            val t = clipRelativeUs
            val a = when {
                t < span.first - ATTACK_US -> 0.0
                t < span.first -> ease((t - (span.first - ATTACK_US)).toDouble() / ATTACK_US)
                t <= span.last -> 1.0
                t < span.last + RELEASE_US -> 1.0 - ease((t - span.last).toDouble() / RELEASE_US)
                else -> 0.0
            }
            if (a > amount) amount = a
            if (amount >= 1.0) break
        }
        return 1.0 - (1.0 - LEVEL) * amount
    }

    private fun ease(x: Double) = sin(PI * 0.5 * x.coerceIn(0.0, 1.0))

    companion object {
        const val LEVEL = 0.25
        const val ATTACK_US = 250_000L
        const val RELEASE_US = 500_000L

        /** Spans where clips other than [clip] (and other ducking clips) make sound. */
        fun forClip(project: Project, clip: Clip, audible: (com.rainyworks.flow.core.model.Track, Clip) -> Boolean): Ducking {
            if (!clip.duckUnderVoice) return Ducking(emptyList())
            val triggers = project.tracks.flatMap { track ->
                track.clips.filter { it.id != clip.id && !it.duckUnderVoice && audible(track, it) }
                    .map { (it.startUs - clip.startUs)..(it.endUs - clip.startUs) }
            }.filter { it.last > -RELEASE_US && it.first < clip.durationUs + ATTACK_US }
                .sortedBy { it.first }
            val merged = mutableListOf<LongRange>()
            for (span in triggers) {
                val last = merged.lastOrNull()
                // Gaps shorter than a breath keep the music down.
                if (last != null && span.first <= last.last + ATTACK_US + RELEASE_US) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
                } else {
                    merged += span
                }
            }
            return Ducking(merged)
        }
    }
}

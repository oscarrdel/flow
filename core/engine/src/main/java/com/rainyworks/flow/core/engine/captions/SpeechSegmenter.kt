package com.rainyworks.flow.core.engine.captions

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Splits mono audio into spoken phrases, each one subtitle long. A 20 ms frame is speech when
 * it is clearly louder than the quiet background; short pauses stay inside a phrase, very short
 * bursts are dropped, and long phrases are cut at their quietest moment.
 */
object SpeechSegmenter {
    private const val FRAME_MS = 20
    private const val MERGE_GAP_MS = 350
    private const val MIN_PHRASE_MS = 300
    private const val MAX_PHRASE_MS = 6_000
    private const val PAD_MS = 120

    /** Phrases as sample ranges [start, end). */
    fun phrases(samples: FloatArray, rate: Int): List<IntRange> {
        val frame = rate * FRAME_MS / 1000
        if (frame <= 0 || samples.size < frame) return emptyList()
        val frames = samples.size / frame
        val db = DoubleArray(frames) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += samples[i] * samples[i]
            10 * log10(sum / frame + 1e-10)
        }
        // The background is the quiet end of the recording (10th percentile).
        val sorted = db.sortedArray()
        val floor = sorted[(frames * 0.1).toInt().coerceIn(0, frames - 1)]
        val loudest = sorted[(frames * 0.98).toInt().coerceIn(0, frames - 1)]
        if (loudest - floor < 10) return emptyList() // Nothing stands out: no speech.
        val threshold = max(floor + 9, loudest - 35)
        val speech = BooleanArray(frames) { db[it] > threshold }

        val merged = mutableListOf<IntRange>()
        var f = 0
        val gap = MERGE_GAP_MS / FRAME_MS
        while (f < frames) {
            if (!speech[f]) { f++; continue }
            var end = f
            var quiet = 0
            var g = f
            while (g < frames && quiet <= gap) {
                if (speech[g]) { end = g; quiet = 0 } else quiet++
                g++
            }
            merged += f..end
            f = end + 1
        }
        val pad = PAD_MS / FRAME_MS
        val maxFrames = MAX_PHRASE_MS / FRAME_MS
        val result = mutableListOf<IntRange>()
        for (phrase in merged) {
            if (phrase.last - phrase.first + 1 < MIN_PHRASE_MS / FRAME_MS) continue
            for (part in split(phrase, db, maxFrames)) {
                val start = (part.first - pad).coerceAtLeast(0)
                val end = (part.last + pad).coerceAtMost(frames - 1)
                result += (start * frame) until ((end + 1) * frame)
            }
        }
        // Padding can make neighbors touch; keep them in order without overlap.
        for (i in 1 until result.size) {
            if (result[i].first <= result[i - 1].last) {
                result[i] = (result[i - 1].last + 1)..result[i].last
            }
        }
        return result.filter { it.last > it.first }
    }

    private fun split(range: IntRange, db: DoubleArray, maxFrames: Int): List<IntRange> {
        if (range.last - range.first + 1 <= maxFrames) return listOf(range)
        // Cut at the quietest frame in the middle part, so words are not split.
        val from = range.first + maxFrames / 3
        val to = minOf(range.last - maxFrames / 3, range.first + maxFrames)
        val cut = (from..to).minByOrNull { db[it] } ?: (range.first + maxFrames)
        return split(range.first until cut, db, maxFrames) + split(cut..range.last, db, maxFrames)
    }

    /** Root-mean-square level, handy for checks. */
    internal fun rms(samples: FloatArray, range: IntRange): Double =
        sqrt(range.sumOf { samples[it].toDouble() * samples[it] } / range.count().coerceAtLeast(1))
}

package com.rainyworks.flow.core.engine.audio

import android.content.Context
import android.net.Uri
import com.rainyworks.flow.core.model.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Measures how loud a clip sounds and the volume that brings it to a common level, so clips
 * recorded quietly or loudly play evenly. Silent passages do not count (absolute and relative
 * gates, in the spirit of LUFS), and the result never pushes peaks past full scale.
 */
class Loudness(context: Context) {
    private val app = context.applicationContext

    /** Volume (1 = unchanged) for [clip]'s trimmed audio to reach the target level. */
    suspend fun volumeFor(clip: Clip): Float = withContext(Dispatchers.IO) {
        val media = clip.media ?: return@withContext clip.volume
        val (samples, _) = MonoAudioDecoder.decode(app, Uri.parse(media.uri), RATE, clip.trimInUs, clip.trimOutUs)
        volumeFor(samples, RATE)
    }

    companion object {
        private const val RATE = 16_000
        /** Speech and music land comfortably here (about -16 LUFS on a phone speaker). */
        const val TARGET_DB = -18.0
        private const val WINDOW_MS = 50
        private const val ABSOLUTE_GATE_DB = -60.0
        private const val RELATIVE_GATE_DB = -15.0
        private const val PEAK_CEILING = 0.98
        const val MIN_VOLUME = 0.1f
        const val MAX_VOLUME = 2f

        /** Gated level in dB full scale (mean power of the sounding windows), or null when silent. */
        fun levelDb(samples: FloatArray, rate: Int): Double? {
            val window = rate * WINDOW_MS / 1000
            if (window <= 0 || samples.size < window) return null
            val powers = (0 until samples.size / window).map { w ->
                var sum = 0.0
                for (i in w * window until (w + 1) * window) sum += samples[i].toDouble() * samples[i]
                sum / window
            }
            fun db(power: Double) = 10 * log10(power + 1e-12)
            val audible = powers.filter { db(it) > ABSOLUTE_GATE_DB }
            if (audible.isEmpty()) return null
            val relative = db(audible.average()) + RELATIVE_GATE_DB
            val gated = audible.filter { db(it) > relative }
            return db(gated.average())
        }

        fun volumeFor(samples: FloatArray, rate: Int): Float {
            // RMS of a full-scale sine is -3 dB; this measure is power, so it lines up with dBFS RMS.
            val level = levelDb(samples, rate) ?: return 1f
            val peak = samples.maxOfOrNull { abs(it) }?.toDouble()?.coerceAtLeast(1e-6) ?: return 1f
            val toTarget = 10.0.pow((TARGET_DB - level) / 20)
            // Lowering is always fine; raising stops where the loudest peak would clip.
            val gain = if (toTarget <= 1.0) toTarget else min(toTarget, max(1.0, PEAK_CEILING / peak))
            return (Math.round(gain * 100) / 100f).coerceIn(MIN_VOLUME, MAX_VOLUME)
        }
    }
}

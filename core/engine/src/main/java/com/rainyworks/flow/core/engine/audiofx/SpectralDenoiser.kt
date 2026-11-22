package com.rainyworks.flow.core.engine.audiofx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Streaming noise reduction for one channel. The signal is cut into overlapping windows, each
 * frequency band is compared with a running estimate of the steady background noise (the
 * lowest level that band has had lately), and bands near that floor are turned down. Output is
 * delayed by [LATENCY] samples.
 *
 * [strength] goes from 0 (off) to 1 (strongest); stronger settings remove more noise and
 * leave a lower floor.
 */
class SpectralDenoiser(private val strength: Float) {
    private val window = DoubleArray(SIZE) { sqrt(0.5 - 0.5 * cos(2 * PI * (it + 0.5) / SIZE)) }
    private val input = DoubleArray(SIZE)
    private var filled = 0
    private val overlap = DoubleArray(SIZE)
    private val ready = DoubleArray(HOP)
    private var readyIndex = HOP
    private val re = DoubleArray(SIZE)
    private val im = DoubleArray(SIZE)
    private val bins = SIZE / 2 + 1
    private val smoothedPower = DoubleArray(bins)
    private val noise = DoubleArray(bins) { -1.0 }
    private val gains = DoubleArray(bins) { 1.0 }
    private val floor = 1.0 - 0.9 * strength.coerceIn(0f, 1f)
    private val overSubtract = 1.0 + 2.0 * strength.coerceIn(0f, 1f)

    fun process(sample: Double): Double {
        // Silence until the first window is processed; then one processed block per hop.
        val out = if (readyIndex < HOP) ready[readyIndex++] else 0.0
        input[filled++] = sample
        if (filled == SIZE) {
            processWindow()
            // The second half starts the next window.
            System.arraycopy(input, HOP, input, 0, HOP)
            filled = HOP
        }
        return out
    }

    private fun processWindow() {
        for (i in 0 until SIZE) {
            re[i] = input[i] * window[i]
            im[i] = 0.0
        }
        Fft.transform(re, im, inverse = false)
        for (k in 0 until bins) {
            val power = re[k] * re[k] + im[k] * im[k]
            smoothedPower[k] = if (noise[k] < 0) power else 0.7 * smoothedPower[k] + 0.3 * power
            // The floor follows drops at once and rises slowly, so speech does not raise it.
            noise[k] = if (noise[k] < 0) power else minOf(smoothedPower[k], noise[k] * NOISE_RISE + 1e-12)
            val target = max(floor, 1.0 - overSubtract * noise[k] / (smoothedPower[k] + 1e-12))
            // Open quickly for speech onsets, close slowly to avoid fluttering artifacts.
            gains[k] = if (target > gains[k]) target else 0.6 * gains[k] + 0.4 * target
            val g = gains[k]
            re[k] *= g
            im[k] *= g
            if (k in 1 until SIZE / 2) {
                re[SIZE - k] = re[k]
                im[SIZE - k] = -im[k]
            }
        }
        Fft.transform(re, im, inverse = true)
        for (i in 0 until SIZE) overlap[i] += re[i] * window[i]
        System.arraycopy(overlap, 0, ready, 0, HOP)
        System.arraycopy(overlap, HOP, overlap, 0, HOP)
        java.util.Arrays.fill(overlap, HOP, SIZE, 0.0)
        readyIndex = 0
    }

    companion object {
        const val SIZE = 1024
        const val HOP = SIZE / 2
        /** Samples between a sample going in and its processed version coming out. */
        const val LATENCY = SIZE
        /** Per-window growth of the noise floor (about +6 dB per second at 48 kHz). */
        private const val NOISE_RISE = 1.0065
    }
}

/** In-place radix-2 complex FFT; sizes must be powers of two. */
internal object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var length = 2
        while (length <= n) {
            val angle = 2 * PI / length * (if (inverse) 1 else -1)
            val wr = cos(angle)
            val wi = kotlin.math.sin(angle)
            var start = 0
            while (start < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                start += length
            }
            length = length shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

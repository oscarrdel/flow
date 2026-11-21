package com.rainyworks.flow.core.gif

/**
 * Median-cut color quantization over a 15-bit (5:5:5) histogram, plus a lookup table that
 * maps every 15-bit color to its nearest palette entry.
 */
internal class Palette private constructor(val colors: IntArray) {
    private val lookup = IntArray(1 shl 15) { key ->
        val r = (key shr 10 and 31) * 255 / 31
        val g = (key shr 5 and 31) * 255 / 31
        val b = (key and 31) * 255 / 31
        nearest(r, g, b)
    }

    val size: Int get() = colors.size

    fun indexOf(r: Int, g: Int, b: Int): Int = lookup[(r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)]

    private fun nearest(r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in colors.indices) {
            val c = colors[i]
            val dr = (c shr 16 and 0xFF) - r
            val dg = (c shr 8 and 0xFF) - g
            val db = (c and 0xFF) - b
            // Weighted toward green, as the eye is most sensitive to it.
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    companion object {
        fun build(argb: IntArray, maxColors: Int = 256): Palette {
            require(maxColors in 2..256)
            val histogram = IntArray(1 shl 15)
            for (p in argb) histogram[(p shr 19 and 31 shl 10) or (p shr 11 and 31 shl 5) or (p shr 3 and 31)]++
            val keys = histogram.indices.filter { histogram[it] > 0 }.toIntArray()
            if (keys.size <= maxColors) return Palette(IntArray(keys.size) { expand(keys[it]) })

            val boxes = mutableListOf(Box(keys))
            while (boxes.size < maxColors) {
                val target = boxes.filter { it.keys.size > 1 }.maxByOrNull { it.score(histogram) } ?: break
                boxes.remove(target)
                boxes.addAll(target.split(histogram))
            }
            return Palette(IntArray(boxes.size) { boxes[it].average(histogram) })
        }

        private fun expand(key: Int): Int {
            val r = (key shr 10 and 31) * 255 / 31
            val g = (key shr 5 and 31) * 255 / 31
            val b = (key and 31) * 255 / 31
            return (r shl 16) or (g shl 8) or b
        }
    }

    private class Box(val keys: IntArray) {
        private fun channel(key: Int, c: Int) = key shr (10 - 5 * c) and 31

        private fun range(c: Int): Int {
            var lo = 31
            var hi = 0
            for (k in keys) {
                val v = channel(k, c)
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return hi - lo
        }

        /** Prefer splitting boxes that are both populous and wide. */
        fun score(histogram: IntArray): Long {
            val population = keys.sumOf { histogram[it].toLong() }
            return population * (maxOf(range(0), range(1), range(2)) + 1)
        }

        fun split(histogram: IntArray): List<Box> {
            val c = (0..2).maxBy { range(it) }
            val sorted = keys.sortedBy { channel(it, c) }
            val total = sorted.sumOf { histogram[it].toLong() }
            var acc = 0L
            var cut = 1
            for (i in sorted.indices) {
                acc += histogram[sorted[i]]
                if (acc * 2 >= total) {
                    cut = (i + 1).coerceIn(1, sorted.size - 1)
                    break
                }
            }
            return listOf(Box(sorted.subList(0, cut).toIntArray()), Box(sorted.subList(cut, sorted.size).toIntArray()))
        }

        fun average(histogram: IntArray): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            for (k in keys) {
                val w = histogram[k].toLong()
                r += w * ((k shr 10 and 31) * 255 / 31)
                g += w * ((k shr 5 and 31) * 255 / 31)
                b += w * ((k and 31) * 255 / 31)
                n += w
            }
            return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
    }
}

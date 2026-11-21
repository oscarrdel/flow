package com.rainyworks.flow.core.model

import kotlin.math.floor

/**
 * A parsed Adobe/Resolve .cube look-up table. [data] holds RGB triplets with red changing
 * fastest, as in the file: size entries for a 1D LUT, size^3 entries for a 3D LUT.
 */
class CubeLut(
    val title: String,
    val size: Int,
    val is3d: Boolean,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val data: FloatArray,
) {
    /** Applies the LUT to a color with channels in the input domain, using (tri)linear interpolation. */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val nr = normalize(r, 0)
        val ng = normalize(g, 1)
        val nb = normalize(b, 2)
        return if (is3d) sample3d(nr, ng, nb) else floatArrayOf(sample1d(nr, 0), sample1d(ng, 1), sample1d(nb, 2))
    }

    private fun normalize(v: Float, channel: Int): Float {
        val span = domainMax[channel] - domainMin[channel]
        return ((v - domainMin[channel]) / span).coerceIn(0f, 1f) * (size - 1)
    }

    private fun sample1d(position: Float, channel: Int): Float {
        val i0 = floor(position).toInt().coerceIn(0, size - 1)
        val i1 = (i0 + 1).coerceAtMost(size - 1)
        val t = position - i0
        val a = data[i0 * 3 + channel]
        return a + (data[i1 * 3 + channel] - a) * t
    }

    private fun sample3d(r: Float, g: Float, b: Float): FloatArray {
        val r0 = floor(r).toInt().coerceIn(0, size - 1)
        val g0 = floor(g).toInt().coerceIn(0, size - 1)
        val b0 = floor(b).toInt().coerceIn(0, size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        val tr = r - r0
        val tg = g - g0
        val tb = b - b0
        val out = FloatArray(3)
        for (c in 0 until 3) {
            fun at(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + c]
            val c00 = at(r0, g0, b0) + (at(r1, g0, b0) - at(r0, g0, b0)) * tr
            val c10 = at(r0, g1, b0) + (at(r1, g1, b0) - at(r0, g1, b0)) * tr
            val c01 = at(r0, g0, b1) + (at(r1, g0, b1) - at(r0, g0, b1)) * tr
            val c11 = at(r0, g1, b1) + (at(r1, g1, b1) - at(r0, g1, b1)) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            out[c] = c0 + (c1 - c0) * tb
        }
        return out
    }

    companion object {
        const val MAX_3D_SIZE = 128
        const val MAX_1D_SIZE = 65_536

        /** Parses .cube text, throwing [CubeLutException] with a user-presentable reason when invalid. */
        fun parse(content: String): CubeLut {
            var title = ""
            var size3d = 0
            var size1d = 0
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            var values = FloatArray(0)
            var count = 0

            content.removePrefix("﻿").lineSequence().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                val key = parts[0].uppercase()
                when {
                    key == "TITLE" -> title = line.substringAfter(parts[0]).trim().trim('"')
                    key == "LUT_3D_SIZE" -> size3d = intArg(parts, index)
                    key == "LUT_1D_SIZE" -> size1d = intArg(parts, index)
                    key == "DOMAIN_MIN" -> floats(parts, index).copyInto(min)
                    key == "DOMAIN_MAX" -> floats(parts, index).copyInto(max)
                    key == "LUT_3D_INPUT_RANGE" || key == "LUT_1D_INPUT_RANGE" -> {
                        val range = floats(parts, index, expected = 2)
                        min.fill(range[0])
                        max.fill(range[1])
                    }
                    key.first().isLetter() -> Unit // Unknown keywords are ignored, as most tools do.
                    else -> {
                        if (values.isEmpty()) values = allocate(size3d, size1d)
                        if (count >= values.size) throw CubeLutException("Hay más datos de los que indica el tamaño de la LUT")
                        val rgb = floats(parts, index)
                        rgb.copyInto(values, count)
                        count += 3
                    }
                }
            }
            val size = if (size3d > 0) size3d else size1d
            if (size == 0) throw CubeLutException("Falta LUT_3D_SIZE o LUT_1D_SIZE")
            if (count != values.size || values.isEmpty()) {
                throw CubeLutException("La LUT está incompleta: se esperaban ${expectedEntries(size3d, size1d)} colores")
            }
            for (c in 0 until 3) if (max[c] <= min[c]) throw CubeLutException("DOMAIN_MAX debe ser mayor que DOMAIN_MIN")
            return CubeLut(title, size, size3d > 0, min, max, values)
        }

        private fun allocate(size3d: Int, size1d: Int): FloatArray {
            if (size3d > 0 && size1d > 0) throw CubeLutException("La LUT no puede ser 1D y 3D a la vez")
            if (size3d == 0 && size1d == 0) throw CubeLutException("El tamaño de la LUT debe aparecer antes de los datos")
            if (size3d !in 0..MAX_3D_SIZE || size3d == 1) throw CubeLutException("Tamaño 3D no soportado: $size3d")
            if (size1d !in 0..MAX_1D_SIZE || size1d == 1) throw CubeLutException("Tamaño 1D no soportado: $size1d")
            return FloatArray(expectedEntries(size3d, size1d) * 3)
        }

        private fun expectedEntries(size3d: Int, size1d: Int): Int =
            if (size3d > 0) size3d * size3d * size3d else size1d

        private fun intArg(parts: List<String>, line: Int): Int =
            parts.getOrNull(1)?.toIntOrNull() ?: throw CubeLutException("Número inválido en la línea ${line + 1}")

        private fun floats(parts: List<String>, line: Int, expected: Int = 3): FloatArray {
            val start = if (parts[0].first().isLetter()) 1 else 0
            if (parts.size - start != expected) throw CubeLutException("Se esperaban $expected valores en la línea ${line + 1}")
            return FloatArray(expected) {
                parts[start + it].toFloatOrNull()?.takeIf(Float::isFinite)
                    ?: throw CubeLutException("Valor inválido en la línea ${line + 1}")
            }
        }
    }
}

class CubeLutException(message: String) : IllegalArgumentException(message)

package com.rainyworks.flow.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {
    private fun identity3d(size: Int) = buildString {
        appendLine("TITLE \"Identidad\"")
        appendLine("# comentario")
        appendLine("LUT_3D_SIZE $size")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val d = (size - 1).toFloat()
            appendLine("${r / d} ${g / d} ${b / d}")
        }
    }

    @Test
    fun identity3dReturnsInput() {
        val lut = CubeLut.parse(identity3d(5))
        assertEquals("Identidad", lut.title)
        assertTrue(lut.is3d)
        assertArrayEquals(floatArrayOf(0.3f, 0.55f, 0.9f), lut.sample(0.3f, 0.55f, 0.9f), 1e-5f)
        assertArrayEquals(floatArrayOf(1f, 0f, 1f), lut.sample(2f, -1f, 1f), 1e-5f)
    }

    @Test
    fun redChangesFastest() {
        val text = "LUT_3D_SIZE 2\n" +
            "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n0.5 0.5 0.5\n"
        val lut = CubeLut.parse(text)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), lut.sample(1f, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), lut.sample(1f, 1f, 1f), 1e-6f)
    }

    @Test
    fun parses1dWithCustomDomain() {
        val lut = CubeLut.parse("LUT_1D_SIZE 3\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n0 0 0\n0.25 0.5 1\n1 1 1\n")
        assertFalse(lut.is3d)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 1f), lut.sample(1f, 1f, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.125f, 0.25f, 0.5f), lut.sample(0.5f, 0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun rejectsInvalidFiles() {
        assertThrows(CubeLutException::class.java) { CubeLut.parse("0 0 0\n") }
        assertThrows(CubeLutException::class.java) { CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n") }
        assertThrows(CubeLutException::class.java) { CubeLut.parse("LUT_3D_SIZE 2\n0 0 x\n") }
        assertThrows(CubeLutException::class.java) { CubeLut.parse("LUT_3D_SIZE 999\n0 0 0\n") }
        assertThrows(CubeLutException::class.java) { CubeLut.parse("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n1 1 1\n") }
    }

    @Test
    fun acceptsCrlfAndBom() {
        val lut = CubeLut.parse("﻿" + identity3d(2).replace("\n", "\r\n"))
        assertEquals(2, lut.size)
    }
}

package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AspectRatioTest {
    @Test
    fun fromSizeReducesDimensionsAndPreservesOrientation() {
        assertEquals(AspectRatio.R16_9, AspectRatio.fromSize(1920, 1080))
        assertEquals(AspectRatio.R9_16, AspectRatio.fromSize(1080, 1920))
        assertEquals(AspectRatio.R1_1, AspectRatio.fromSize(2048, 2048))
        assertEquals(AspectRatio(13, 7), AspectRatio.fromSize(13, 7))
    }

    @Test
    fun fromSizeRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) { AspectRatio.fromSize(0, 1080) }
        assertThrows(IllegalArgumentException::class.java) { AspectRatio.fromSize(1920, -1) }
    }
}

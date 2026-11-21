package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipTest {
    @Test
    fun sourceTimeAtAppliesClipOffsetTrimAndSpeed() {
        val clip = testVideo("clip", startUs = 500_000L, trimInUs = 200_000L, trimOutUs = 1_200_000L)
            .copy(speed = 2f)

        assertEquals(200_000L, clip.sourceTimeAt(499_999L))
        assertEquals(200_000L, clip.sourceTimeAt(500_000L))
        assertEquals(500_000L, clip.sourceTimeAt(650_000L))
        assertEquals(1_200_000L, clip.sourceTimeAt(1_000_000L))
        assertEquals(1_200_000L, clip.sourceTimeAt(2_000_000L))
    }

    @Test
    fun sourceTimeAtCountsBackwardWhenReversed() {
        val clip = testVideo("clip", startUs = 500_000L, trimInUs = 200_000L, trimOutUs = 1_200_000L)
            .copy(speed = 0.5f, reversed = true)

        assertEquals(1_200_000L, clip.sourceTimeAt(0L))
        assertEquals(1_200_000L, clip.sourceTimeAt(500_000L))
        assertEquals(900_000L, clip.sourceTimeAt(1_100_000L))
        assertEquals(200_000L, clip.sourceTimeAt(2_500_000L))
        assertEquals(200_000L, clip.sourceTimeAt(3_000_000L))
    }

    @Test
    fun sourceTimeAtStaysWithinTrimRangeWhenTimelineDurationRoundsUp() {
        val clip = testVideo("clip", trimInUs = 200_000L, trimOutUs = 1_200_001L).copy(speed = 2f)

        assertEquals(
            listOf(1_200_001L, 200_000L),
            listOf(clip.sourceTimeAt(clip.endUs), clip.copy(reversed = true).sourceTimeAt(clip.endUs)),
        )
    }
}

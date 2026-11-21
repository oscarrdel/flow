package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TimecodeTest {
    @Test
    fun formatUsesMinutesSecondsAndFrameNumber() {
        assertEquals("00:00:00", Timecode.format(-1L))
        assertEquals("00:00:29", Timecode.format(999_999L))
        assertEquals("00:01:00", Timecode.format(1_000_000L))
        assertEquals("01:02:15", Timecode.format(62_500_000L))
        assertEquals("01:02:12", Timecode.format(62_500_000L, fps = 24))
    }

    @Test
    fun snapToFrameChoosesNearestBoundaryAndRoundsTiesUp() {
        assertEquals(0L, Timecode.snapToFrame(19_999L, fps = 25))
        assertEquals(40_000L, Timecode.snapToFrame(20_000L, fps = 25))
        assertEquals(40_000L, Timecode.snapToFrame(40_000L, fps = 25))
        assertEquals(40_000L, Timecode.snapToFrame(59_999L, fps = 25))
        assertEquals(80_000L, Timecode.snapToFrame(60_000L, fps = 25))
        assertEquals(33_333L, Timecode.snapToFrame(33_000L, fps = 30))
    }
}

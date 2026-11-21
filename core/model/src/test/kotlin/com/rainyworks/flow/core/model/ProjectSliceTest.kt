package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSliceTest {
    private fun video(durationUs: Long) =
        ProjectFactory.clipFor(MediaRef("v$durationUs", MediaType.VIDEO, durationUs = durationUs, width = 10, height = 10))

    private fun project(): Project {
        var p = ProjectFactory.create("p", 0L)
        p = Edits.insertIntoMain(p, listOf(video(4_000_000), video(6_000_000)))
        p = MediaEdits.addText(p, 3_000_000, TextContent("hola"), durationUs = 4_000_000).first
        return Edits.addMarker(Edits.addMarker(p, 1_000_000), 5_000_000)
    }

    @Test
    fun keepsOnlyTheRangeShiftedToZero() {
        val slice = ProjectSlice.slice(project(), 2_000_000, 6_000_000)
        assertEquals(4_000_000, slice.durationUs)
        val main = slice.mainTrack.clips
        assertEquals(2, main.size)
        assertEquals(0L, main[0].startUs)
        assertEquals(2_000_000, main[0].trimInUs)
        assertEquals(2_000_000, main[0].durationUs)
        assertEquals(2_000_000, main[1].durationUs)
        assertEquals(0L, main[1].trimInUs)
        val text = slice.tracks.single { it.kind == TrackKind.TEXT }.clips.single()
        assertEquals(1_000_000, text.startUs)
        assertEquals(3_000_000, text.durationUs)
        assertEquals(listOf(3_000_000L), slice.markers.map { it.timeUs })
    }

    @Test
    fun respectsSpeedWhenTrimming() {
        var p = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(video(8_000_000)))
        p = Edits.setSpeed(p, p.mainTrack.clips.single().id, 2f)
        val clip = ProjectSlice.slice(p, 1_000_000, 3_000_000).mainTrack.clips.single()
        assertEquals(2_000_000, clip.trimInUs)
        assertEquals(6_000_000, clip.trimOutUs)
        assertEquals(2_000_000, clip.durationUs)
    }

    @Test
    fun emptyRangeGivesEmptyProject() {
        assertTrue(ProjectSlice.slice(project(), 5_000_000, 5_000_000).isEmpty)
    }

    @Test
    fun keepsLockState() {
        var p = project()
        p = Edits.updateTrack(p, p.mainTrack.id) { it.copy(locked = true) }
        val slice = ProjectSlice.slice(p, 1_000_000, 5_000_000)
        assertTrue(slice.mainTrack.locked)
        assertEquals(4_000_000, slice.durationUs)
    }
}

package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceInLayersTest {
    private val image = MediaRef("img", MediaType.IMAGE, width = 10, height = 10)

    private fun twoOverlays(): Project {
        var p = ProjectFactory.create("p", 0L)
        p = MediaEdits.addOverlay(p, image, 0L)
        p = MediaEdits.addOverlay(p, image, 0L) // Overlaps, so it lands on a second track.
        return p
    }

    private fun Project.overlays() = tracks.filter { it.kind == TrackKind.OVERLAY }

    @Test
    fun movesInTimeOnTheSameTrack() {
        val p = twoOverlays()
        val clip = p.overlays()[0].clips.single()
        val moved = Edits.placeInLayers(p, clip.id, p.overlays()[0].id, 5_000_000L)
        assertEquals(5_000_000L, moved.findClip(clip.id)!!.second.startUs)
        assertEquals(p.overlays()[0].id, moved.findClip(clip.id)!!.first.id)
    }

    @Test
    fun movesToAnotherTrackWhenItFitsAndDropsTheEmptyOne() {
        val p = twoOverlays()
        val clip = p.overlays()[1].clips.single()
        val moved = Edits.placeInLayers(p, clip.id, p.overlays()[0].id, 10_000_000L)
        assertEquals(1, moved.overlays().size)
        assertEquals(2, moved.overlays().single().clips.size)
    }

    @Test
    fun anOccupiedTargetFallsBackToAFreeOrNewTrack() {
        val p = twoOverlays()
        val clip = p.overlays()[1].clips.single()
        // Track 0 is busy at 0 s, so the clip keeps a track of its own.
        val moved = Edits.placeInLayers(p, clip.id, p.overlays()[0].id, 0L)
        assertEquals(2, moved.overlays().size)
        assertEquals(0L, moved.findClip(clip.id)!!.second.startUs)
    }

    @Test
    fun otherKindsAreNeverMixed() {
        var p = twoOverlays()
        val (withText, _) = MediaEdits.addText(p, 0L, TextContent("hola"))
        p = withText
        val clip = p.overlays()[0].clips.single()
        val textTrack = p.tracks.first { it.kind == TrackKind.TEXT }
        val moved = Edits.placeInLayers(p, clip.id, textTrack.id, 20_000_000L)
        assertEquals(TrackKind.OVERLAY, moved.findClip(clip.id)!!.first.kind)
    }
}

package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatMarkerTest {
    @Test
    fun beatsFollowTrimSpeedAndPosition() {
        var p = ProjectFactory.create("p", 0L)
        val song = MediaRef("song", MediaType.AUDIO, durationUs = 10_000_000, hasAudio = true, hasVideo = false)
        p = MediaEdits.addAudio(p, song, 1_000_000L)
        val id = p.tracks.first { it.kind == TrackKind.AUDIO }.clips.single().id
        p = Edits.updateClip(p, id) { it.copy(trimInUs = 2_000_000, trimOutUs = 6_000_000, speed = 2f) }
        // Beats at 1 s (before the trim), 3 s, 5 s and 7 s (after it).
        p = Edits.addBeatMarkers(p, id, longArrayOf(1_000_000, 3_000_000, 5_000_000, 7_000_000))
        assertEquals(listOf(1_500_000L, 2_500_000L), p.markers.map { it.timeUs })
        // Detecting again replaces the earlier beat markers.
        p = Edits.addBeatMarkers(p, id, longArrayOf(4_000_000))
        assertEquals(listOf(2_000_000L), p.markers.map { it.timeUs })
    }
}

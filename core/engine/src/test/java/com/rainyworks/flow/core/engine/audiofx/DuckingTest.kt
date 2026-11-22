package com.rainyworks.flow.core.engine.audiofx

import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DuckingTest {
    @Test
    fun musicDropsWhileTheVideoSpeaksAndRecoversAfter() {
        var p = ProjectFactory.create("p", 0L)
        val video = MediaRef("v", MediaType.VIDEO, durationUs = 4_000_000, hasAudio = true)
        // The video speaks from 2 s to 6 s; the music plays from 0 s to 10 s.
        p = MediaEdits.addOverlay(p, video, 2_000_000)
        val song = MediaRef("song", MediaType.AUDIO, durationUs = 10_000_000, hasAudio = true, hasVideo = false)
        p = MediaEdits.addAudio(p, song, 0L)
        val musicId = p.tracks.first { it.kind == TrackKind.AUDIO }.clips.single().id
        p = Edits.updateClip(p, musicId) { it.copy(duckUnderVoice = true) }
        val music = p.findClip(musicId)!!.second

        val ducking = Ducking.forClip(p, music) { _, clip -> clip.media?.hasAudio == true }
        assertEquals(1.0, ducking.gainAt(1_000_000), 1e-9)
        assertEquals(Ducking.LEVEL, ducking.gainAt(3_000_000), 1e-9)
        val halfway = ducking.gainAt(2_000_000 - Ducking.ATTACK_US / 2)
        assert(halfway < 1.0 && halfway > Ducking.LEVEL)
        assertEquals(1.0, ducking.gainAt(6_000_000 + Ducking.RELEASE_US + 1), 1e-9)
    }

    @Test
    fun clipsWithoutTheOptionAreNotTouched() {
        var p = ProjectFactory.create("p", 0L)
        val song = MediaRef("song", MediaType.AUDIO, durationUs = 10_000_000, hasAudio = true, hasVideo = false)
        p = MediaEdits.addAudio(p, song, 0L)
        val music = p.tracks.first { it.kind == TrackKind.AUDIO }.clips.single()
        assertEquals(true, Ducking.forClip(p, music) { _, _ -> true }.isEmpty)
    }
}

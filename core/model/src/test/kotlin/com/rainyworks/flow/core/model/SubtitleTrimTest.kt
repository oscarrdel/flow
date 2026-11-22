package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTrimTest {
    @Test
    fun subtitleEdgesCanBeDragged() {
        var p = ProjectFactory.create("p", 0L)
        p = SubtitleEdits.addCue(p, SubtitleCue(0, 2_000_000, "Hola"))
        val id = p.tracks.single { it.kind == TrackKind.TEXT }.clips.single().id
        val longer = Edits.trimEnd(p, id, 3_500_000)
        assertEquals(3_500_000L, longer.findClip(id)!!.second.endUs)
        val shorter = Edits.trimStart(p, id, 500_000)
        assertEquals(500_000L, shorter.findClip(id)!!.second.startUs)
        assertEquals(2_000_000L, shorter.findClip(id)!!.second.endUs)
    }
}

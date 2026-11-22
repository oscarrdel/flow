package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseEditsTest {
    private val original = MediaRef("orig", MediaType.VIDEO, durationUs = 10_000_000, width = 10, height = 10, hasAudio = true)
    private val reversed = original.copy(uri = "rev")

    private fun project(): Pair<Project, String> {
        var p = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(ProjectFactory.clipFor(original)))
        val id = p.mainTrack.clips.single().id
        p = Edits.trimStart(p, id, 2_000_000)
        p = Edits.trimEnd(p, id, 3_000_000)
        return p to id
    }

    @Test
    fun reversingMirrorsTheUsedRange() {
        val (p, id) = project()
        val before = p.findClip(id)!!.second
        assertEquals(2_000_000L, before.trimInUs)
        assertEquals(5_000_000L, before.trimOutUs)
        val after = MediaEdits.swapReversed(p, id, reversed).findClip(id)!!.second
        assertEquals("rev", after.media!!.uri)
        assertEquals(5_000_000L, after.trimInUs)
        assertEquals(8_000_000L, after.trimOutUs)
        assertEquals(before.durationUs, after.durationUs)
        assertTrue(MediaEdits.isReversed(after))
    }

    @Test
    fun reversingTwiceRestoresTheOriginal() {
        val (p, id) = project()
        val once = MediaEdits.swapReversed(p, id, reversed)
        val twice = MediaEdits.swapReversed(once, id, once.findClip(id)!!.second.originalMedia!!)
        val clip = twice.findClip(id)!!.second
        assertEquals("orig", clip.media!!.uri)
        assertEquals(2_000_000L, clip.trimInUs)
        assertEquals(5_000_000L, clip.trimOutUs)
        assertFalse(MediaEdits.isReversed(clip))
    }

    @Test
    fun speedCurveIsMirrored() {
        var (p, id) = project()
        p = Edits.setSpeedCurve(p, id, SpeedCurve(listOf(SpeedPoint(0f, 0.5f), SpeedPoint(1f, 2f))))
        val clip = MediaEdits.swapReversed(p, id, reversed).findClip(id)!!.second
        assertEquals(listOf(SpeedPoint(0f, 2f), SpeedPoint(1f, 0.5f)), clip.speedCurve!!.points)
    }
}

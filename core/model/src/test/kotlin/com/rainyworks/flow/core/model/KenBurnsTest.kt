package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KenBurnsTest {
    private fun photo(): Pair<Project, String> {
        var p = ProjectFactory.create("p", 0L)
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(MediaRef("img", MediaType.IMAGE, width = 10, height = 10), imageDurationUs = 4_000_000)))
        return p to p.mainTrack.clips.single().id
    }

    @Test
    fun zoomInGrowsAcrossTheClip() {
        val (p, id) = photo()
        val clip = KenBurns.apply(p, id, KenBurnsStyle.ZOOM_IN).findClip(id)!!.second
        assertEquals(1f, MediaEdits.propertyValue(clip, AnimatedProperty.SCALE, clip.startUs), 1e-4f)
        assertEquals(1.25f, MediaEdits.propertyValue(clip, AnimatedProperty.SCALE, clip.startUs + clip.durationUs), 1e-4f)
        assertEquals(KenBurnsStyle.ZOOM_IN, KenBurns.styleOf(clip))
    }

    @Test
    fun panMovesSidewaysAndCanBeRemoved() {
        val (p, id) = photo()
        val panned = KenBurns.apply(p, id, KenBurnsStyle.PAN_LEFT)
        val clip = panned.findClip(id)!!.second
        val startX = MediaEdits.propertyValue(clip, AnimatedProperty.OFFSET_X, 0L)
        val endX = MediaEdits.propertyValue(clip, AnimatedProperty.OFFSET_X, clip.durationUs)
        assert(startX > endX)
        assertEquals(KenBurnsStyle.PAN_LEFT, KenBurns.styleOf(clip))
        val removed = KenBurns.apply(panned, id, null).findClip(id)!!.second
        assertFalse(MediaEdits.hasMotionKeyframes(removed))
    }
}

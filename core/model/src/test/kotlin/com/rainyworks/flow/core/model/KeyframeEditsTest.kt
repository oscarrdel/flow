package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeEditsTest {
    private fun project(): Pair<Project, String> {
        val media = MediaRef("v", MediaType.VIDEO, durationUs = 4_000_000, width = 10, height = 10)
        val p = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(ProjectFactory.clipFor(media)))
        return p to p.mainTrack.clips.single().id
    }

    @Test
    fun toggleAddsThenRemovesKeyframe() {
        var (p, id) = project()
        p = MediaEdits.toggleKeyframe(p, id, AnimatedProperty.SCALE, 1_000_000)
        assertEquals(1, p.findClip(id)!!.second.keyframes[AnimatedProperty.SCALE]!!.size)
        p = MediaEdits.toggleKeyframe(p, id, AnimatedProperty.SCALE, 1_010_000)
        assertTrue(p.findClip(id)!!.second.keyframes[AnimatedProperty.SCALE] == null)
    }

    @Test
    fun gesturesEditAnimatedPropertiesThroughKeyframes() {
        var (p, id) = project()
        p = MediaEdits.toggleKeyframe(p, id, AnimatedProperty.SCALE, 0)
        p = MediaEdits.setTransformAt(p, id, Transform(scale = 2f, offsetX = 0.3f), 2_000_000)
        val clip = p.findClip(id)!!.second
        assertEquals(1f, clip.transform.scale, 0f)
        assertEquals(0.3f, MediaEdits.propertyValue(clip, AnimatedProperty.OFFSET_X, 2_000_000), 1e-4f)
        assertEquals(2, clip.keyframes[AnimatedProperty.SCALE]!!.size)
        assertEquals(1.5f, MediaEdits.propertyValue(clip, AnimatedProperty.SCALE, 1_000_000), 1e-4f)
    }

    @Test
    fun keyframeTimesAreAbsoluteAndSorted() {
        var (p, id) = project()
        p = MediaEdits.toggleKeyframe(p, id, AnimatedProperty.OPACITY, 3_000_000)
        p = MediaEdits.toggleKeyframe(p, id, AnimatedProperty.SCALE, 500_000)
        assertEquals(listOf(500_000L, 3_000_000L), MediaEdits.keyframeTimes(p.findClip(id)!!.second))
    }
}

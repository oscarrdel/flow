package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionKeyframeTest {
    private fun project(): Pair<Project, String> {
        val media = MediaRef("v", MediaType.VIDEO, durationUs = 4_000_000, width = 10, height = 10)
        val p = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(ProjectFactory.clipFor(media)))
        return p to p.mainTrack.clips.single().id
    }

    private fun Project.clip(id: String) = findClip(id)!!.second

    @Test
    fun withoutKeyframesTransformIsStatic() {
        var (p, id) = project()
        p = MediaEdits.setTransformAt(p, id, Transform(offsetX = 0.3f), 2_000_000)
        assertEquals(0.3f, p.clip(id).transform.offsetX, 0f)
        assertTrue(p.clip(id).keyframes.isEmpty())
    }

    @Test
    fun oneKeyframeRecordsEveryMotionProperty() {
        var (p, id) = project()
        p = MediaEdits.toggleMotionKeyframe(p, id, 0)
        MediaEdits.MOTION_PROPERTIES.forEach { assertEquals(it, 1, p.clip(id).keyframes[it]!!.size) }
        p = MediaEdits.toggleMotionKeyframe(p, id, 10_000)
        assertFalse(MediaEdits.hasMotionKeyframes(p.clip(id)))
    }

    @Test
    fun movingTheClipLaterAnimatesFromTheFirstKeyframe() {
        var (p, id) = project()
        p = MediaEdits.toggleMotionKeyframe(p, id, 0)
        p = MediaEdits.setTransformAt(p, id, Transform(offsetX = 0.4f, offsetY = -0.2f, scale = 2f), 2_000_000)
        val clip = p.clip(id)
        assertEquals(0f, MediaEdits.propertyValue(clip, AnimatedProperty.OFFSET_X, 0), 1e-4f)
        assertEquals(0.2f, MediaEdits.propertyValue(clip, AnimatedProperty.OFFSET_X, 1_000_000), 1e-4f)
        assertEquals(-0.2f, MediaEdits.propertyValue(clip, AnimatedProperty.OFFSET_Y, 2_000_000), 1e-4f)
        assertEquals(1.5f, MediaEdits.propertyValue(clip, AnimatedProperty.SCALE, 1_000_000), 1e-4f)
        assertEquals(0f, clip.transform.offsetX, 0f)
    }

    @Test
    fun opacityChangeOnAnimatedClipAddsKeyframe() {
        var (p, id) = project()
        p = MediaEdits.toggleMotionKeyframe(p, id, 0)
        p = MediaEdits.setOpacityAt(p, id, 0f, 2_000_000)
        val clip = p.clip(id)
        assertEquals(1f, MediaEdits.propertyValue(clip, AnimatedProperty.OPACITY, 0), 1e-4f)
        assertEquals(0.5f, MediaEdits.propertyValue(clip, AnimatedProperty.OPACITY, 1_000_000), 1e-4f)
    }

    @Test
    fun easingAppliesToTheWholeKeyframe() {
        var (p, id) = project()
        p = MediaEdits.toggleMotionKeyframe(p, id, 0)
        p = MediaEdits.setMotionEasing(p, id, 0, Easing.EASE_IN_OUT)
        MediaEdits.MOTION_PROPERTIES.forEach { assertEquals(Easing.EASE_IN_OUT, p.clip(id).keyframes[it]!!.single().easing) }
    }
}

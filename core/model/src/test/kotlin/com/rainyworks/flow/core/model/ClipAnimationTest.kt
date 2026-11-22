package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipAnimationTest {
    private fun clip(enter: ClipAnimation?, exit: ClipAnimation?) = Clip(
        id = "c", kind = ClipKind.IMAGE, startUs = 1_000_000, trimOutUs = 4_000_000,
        enterAnimation = enter, exitAnimation = exit,
    )

    @Test
    fun identityOutsideWindows() {
        val c = clip(ClipAnimation(ClipAnimationType.ZOOM_IN), ClipAnimation(ClipAnimationType.SLIDE_UP))
        assertEquals(MotionSample.Identity, ClipAnimations.sample(c, 3_000_000))
    }

    @Test
    fun zoomInGrowsWithTransparency() {
        val c = clip(ClipAnimation(ClipAnimationType.ZOOM_IN, 1_000_000), null)
        val start = ClipAnimations.sample(c, 1_000_000)
        val mid = ClipAnimations.sample(c, 1_500_000)
        assertEquals(0.6f, start.scale, 1e-4f)
        assertEquals(0f, start.opacity, 1e-4f)
        assertTrue(mid.scale in 0.6f..1f && mid.opacity in 0.01f..0.99f)
    }

    @Test
    fun slideUpExitLeavesUpwards() {
        val c = clip(null, ClipAnimation(ClipAnimationType.SLIDE_UP, 1_000_000))
        val end = ClipAnimations.sample(c, 4_999_999)
        assertTrue("moves up", end.offsetY < -0.2f)
        assertTrue("fades", end.opacity < 0.05f)
    }

    @Test
    fun slideUpEntranceComesFromBelow() {
        val c = clip(ClipAnimation(ClipAnimationType.SLIDE_UP, 1_000_000), null)
        assertTrue(ClipAnimations.sample(c, 1_000_000).offsetY > 0.2f)
    }

    @Test
    fun windowsAreLimitedToHalfTheClip() {
        val short = Clip(id = "s", kind = ClipKind.IMAGE, trimOutUs = 600_000,
            enterAnimation = ClipAnimation(ClipAnimationType.FADE, 3_000_000),
            exitAnimation = ClipAnimation(ClipAnimationType.FADE, 3_000_000))
        assertEquals(1f, ClipAnimations.sample(short, 300_000).opacity, 1e-3f)
    }

    @Test
    fun renderedTransformCombinesKeyframesAndAnimation() {
        var p = ProjectFactory.create("p", 0L)
        val media = MediaRef("i", MediaType.IMAGE, width = 10, height = 10)
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(media)))
        val id = p.mainTrack.clips.single().id
        p = MediaEdits.setEnterAnimation(p, id, ClipAnimation(ClipAnimationType.FADE, 1_000_000))
        p = Edits.updateClip(p, id) { it.copy(opacity = 0.8f) }
        val (_, opacity) = ClipAnimations.renderedTransform(p.findClip(id)!!.second, 500_000)
        assertTrue(opacity in 0.5f..0.8f)
    }

    @Test
    fun everyTypeEndsFullyVisible() {
        ClipAnimationType.entries.forEach { type ->
            val s = ClipAnimations.shape(type, 1f, entering = true)
            assertEquals(type.name, 1f, s.opacity, 1e-3f)
            assertEquals(type.name, 1f, s.scale, 1e-3f)
            assertEquals(type.name, 0f, s.offsetX, 1e-3f)
            assertEquals(type.name, 0f, s.offsetY, 1e-3f)
        }
    }
}

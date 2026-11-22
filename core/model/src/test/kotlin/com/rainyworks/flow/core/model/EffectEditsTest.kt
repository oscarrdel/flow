package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectEditsTest {
    private fun video(us: Long) = ProjectFactory.clipFor(MediaRef("v$us", MediaType.VIDEO, durationUs = us))

    private fun project() =
        Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(video(4_000_000), video(1_000_000), video(4_000_000)))

    @Test
    fun catalogIdsAreUniqueAndDefaultsInRange() {
        assertEquals(EffectCatalog.all.size, EffectCatalog.all.map { it.type }.toSet().size)
        EffectCatalog.all.flatMap { it.params }.forEach { assertTrue(it.default in it.min..it.max) }
        assertEquals(TransitionCatalog.all.size, TransitionCatalog.all.map { it.type }.toSet().size)
    }

    @Test
    fun effectsAddClampAndRemove() {
        var p = project()
        val id = p.mainTrack.clips[0].id
        val (added, effectId) = EffectEdits.addEffect(p, id, "glitch")
        p = EffectEdits.setEffectParam(added, id, effectId!!, "intensity", 5f)
        val effect = p.findClip(id)!!.second.effects.single()
        assertEquals(1f, EffectCatalog.value(effect, "intensity"), 0f)
        assertEquals(0.5f, EffectCatalog.value(effect, "speed"), 0f)
        p = EffectEdits.removeEffect(p, id, effectId)
        assertTrue(p.findClip(id)!!.second.effects.isEmpty())
    }

    @Test
    fun transitionDurationIsClampedToShortNeighbor() {
        val p = project()
        val second = p.mainTrack.clips[1].id
        val result = EffectEdits.setTransition(p, second, Transition("dissolve", 2_000_000))
        assertEquals(500_000L, result.findClip(second)!!.second.transitionIn!!.durationUs)
    }

    @Test
    fun firstClipAndCutHaveNoTransition() {
        val p = project()
        val first = p.mainTrack.clips[0].id
        assertEquals(p, EffectEdits.setTransition(p, first, Transition("dissolve")))
        val third = p.mainTrack.clips[2].id
        val withCut = EffectEdits.setTransition(p, third, Transition("cut"))
        assertNull(withCut.findClip(third)!!.second.transitionIn)
    }

    @Test
    fun transitionEverywhereSkipsFirstClip() {
        val p = EffectEdits.setTransitionEverywhere(project(), Transition("fade.black", 400_000))
        assertNull(p.mainTrack.clips[0].transitionIn)
        assertTrue(p.mainTrack.clips.drop(1).all { it.transitionIn?.type == "fade.black" })
    }

    @Test
    fun effectLayerGoesToEffectTrack() {
        val (p, id) = EffectEdits.addEffectLayer(project(), "shake", 1_000_000)!!
        val track = p.tracks.single { it.kind == TrackKind.EFFECT }
        assertEquals(id, track.clips.single().id)
        assertEquals("shake", track.clips.single().effects.single().type)
    }
}

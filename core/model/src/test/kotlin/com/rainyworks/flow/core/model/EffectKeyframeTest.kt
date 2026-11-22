package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectKeyframeTest {
    private fun setup(): Triple<Project, String, Pair<String, String>> {
        var p = ProjectFactory.create("p", 0L)
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(MediaRef("v", MediaType.VIDEO, durationUs = 4_000_000))))
        val clipId = p.mainTrack.clips.single().id
        val type = EffectCatalog.all.first { it.params.isNotEmpty() }
        val (withEffect, effectId) = EffectEdits.addEffect(p, clipId, type.type)
        return Triple(withEffect, clipId, effectId!! to type.params.first().key)
    }

    @Test
    fun animatesBetweenKeyframesAndEditsTheOneUnderThePlayhead() {
        var (p, clipId, ids) = setup()
        val (effectId, key) = ids
        val param = EffectCatalog.all.flatMap { it.params }.first { it.key == key }
        p = EffectEdits.toggleEffectKeyframe(p, clipId, effectId, key, 0L)
        p = EffectEdits.toggleEffectKeyframe(p, clipId, effectId, key, 2_000_000L)
        p = EffectEdits.setEffectParam(p, clipId, effectId, key, param.min, atTimelineUs = 0L)
        p = EffectEdits.setEffectParam(p, clipId, effectId, key, param.max, atTimelineUs = 2_000_000L)
        val effect = p.findClip(clipId)!!.second.effects.single()
        assertEquals(2, effect.keyframes.getValue(key).size)
        val middle = EffectCatalog.valueAt(effect, key, 1_000_000L)
        assertEquals((param.min + param.max) / 2f, middle, (param.max - param.min) * 0.01f)
        assertEquals(param.max, EffectCatalog.valueAt(effect, key, 3_500_000L), 1e-4f)
    }

    @Test
    fun removingTheLastKeyframeKeepsItsValue() {
        var (p, clipId, ids) = setup()
        val (effectId, key) = ids
        val param = EffectCatalog.all.flatMap { it.params }.first { it.key == key }
        p = EffectEdits.toggleEffectKeyframe(p, clipId, effectId, key, 1_000_000L)
        p = EffectEdits.setEffectParam(p, clipId, effectId, key, param.max, atTimelineUs = 1_000_000L)
        p = EffectEdits.toggleEffectKeyframe(p, clipId, effectId, key, 1_000_000L)
        val effect = p.findClip(clipId)!!.second.effects.single()
        assertTrue(effect.keyframes.isEmpty())
        assertEquals(param.max, EffectCatalog.value(effect, key), 1e-4f)
    }
}

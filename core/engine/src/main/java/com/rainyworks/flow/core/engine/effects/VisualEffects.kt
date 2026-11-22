package com.rainyworks.flow.core.engine.effects

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.EffectCatalog
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.TrackKind

@OptIn(UnstableApi::class)
object VisualEffects {
    /** Effects attached to a single clip, in the order the user added them. */
    fun forClip(clip: Clip): List<GlEffect> =
        clip.effects.filter { EffectCatalog.find(it.type) != null }.map { VisualEffect(it) }

    /**
     * Effects of EFFECT-track clips. They run on the final frame (composition time equals
     * timeline time) and are active only during their clip.
     */
    /** The clip's visual effects applied to the whole picture during the clip's time. */
    fun forClipWindow(clip: Clip): List<GlEffect> =
        clip.effects.filter { EffectCatalog.find(it.type) != null }.map { VisualEffect(it, clip.startUs, clip.endUs) }

    fun forLayers(project: Project): List<GlEffect> = project.tracks
        .filter { it.kind == TrackKind.EFFECT && !it.hidden }
        .flatMap { track -> track.clips }
        .sortedBy { it.startUs }
        .flatMap { clip ->
            clip.effects.filter { EffectCatalog.find(it.type) != null }
                .map { VisualEffect(it, clip.startUs, clip.endUs) }
        }
}

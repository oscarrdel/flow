package com.rainyworks.flow.core.model

/** A tunable parameter of an effect. Values are stored in [EffectInstance.params] by [key]. */
data class EffectParam(
    val key: String,
    val label: String,
    val min: Float = 0f,
    val max: Float = 1f,
    val default: Float,
)

enum class EffectCategory(val label: String) {
    BLUR("Desenfoque"),
    STYLIZE("Estilo"),
    MOTION("Movimiento"),
    LIGHT("Luz"),
}

data class EffectDefinition(
    val type: String,
    val name: String,
    val category: EffectCategory,
    val params: List<EffectParam>,
) {
    fun defaults(): Map<String, Float> = params.associate { it.key to it.default }
}

/** Visual effects the editor offers; renderers look them up by [EffectDefinition.type]. */
object EffectCatalog {
    private val intensity = EffectParam("intensity", "Intensidad", default = 0.5f)
    private val speed = EffectParam("speed", "Velocidad", default = 0.5f)
    private val size = EffectParam("size", "Tamaño", default = 0.5f)

    val all: List<EffectDefinition> = listOf(
        EffectDefinition("blur.gaussian", "Desenfoque", EffectCategory.BLUR, listOf(intensity)),
        EffectDefinition(
            "blur.motion", "Desenfoque de movimiento", EffectCategory.BLUR,
            listOf(intensity, EffectParam("angle", "Ángulo", 0f, 360f, 0f)),
        ),
        EffectDefinition(
            "pixelate", "Pixelado", EffectCategory.BLUR,
            listOf(size, EffectParam("regionScale", "Área", 0f, 1f, 1f)),
        ),
        EffectDefinition("glitch", "Glitch", EffectCategory.STYLIZE, listOf(intensity, speed)),
        EffectDefinition("chromatic", "Aberración cromática", EffectCategory.STYLIZE, listOf(intensity)),
        EffectDefinition(
            "frame.retro", "Marco retro", EffectCategory.STYLIZE,
            listOf(EffectParam("style", "Estilo", 0f, 2f, 0f), size),
        ),
        EffectDefinition("shake", "Sacudida", EffectCategory.MOTION, listOf(intensity, speed)),
        EffectDefinition("zoom.dynamic", "Zoom dinámico", EffectCategory.MOTION, listOf(intensity, speed)),
        EffectDefinition(
            "flare", "Destello de lente", EffectCategory.LIGHT,
            listOf(intensity, EffectParam("x", "Posición", 0f, 1f, 0.7f)),
        ),
        EffectDefinition("particles", "Partículas", EffectCategory.LIGHT, listOf(intensity, speed, size)),
    )

    private val byType = all.associateBy { it.type }

    fun find(type: String): EffectDefinition? = byType[type]

    /** Value of [key] for [effect], falling back to the definition default. */
    fun value(effect: EffectInstance, key: String): Float =
        effect.params[key] ?: find(effect.type)?.params?.firstOrNull { it.key == key }?.default ?: 0f
}

data class TransitionDefinition(val type: String, val name: String)

object TransitionCatalog {
    val all: List<TransitionDefinition> = listOf(
        TransitionDefinition("cut", "Corte"),
        TransitionDefinition("dissolve", "Disolución"),
        TransitionDefinition("fade.black", "Fundido a negro"),
        TransitionDefinition("fade.white", "Fundido a blanco"),
        TransitionDefinition("slide.left", "Deslizar izquierda"),
        TransitionDefinition("slide.right", "Deslizar derecha"),
        TransitionDefinition("slide.up", "Deslizar arriba"),
        TransitionDefinition("slide.down", "Deslizar abajo"),
        TransitionDefinition("wipe.left", "Barrido izquierda"),
        TransitionDefinition("wipe.right", "Barrido derecha"),
        TransitionDefinition("zoom", "Zoom"),
        TransitionDefinition("spin", "Giro"),
        TransitionDefinition("glitch", "Glitch"),
        TransitionDefinition("flash", "Destello"),
    )

    const val MIN_DURATION_US = 100_000L
    const val MAX_DURATION_US = 3_000_000L

    fun find(type: String): TransitionDefinition? = all.firstOrNull { it.type == type }
}

object EffectEdits {
    /** Adds an effect to a clip (or to an EFFECT-track clip) with default parameters. */
    fun addEffect(project: Project, clipId: String, type: String): Pair<Project, String?> {
        val definition = EffectCatalog.find(type) ?: return project to null
        val effect = EffectInstance(Edits.newId(), definition.type, definition.defaults())
        return Edits.updateClip(project, clipId) { it.copy(effects = it.effects + effect) } to effect.id
    }

    fun setEffectParam(project: Project, clipId: String, effectId: String, key: String, value: Float): Project =
        Edits.updateClip(project, clipId) { clip ->
            clip.copy(
                effects = clip.effects.map { effect ->
                    if (effect.id != effectId) return@map effect
                    val param = EffectCatalog.find(effect.type)?.params?.firstOrNull { it.key == key } ?: return@map effect
                    effect.copy(params = effect.params + (key to value.coerceIn(param.min, param.max)))
                },
            )
        }

    fun removeEffect(project: Project, clipId: String, effectId: String): Project =
        Edits.updateClip(project, clipId) { clip -> clip.copy(effects = clip.effects.filterNot { it.id == effectId }) }

    /** Adds an effect layer: an EFFECT-track clip that applies [type] to everything below it. */
    fun addEffectLayer(project: Project, type: String, atUs: Long, durationUs: Long = 3_000_000L): Pair<Project, String>? {
        val definition = EffectCatalog.find(type) ?: return null
        val clip = Clip(
            id = Edits.newId(),
            kind = ClipKind.EFFECT,
            startUs = atUs.coerceAtLeast(0L),
            trimOutUs = durationUs,
            effects = listOf(EffectInstance(Edits.newId(), definition.type, definition.defaults())),
        )
        return Edits.addToLayer(project, clip, TrackKind.EFFECT) to clip.id
    }

    /**
     * Sets the transition into a main-track clip from the clip before it. The duration is
     * clamped so it never exceeds half of either neighbor; "cut" or null removes it.
     */
    fun setTransition(project: Project, clipId: String, transition: Transition?): Project {
        val main = project.mainTrack
        val index = main.clips.indexOfFirst { it.id == clipId }
        if (index <= 0 || main.locked) return project
        val effective = transition?.takeIf { it.type != "cut" && TransitionCatalog.find(it.type) != null }?.let {
            val limit = minOf(main.clips[index - 1].durationUs, main.clips[index].durationUs) / 2
            it.copy(durationUs = it.durationUs.coerceIn(TransitionCatalog.MIN_DURATION_US, maxOf(TransitionCatalog.MIN_DURATION_US, minOf(limit, TransitionCatalog.MAX_DURATION_US))))
        }
        return Edits.updateClip(project, clipId) { it.copy(transitionIn = effective) }
    }

    /** Applies the same transition to every cut of the main track. */
    fun setTransitionEverywhere(project: Project, transition: Transition?): Project =
        project.mainTrack.clips.drop(1).fold(project) { acc, clip -> setTransition(acc, clip.id, transition) }
}

package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable

/** A visual effect applied to a clip (or, on an EFFECT track, to everything below it). */
@Serializable
data class EffectInstance(
    val id: String,
    /** Catalog id, for example "blur.gaussian", "glitch", "shake". */
    val type: String,
    /** Static parameter values, used where a parameter has no keyframes. */
    val params: Map<String, Float> = emptyMap(),
    /** Animated parameters: keyframes per parameter key, times relative to the clip start. */
    val keyframes: Map<String, List<Keyframe>> = emptyMap(),
)

/** Transition into a main-track clip from the clip before it. */
@Serializable
data class Transition(
    /** Catalog id, for example "dissolve", "fade.black", "slide.left", "zoom", "spin", "glitch". */
    val type: String,
    val durationUs: Long = 500_000L,
)

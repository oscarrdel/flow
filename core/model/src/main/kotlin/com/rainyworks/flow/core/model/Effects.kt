package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable

/** A visual effect applied to a clip (or, on an EFFECT track, to everything below it). */
@Serializable
data class EffectInstance(
    val id: String,
    /** Catalog id, for example "blur.gaussian", "glitch", "shake". */
    val type: String,
    /** Static parameter values; animated ones live in the clip keyframes. */
    val params: Map<String, Float> = emptyMap(),
)

/** Transition into a main-track clip from the clip before it. */
@Serializable
data class Transition(
    /** Catalog id, for example "dissolve", "fade.black", "slide.left", "zoom", "spin", "glitch". */
    val type: String,
    val durationUs: Long = 500_000L,
)

package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable

enum class BlendMode { NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, SOFT_LIGHT, DIFFERENCE }

@Serializable
data class ChromaKey(
    val argb: Long = 0xFF00FF00,
    /** How far from the key color a pixel can be and still be removed (0..1). */
    val tolerance: Float = 0.3f,
    /** Width of the soft transition at the matte edge (0..1). */
    val softness: Float = 0.1f,
    /** Removes the key color reflected onto the subject (0..1). */
    val spillSuppression: Float = 0.5f,
    /** Keeps darker areas of the key color, such as shadows on the backdrop (0..1). */
    val shadows: Float = 0f,
)

enum class MaskShape { SPLIT, CIRCLE, RECTANGLE, HEART, STAR }

@Serializable
data class Mask(
    val shape: MaskShape,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val feather: Float = 0f,
    val inverted: Boolean = false,
    /** Rectangle only: corner roundness 0..1. */
    val cornerRadius: Float = 0f,
)

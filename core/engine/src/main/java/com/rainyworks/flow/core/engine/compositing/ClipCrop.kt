package com.rainyworks.flow.core.engine.compositing

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.CropRect

/**
 * The clip's crop as the first video effect, so the cropped picture is what gets graded,
 * masked and fitted to the canvas, matching the editor's on-canvas frame.
 */
@OptIn(UnstableApi::class)
fun clipCropEffects(clip: Clip): List<Effect> {
    val crop = clip.transform.crop
    if (crop.isFull) return emptyList()
    // CropRect is normalized with y down; Media3 uses normalized device coordinates with y up.
    return listOf(
        Crop(
            /* left = */ 2f * crop.left - 1f,
            /* right = */ 2f * crop.right - 1f,
            /* bottom = */ 1f - 2f * crop.bottom,
            /* top = */ 1f - 2f * crop.top,
        ),
    )
}

private const val FULL_EPSILON = 0.0005f

private val CropRect.isFull: Boolean
    get() = left <= FULL_EPSILON && top <= FULL_EPSILON && right >= 1f - FULL_EPSILON && bottom >= 1f - FULL_EPSILON

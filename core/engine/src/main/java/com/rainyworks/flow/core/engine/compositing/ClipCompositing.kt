package com.rainyworks.flow.core.engine.compositing

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipAnimations
import com.rainyworks.flow.core.model.MediaType

/** [presentationTimeUs] is composition time, including gaps and preceding items. */
@OptIn(UnstableApi::class)
fun animatedOverlaySettings(clip: Clip, presentationTimeUs: Long): OverlaySettings {
    // Keyframes plus entrance/exit animations, evaluated at composition (timeline) time.
    val (t, opacity) = ClipAnimations.renderedTransform(clip, presentationTimeUs)
    return StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor((2f * t.offsetX).coerceIn(-1f, 1f), (-2f * t.offsetY).coerceIn(-1f, 1f))
        .setScale(if (t.flipHorizontal) -t.scale else t.scale, if (t.flipVertical) -t.scale else t.scale)
        .setRotationDegrees(-t.rotationDegrees)
        .setAlphaScale(opacity)
        .build()
}

/** Takes straight RGB after grading and emits premultiplied mattes before Presentation. */
@OptIn(UnstableApi::class)
fun clipMatteEffects(clip: Clip, itemStartPtsUs: Long = clip.startUs): List<GlEffect> = buildList {
    clip.chromaKey?.let { add(ChromaKeyEffect(it)) }
    if (clip.mask != null) add(MaskEffect(clip, clip.chromaKey != null, itemStartPtsUs))
}

/** Convert premultiplied RGBA to straight RGBA for grading or Media3's SRC_ALPHA blend. */
@OptIn(UnstableApi::class)
object StraightAlphaForMedia3 : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : CompositingProgram(useHdr, FRAGMENT_HEADER + """
            void main() {
                vec4 src = texture2D(uInput, vUv);
                gl_FragColor = vec4(src.a > 0.0 ? src.rgb / src.a : vec3(0.0), src.a);
            }
        """) {}
}

@OptIn(UnstableApi::class)
private class AlphaOutput(private val flattenOnBlack: Boolean) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : CompositingProgram(useHdr, FRAGMENT_HEADER + """
            void main() {
                vec4 src = texture2D(uInput, vUv);
                gl_FragColor = ${if (flattenOnBlack) "vec4(src.rgb, 1.0)" else "vec4(src.rgb * src.a, src.a)"};
            }
        """) {}
}

/**
 * Complete visible-item chain for Flow's Bitmap and decoded-video inputs. Bitmap uploads
 * carry premultiplied RGB, while grading and key selection operate on straight color.
 * Presentation resamples premultiplied pixels. A single layer keeps that representation
 * for the canvas fill. The native multi-input compositor expects straight alpha instead.
 */
@OptIn(UnstableApi::class)
fun clipVideoEffects(
    clip: Clip,
    colorEffects: List<Effect>,
    presentation: Presentation,
    hasVideoLayers: Boolean,
): List<Effect> = buildList {
    val bitmap = clip.media?.type == MediaType.IMAGE || clip.media?.type == MediaType.GIF
    val matte = clipMatteEffects(clip)
    if (bitmap && (colorEffects.isNotEmpty() || matte.isNotEmpty())) add(StraightAlphaForMedia3)
    addAll(colorEffects)
    addAll(matte)
    if (bitmap && colorEffects.isNotEmpty() && matte.isEmpty()) add(AlphaOutput(flattenOnBlack = false))
    add(presentation)
    if (hasVideoLayers && (bitmap || matte.isNotEmpty())) add(StraightAlphaForMedia3)
}

/** Keep the input texture size stable at a cut from a picture to a generated video gap. */
@OptIn(UnstableApi::class)
fun EditedMediaItemSequence.Builder.addCanvasGap(durationUs: Long, canvas: Size): EditedMediaItemSequence.Builder {
    val gap = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        .addGap(durationUs).build().editedMediaItems.single()
    val size = Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT)
    return addItem(gap.buildUpon().setEffects(Effects(emptyList(), listOf(size))).build())
}

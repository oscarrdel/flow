package com.rainyworks.flow.core.engine.background

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.rainyworks.flow.core.engine.compositing.CompositingProgram
import com.rainyworks.flow.core.engine.compositing.FRAGMENT_HEADER

/** Replace just the main pictures' fit, preserving the matte and alpha conversion on either side. */
@OptIn(UnstableApi::class)
internal fun EditedMediaItemSequence.withBlurredPresentation(
    canvas: Size,
    radius: Float,
    pictureIds: Set<String>,
): EditedMediaItemSequence {
    val blur = BlurredPresentation(canvas, radius)
    val items = editedMediaItems.map { item ->
        if (item.removeVideo || item.mediaItem.mediaId !in pictureIds) item else {
            val video = item.effects.videoEffects.map { if (it is Presentation) blur else it }
            item.buildUpon().setEffects(Effects(item.effects.audioProcessors, video)).build()
        }
    }
    return EditedMediaItemSequence.Builder(trackTypes).addItems(items).setIsLooping(isLooping).build()
}

/** The compositor moves, rotates and fades the sharp picture and its backdrop together. */
@OptIn(UnstableApi::class)
internal class BlurredPresentation(private val canvas: Size, private val radius: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : CompositingProgram(useHdr, FRAGMENT) {
            override fun configure(program: GlProgram, width: Int, height: Int): Size {
                val ratio = (canvas.width.toFloat() / canvas.height) / (width.toFloat() / height)
                val coverX = minOf(1f, ratio)
                val coverY = minOf(1f, 1f / ratio)
                program.setFloatsUniform("uContain", floatArrayOf(maxOf(1f, ratio), maxOf(1f, 1f / ratio)))
                program.setFloatsUniform("uCover", floatArrayOf(coverX, coverY))
                val stepPx = radius.coerceIn(0f, 1f) * minOf(canvas.width, canvas.height) * 0.008f
                program.setFloatsUniform("uStep", floatArrayOf(stepPx * coverX / canvas.width, stepPx * coverY / canvas.height))
                return canvas
            }
        }
}

private val FRAGMENT = FRAGMENT_HEADER + """
    uniform vec2 uContain, uCover, uStep;
    float weight(int i) {
        if (i == 0) return 20.0;
        if (i == 1 || i == -1) return 15.0;
        if (i == 2 || i == -2) return 6.0;
        return 1.0;
    }
    void main() {
        vec2 centered = vUv - 0.5;
        vec2 uv = 0.5 + centered * uContain;
        vec4 sharp = vec4(0.0);
        if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0))))
            sharp = texture2D(uInput, uv);
        if (sharp.a >= 1.0) { gl_FragColor = sharp; return; }

        vec2 cover = 0.5 + centered * uCover;
        vec4 back = vec4(0.0);
        if (uStep.x == 0.0) {
            back = texture2D(uInput, cover);
        } else {
            for (int y = -3; y <= 3; y++) {
                for (int x = -3; x <= 3; x++) {
                    vec2 p = clamp(cover + vec2(float(x), float(y)) * uStep, 0.0, 1.0);
                    back += (weight(x) * weight(y) / 4096.0) * texture2D(uInput, p);
                }
            }
        }
        gl_FragColor = sharp + (1.0 - sharp.a) * back;
    }
"""

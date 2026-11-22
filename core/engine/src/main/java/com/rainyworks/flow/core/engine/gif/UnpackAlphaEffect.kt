package com.rainyworks.flow.core.engine.gif

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.rainyworks.flow.core.engine.compositing.CompositingProgram
import com.rainyworks.flow.core.engine.compositing.FRAGMENT_HEADER

/**
 * Rebuilds a transparent picture from a [GifAnimationProxy] video: color from the left half,
 * alpha from the right half. The output is premultiplied, like a decoded bitmap, so the rest
 * of the clip's effects treat the animation exactly like a still image.
 */
@OptIn(UnstableApi::class)
internal object UnpackAlphaEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : CompositingProgram(useHdr, FRAGMENT) {
            override fun configure(program: GlProgram, width: Int, height: Int) = Size(width / 2, height)
        }

    private val FRAGMENT = FRAGMENT_HEADER + """
        void main() {
            vec3 color = texture2D(uInput, vec2(vUv.x * 0.5, vUv.y)).rgb;
            // Compression leaves alpha slightly off black and white; GIF alpha is on or off.
            float a = clamp((texture2D(uInput, vec2(0.5 + vUv.x * 0.5, vUv.y)).g - 0.06) / 0.88, 0.0, 1.0);
            gl_FragColor = vec4(color * a, a);
        }
    """
}

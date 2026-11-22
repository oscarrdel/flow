package com.rainyworks.flow.core.engine.effects

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.rainyworks.flow.core.engine.compositing.CompositingProgram
import com.rainyworks.flow.core.engine.compositing.FRAGMENT_HEADER
import com.rainyworks.flow.core.model.EffectCatalog
import com.rainyworks.flow.core.model.EffectInstance

/**
 * One visual effect from [EffectCatalog] as a single-pass shader. Frames whose
 * presentation time falls outside [activeFromUs, activeUntilUs) pass through unchanged,
 * which lets effect layers apply only while their clip is on the timeline.
 */
@OptIn(UnstableApi::class)
class VisualEffect(
    private val effect: EffectInstance,
    private val activeFromUs: Long = Long.MIN_VALUE,
    private val activeUntilUs: Long = Long.MAX_VALUE,
    /** Timeline start of the clip that owns the effect, for its parameter keyframes. */
    private val clipStartUs: Long = 0L,
) : GlEffect {
    private fun param(key: String) = EffectCatalog.value(effect, key)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val body = EffectShaders.body(effect.type)
        val fragment = FRAGMENT_HEADER + EffectShaders.COMMON + """
            uniform vec2 uSize;
            uniform float uTime;
            uniform float uEnabled;
            uniform vec4 uParams;
            vec4 effect(vec2 uv) { $body }
            void main() {
                gl_FragColor = uEnabled > 0.5 ? effect(vUv) : texture2D(uInput, vUv);
            }
        """
        val keys = EffectCatalog.find(effect.type)?.params?.map { it.key }.orEmpty()
        val animated = keys.any { effect.keyframes[it]?.isNotEmpty() == true }
        return object : CompositingProgram(useHdr, fragment) {
            override fun configure(program: GlProgram, width: Int, height: Int): Size {
                program.setIfPresent("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                val values = FloatArray(4) { i -> keys.getOrNull(i)?.let(::param) ?: 0f }
                program.setIfPresent("uParams", values)
                return Size(width, height)
            }

            override fun setFrame(program: GlProgram, presentationTimeUs: Long) {
                val active = presentationTimeUs in activeFromUs until activeUntilUs
                program.setIfPresent("uEnabled", floatArrayOf(if (active) 1f else 0f))
                if (animated) {
                    val relative = presentationTimeUs - clipStartUs
                    program.setIfPresent("uParams", FloatArray(4) { i ->
                        keys.getOrNull(i)?.let { EffectCatalog.valueAt(effect, it, relative) } ?: 0f
                    })
                }
                // Wrap the clock so float precision stays good on long timelines.
                program.setIfPresent("uTime", floatArrayOf(((presentationTimeUs % 3_600_000_000L) / 1_000_000.0).toFloat()))
            }
        }
    }
}

/** GLSL compilers drop unused uniforms, and GlProgram rejects names it cannot find. */
@OptIn(UnstableApi::class)
private fun GlProgram.setIfPresent(name: String, values: FloatArray) {
    if (getUniformLocation(name) < 0) return
    if (values.size == 1) setFloatUniform(name, values[0]) else setFloatsUniform(name, values)
}

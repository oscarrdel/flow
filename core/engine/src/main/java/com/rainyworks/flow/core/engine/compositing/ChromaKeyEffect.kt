package com.rainyworks.flow.core.engine.compositing

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.rainyworks.flow.core.model.ChromaKey
import kotlin.math.abs
import kotlin.math.hypot

/** BT.709 chroma at unit HSV value. Neutral keys also discriminate by luminance. */
internal data class KeyColor(val cb: Float, val cr: Float, val luma: Float, val saturation: Float) {
    fun distance(sample: KeyColor): Float =
        (hypot(cb - sample.cb, cr - sample.cr) * 0.70710678f +
            (1f - saturation) * abs(luma - sample.luma)).coerceAtMost(1f)

    companion object {
        fun fromRgb(r: Float, g: Float, b: Float): KeyColor {
            val value = maxOf(r, g, b).coerceAtLeast(0.00001f)
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            return KeyColor((b - y) / (1.8556f * value), (r - y) / (1.5748f * value), y,
                (maxOf(r, g, b) - minOf(r, g, b)) / value)
        }
    }
}

/** Takes straight SDR RGB and alpha, and emits premultiplied RGBA. */
@OptIn(UnstableApi::class)
class ChromaKeyEffect(private val key: ChromaKey) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : CompositingProgram(useHdr, FRAGMENT_HEADER + CHROMA) {
            override fun configure(program: GlProgram, width: Int, height: Int): Size {
                fun channel(shift: Int) = ((key.argb ushr shift) and 255L) / 255f
                val c = KeyColor.fromRgb(channel(16), channel(8), channel(0))
                program.setFloatsUniform("uKey", floatArrayOf(c.cb, c.cr, c.luma, c.saturation))
                program.setFloatsUniform("uControls", floatArrayOf(key.tolerance.coerceIn(0f, 1f),
                    key.softness.coerceIn(0f, 1f), key.spillSuppression.coerceIn(0f, 1f), key.shadows.coerceIn(0f, 1f)))
                return Size(width, height)
            }
        }
}

private const val CHROMA = """
    uniform vec4 uKey;
    uniform vec4 uControls;

    void main() {
        vec4 src = texture2D(uInput, vUv);
        float y = dot(src.rgb, vec3(0.2126, 0.7152, 0.0722));
        float value = max(max(src.r, src.g), max(src.b, 0.00001));
        vec2 c = (src.br - y) / (vec2(1.8556, 1.5748) * value);
        float d = min(1.0, length(c - uKey.xy) * 0.70710678 + (1.0 - uKey.w) * abs(y - uKey.z));
        float edge = d - uControls.x;
        float matte = uControls.y > 0.0 ? smoothstep(0.0, uControls.y, edge) : float(edge > 0.000001);

        float vicinity = 1.0 - smoothstep(0.0, uControls.y + 0.16, edge);
        float alignment = clamp(dot(c, uKey.xy) / max(dot(uKey.xy, uKey.xy), 0.00001), 0.0, 1.0);
        vec3 subject = mix(src.rgb, vec3(y), uControls.z * vicinity * alignment);
        float darkness = clamp(1.0 - y / max(uKey.z, 0.00001), 0.0, 1.0);
        float shadow = (1.0 - matte) * darkness * uControls.w;
        // A neutral black shadow contributes coverage only.
        gl_FragColor = vec4(subject * (src.a * matte), src.a * (matte + shadow));
    }
"""

package com.rainyworks.flow.core.engine.compositing

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.rainyworks.flow.core.model.AnimatedProperty
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.MaskShape
import com.rainyworks.flow.core.model.animatedValue
import kotlin.math.cos
import kotlin.math.sin

/**
 * Emits premultiplied RGBA. Set [inputPremultiplied] after [ChromaKeyEffect].
 * [itemStartPtsUs] is the effect clock's timestamp at the start of the item. A standalone
 * item uses zero. Flow's sequenced Media3 graph registers streams with a composition offset.
 */
@OptIn(UnstableApi::class)
class MaskEffect(
    private val clip: Clip,
    private val inputPremultiplied: Boolean = false,
    private val itemStartPtsUs: Long = 0L,
) : GlEffect {
    private val mask = requireNotNull(clip.mask)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val distance = when (mask.shape) {
            MaskShape.SPLIT -> "return p.y;"
            MaskShape.CIRCLE -> "return length(p / uAspect) - 1.0;"
            MaskShape.RECTANGLE -> """
                vec2 halfSize = (0.30 / 0.35) * uAspect;
                float r = min(halfSize.x, halfSize.y) * uCorner;
                vec2 d = abs(p) - halfSize + r;
                return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
            """
            MaskShape.HEART -> HEART
            MaskShape.STAR -> STAR
        }
        val fragment = FRAGMENT_HEADER + """
            uniform vec2 uSize;
            uniform vec2 uAspect;
            uniform vec2 uCenter;
            uniform vec2 uRotation;
            uniform float uRadius;
            uniform float uEdge;
            uniform float uCorner;
            float distanceToShape(vec2 p) { $distance }
            void main() {
                vec2 d = (vec2(vUv.x, 1.0 - vUv.y) - uCenter) * uSize;
                vec2 p = vec2(dot(d, uRotation), dot(d, vec2(-uRotation.y, uRotation.x))) / uRadius;
                float coverage = 1.0 - smoothstep(-uEdge, uEdge, distanceToShape(p));
                ${if (mask.inverted) "coverage = 1.0 - coverage;" else ""}
                vec4 src = texture2D(uInput, vUv);
                ${if (inputPremultiplied) "" else "src.rgb *= src.a;"}
                gl_FragColor = src * coverage;
            }
        """
        return object : CompositingProgram(useHdr, fragment) {
            private var baseRadius = 1f
            override fun configure(program: GlProgram, width: Int, height: Int): Size {
                val side = minOf(width, height).toFloat()
                baseRadius = 0.35f * side
                program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                if (mask.shape == MaskShape.CIRCLE || mask.shape == MaskShape.RECTANGLE) {
                    program.setFloatsUniform("uAspect", floatArrayOf(width / side, height / side))
                }
                if (mask.shape == MaskShape.RECTANGLE) {
                    program.setFloatUniform("uCorner", mask.cornerRadius.coerceIn(0f, 1f))
                }
                return Size(width, height)
            }

            override fun setFrame(program: GlProgram, presentationTimeUs: Long) {
                val timelineUs = clip.startUs + (presentationTimeUs - itemStartPtsUs)
                fun value(key: String, default: Float) = clip.animatedValue(key, timelineUs, default)
                val x = value(AnimatedProperty.MASK_OFFSET_X, mask.offsetX)
                val y = value(AnimatedProperty.MASK_OFFSET_Y, mask.offsetY)
                val radians = Math.toRadians(value(AnimatedProperty.MASK_ROTATION, mask.rotationDegrees).toDouble())
                val radius = (baseRadius * value(AnimatedProperty.MASK_SCALE, mask.scale)).coerceAtLeast(0.00001f)
                val feather = value(AnimatedProperty.MASK_FEATHER, mask.feather).coerceIn(0f, 1f)
                program.setFloatsUniform("uCenter", floatArrayOf(0.5f + x, 0.5f + y))
                program.setFloatsUniform("uRotation", floatArrayOf(cos(radians).toFloat(), sin(radians).toFloat()))
                program.setFloatUniform("uRadius", radius)
                program.setFloatUniform("uEdge", maxOf(0.5f / radius, 0.6f * feather))
            }
        }
    }
}

// Fold into one 36-degree sector. The nearest edge runs from a tip to the adjacent valley.
private const val STAR = """
    float angle = abs(mod(atan(p.x, -p.y + 1e-10) + 0.628318531, 1.256637061) - 0.628318531);
    vec2 q = length(p) * vec2(sin(angle), cos(angle)) - vec2(0.0, 1.0);
    vec2 edge = vec2(0.258625511, -0.644032522);
    vec2 delta = q - edge * clamp(dot(q, edge) / dot(edge, edge), 0.0, 1.0);
    return length(delta) * sign(q.x * (-edge.y) + q.y * edge.x);
"""

// Each half has a circular lobe of radius 1/2, tangent to a 45-degree side.
private const val HEART = """
    vec2 q = vec2(abs(p.x) - 0.5, p.y + 0.407106781);
    if (q.y <= 0.0 || q.y <= q.x) return length(q) - 0.5;
    vec2 fromTip = vec2(abs(p.x), p.y - 0.8);
    float along = clamp(0.5 * (fromTip.x - fromTip.y), 0.0, 0.853553391);
    float side = length(fromTip - vec2(along, -along));
    float notch = length(q + vec2(0.5, 0.0));
    return min(side, notch) * sign(fromTip.x + fromTip.y);
"""

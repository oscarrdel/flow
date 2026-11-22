package com.rainyworks.flow.core.engine.color

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.rainyworks.flow.core.model.HslRange
import java.nio.ByteBuffer
import kotlin.math.pow

/** Controls, luma masks and curves operate on Flow's display-referred SDR RGB. */
@OptIn(UnstableApi::class)
internal class GradeShader(private val grade: ResolvedGrade) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int) = grade.isIdentity

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        GradeProgram(grade, useHdr)
}

@OptIn(UnstableApi::class)
private class GradeProgram(private val grade: ResolvedGrade, useHdr: Boolean) :
    ColorProgram(useHdr, gradeFragment(grade)) {

    override fun initialize(program: GlProgram) {
        val a = grade.adjustments
        // Approximate +/-2 stops in gamma 2.2 RGB. Contrast pivots at display mid-gray.
        val gain = 2.0.pow(a.exposure * 2.0 / 2.2).toFloat()
        program.setFloatsUniform("uTone", floatArrayOf(gain, a.brightness * 0.25f, a.contrast, a.saturation))
        program.setFloatsUniform("uBalance", floatArrayOf(a.highlights, a.shadows, a.temperature, a.tint))
        program.setFloatsUniform("uFinish", floatArrayOf(a.fade, a.vignette, a.grain, a.sharpness))
        for ((range, hsl) in grade.hsl) {
            program.setFloatsUniform("u${range.name}", floatArrayOf(hsl.hue, hsl.saturation, hsl.luminance))
        }
        grade.curves?.let { rgba ->
            bindLookup(program, GLES20.GL_TEXTURE_2D) {
                val pixels = ByteBuffer.allocateDirect(rgba.size).apply { put(rgba).flip() }
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            }
        }
    }

    override fun setSize(program: GlProgram, width: Int, height: Int) {
        program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
    }

    override fun setTime(program: GlProgram, presentationTimeUs: Long) {
        if (grade.adjustments.grain == 0f) return
        // Integer avalanche before conversion keeps nearby timestamps distinct on long clips.
        var hash = presentationTimeUs.hashCode()
        hash = (hash xor (hash ushr 16)) * 0x45d9f3b
        hash = (hash xor (hash ushr 16)) * 0x45d9f3b
        program.setFloatUniform("uSeed", (hash ushr 8) / 16777216f)
    }
}

// Centers in degrees. Each range fades to zero at the center of each adjacent range.
private val hueCenters = mapOf(
    HslRange.RED to 0, HslRange.ORANGE to 30, HslRange.YELLOW to 60, HslRange.GREEN to 120,
    HslRange.CYAN to 180, HslRange.BLUE to 240, HslRange.PURPLE to 270, HslRange.MAGENTA to 300,
)

private fun gradeFragment(grade: ResolvedGrade): String {
    val ranges = grade.hsl.keys
    val declarations = ranges.joinToString("\n") { "uniform vec3 u${it.name};" }
    val centers = hueCenters.values.toList()
    val weights = ranges.joinToString("\n") { range ->
        val center = hueCenters.getValue(range)
        val i = centers.indexOf(center)
        val left = (center - centers[(i + centers.lastIndex) % centers.size] + 360) % 360
        val right = (centers[(i + 1) % centers.size] - center + 360) % 360
        "delta += weight(h, $center.0, $left.0, $right.0) * u${range.name};"
    }
    return """
        precision highp float;
        uniform highp sampler2D uInput;
        varying highp vec2 vUv;
        uniform vec4 uTone;
        uniform vec4 uBalance;
        uniform vec4 uFinish;
        uniform vec2 uSize;
        uniform float uSeed;
        const vec3 luma = vec3(0.2126, 0.7152, 0.0722);

        ${if (grade.curves != null) "#define CURVES" else ""}
        #ifdef CURVES
        uniform highp sampler2D uLookup;
        vec4 curve(float v) {
            return texture2D(uLookup, vec2((clamp(v, 0.0, 1.0) * 255.0 + 0.5) / 256.0, 0.5));
        }
        #endif

        ${if (ranges.isNotEmpty()) "#define HSL" else ""}
        #ifdef HSL
        $declarations
        float weight(float h, float center, float left, float right) {
            float d = mod(h - center + 180.0, 360.0) - 180.0;
            return 1.0 - smoothstep(0.0, d < 0.0 ? left : right, abs(d));
        }

        vec3 adjustHsl(vec3 rgb) {
            vec3 unit = clamp(rgb, 0.0, 1.0);
            float hi = max(max(unit.r, unit.g), unit.b);
            float lo = min(min(unit.r, unit.g), unit.b);
            float chroma = hi - lo;
            if (chroma == 0.0) return rgb;

            float h = hi == unit.r ? (unit.g - unit.b) / chroma :
                     (hi == unit.g ? 2.0 + (unit.b - unit.r) / chroma :
                                     4.0 + (unit.r - unit.g) / chroma);
            h *= 60.0;
            vec3 delta = vec3(0.0);
            $weights
            if (all(equal(delta, vec3(0.0)))) return rgb;
            delta *= smoothstep(0.0, 0.03, chroma);

            float light = (hi + lo) * 0.5;
            float sat = chroma / max(1.0 - abs(2.0 * light - 1.0), 1.e-7);
            sat = clamp(sat + delta.y, 0.0, 1.0);
            light = clamp(light + 0.5 * delta.z, 0.0, 1.0);
            float hue = (h + 30.0 * delta.x) / 360.0;
            vec3 triangle = abs(fract(hue + vec3(0.0, 2.0/3.0, 1.0/3.0)) * 6.0 - 3.0);
            vec3 hueRgb = clamp(triangle - 1.0, 0.0, 1.0);
            return light + (hueRgb - 0.5) * (1.0 - abs(2.0 * light - 1.0)) * sat;
        }
        #endif

        float grain(vec2 pixel) {
            vec3 p = fract(vec3(pixel, uSeed * 8191.0) * vec3(0.1031, 0.1030, 0.0973));
            p += dot(p, p.yzx + 33.33);
            return fract((p.x + p.y) * p.z) - 0.5;
        }

        vec4 graded(vec2 uv) {
            vec2 halfPixel = 0.5 / uSize;
            uv = clamp(uv, halfPixel, 1.0 - halfPixel);
            vec4 src = texture2D(uInput, uv);
            vec3 c = src.rgb * uTone.x + uTone.y;
            c += (c - 0.5) * uTone.z;
            float y = dot(c, luma);
            c += 0.35 * (uBalance.x * smoothstep(0.35, 1.0, y) +
                         uBalance.y * (1.0 - smoothstep(0.0, 0.65, y)));
            c *= 1.0 + uBalance.z * vec3(0.20, 0.0, -0.20) +
                       uBalance.w * vec3(0.08, -0.16, 0.08);
            c += (c - dot(c, luma)) * uTone.w;
            #ifdef HSL
            c = adjustHsl(c);
            #endif
            #ifdef CURVES
            vec3 master = vec3(curve(c.r).a, curve(c.g).a, curve(c.b).a);
            c = vec3(curve(master.r).r, curve(master.g).g, curve(master.b).b);
            #endif
            c += (1.0 - c) * (0.22 * uFinish.x);
            float radius = length((2.0 * uv - 1.0) * uSize) / length(uSize);
            c *= 1.0 - 0.85 * uFinish.y * smoothstep(0.35, 1.0, radius);
            if (uFinish.z > 0.0) c += grain(floor(uv * uSize)) * (0.14 * uFinish.z);
            return vec4(c, src.a);
        }

        void main() {
            vec4 center = graded(vUv);
            if (uFinish.w != 0.0) {
                // The blur reads the same preceding stages, including grain, at each neighbor.
                vec2 dx = vec2(1.0 / uSize.x, 0.0);
                vec2 dy = vec2(0.0, 1.0 / uSize.y);
                vec3 neighbors = graded(vUv - dx).rgb + graded(vUv + dx).rgb +
                                 graded(vUv - dy).rgb + graded(vUv + dy).rgb;
                center.rgb += uFinish.w * (center.rgb - 0.25 * neighbors);
            }
            gl_FragColor = center;
        }
    """.trimIndent()
}

package com.rainyworks.flow.core.engine.color

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.rainyworks.flow.core.model.CubeLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
internal class CubeEffect(
    private val lut: CubeLut,
    private val intensity: Float,
    private val contextMajorVersion: () -> Long = GlUtil::getContextMajorVersion,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        try {
            val es3 = contextMajorVersion() >= 3
            val layout = when {
                !lut.is3d -> {
                    val width = minOf(lut.size, glLimit(GLES20.GL_MAX_TEXTURE_SIZE))
                    Layout(width, (lut.size + width - 1) / width)
                }
                es3 && lut.size <= glLimit(GLES30.GL_MAX_3D_TEXTURE_SIZE) ->
                    Layout(lut.size, lut.size, depth = lut.size)
                else -> {
                    val tiles = ceil(sqrt(lut.size.toDouble())).toInt()
                    val width = tiles * lut.size
                    require(width <= glLimit(GLES20.GL_MAX_TEXTURE_SIZE)) { "La LUT excede el tamaño de textura 2D" }
                    Layout(width, ((lut.size + tiles - 1) / tiles) * lut.size, tiles = tiles)
                }
            }
            return CubeProgram(lut, intensity, layout, es3, useHdr)
        } catch (e: Exception) {
            throw VideoFrameProcessingException.from(e)
        }
    }

    private fun glLimit(name: Int): Int {
        val limit = IntArray(1)
        GLES20.glGetIntegerv(name, limit, 0)
        GlUtil.checkGlError()
        return limit.single()
    }
}

private data class Layout(val width: Int, val height: Int, val depth: Int = 1, val tiles: Int = 0) {
    val volume get() = depth > 1
}

@OptIn(UnstableApi::class)
private class CubeProgram(
    private val lut: CubeLut,
    private val intensity: Float,
    private val layout: Layout,
    private val es3: Boolean,
    useHdr: Boolean,
) : ColorProgram(useHdr, cubeFragment(layout), layout.volume) {

    override fun initialize(program: GlProgram) {
        program.setFloatUniform("uIntensity", intensity)
        program.setFloatUniform("uSize", lut.size.toFloat())
        program.setFloatsUniform("uDomainMin", lut.domainMin)
        program.setFloatsUniform("uDomainMax", lut.domainMax)
        if (!layout.volume) {
            program.setFloatsUniform("uDimensions", floatArrayOf(layout.width.toFloat(), layout.height.toFloat()))
            if (lut.is3d) program.setFloatUniform("uTiles", layout.tiles.toFloat())
        }
        val target = if (layout.volume) GLES30.GL_TEXTURE_3D else GLES20.GL_TEXTURE_2D
        val offset = FloatArray(3)
        val scale = FloatArray(3) { 1f }
        bindLookup(program, target) {
            if (es3) uploadFloat(target) else uploadBytes(target, offset, scale)
        }
        program.setFloatsUniform("uOffset", offset)
        program.setFloatsUniform("uScale", scale)
    }

    // Source and native volumes both have R changing fastest. Only atlas rows need rearranging.
    private inline fun copyRuns(copy: (srcEntry: Int, dstEntry: Int, count: Int) -> Unit) {
        val n = lut.size
        if (layout.tiles == 0) {
            copy(0, 0, lut.data.size / 3)
        } else {
            for (b in 0 until n) for (g in 0 until n) {
                val x = (b % layout.tiles) * n
                val y = (b / layout.tiles) * n + g
                copy((b * n + g) * n, y * layout.width + x, n)
            }
        }
    }

    private fun uploadFloat(target: Int) {
        val pixels = ByteBuffer.allocateDirect(layout.width * layout.height * layout.depth * 12)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        copyRuns { src, dst, count ->
            pixels.position(dst * 3)
            pixels.put(lut.data, src * 3, count * 3)
        }
        pixels.rewind()
        if (layout.volume) {
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage3D(target, 0, GLES30.GL_RGB16F, layout.width, layout.height, layout.depth,
                0, GLES20.GL_RGB, GLES20.GL_FLOAT, pixels)
        } else {
            GLES20.glTexImage2D(target, 0, GLES30.GL_RGB16F, layout.width, layout.height, 0, GLES20.GL_RGB,
                GLES20.GL_FLOAT, pixels)
        }
    }

    // GLES2 guarantees linear filtering of RGBA8. Encode the actual output range so negative
    // and above-white entries survive interpolation and the subsequent intensity mix.
    private fun uploadBytes(target: Int, offset: FloatArray, scale: FloatArray) {
        for (c in 0..2) {
            var lo = lut.data[c]
            var hi = lo
            for (i in c until lut.data.size step 3) {
                lo = minOf(lo, lut.data[i])
                hi = maxOf(hi, lut.data[i])
            }
            offset[c] = lo
            scale[c] = hi - lo
        }
        val pixels = ByteBuffer.allocateDirect(layout.width * layout.height * 4)
        copyRuns { src, dst, count ->
            pixels.position(dst * 4)
            for (i in src until src + count) {
                for (c in 0..2) {
                    val v = if (scale[c] == 0f) 0f else (lut.data[i * 3 + c] - offset[c]) / scale[c]
                    pixels.put((v * 255f).roundToInt().toByte())
                }
                pixels.put(0xff.toByte())
            }
        }
        pixels.rewind()
        GLES20.glTexImage2D(target, 0, GLES20.GL_RGBA, layout.width, layout.height, 0, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, pixels)
    }
}

private fun cubeFragment(layout: Layout): String {
    val lookup = when {
        layout.volume -> """
            uniform highp sampler3D uLookup;
            vec3 lookup(vec3 pos) {
                return texture(uLookup, (pos + 0.5) / uSize).rgb;
            }
        """
        layout.tiles > 0 -> """
            uniform highp sampler2D uLookup;
            uniform vec2 uDimensions;
            uniform float uTiles;
            vec3 slice(vec2 rg, float b) {
                vec2 tile = vec2(mod(b, uTiles), floor(b / uTiles));
                return texture2D(uLookup, (tile * uSize + rg + 0.5) / uDimensions).rgb;
            }
            vec3 lookup(vec3 pos) {
                float b = floor(pos.b);
                return mix(slice(pos.rg, b), slice(pos.rg, min(b + 1.0, uSize - 1.0)), fract(pos.b));
            }
        """
        else -> """
            uniform highp sampler2D uLookup;
            uniform vec2 uDimensions;
            vec3 entry(float index) {
                vec2 xy = vec2(mod(index, uDimensions.x), floor(index / uDimensions.x));
                return texture2D(uLookup, (xy + 0.5) / uDimensions).rgb;
            }
            vec3 lookup(vec3 pos) {
                vec3 lo = floor(pos);
                vec3 hi = min(lo + 1.0, uSize - 1.0);
                vec3 a = vec3(entry(lo.r).r, entry(lo.g).g, entry(lo.b).b);
                vec3 b = vec3(entry(hi.r).r, entry(hi.g).g, entry(hi.b).b);
                return mix(a, b, fract(pos));
            }
        """
    }
    val header = if (layout.volume) "#version 300 es" else "#version 100"
    return header + "\n" + """
        precision highp float;
        uniform highp sampler2D uInput;
        uniform float uSize;
        uniform float uIntensity;
        uniform vec3 uDomainMin;
        uniform vec3 uDomainMax;
        uniform vec3 uOffset;
        uniform vec3 uScale;
        ${if (layout.volume) "in highp vec2 vUv;\nout vec4 fragColor;" else "varying highp vec2 vUv;"}
        $lookup
        void main() {
            vec4 src = ${if (layout.volume) "texture" else "texture2D"}(uInput, vUv);
            vec3 unit = clamp((src.rgb - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0);
            vec3 mapped = lookup(unit * (uSize - 1.0)) * uScale + uOffset;
            ${if (layout.volume) "fragColor" else "gl_FragColor"} = vec4(mix(src.rgb, mapped, uIntensity), src.a);
        }
    """.trimIndent()
}

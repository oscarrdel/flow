package com.rainyworks.flow.core.engine.background

import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.rainyworks.flow.core.engine.color.ColorProgram
import com.rainyworks.flow.core.engine.compositing.FRAGMENT_HEADER
import com.rainyworks.flow.core.model.CanvasBackground
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Fills behind the premultiplied layer mix. The SDR output surface is opaque. */
@OptIn(UnstableApi::class)
internal class CanvasFill(
    private val background: CanvasBackground,
    private val canvas: Size,
    private val pictureAt: ((Long) -> Boolean)? = null,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val fill = when (background) {
            is CanvasBackground.Solid -> "uniform vec3 uColor; vec3 fill() { return uColor; }"
            is CanvasBackground.Gradient -> """
                uniform vec3 uStart, uEnd;
                uniform vec2 uDirection;
                vec3 fill() { return mix(uStart, uEnd, clamp(0.5 + dot(vUv - 0.5, uDirection), 0.0, 1.0)); }
            """
            is CanvasBackground.Image -> """
                uniform sampler2D uLookup;
                vec3 fill() { return texture2D(uLookup, vec2(vUv.x, 1.0 - vUv.y)).rgb; }
            """
            is CanvasBackground.Blur -> "vec3 fill() { return vec3(0.0); }"
        }
        val fragment = FRAGMENT_HEADER + fill + """
            uniform float uPicture;
            void main() {
                vec4 src = texture2D(uInput, vUv) * uPicture;
                gl_FragColor = vec4(src.rgb + (1.0 - src.a) * fill(), 1.0);
            }
        """
        return object : ColorProgram(useHdr, fragment) {
            override fun initialize(program: GlProgram) {
                when (background) {
                    is CanvasBackground.Solid -> program.setFloatsUniform("uColor", rgbOnBlack(background.argb))
                    is CanvasBackground.Gradient -> {
                        val angle = Math.toRadians(background.angleDegrees.toDouble())
                        // Canvas angles increase clockwise from left-to-right; GL's Y axis points up.
                        val x = cos(angle).toFloat() * canvas.width
                        val y = -sin(angle).toFloat() * canvas.height
                        val span = abs(x) + abs(y)
                        program.setFloatsUniform("uDirection", floatArrayOf(x / span, y / span))
                        program.setFloatsUniform("uStart", rgbOnBlack(background.startArgb))
                        program.setFloatsUniform("uEnd", rgbOnBlack(background.endArgb))
                    }
                    is CanvasBackground.Image -> {
                        val bitmap = decodeCover(context, background.uri, canvas)
                        try {
                            bindLookup(program, GLES20.GL_TEXTURE_2D) {
                                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    is CanvasBackground.Blur -> Unit
                }
                program.setFloatUniform("uPicture", 1f)
            }

            override fun setTime(program: GlProgram, presentationTimeUs: Long) {
                pictureAt?.let { program.setFloatUniform("uPicture", if (it(presentationTimeUs)) 1f else 0f) }
            }
        }
    }
}

private fun rgbOnBlack(argb: Long): FloatArray {
    val alpha = ((argb ushr 24) and 255) / 255f
    return floatArrayOf(((argb ushr 16) and 255) / 255f * alpha,
        ((argb ushr 8) and 255) / 255f * alpha, (argb and 255) / 255f * alpha)
}

@OptIn(UnstableApi::class)
private fun decodeCover(context: Context, uri: String, canvas: Size) =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
        val scale = maxOf(canvas.width.toDouble() / info.size.width, canvas.height.toDouble() / info.size.height)
        val width = ceil(info.size.width * scale).toInt()
        val height = ceil(info.size.height * scale).toInt()
        val left = (width - canvas.width) / 2
        val top = (height - canvas.height) / 2
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setTargetSize(width, height)
        decoder.crop = Rect(left, top, left + canvas.width, top + canvas.height)
    }

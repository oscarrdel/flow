package com.rainyworks.flow.core.engine.compositing

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

@OptIn(UnstableApi::class)
internal abstract class CompositingProgram(useHdr: Boolean, private val fragment: String) :
    BaseGlShaderProgram(useHdr, 1) {
    private var program: GlProgram? = null

    protected open fun configure(program: GlProgram, width: Int, height: Int) = Size(width, height)
    protected open fun setFrame(program: GlProgram, presentationTimeUs: Long) = Unit

    final override fun configure(inputWidth: Int, inputHeight: Int): Size {
        try {
            val shader = program ?: GlProgram(VERTEX, fragment).also {
                program = it
                it.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
            return configure(shader, inputWidth, inputHeight)
        } catch (e: Exception) {
            throw VideoFrameProcessingException.from(e)
        }
    }

    final override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val shader = checkNotNull(program)
            shader.use()
            shader.setSamplerTexIdUniform("uInput", inputTexId, 0)
            setFrame(shader, presentationTimeUs)
            shader.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: Exception) {
            throw VideoFrameProcessingException.from(e, presentationTimeUs)
        }
    }

    final override fun release() {
        try {
            program?.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e)
        } finally {
            program = null
            super.release()
        }
    }
}

internal const val FRAGMENT_HEADER = """
    precision highp float;
    uniform sampler2D uInput;
    varying highp vec2 vUv;
"""

private const val VERTEX = """
    attribute vec4 aPosition;
    varying highp vec2 vUv;
    void main() {
        gl_Position = aPosition;
        vUv = 0.5 * aPosition.xy + 0.5;
    }
"""

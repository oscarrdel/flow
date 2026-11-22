package com.rainyworks.flow.core.engine.color

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/** A full-frame draw. Each Media3 program owns its lookup texture in its own GL context. */
@OptIn(UnstableApi::class)
internal abstract class ColorProgram(
    useHdr: Boolean,
    private val fragment: String,
    private val es3Shader: Boolean = false,
) : BaseGlShaderProgram(useHdr, 1) {
    private var program: GlProgram? = null
    private var lookupId = 0

    protected abstract fun initialize(program: GlProgram)
    protected open fun setSize(program: GlProgram, width: Int, height: Int) = Unit
    protected open fun setTime(program: GlProgram, presentationTimeUs: Long) = Unit

    protected fun bindLookup(program: GlProgram, target: Int, upload: () -> Unit) {
        lookupId = GlUtil.generateTexture()
        GlUtil.bindTexture(target, lookupId, GLES20.GL_LINEAR)
        upload()
        GlUtil.checkGlError()
        program.setSamplerTexIdUniform("uLookup", lookupId, 1)
    }

    final override fun configure(inputWidth: Int, inputHeight: Int): Size {
        try {
            val current = program ?: GlProgram(vertexSource(es3Shader), fragment).also {
                program = it
                it.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
                initialize(it)
            }
            setSize(current, inputWidth, inputHeight)
            return Size(inputWidth, inputHeight)
        } catch (e: Exception) {
            try {
                release()
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw VideoFrameProcessingException.from(e)
        }
    }

    final override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val current = checkNotNull(program)
            current.use()
            current.setSamplerTexIdUniform("uInput", inputTexId, 0)
            setTime(current, presentationTimeUs)
            current.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: Exception) {
            throw VideoFrameProcessingException.from(e, presentationTimeUs)
        }
    }

    final override fun release() {
        try {
            try {
                if (lookupId != 0) GlUtil.deleteTexture(lookupId)
            } finally {
                lookupId = 0
                program?.delete()
                program = null
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e)
        } finally {
            super.release()
        }
    }
}

private fun vertexSource(es3: Boolean) = """
    ${if (es3) "#version 300 es" else "#version 100"}
    ${if (es3) "in" else "attribute"} vec4 aPosition;
    ${if (es3) "out" else "varying"} highp vec2 vUv;

    void main() {
        gl_Position = aPosition;
        vUv = aPosition.xy * 0.5 + 0.5;
    }
""".trimIndent()

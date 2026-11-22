package com.rainyworks.flow.core.engine.compositing

import android.opengl.EGL14
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals

internal class Pixels(val width: Int, val height: Int, val rgba: ByteArray) {
    fun value(x: Int, y: Int, channel: Int) = rgba[4 * (y * width + x) + channel].toInt() and 255
}

@OptIn(UnstableApi::class)
internal fun withGl(block: () -> Unit) {
    val display = GlUtil.getDefaultEglDisplay()
    val context = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
    try {
        val surface = GlUtil.createFocusedPlaceholderEglSurface(context, display)
        try {
            block()
        } finally {
            GlUtil.destroyEglSurface(display, surface)
        }
    } finally {
        GlUtil.destroyEglContext(display, context)
        GlUtil.terminate(display)
    }
}

/** Reuses the same effect program for all timestamps, like the frame processor does. */
@OptIn(UnstableApi::class)
internal fun frames(effect: GlEffect, input: Pixels, vararg timesUs: Long): List<Pixels> {
    val texture = GlUtil.createTexture(input.width, input.height, false)
    try {
        GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, texture, GLES20.GL_LINEAR)
        val upload = ByteBuffer.allocateDirect(input.rgba.size).apply { put(input.rgba).flip() }
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, input.width, input.height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, upload)
        val program = effect.toGlShaderProgram(InstrumentationRegistry.getInstrumentation().targetContext, false)
        try {
            var output: GlTextureInfo? = null
            var outputTime = C.TIME_UNSET
            program.setErrorListener({ it.run() }) { throw AssertionError("Shader failed", it) }
            program.setOutputListener(object : GlShaderProgram.OutputListener {
                override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                    output = outputTexture
                    outputTime = presentationTimeUs
                }
            })
            val objects = DefaultGlObjectsProvider()
            val info = GlTextureInfo(texture, C.INDEX_UNSET, C.INDEX_UNSET, input.width, input.height)
            return timesUs.map { time ->
                output = null
                program.queueInputFrame(objects, info, time)
                val frame = checkNotNull(output)
                assertEquals(time, outputTime)
                GlUtil.focusFramebufferUsingCurrentContext(frame.fboId, frame.width, frame.height)
                val readback = ByteBuffer.allocateDirect(frame.width * frame.height * 4)
                GLES20.glReadPixels(0, 0, frame.width, frame.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
                GlUtil.checkGlError()
                val pixels = Pixels(frame.width, frame.height, ByteArray(readback.capacity()).also { readback.get(it) })
                program.releaseOutputFrame(frame)
                pixels
            }
        } finally {
            program.release()
        }
    } finally {
        GlUtil.deleteTexture(texture)
    }
}

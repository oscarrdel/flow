package com.rainyworks.flow.core.engine.adjustment

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.rainyworks.flow.core.engine.color.ColorProgram
import com.rainyworks.flow.core.engine.compositing.CompositingProgram
import com.rainyworks.flow.core.engine.compositing.FRAGMENT_HEADER

/** Both the grade and LUT are size-preserving, single-pass ColorPrograms. */
@OptIn(UnstableApi::class)
internal class TimedColorEffect(
    private val effect: GlEffect,
    private val activeFromUs: Long,
    private val activeUntilUs: Long,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val grade = effect.toGlShaderProgram(context, useHdr) as ColorProgram
        val copy = object : CompositingProgram(useHdr, FRAGMENT_HEADER + """
            void main() { gl_FragColor = texture2D(uInput, vUv); }
        """) {}
        // Only this outer program queues frames and owns output textures. Both draws
        // target its current framebuffer and keep the original composition timestamp.
        return object : BaseGlShaderProgram(useHdr, 1) {
            override fun configure(inputWidth: Int, inputHeight: Int) =
                grade.configure(inputWidth, inputHeight).also { copy.configure(inputWidth, inputHeight) }

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                val program = if (presentationTimeUs in activeFromUs until activeUntilUs) grade else copy
                program.drawFrame(inputTexId, presentationTimeUs)
            }

            override fun release() {
                try {
                    try { grade.release() } finally { copy.release() }
                } finally {
                    super.release()
                }
            }
        }
    }
}

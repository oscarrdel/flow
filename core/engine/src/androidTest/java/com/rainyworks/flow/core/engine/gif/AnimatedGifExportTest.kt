package com.rainyworks.flow.core.engine.gif

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.engine.MediaProbe
import com.rainyworks.flow.core.gif.GifEncoder
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.CanvasBackground
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.ProjectFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class AnimatedGifExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Frame 1 (0.5 s) fully transparent, frame 2 (0.5 s) blue. */
    private fun gif(): File {
        val bytes = ByteArrayOutputStream().also { out ->
            GifEncoder(out, 32, 32, dither = false).use { gif ->
                gif.addFrame(IntArray(32 * 32) { Color.RED }, 500)
                gif.addFrame(IntArray(32 * 32) { Color.BLUE }, 500)
            }
        }.toByteArray()
        // Make the first frame's only color (index 0) transparent.
        val gce = (0 until bytes.size - 3).first {
            bytes[it] == 0x21.toByte() && bytes[it + 1] == 0xF9.toByte() && bytes[it + 2] == 4.toByte()
        }
        bytes[gce + 3] = (bytes[gce + 3].toInt() or 1).toByte()
        bytes[gce + 6] = 0
        return File(context.cacheDir, "anim-${System.nanoTime()}.gif").apply { writeBytes(bytes) }
    }

    @Test
    fun animatedGifMovesAndKeepsItsTransparency() {
        val media = runBlocking { MediaProbe(context).probe(Uri.fromFile(gif())) }!!
        assertNotNull("animation copy", media.animationUri)
        assertEquals(1_000_000L, media.durationUs)

        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false, background = CanvasBackground.Solid(0xFF00FF00)))
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(media, imageDurationUs = 2_000_000L)))
        val id = p.mainTrack.clips.single().id
        p = Edits.updateClip(p, id) { it.copy(trimOutUs = it.trimInUs + 2_000_000L) }
        assertEquals(2_000_000L, p.durationUs)

        val out = File(context.cacheDir, "gif-${System.nanoTime()}.mp4")
        runBlocking { Exporter(context).render(p, ExportSettings(heightPx = 128, frameRate = 30), out) { _, _ -> } }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(out.absolutePath)
            fun center(timeUs: Long): Int {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
                return frame.getPixel(frame.width / 2, frame.height / 2)
            }
            val transparent = center(250_000)
            val blue = center(750_000)
            val loop = center(1_250_000)
            assertTrue("transparent shows the background ${Integer.toHexString(transparent)}", Color.green(transparent) > 200 && Color.red(transparent) < 60)
            assertTrue("second frame ${Integer.toHexString(blue)}", Color.blue(blue) > 200 && Color.green(blue) < 60)
            assertTrue("loops ${Integer.toHexString(loop)}", Color.green(loop) > 200 && Color.blue(loop) < 60)
        } finally {
            retriever.release()
            out.delete()
        }
    }
}

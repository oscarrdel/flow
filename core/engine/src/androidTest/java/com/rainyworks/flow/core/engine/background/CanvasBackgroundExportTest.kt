package com.rainyworks.flow.core.engine.background

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.CanvasBackground
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.Transform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A square picture on a 16:9 canvas leaves bars on both sides for the background. */
@RunWith(AndroidJUnit4::class)
class CanvasBackgroundExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun image(name: String, paint: (x: Int) -> Int): MediaRef {
        val file = File(context.cacheDir, "$name.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 64) for (y in 0 until 64) setPixel(x, y, paint(x))
        }.also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 64, height = 64, mimeType = "image/png")
    }

    private fun project(background: CanvasBackground, main: MediaRef = image("red") { Color.RED }): Project {
        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R16_9, followFirstClip = false, background = background))
        return Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(main, imageDurationUs = 1_000_000L)))
    }

    /** Pixels at the given horizontal fractions, vertically centered, at [timeUs]. */
    private fun pixels(project: Project, timeUs: Long, vararg xs: Float): List<Int> {
        val out = File(context.cacheDir, "bg-${System.nanoTime()}.mp4")
        runBlocking { Exporter(context).render(project, ExportSettings(heightPx = 144, frameRate = 30), out) { _, _ -> } }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(out.absolutePath)
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
            return xs.map { frame.getPixel((frame.width * it).toInt(), frame.height / 2) }
        } finally {
            retriever.release()
            out.delete()
        }
    }

    private fun hex(c: Int) = Integer.toHexString(c)

    @Test
    fun solidColorFillsTheBars() {
        val (edge, center) = pixels(project(CanvasBackground.Solid(0xFF00FF00)), 500_000, 0.05f, 0.5f)
        assertTrue("edge ${hex(edge)}", Color.green(edge) > 200 && Color.red(edge) < 60)
        assertTrue("center ${hex(center)}", Color.red(center) > 200 && Color.green(center) < 60)
    }

    @Test
    fun gradientRunsAcrossTheCanvas() {
        // Angle 0 runs left to right: green on the left, blue on the right.
        val (left, right) = pixels(project(CanvasBackground.Gradient(0xFF00FF00, 0xFF0000FF, 0f)), 500_000, 0.03f, 0.97f)
        assertTrue("left ${hex(left)}", Color.green(left) > Color.blue(left) + 80)
        assertTrue("right ${hex(right)}", Color.blue(right) > Color.green(right) + 80)
    }

    @Test
    fun imageCoversTheCanvas() {
        val blue = image("bg-blue") { Color.BLUE }
        val (edge) = pixels(project(CanvasBackground.Image(blue.uri)), 500_000, 0.05f)
        assertTrue("edge ${hex(edge)}", Color.blue(edge) > 200 && Color.red(edge) < 60)
    }

    @Test
    fun blurExtendsThePictureIntoTheBars() {
        val halves = image("halves") { x -> if (x < 32) Color.RED else Color.BLUE }
        val (left, right) = pixels(project(CanvasBackground.Blur(0.5f), halves), 500_000, 0.05f, 0.95f)
        assertTrue("left ${hex(left)}", Color.red(left) > 120 && Color.blue(left) < 100)
        assertTrue("right ${hex(right)}", Color.blue(right) > 120 && Color.red(right) < 100)
    }

    @Test
    fun overlaysAndMainTrackGapsShowTheBackground() {
        var p = project(CanvasBackground.Solid(0xFF00FF00))
        // A small blue overlay after the main clip ends: the main track has a gap there.
        p = MediaEdits.addOverlay(p, image("blue-small") { Color.BLUE }, 1_000_000L)
        val overlay = p.tracks.first { it !== p.mainTrack && it.clips.isNotEmpty() }.clips.single()
        p = Edits.updateClip(p, overlay.id) { it.copy(transform = Transform(scale = 0.3f)) }
        val (edge, center) = pixels(p, 1_500_000, 0.05f, 0.5f)
        assertTrue("edge ${hex(edge)}", Color.green(edge) > 200 && Color.blue(edge) < 60)
        assertTrue("center ${hex(center)}", Color.blue(center) > 200 && Color.green(center) < 60)
    }
}

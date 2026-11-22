package com.rainyworks.flow.core.engine.compositing

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.KenBurns
import com.rainyworks.flow.core.model.KenBurnsStyle
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.Transform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KenBurnsExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun solid(name: String, color: Int): MediaRef {
        val file = File(context.cacheDir, "$name.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 64, height = 64, mimeType = "image/png")
    }

    /** Width (fraction of the frame) of the blue run across the middle row. */
    private fun blueWidth(retriever: MediaMetadataRetriever, timeUs: Long): Float {
        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
        val y = frame.height / 2
        val count = (0 until frame.width).count { x -> frame.getPixel(x, y).let { Color.blue(it) > 180 && Color.red(it) < 90 } }
        return count.toFloat() / frame.width
    }

    @Test
    fun zoomInGrowsAnOverlayAmongSeveralLayers() {
        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false))
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(solid("kb-red", Color.RED), imageDurationUs = 3_000_000L)))
        p = MediaEdits.addOverlay(p, solid("kb-green", Color.GREEN), 0L)
        p = MediaEdits.addOverlay(p, solid("kb-blue", Color.BLUE), 0L)
        val tracks = p.tracks.filter { it !== p.mainTrack && it.clips.isNotEmpty() }
        val green = tracks[0].clips.single().id
        val blue = tracks[1].clips.single().id
        p = Edits.updateClip(p, green) { it.copy(transform = Transform(scale = 0.2f, offsetX = -0.35f, offsetY = -0.35f), trimOutUs = 3_000_000L) }
        p = Edits.updateClip(p, blue) { it.copy(transform = Transform(scale = 0.4f), trimOutUs = 3_000_000L) }
        p = KenBurns.apply(p, blue, KenBurnsStyle.ZOOM_IN)

        val out = File(context.cacheDir, "kb-${System.nanoTime()}.mp4")
        runBlocking { Exporter(context).render(p, ExportSettings(heightPx = 160, frameRate = 30), out) { _, _ -> } }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(out.absolutePath)
            val start = blueWidth(retriever, 50_000)
            val end = blueWidth(retriever, 2_900_000)
            assertTrue("start $start", start in 0.35f..0.45f)
            assertTrue("end $end grows from $start", end > start * 1.15f)
        } finally {
            retriever.release()
            out.delete()
        }
    }
}

package com.rainyworks.flow.core.engine.adjustment

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.ColorAdjustments
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.ProjectFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AdjustmentLayerExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun gradeAppliesOnlyWhileTheAdjustmentClipIsOnTheTimeline() {
        val file = File(context.cacheDir, "adjust-red.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val red = MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 64, height = 64, mimeType = "image/png")
        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false))
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(red, imageDurationUs = 3_000_000L)))
        // Adjustment from 1 s to the end that removes all color.
        val (withLayer, layerId) = MediaEdits.addAdjustmentLayer(p, 1_000_000L)
        p = MediaEdits.setColorGrade(withLayer, layerId, ColorGrade(adjustments = ColorAdjustments(saturation = -1f)))

        val out = File(context.cacheDir, "adjust-${System.nanoTime()}.mp4")
        runBlocking { Exporter(context).render(p, ExportSettings(heightPx = 128, frameRate = 30), out) { _, _ -> } }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(out.absolutePath)
            fun center(timeUs: Long): Int {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
                return frame.getPixel(frame.width / 2, frame.height / 2)
            }
            val before = center(500_000)
            val during = center(2_000_000)
            assertTrue("before ${Integer.toHexString(before)}", Color.red(before) > 200 && Color.green(before) < 60)
            assertTrue(
                "during ${Integer.toHexString(during)}",
                abs(Color.red(during) - Color.green(during)) < 40 && abs(Color.green(during) - Color.blue(during)) < 40,
            )
        } finally {
            retriever.release()
            out.delete()
        }
    }
}

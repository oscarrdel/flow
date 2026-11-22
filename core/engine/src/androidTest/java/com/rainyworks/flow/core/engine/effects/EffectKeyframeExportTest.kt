package com.rainyworks.flow.core.engine.effects

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.EffectCatalog
import com.rainyworks.flow.core.model.EffectEdits
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.ProjectFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EffectKeyframeExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun blurGrowsBetweenKeyframes() {
        val file = File(context.cacheDir, "kf-halves.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 64) for (y in 0 until 64) setPixel(x, y, if (x < 32) Color.RED else Color.BLUE)
        }.also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val media = MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 64, height = 64, mimeType = "image/png")
        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false))
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(media, imageDurationUs = 2_000_000L)))
        val clipId = p.mainTrack.clips.single().id
        val (withBlur, effectId) = EffectEdits.addEffect(p, clipId, "blur.gaussian")
        p = withBlur
        val key = EffectCatalog.find("blur.gaussian")!!.params.first()
        p = EffectEdits.toggleEffectKeyframe(p, clipId, effectId!!, key.key, 0L)
        p = EffectEdits.toggleEffectKeyframe(p, clipId, effectId, key.key, 1_900_000L)
        p = EffectEdits.setEffectParam(p, clipId, effectId, key.key, key.min, atTimelineUs = 0L)
        p = EffectEdits.setEffectParam(p, clipId, effectId, key.key, key.max, atTimelineUs = 1_900_000L)

        val out = File(context.cacheDir, "kf-${System.nanoTime()}.mp4")
        runBlocking { Exporter(context).render(p, ExportSettings(heightPx = 128, frameRate = 30), out) { _, _ -> } }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(out.absolutePath)
            // Blue leaking into the red pixels just left of the edge measures the blur.
            fun leak(timeUs: Long): Int {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
                return (-5..-1).sumOf { Color.blue(frame.getPixel(frame.width / 2 + it, frame.height / 2)) }
            }
            val sharp = leak(50_000)
            val middle = leak(1_000_000)
            val blurred = leak(1_900_000)
            assertTrue("sharp $sharp", sharp < 30)
            assertTrue("grows: $sharp < $middle < $blurred", middle > sharp + 20 && blurred > middle + 20)
        } finally {
            retriever.release()
            out.delete()
        }
    }
}

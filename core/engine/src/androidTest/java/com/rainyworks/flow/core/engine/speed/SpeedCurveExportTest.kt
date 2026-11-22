package com.rainyworks.flow.core.engine.speed

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.engine.MediaProbe
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.SpeedRamp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SpeedCurveExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun exportedDurationMatchesTheTimeline(): Unit = runBlocking {
        val input = File(context.cacheDir, "speed-input.mp4")
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Movies/baseline_test.mp4"))
            .use { stream -> input.outputStream().use { stream.copyTo(it) } }
        val media = checkNotNull(MediaProbe(context).probe(Uri.fromFile(input)))
        for (presetId in listOf("hero", "montage")) {
            var project = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(ProjectFactory.clipFor(media)))
            val id = project.mainTrack.clips.single().id
            project = Edits.setSpeedCurve(project, id, SpeedRamp.presets.single { it.presetId == presetId })
            val expected = project.durationUs
            val out = File(context.cacheDir, "speed-$presetId.mp4")
            Exporter(context).render(project, ExportSettings(heightPx = 360), out) { _, _ -> }
            val retriever = MediaMetadataRetriever()
            val actual = try {
                retriever.setDataSource(out.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1_000
            } finally {
                retriever.release()
            }
            assertTrue("$presetId: exported $actual µs, timeline $expected µs", abs(actual - expected) <= 150_000)
            assertTrue("$presetId changes the length", abs(expected - media.durationUs) > 200_000)
        }
    }
}

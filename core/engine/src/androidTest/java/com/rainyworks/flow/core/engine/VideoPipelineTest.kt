package com.rainyworks.flow.core.engine

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    // Copiar con la identidad de shell permite usar los clips del AVD sin permisos de almacenamiento.
    private suspend fun withVideo(name: String, test: suspend (Project) -> Unit) {
        val file = File.createTempFile("input-", ".mp4", context.cacheDir)
        try {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Movies/$name")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            check(file.length() > 0) { "Falta /sdcard/Movies/$name" }
            val media = checkNotNull(MediaProbe(context).probe(Uri.fromFile(file)))
            val project = ProjectFactory.create("Prueba", 0)
            test(Edits.insertIntoMain(project, listOf(ProjectFactory.clipFor(media))))
        } finally {
            file.delete()
        }
    }

    @Test
    fun exportBaseline() = checkExport("baseline_test.mp4", 720)

    @Test
    fun exportHighVertical() = checkExport("vertical_test.mp4", 1080)

    @Test
    fun exportHorizontal() = checkExport("horizontal_test.mp4", 1080)

    private fun monochrome(project: Project) = Edits.updateClip(project, project.mainTrack.clips.single().id) {
        it.copy(color = ColorGrade(filterId = "mono.classic"))
    }

    @Test
    fun exportClassicMonochrome() = checkExport("baseline_test.mp4", 720, filtered = true)

    private fun checkExport(name: String, shortSide: Int, filtered: Boolean = false) = runBlocking {
        withVideo(name) { project ->
            val input = if (filtered) monochrome(project) else project
            val result = withTimeout(120_000) {
                Exporter(context).export(input, ExportSettings(heightPx = shortSide), "Flow-prueba").last()
            }
            assertTrue(result.toString(), result is ExportState.Success)
            result as ExportState.Success
            try {
                MediaMetadataRetriever().use { reader ->
                    reader.setDataSource(context, result.uri)
                    val frames = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: 0
                    assertTrue("Fotogramas exportados: $frames", frames > 1)
                    val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertEquals(project.durationUs / 1_000.0, durationMs.toDouble(), 100.0)
                    for (timeUs in if (filtered) listOf(500_000L, 1_500_000L) else listOf(500_000L)) {
                        val frame = reader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        assertNotNull(frame)
                        try {
                            if (filtered) {
                                checkNotNull(frame)
                                assertEquals(FrameColor.GRAY, classify(frame.width, frame.height, frame::getPixel))
                            }
                        } finally {
                            frame?.recycle()
                        }
                    }
                    Log.i("FlowVideoTest", "$name exportado: ${result.sizeBytes} bytes, $frames fotogramas, $durationMs ms")
                }
            } finally {
                context.contentResolver.delete(result.uri, null, null)
            }
        }
    }

    @Test
    fun previewBaseline() = runBlocking { withVideo("baseline_test.mp4") { checkPreview(it) } }

    @Test
    fun previewHighVertical() = runBlocking { withVideo("vertical_test.mp4") { checkPreview(it) } }

    @Test
    fun previewHorizontal() = runBlocking { withVideo("horizontal_test.mp4") { checkPreview(it) } }

    @Test
    fun persistentErrorRetriesOnceAndClearsOnRecovery() = runBlocking {
        withVideo("baseline_test.mp4") { checkPreview(it, failFirst = true) }
    }

    @Test
    fun applyClassicMonochromeWhilePaused() = runBlocking {
        withVideo("baseline_test.mp4") { checkPreview(it, applyFilter = true) }
    }

    private enum class FrameColor { BLANK, COLOR, GRAY }

    private fun classify(image: Image): FrameColor {
        val plane = image.planes[0]
        val buffer = plane.buffer
        return classify(image.width, image.height) { x, y ->
            val i = y * plane.rowStride + x * plane.pixelStride
            Color.rgb(
                buffer.get(i).toInt() and 255,
                buffer.get(i + 1).toInt() and 255,
                buffer.get(i + 2).toInt() and 255,
            )
        }
    }

    private fun classify(width: Int, height: Int, pixel: (Int, Int) -> Int): FrameColor {
        var darkest = 255
        var lightest = 0
        for (y in 1..15) for (x in 1..15) {
            val rgb = pixel(x * width / 16, y * height / 16)
            val lo = minOf(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            val hi = maxOf(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            if (hi - lo > 6) return FrameColor.COLOR
            darkest = minOf(darkest, lo)
            lightest = maxOf(lightest, hi)
        }
        return if (lightest - darkest > 32) FrameColor.GRAY else FrameColor.BLANK
    }

    private suspend fun checkPreview(project: Project, failFirst: Boolean = false, applyFilter: Boolean = false) = withContext(Dispatchers.Main) {
        val frames = AtomicInteger()
        val reader = ImageReader.newInstance(360, 640, PixelFormat.RGBA_8888, 3)
        val frameColor = MutableStateFlow(FrameColor.BLANK)
        var grayFrames = 0
        val preview = PreviewController(context)
        try {
            reader.setOnImageAvailableListener({
                it.acquireLatestImage()?.use { image ->
                    frames.incrementAndGet()
                    if (applyFilter) {
                        frameColor.value = classify(image)
                        if (frameColor.value == FrameColor.GRAY) grayFrames++
                    }
                }
            }, Handler(Looper.getMainLooper()))
            val holder = object : SurfaceHolder by SurfaceView(context).holder {
                override fun getSurface() = reader.surface
                override fun getSurfaceFrame() = Rect(0, 0, reader.width, reader.height)
            }
            preview.player.setVideoSurfaceHolder(holder)
            if (failFirst) checkPersistentError(preview, project)
            preview.setProject(project)
            if (applyFilter) {
                withTimeout(20_000) { frameColor.first { it == FrameColor.COLOR } }
                preview.setProject(monochrome(project))
                withTimeout(20_000) { frameColor.first { it == FrameColor.GRAY } }
            }
            preview.play()
            val position = withTimeout(20_000) {
                preview.positionUs.first { it >= 750_000 && frames.get() >= 2 && (!applyFilter || grayFrames >= 2) }
            }
            assertNull(preview.error.value)
            Log.i("FlowVideoTest", "Vista previa: $position µs, ${frames.get()} fotogramas")
        } finally {
            preview.release()
            reader.close()
        }
        Unit
    }

    private suspend fun checkPersistentError(preview: PreviewController, project: Project) {
        var failures = 0
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failures++ }
        }
        preview.player.addListener(listener)
        val clip = project.mainTrack.clips.single()
        val media = checkNotNull(clip.media)
        preview.setProject(Edits.updateClip(project, clip.id) { it.copy(media = media.copy(uri = "${media.uri}.missing")) })
        preview.play()
        val message = withTimeout(30_000) {
            preview.error.first { it != null && !it.contains("Reintentando") }
        }
        assertTrue(message.orEmpty().contains("vista previa"))
        assertEquals(2, failures)
        preview.player.removeListener(listener)
    }
}

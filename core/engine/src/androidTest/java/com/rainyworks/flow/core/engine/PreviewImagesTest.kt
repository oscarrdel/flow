package com.rainyworks.flow.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.EffectEdits
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.Transform
import com.rainyworks.flow.core.model.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PreviewImagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun photo(name: String, color: Int): MediaRef {
        val file = File(context.cacheDir, "$name.jpg")
        Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        return MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 1600, height = 1200, mimeType = "image/jpeg")
    }

    private fun base(): Project {
        val p = ProjectFactory.create("p", 0L)
        return p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R16_9, followFirstClip = false))
    }

    private fun twoPhotos(transition: Boolean): Project {
        var p = Edits.insertIntoMain(base(), listOf(ProjectFactory.clipFor(photo("a", Color.RED)), ProjectFactory.clipFor(photo("b", Color.BLUE))))
        if (transition) p = EffectEdits.setTransition(p, p.mainTrack.clips[1].id, Transition("wipe.left", 1_500_000))
        return p
    }

    private fun withOverlay(p: Project): Project {
        var project = MediaEdits.addOverlay(p, photo("c", Color.GREEN), 263_897)
        val overlay = project.tracks.last().clips.single()
        project = Edits.trimEnd(project, overlay.id, overlay.startUs + 1_180_420)
        project = MediaEdits.toggleMotionKeyframe(project, overlay.id, overlay.startUs)
        project = MediaEdits.setTransformAt(project, overlay.id, Transform(offsetX = -0.3f, offsetY = -0.15f, scale = 0.5f), overlay.startUs + 900_000)
        return project
    }

    private fun withTitle(p: Project): Project {
        var (project, id) = MediaEdits.addText(p, 0, TextContent("Hola"), durationUs = 933_626)
        project = MediaEdits.toggleMotionKeyframe(project, id, 0)
        return project
    }

    private fun plays(project: Project, targetUs: Long): Long = runBlocking {
        withContext(Dispatchers.Main) {
            val reader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 3)
            val preview = PreviewController(context)
            try {
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                val holder = object : SurfaceHolder by SurfaceView(context).holder {
                    override fun getSurface() = reader.surface
                    override fun getSurfaceFrame() = Rect(0, 0, reader.width, reader.height)
                }
                preview.player.setVideoSurfaceHolder(holder)
                preview.setProject(project)
                preview.play()
                val reached = withTimeoutOrNull(15_000) { preview.positionUs.first { it >= targetUs } }
                val position = preview.positionUs.value
                Log.i("FlowPreviewImages", "reached=$reached position=$position error=${preview.error.value}")
                position
            } finally {
                preview.release()
                reader.close()
            }
        }
    }

    @Test
    fun overlayPhotoAfterGapPlays() {
        val position = plays(withOverlay(base().let { Edits.insertIntoMain(it, listOf(ProjectFactory.clipFor(photo("a", Color.RED)))) }), 2_500_000)
        assertTrue("stalled at $position", position >= 2_500_000)
    }

    @Test
    fun photosWithTransitionPlay() {
        val position = plays(twoPhotos(transition = true), 3_500_000)
        assertTrue("stalled at $position", position >= 3_500_000)
    }

    @Test
    fun userProjectPlays() {
        val position = plays(withTitle(withOverlay(twoPhotos(transition = true))), 3_500_000)
        assertTrue("stalled at $position", position >= 3_500_000)
    }
}

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
import com.rainyworks.flow.core.model.EffectEdits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.Transition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TransitionExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun solid(name: String, color: Int): MediaRef {
        val file = File(context.cacheDir, "$name.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 64, height = 64, mimeType = "image/png")
    }

    private fun project(type: String): Project {
        val clips = listOf(solid("red", Color.RED), solid("blue", Color.BLUE), solid("green", Color.GREEN))
            .map { ProjectFactory.clipFor(it, imageDurationUs = 2_000_000L) }
        var p = ProjectFactory.create("t", 0L).copy(canvas = ProjectFactory.create("t", 0L).canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false))
        p = Edits.insertIntoMain(p, clips)
        p = EffectEdits.setTransition(p, p.mainTrack.clips[1].id, Transition(type, 1_000_000))
        p = EffectEdits.setTransition(p, p.mainTrack.clips[2].id, Transition(type, 1_000_000))
        return p
    }

    private fun render(project: Project): File {
        val out = File(context.cacheDir, "transition-${System.nanoTime()}.mp4")
        runBlocking { Exporter(context).render(project, ExportSettings(heightPx = 128, frameRate = 30), out) { _, _ -> } }
        return out
    }

    private fun centerColor(file: File, timeUs: Long): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
            return frame.getPixel(frame.width / 2, frame.height / 2)
        } finally {
            retriever.release()
        }
    }

    @Test
    fun dissolveBlendsNeighborsAndRollsAlternate() {
        val project = project("dissolve")
        // red [0,2), blue [1,3), green [2,4): windows [1,2) and [2,3).
        assertTrue(project.durationUs == 4_000_000L)
        val video = render(project)
        val start = centerColor(video, 300_000)
        val firstMid = centerColor(video, 1_500_000)
        val secondMid = centerColor(video, 2_500_000)
        val end = centerColor(video, 3_700_000)
        assertTrue("start ${Integer.toHexString(start)}", Color.red(start) > 200 && Color.blue(start) < 60)
        assertTrue("first mid ${Integer.toHexString(firstMid)}", Color.red(firstMid) in 70..190 && Color.blue(firstMid) in 70..190)
        assertTrue("second mid ${Integer.toHexString(secondMid)}", Color.blue(secondMid) in 70..190 && Color.green(secondMid) in 70..190)
        assertTrue("end ${Integer.toHexString(end)}", Color.green(end) > 200 && Color.red(end) < 60)
    }

    @Test
    fun everyTransitionTypeRenders() {
        for (type in listOf("fade.black", "fade.white", "slide.left", "slide.up", "wipe.right", "zoom", "spin", "glitch", "flash")) {
            val video = render(project(type))
            assertTrue(type, video.length() > 0)
            val end = centerColor(video, 3_700_000)
            assertTrue("$type end ${Integer.toHexString(end)}", Color.green(end) > 200)
        }
    }

    @Test
    fun cropKeepsOnlyTheSelectedPart() {
        // Left half red, right half blue; keeping the left half must show only red.
        val file = File(context.cacheDir, "halves.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 64) for (y in 0 until 64) setPixel(x, y, if (x < 32) Color.RED else Color.BLUE)
        }.also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val media = MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 64, height = 64, mimeType = "image/png")
        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false))
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(media, imageDurationUs = 1_000_000L)))
        val id = p.mainTrack.clips.single().id
        p = Edits.updateClip(p, id) {
            it.copy(transform = com.rainyworks.flow.core.model.Transform(crop = com.rainyworks.flow.core.model.CropRect(0f, 0f, 0.5f, 1f)))
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(render(p).absolutePath)
            val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            // The 1:2 cropped picture is fitted in the middle half of the square canvas.
            val inside = frame.getPixel((frame.width * 0.65f).toInt(), frame.height / 2)
            val outside = frame.getPixel((frame.width * 0.9f).toInt(), frame.height / 2)
            assertTrue("inside ${Integer.toHexString(inside)}", Color.red(inside) > 200 && Color.blue(inside) < 60)
            assertTrue("outside ${Integer.toHexString(outside)}", Color.red(outside) < 40 && Color.blue(outside) < 40)
        } finally {
            retriever.release()
        }
    }

    @Test
    fun singleMovedClipExports() {
        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false))
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(solid("red2", Color.RED), imageDurationUs = 1_000_000L)))
        val id = p.mainTrack.clips.single().id
        p = Edits.updateClip(p, id) { it.copy(transform = com.rainyworks.flow.core.model.Transform(offsetX = 0.5f, scale = 0.5f)) }
        val video = render(p)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val left = frame.getPixel(frame.width / 4, frame.height / 2)
            val right = frame.getPixel(frame.width * 3 / 4, frame.height / 2)
            assertTrue("left ${Integer.toHexString(left)}", Color.red(left) < 60)
            assertTrue("right ${Integer.toHexString(right)}", Color.red(right) > 200)
        } finally {
            retriever.release()
        }
    }

    @Test
    fun entranceAndExitAnimationsRender() {
        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false))
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(solid("red3", Color.RED), imageDurationUs = 3_000_000L)))
        val id = p.mainTrack.clips.single().id
        p = com.rainyworks.flow.core.model.MediaEdits.setEnterAnimation(
            p, id, com.rainyworks.flow.core.model.ClipAnimation(com.rainyworks.flow.core.model.ClipAnimationType.ZOOM_IN, 1_000_000),
        )
        p = com.rainyworks.flow.core.model.MediaEdits.setExitAnimation(
            p, id, com.rainyworks.flow.core.model.ClipAnimation(com.rainyworks.flow.core.model.ClipAnimationType.SLIDE_UP, 1_000_000),
        )
        val video = render(p)
        val early = centerColor(video, 50_000)
        val middle = centerColor(video, 1_500_000)
        assertTrue("entrance starts transparent ${Integer.toHexString(early)}", Color.red(early) < 90)
        assertTrue("fully visible in the middle ${Integer.toHexString(middle)}", Color.red(middle) > 200)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(2_800_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val bottom = frame.getPixel(frame.width / 2, frame.height * 9 / 10)
            assertTrue("exit moved up, bottom cleared ${Integer.toHexString(bottom)}", Color.red(bottom) < 90)
        } finally {
            retriever.release()
        }
    }
}

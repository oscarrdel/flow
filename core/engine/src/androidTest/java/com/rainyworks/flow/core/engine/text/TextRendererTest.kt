package com.rainyworks.flow.core.engine.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.model.AnimatedProperty
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Keyframe
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.TextAnimation
import com.rainyworks.flow.core.model.TextAnimationType.TYPEWRITER
import com.rainyworks.flow.core.model.TextBackground
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.TextFill
import com.rainyworks.flow.core.model.TextShadow
import com.rainyworks.flow.core.model.TextStroke
import com.rainyworks.flow.core.model.Track
import com.rainyworks.flow.core.model.TrackKind
import com.rainyworks.flow.core.model.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class TextRendererTest {
    private val renderer = TextRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
    private val title = Clip("title", ClipKind.TEXT, startUs = 100, trimOutUs = 200,
        text = TextContent("MMMM", size = 0.1f))
    private val width = 320
    private val height = 200

    private fun draw(clip: Clip, timeUs: Long): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            renderer.draw(Canvas(it), clip, timeUs, width, height)
        }

    private fun pixels(bitmap: Bitmap) = IntArray(width * height).also {
        bitmap.getPixels(it, 0, width, 0, 0, width, height)
    }

    @Test fun transformedGlyphsUseClipRelativeKeysAndGroupOpacity() {
        val clip = title.copy(opacity = 0.5f, transform = Transform(offsetY = 0.25f, rotationDegrees = 90f),
            keyframes = mapOf(
                AnimatedProperty.OFFSET_X to listOf(Keyframe(0, 0f), Keyframe(100, 0.5f)),
                AnimatedProperty.OPACITY to listOf(Keyframe(0, 0.5f)),
            ))
        assertTrue(pixels(draw(clip, 99)).all { it == 0 })
        val content = pixels(draw(clip, 150))
        val painted = content.indices.filter { content[it] != 0 }
        assertTrue(painted.size > 80)
        assertTrue(painted.all { it % width in 228..252 && it / width in 108..192 })
        assertEquals(64, painted.maxOf { Color.alpha(content[it]) })
        assertTrue(pixels(draw(clip, 300)).all { it == 0 })
    }

    @Test fun gradientStrokeShadowAndBoxAreVisibleInOneLayout() {
        val clip = title.copy(text = TextContent("HI\nHI", font = "monospace", bold = true, size = 0.19f,
            fill = TextFill(0xFFFF0000, 0xFF0000FF), stroke = TextStroke(0xFFFFFF00, 0.12f),
            shadow = TextShadow(0xFF00FF00, 0.32f, 0.15f, 0.04f),
            background = TextBackground(0x80000000, 0.2f, 0.6f)))
        val colors = pixels(draw(clip, 100))
        assertTrue(colors.any { it == 0x80000000.toInt() })
        assertTrue(colors.any { Color.green(it) > 150 && Color.red(it) < 50 && Color.blue(it) < 50 })
        assertTrue(colors.any { Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) < 50 })
        assertTrue(colors.indices.any { it / width < height / 2 && Color.red(colors[it]) > 140 && Color.green(colors[it]) < 30 })
        assertTrue(colors.indices.any { it / width > height / 2 && Color.blue(colors[it]) > 140 && Color.green(colors[it]) < 30 })
    }

    @Test fun overlayTracksVisibilityStackingBoundariesAndReverseSeeks() {
        fun box(argb: Long) = TextContent("Box", fill = TextFill(0), background = TextBackground(argb))
        val bottom = title.copy(text = box(0xFFFF0000))
        val top = title.copy(id = "top", startUs = 160, trimOutUs = 60, text = box(0xFF0000FF))
        val hidden = title.copy(id = "hidden", text = box(0xFF00FF00))
        val tracks = listOf(Track("bottom", TrackKind.TEXT, listOf(bottom), muted = true),
            Track("top", TrackKind.OVERLAY, listOf(top)), Track("hidden", TrackKind.TEXT, listOf(hidden), hidden = true))
        val overlay = TextOverlays.forProject(Project("test", "", 0, 0, tracks = tracks), renderer, width, height)
            .single() as BitmapOverlay
        try {
            val bitmap = overlay.getBitmap(100)
            assertEquals(Color.RED, bitmap.getPixel(width / 2, height / 2))
            val unchanged = bitmap.generationId
            assertSame(bitmap, overlay.getBitmap(159))
            assertEquals(unchanged, bitmap.generationId)
            overlay.getBitmap(160)
            assertEquals(Color.BLUE, bitmap.getPixel(width / 2, height / 2))
            overlay.getBitmap(220)
            assertEquals(Color.RED, bitmap.getPixel(width / 2, height / 2))
            overlay.getBitmap(300)
            assertTrue(pixels(bitmap).all { it == 0 })
            overlay.getBitmap(200)
            assertEquals(Color.BLUE, bitmap.getPixel(width / 2, height / 2))
        } finally {
            overlay.release()
        }
    }

    @Test fun typewriterKeepsGraphemesAndLayoutStableBetweenSteps() {
        val content = TextContent("a\u0301\uD83D\uDE80Z", size = 0.16f, enter = TextAnimation(TYPEWRITER, 150))
        val clip = title.copy(text = content)
        val track = Track("subtitles", TrackKind.TEXT, listOf(clip))
        val overlay = TextOverlays.forProject(Project("test", "", 0, 0, tracks = listOf(track)), renderer, width, height)
            .single() as BitmapOverlay
        try {
            val bitmap = overlay.getBitmap(100)
            assertTrue(pixels(bitmap).all { it == 0 })
            overlay.getBitmap(160)
            assertEquals(2, renderer.frameAt(clip, 160)!!.visibleEnd)
            val partial = pixels(bitmap)
            val unchanged = bitmap.generationId
            overlay.getBitmap(190)
            assertEquals(unchanged, bitmap.generationId)
            overlay.getBitmap(210)
            assertEquals(4, renderer.frameAt(clip, 210)!!.visibleEnd)
            assertNotEquals(unchanged, bitmap.generationId)
            overlay.getBitmap(250)
            val full = pixels(bitmap)
            assertTrue(partial.count { it != 0 } in 1 until full.count { it != 0 })
            assertTrue(partial.indices.filter { partial[it] != 0 }.all { full[it] == partial[it] })
        } finally {
            overlay.release()
        }
    }
}

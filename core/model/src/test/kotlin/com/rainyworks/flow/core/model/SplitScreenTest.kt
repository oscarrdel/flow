package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitScreenTest {
    private fun video(w: Int, h: Int) = MediaRef("v$w", MediaType.VIDEO, durationUs = 5_000_000, width = w, height = h)

    /** Frame in canvas fractions, mirroring the editor's clipFrameFor. */
    private fun frame(clip: Clip, canvasAspect: Float): FloatArray {
        val t = clip.transform
        val m = clip.media!!
        val srcW = m.displayWidth * (t.crop.right - t.crop.left)
        val srcH = m.displayHeight * (t.crop.bottom - t.crop.top)
        val canvasW = canvasAspect
        val canvasH = 1f
        val fit = minOf(canvasW / srcW, canvasH / srcH)
        val w = srcW * fit * t.scale / canvasW
        val h = srcH * fit * t.scale / canvasH
        return floatArrayOf(0.5f + t.offsetX - w / 2, 0.5f + t.offsetY - h / 2, w, h)
    }

    private fun project(): Project {
        var p = ProjectFactory.create("p", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R9_16, followFirstClip = false))
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(video(1920, 1080))))
        p = MediaEdits.addOverlay(p, video(1080, 1920), 0L)
        return p
    }

    @Test
    fun stackedFillsTopAndBottomHalves() {
        val p = SplitScreen.apply(project(), SplitLayout.STACKED, 1_000_000)
        val aspect = 9f / 16f
        val clips = SplitScreen.picturesAt(p, 1_000_000)
        val top = frame(clips[0], aspect)
        val bottom = frame(clips[1], aspect)
        floatArrayOf(0f, 0f, 1f, 0.5f).forEachIndexed { i, v -> assertEquals(v, top[i], 0.002f) }
        floatArrayOf(0f, 0.5f, 1f, 0.5f).forEachIndexed { i, v -> assertEquals(v, bottom[i], 0.002f) }
    }

    @Test
    fun sideBySideFillsLeftAndRight() {
        val p = SplitScreen.apply(project(), SplitLayout.SIDE_BY_SIDE, 1_000_000)
        val clips = SplitScreen.picturesAt(p, 1_000_000)
        val left = frame(clips[0], 9f / 16f)
        val right = frame(clips[1], 9f / 16f)
        floatArrayOf(0f, 0f, 0.5f, 1f).forEachIndexed { i, v -> assertEquals(v, left[i], 0.002f) }
        floatArrayOf(0.5f, 0f, 0.5f, 1f).forEachIndexed { i, v -> assertEquals(v, right[i], 0.002f) }
    }

    @Test
    fun pictureInPictureRoundsTheInset() {
        val p = SplitScreen.apply(project(), SplitLayout.PICTURE_IN_PICTURE, 1_000_000)
        val inset = SplitScreen.picturesAt(p, 1_000_000)[1]
        assertEquals(MaskShape.RECTANGLE, inset.mask?.shape)
        val f = frame(inset, 9f / 16f)
        assertEquals(0.34f, f[2], 0.002f)
    }

    @Test
    fun aSinglePictureIsLeftAlone() {
        var p = ProjectFactory.create("p", 0L)
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(video(1920, 1080))))
        assertEquals(p, SplitScreen.apply(p, SplitLayout.GRID, 0))
    }
}

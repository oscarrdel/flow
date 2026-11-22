package com.rainyworks.flow.core.engine.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.TextureOverlay
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Project
import java.util.TreeSet

@OptIn(UnstableApi::class)
object TextOverlays {
    /** Composition-wide overlays in canvas pixels. Each composition owns its overlay instance. */
    fun forProject(project: Project, renderer: TextRenderer, canvasWidth: Int, canvasHeight: Int): List<TextureOverlay> {
        require(canvasWidth > 0 && canvasHeight > 0)
        val clips = project.tracks.asSequence().filterNot { it.hidden }.flatMap { it.clips.asSequence() }
            .filter { it.kind == ClipKind.TEXT && !it.text?.text.isNullOrEmpty() }
            .toList()
        return if (clips.isEmpty()) emptyList() else listOf(ProjectOverlay(clips, renderer, canvasWidth, canvasHeight))
    }

    private class ProjectOverlay(
        private val clips: List<Clip>,
        private val renderer: TextRenderer,
        private val width: Int,
        private val height: Int,
    ) : BitmapOverlay() {
        private data class Boundary(val timeUs: Long, val index: Int, val entering: Boolean)
        private val boundaries = clips.flatMapIndexed { index, clip ->
            listOf(Boundary(clip.startUs, index, true), Boundary(clip.endUs, index, false))
        }.sortedBy { it.timeUs }
        // The project orders layers from bottom to top. Time sorting must preserve that draw order.
        private val active = TreeSet<Int>()
        private var nextBoundary = 0
        private var previous = ArrayList<TextFrame>()
        private var current = ArrayList<TextFrame>()
        private var bitmap: Bitmap? = null
        private var canvas: Canvas? = null

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val target = bitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                bitmap = it
                canvas = Canvas(it)
            }
            // Rewind only the crossed boundaries on backward seeks. Intervals are [start, end).
            while (nextBoundary > 0 && boundaries[nextBoundary - 1].timeUs > presentationTimeUs) {
                val event = boundaries[--nextBoundary]
                if (event.entering) active.remove(event.index) else active.add(event.index)
            }
            while (nextBoundary < boundaries.size && boundaries[nextBoundary].timeUs <= presentationTimeUs) {
                val event = boundaries[nextBoundary++]
                if (event.entering) active.add(event.index) else active.remove(event.index)
            }
            current.clear()
            for (index in active) {
                renderer.frameAt(clips[index], presentationTimeUs)?.let(current::add)
            }
            if (previous != current) {
                target.eraseColor(Color.TRANSPARENT)
                val output = checkNotNull(canvas)
                current.forEach { renderer.drawFrame(output, it, width, height) }
                val scratch = previous
                previous = current
                current = scratch
            }
            return target
        }

        override fun release() {
            try {
                super.release()
            } finally {
                canvas = null
                bitmap?.recycle()
                bitmap = null
                previous.clear()
                current.clear()
            }
        }
    }
}

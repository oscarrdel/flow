package com.rainyworks.flow.core.engine.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.net.Uri
import android.util.LruCache
import com.rainyworks.flow.core.model.AnimatedProperty
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipAnimations
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.animatedValue
import java.io.File
import kotlin.math.roundToInt

/** Draws project text in canvas pixels, using the project's timeline clock. */
class TextRenderer(context: Context) {
    private val locale = context.resources.configuration.locales[0]
    private val fonts = LruCache<Pair<String, Int>, Typeface>(32)
    private val layouts = LruCache<LayoutKey, TitleLayout>(32)
    private val graphemes = LruCache<String, IntArray>(32)

    private data class LayoutKey(val content: TextContent, val fontPx: Float, val maxWidth: Int)

    /** Draws a visible TEXT clip at [timelineUs], including its transform and animations. */
    fun draw(canvas: Canvas, clip: Clip, timelineUs: Long, canvasWidth: Int, canvasHeight: Int) {
        val frame = frameAt(clip, timelineUs) ?: return
        drawFrame(canvas, frame, canvasWidth, canvasHeight)
    }

    internal fun frameAt(clip: Clip, timelineUs: Long): TextFrame? {
        val content = clip.text ?: return null
        if (clip.kind != ClipKind.TEXT || !clip.contains(timelineUs) || content.text.isEmpty() || content.size <= 0f) {
            return null
        }
        val motion = TextAnimations.at(content, timelineUs - clip.startUs, clip.durationUs)
        // Keyframes plus the clip's entrance/exit animation, then the text-specific motion.
        val (rendered, clipOpacity) = ClipAnimations.renderedTransform(clip, timelineUs)
        val opacity = clipOpacity * motion.alpha
        val alpha = (255f * opacity.coerceIn(0f, 1f)).roundToInt()
        val transform = clip.transform
        val scale = rendered.scale * motion.scale
        if (alpha == 0 || scale == 0f) return null

        val end = when {
            motion.reveal >= 1f -> content.text.length
            motion.reveal <= 0f -> 0
            else -> {
                val ends = characterEnds(content.text)
                ends[motion.visibleCharacters(ends.lastIndex)]
            }
        }
        if (end == 0 && !motion.cursor && content.background == null) return null
        return TextFrame(
            content, alpha, end, motion.cursor,
            rendered.offsetX + motion.dx,
            rendered.offsetY + motion.dy,
            rendered.rotationDegrees,
            if (transform.flipHorizontal) -scale else scale,
            if (transform.flipVertical) -scale else scale,
        )
    }

    @Synchronized
    private fun characterEnds(text: String): IntArray {
        graphemes.get(text)?.let { return it }
        val iterator = BreakIterator.getCharacterInstance(locale)
        iterator.setText(text)
        val ends = ArrayList<Int>()
        var offset = iterator.first()
        while (offset != BreakIterator.DONE) {
            ends.add(offset)
            offset = iterator.next()
        }
        return ends.toIntArray().also { graphemes.put(text, it) }
    }

    // StaticLayout shares mutable TextPaint and spans between its drawing passes.
    @Synchronized
    internal fun drawFrame(canvas: Canvas, frame: TextFrame, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val key = LayoutKey(frame.content, height * frame.content.size, (width * 0.9f).toInt().coerceAtLeast(1))
        val layout = layouts.get(key) ?: TitleLayout(
            key.content, typeface(key.content), key.fontPx, key.maxWidth, locale,
        ).also { layouts.put(key, it) }
        val save = canvas.save()
        try {
            canvas.translate(width * (0.5f + frame.offsetX), height * (0.5f + frame.offsetY))
            canvas.rotate(frame.rotation)
            canvas.scale(frame.scaleX, frame.scaleY)
            canvas.translate(-layout.width / 2f, -layout.height / 2f)
            if (frame.alpha < 255) canvas.saveLayerAlpha(null, frame.alpha)
            layout.draw(canvas, frame.visibleEnd, frame.cursor)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun typeface(content: TextContent): Typeface {
        val style = (if (content.bold) Typeface.BOLD else 0) or (if (content.italic) Typeface.ITALIC else 0)
        val key = content.font to style
        fonts.get(key)?.let { return it }
        val face = if (content.font.startsWith("file://", ignoreCase = true)) {
            val path = Uri.parse(content.font).path
            val base = try {
                path?.let { Typeface.createFromFile(File(it)) }
            } catch (_: RuntimeException) {
                null
            }
            Typeface.create(base, style)
        } else {
            Typeface.create(content.font.takeUnless { it == "default" }, style)
        }
        return face.also { fonts.put(key, it) }
    }
}

/** Equality compares the actual drawing parameters, including the discrete reveal and cursor. */
internal data class TextFrame(
    val content: TextContent,
    val alpha: Int,
    val visibleEnd: Int,
    val cursor: Boolean,
    val offsetX: Float,
    val offsetY: Float,
    val rotation: Float,
    val scaleX: Float,
    val scaleY: Float,
)

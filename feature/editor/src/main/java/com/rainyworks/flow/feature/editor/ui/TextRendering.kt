package com.rainyworks.flow.feature.editor.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import com.rainyworks.flow.core.model.AnimatedProperty
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.TextAnimationType
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.Transform
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Typeface as AndroidTypeface
import com.rainyworks.flow.core.model.TextAlign as FlowTextAlign

/** Extra room around unboxed text so the selection outline does not touch the glyphs. */
private const val FRAME_MARGIN_EM = 0.15f
private const val MIN_BOX_WIDTH_EM = 0.6f

/** Fonts available to text clips: bundled system families plus imported files. */
internal object FlowFonts {
    /** System font ids with their display names. */
    val System: List<Pair<String, String>> = listOf(
        "default" to "Predeterminada",
        "serif" to "Serif",
        "sans-serif" to "Sans",
        "monospace" to "Monoespaciada",
        "cursive" to "Cursiva",
        "sans-serif-condensed" to "Condensada",
        "sans-serif-black" to "Black",
    )

    private val namedSystemFamilies = setOf("sans-serif-condensed", "sans-serif-black", "sans-serif-medium", "sans-serif-light")
    private val cache = ConcurrentHashMap<String, FontFamily>()

    fun family(id: String): FontFamily = cache.getOrPut(id) { resolve(id) }

    private fun resolve(id: String): FontFamily = when (id) {
        "default" -> FontFamily.Default
        "serif" -> FontFamily.Serif
        "sans-serif" -> FontFamily.SansSerif
        "monospace" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        in namedSystemFamilies -> FontFamily(Typeface(AndroidTypeface.create(id, AndroidTypeface.NORMAL)))
        else -> fileFamily(id) ?: FontFamily.Default
    }

    /** Loads an imported .ttf/.otf from its file URI; null when it is missing or unreadable. */
    private fun fileFamily(id: String): FontFamily? {
        val path = when {
            id.startsWith("file:") -> Uri.parse(id).path
            id.startsWith("/") -> id
            else -> null
        }
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return null
        val readable = runCatching { AndroidTypeface.Builder(file).build() }.getOrNull() != null
        return if (readable) FontFamily(Font(file)) else null
    }
}

internal fun TextContent.textStyle(fontPx: Float, density: Density): TextStyle = TextStyle(
    fontFamily = FlowFonts.family(font),
    fontSize = with(density) { fontPx.toSp() },
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    letterSpacing = letterSpacing.em,
    lineHeight = lineHeight.coerceAtLeast(0.5f).em,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
    textAlign = when (align) {
        FlowTextAlign.START -> TextAlign.Left
        FlowTextAlign.CENTER -> TextAlign.Center
        FlowTextAlign.END -> TextAlign.Right
    },
)

/** Measured text plus the box it occupies (text and optional background padding), unscaled. */
internal class TextPlacement(
    val layout: TextLayoutResult,
    val fontPx: Float,
    val padding: Float,
    val boxSize: Size,
)

/** Lays out and draws [TextContent] exactly the same way for the preview and for hit testing. */
@Stable
internal class TextRenderer(
    private val measurer: TextMeasurer,
    private val density: Density,
) {
    fun layout(
        content: TextContent,
        fontPx: Float,
        maxWidthPx: Float,
        text: String = content.text,
        fixedWidthPx: Int? = null,
    ): TextLayoutResult {
        val constraints = if (fixedWidthPx != null) {
            Constraints.fixedWidth(fixedWidthPx.coerceAtLeast(1))
        } else {
            Constraints(maxWidth = maxWidthPx.roundToInt().coerceAtLeast(1))
        }
        return measurer.measure(
            text = text.ifEmpty { " " },
            style = content.textStyle(fontPx, density),
            constraints = constraints,
        )
    }

    /** Placement for a canvas of [canvasSize]: the font size is relative to its height. */
    fun place(content: TextContent, canvasSize: Size): TextPlacement =
        placeWithFont(content, (content.size * canvasSize.height).coerceAtLeast(1f), canvasSize.width)

    fun placeWithFont(content: TextContent, fontPx: Float, maxWidthPx: Float): TextPlacement {
        val layout = layout(content, fontPx, maxWidthPx)
        val padding = content.background?.let { it.padding.coerceAtLeast(0f) * fontPx } ?: 0f
        val width = maxOf(layout.size.width.toFloat(), fontPx * MIN_BOX_WIDTH_EM)
        return TextPlacement(
            layout = layout,
            fontPx = fontPx,
            padding = padding,
            boxSize = Size(width + padding * 2f, layout.size.height + padding * 2f),
        )
    }

    /** On-canvas frame of a text clip, used for the selection outline and gestures. */
    fun frameFor(clip: Clip, transform: Transform, canvasSize: Size): ClipFrame? {
        val content = clip.text ?: return null
        val placement = place(content, canvasSize)
        val margin = if (content.background == null) placement.fontPx * FRAME_MARGIN_EM * 2f else 0f
        val scale = transform.scale.coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
        return ClipFrame(
            center = Offset(
                x = canvasSize.width / 2f + transform.offsetX * canvasSize.width,
                y = canvasSize.height / 2f + transform.offsetY * canvasSize.height,
            ),
            width = (placement.boxSize.width + margin) * scale,
            height = (placement.boxSize.height + margin) * scale,
            rotationDegrees = transform.rotationDegrees,
        )
    }
}

@Composable
internal fun rememberTextRenderer(): TextRenderer {
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val density = LocalDensity.current
    return remember(measurer, density) { TextRenderer(measurer, density) }
}

/**
 * Draws styled text centered at [center]: background box, stroke, shadow and solid or
 * gradient fill. [reveal] (0..1) shows only the first characters, for the typewriter effect.
 */
internal fun DrawScope.drawTextContent(
    renderer: TextRenderer,
    content: TextContent,
    placement: TextPlacement,
    center: Offset,
    scale: Float = 1f,
    rotationDegrees: Float = 0f,
    flipHorizontal: Boolean = false,
    flipVertical: Boolean = false,
    alpha: Float = 1f,
    reveal: Float = 1f,
) {
    val opacity = alpha.coerceIn(0f, 1f)
    if (opacity <= 0.001f || scale <= 0f) return
    val fontPx = placement.fontPx
    val box = placement.boxSize
    val boxTopLeft = Offset(-box.width / 2f, -box.height / 2f)

    val layout = if (reveal < 1f) {
        val count = (content.text.length * reveal.coerceIn(0f, 1f)).toInt()
        renderer.layout(
            content = content,
            fontPx = fontPx,
            maxWidthPx = placement.layout.size.width.toFloat(),
            text = content.text.take(count),
            fixedWidthPx = placement.layout.size.width,
        )
    } else {
        placement.layout
    }
    val textWidth = placement.layout.size.width.toFloat()
    val textTopLeft = boxTopLeft + Offset(
        x = placement.padding + (box.width - placement.padding * 2f - textWidth) / 2f,
        y = placement.padding,
    )

    val shadow = content.shadow?.let {
        Shadow(
            color = Color(it.argb),
            offset = Offset(it.offsetX * fontPx, it.offsetY * fontPx),
            blurRadius = (it.blur * fontPx).coerceAtLeast(0.5f),
        )
    }

    withTransform({
        translate(center.x, center.y)
        rotate(rotationDegrees, pivot = Offset.Zero)
        scale(
            scaleX = if (flipHorizontal) -scale else scale,
            scaleY = if (flipVertical) -scale else scale,
            pivot = Offset.Zero,
        )
    }) {
        content.background?.let { background ->
            val radius = background.cornerRadius.coerceIn(0f, 1f) * box.height / 2f
            drawRoundRect(
                color = Color(background.argb),
                topLeft = boxTopLeft,
                size = box,
                cornerRadius = CornerRadius(radius, radius),
                alpha = opacity,
            )
        }

        val stroke = content.stroke?.takeIf { it.width > 0f }
        if (stroke != null) {
            drawText(
                textLayoutResult = layout,
                color = Color(stroke.argb),
                topLeft = textTopLeft,
                alpha = opacity,
                shadow = shadow,
                drawStyle = Stroke(width = stroke.width * fontPx, join = StrokeJoin.Round),
            )
        }

        val fillShadow = if (stroke == null) shadow else null
        val gradientEnd = content.fill.gradientEndArgb
        if (gradientEnd != null) {
            drawText(
                textLayoutResult = layout,
                brush = Brush.verticalGradient(listOf(Color(content.fill.argb), Color(gradientEnd))),
                topLeft = textTopLeft,
                alpha = opacity,
                shadow = fillShadow,
            )
        } else {
            drawText(
                textLayoutResult = layout,
                color = Color(content.fill.argb),
                topLeft = textTopLeft,
                alpha = opacity,
                shadow = fillShadow,
            )
        }
    }
}

/** Animation state of a text clip at a given moment. Offsets are fractions of the canvas. */
internal data class TextMotion(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val reveal: Float = 1f,
) {
    operator fun times(other: TextMotion) = TextMotion(
        alpha = alpha * other.alpha,
        scale = scale * other.scale,
        dx = dx + other.dx,
        dy = dy + other.dy,
        reveal = minOf(reveal, other.reveal),
    )
}

private fun transitionMotion(type: TextAnimationType, progress: Float, exiting: Boolean): TextMotion {
    val visible = if (exiting) 1f - progress.coerceIn(0f, 1f) else progress.coerceIn(0f, 1f)
    val distance = (1f - visible) * if (exiting) -0.2f else 0.2f
    return when (type) {
        TextAnimationType.NONE -> TextMotion()
        TextAnimationType.TYPEWRITER -> TextMotion(reveal = visible)
        TextAnimationType.FADE -> TextMotion(alpha = visible)
        TextAnimationType.BOUNCE -> {
            val t = visible - 1f
            TextMotion(scale = (1f + t * t * (2.70158f * t + 1.70158f)).coerceAtLeast(0f))
        }
        TextAnimationType.SLIDE_UP -> TextMotion(alpha = visible, dy = distance)
        TextAnimationType.SLIDE_DOWN -> TextMotion(alpha = visible, dy = -distance)
        TextAnimationType.SLIDE_LEFT -> TextMotion(alpha = visible, dx = distance)
        TextAnimationType.SLIDE_RIGHT -> TextMotion(alpha = visible, dx = -distance)
        TextAnimationType.SCALE -> TextMotion(scale = visible)
    }
}

private fun loopMotion(type: TextAnimationType, progress: Float): TextMotion {
    val phase = progress - floor(progress)
    val angle = 2f * PI.toFloat() * phase
    val wave = sin(angle)
    val pulse = (1f - cos(angle)) * 0.5f
    return when (type) {
        TextAnimationType.NONE, TextAnimationType.TYPEWRITER -> TextMotion()
        TextAnimationType.FADE -> TextMotion(alpha = 1f - 0.5f * pulse)
        TextAnimationType.BOUNCE -> TextMotion(scale = 1f + 0.12f * pulse * pulse * pulse)
        TextAnimationType.SLIDE_UP -> TextMotion(dy = -0.03f * wave)
        TextAnimationType.SLIDE_DOWN -> TextMotion(dy = 0.03f * wave)
        TextAnimationType.SLIDE_LEFT -> TextMotion(dx = -0.03f * wave)
        TextAnimationType.SLIDE_RIGHT -> TextMotion(dx = 0.03f * wave)
        TextAnimationType.SCALE -> TextMotion(scale = 1f + 0.06f * pulse)
    }
}

/** Combined entrance, exit and loop animation [elapsedUs] into a clip lasting [durationUs]. */
internal fun textMotionAt(content: TextContent, elapsedUs: Long, durationUs: Long): TextMotion {
    var motion = TextMotion()
    val enter = content.enter
    if (enter.type != TextAnimationType.NONE && enter.durationUs > 0 && elapsedUs < enter.durationUs) {
        motion = transitionMotion(enter.type, elapsedUs.toFloat() / enter.durationUs, exiting = false)
    }
    val exit = content.exit
    val remainingUs = durationUs - elapsedUs
    if (exit.type != TextAnimationType.NONE && exit.durationUs > 0 && remainingUs < exit.durationUs) {
        motion *= transitionMotion(exit.type, 1f - remainingUs.toFloat() / exit.durationUs, exiting = true)
    }
    val loop = content.loop
    if (loop.type != TextAnimationType.NONE && loop.durationUs > 0) {
        motion *= loopMotion(loop.type, (elapsedUs % loop.durationUs).toFloat() / loop.durationUs)
    }
    return motion
}

private fun Clip.fadeFactorAt(timeUs: Long): Float {
    val elapsed = timeUs - startUs
    val remaining = endUs - timeUs
    var factor = 1f
    if (fadeInUs > 0 && elapsed < fadeInUs) factor *= elapsed.toFloat() / fadeInUs
    if (fadeOutUs > 0 && remaining < fadeOutUs) factor *= remaining.toFloat() / fadeOutUs
    return factor.coerceIn(0f, 1f)
}

/**
 * Draws every TEXT clip (titles and subtitles) visible at the playhead over the preview
 * canvas, honoring transform, opacity, fades, style and animations.
 */
@Composable
internal fun TextOverlayLayer(
    project: Project?,
    playheadUs: () -> Long,
    renderer: TextRenderer,
    modifier: Modifier = Modifier,
) {
    val textClips = remember(project) {
        project?.tracks.orEmpty()
            .filterNot { it.hidden }
            .flatMap { track -> track.clips.filter { it.kind == ClipKind.TEXT && it.text != null } }
    }
    if (textClips.isEmpty()) return

    Canvas(modifier = modifier.clipToBounds()) {
        val now = playheadUs()
        for (clip in textClips) {
            if (!clip.contains(now)) continue
            val content = clip.text ?: continue
            val placement = renderer.place(content, size)
            val motion = textMotionAt(content, now - clip.startUs, clip.durationUs)
            val transform = clip.animatedTransformAt(now)
            val opacity = MediaEdits.propertyValue(clip, AnimatedProperty.OPACITY, now).coerceIn(0f, 1f)
            drawTextContent(
                renderer = renderer,
                content = content,
                placement = placement,
                center = Offset(
                    x = size.width / 2f + (transform.offsetX + motion.dx) * size.width,
                    y = size.height / 2f + (transform.offsetY + motion.dy) * size.height,
                ),
                scale = transform.scale.coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE) * motion.scale,
                rotationDegrees = transform.rotationDegrees,
                flipHorizontal = transform.flipHorizontal,
                flipVertical = transform.flipVertical,
                alpha = opacity * motion.alpha * clip.fadeFactorAt(now),
                reveal = motion.reveal,
            )
        }
    }
}

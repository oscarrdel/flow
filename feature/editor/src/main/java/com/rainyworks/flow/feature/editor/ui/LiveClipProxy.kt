package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipAnimations
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.feature.editor.TimelineMediaSource
import kotlin.math.roundToInt

/**
 * Draws [clip] where it currently is while the user drags it. The preview player leaves
 * the clip out meanwhile (it cannot redraw a paused frame fast enough), so this keeps the
 * picture under the finger at display rate. Color grading and effects return when the
 * gesture ends and the player renders the clip again.
 */
@Composable
internal fun LiveClipProxy(
    clip: Clip,
    playheadUs: Long,
    mediaSource: TimelineMediaSource,
    canvasHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val media = clip.media ?: return
    if (media.type != MediaType.VIDEO && media.type != MediaType.IMAGE && media.type != MediaType.GIF) return
    // The playhead does not move during a drag, so this loads one frame per gesture.
    val sourceUs = if (media.type == MediaType.VIDEO) clip.sourceTimeAt(playheadUs) else 0L
    val heightPx = canvasHeightPx.coerceIn(240, 1080)
    val frame by produceState<ImageBitmap?>(null, media.uri, sourceUs, heightPx) {
        value = mediaSource.thumbnail(media, sourceUs, heightPx)
    }
    val bitmap = frame ?: return
    Canvas(modifier.clipToBounds()) {
        val (transform, opacity) = ClipAnimations.renderedTransform(clip, playheadUs)
        if (opacity <= 0f) return@Canvas
        val placed = clipFrameFor(Size(size.width, size.height), media, transform)
        val crop = transform.crop
        val src = IntOffset((crop.left * bitmap.width).roundToInt(), (crop.top * bitmap.height).roundToInt())
        val srcSize = IntSize(
            ((crop.right - crop.left) * bitmap.width).roundToInt().coerceAtLeast(1),
            ((crop.bottom - crop.top) * bitmap.height).roundToInt().coerceAtLeast(1),
        )
        val left = placed.center.x - placed.width / 2f
        val top = placed.center.y - placed.height / 2f
        rotate(placed.rotationDegrees, pivot = placed.center) {
            scale(
                scaleX = if (transform.flipHorizontal) -1f else 1f,
                scaleY = if (transform.flipVertical) -1f else 1f,
                pivot = placed.center,
            ) {
                drawImage(
                    image = bitmap,
                    srcOffset = src,
                    srcSize = srcSize,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(placed.width.roundToInt().coerceAtLeast(1), placed.height.roundToInt().coerceAtLeast(1)),
                    alpha = opacity.coerceIn(0f, 1f),
                )
            }
        }
    }
}

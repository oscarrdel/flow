package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.CropRect
import kotlin.math.abs

private val CROP_TOUCH_RADIUS = 22.dp

/**
 * Visual crop editor: shows [frame] with the current [crop] rectangle on top. The rectangle
 * can be dragged from its corners, edges or center. [lockedRatio] is the width/height
 * proportion in normalized frame space, or null for a free crop.
 *
 * [onCropChange] receives intermediate values with `final = false` and the last one with
 * `final = true` when the finger is lifted.
 */
@Composable
internal fun CropFrameEditor(
    frame: ImageBitmap?,
    sourceAspect: Float,
    crop: CropRect,
    lockedRatio: Float?,
    enabled: Boolean,
    onCropChange: (crop: CropRect, final: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
) {
    val density = LocalDensity.current
    val touchRadiusPx = with(density) { CROP_TOUCH_RADIUS.toPx() }
    val safeAspect = sourceAspect.takeIf { it.isFinite() && it > 0f } ?: 1f

    var dragCrop by remember { mutableStateOf<CropRect?>(null) }
    val currentCrop by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(lockedRatio)
    val currentOnCropChange by rememberUpdatedState(onCropChange)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        val availableRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f

        Box(
            modifier = Modifier
                .aspectRatio(safeAspect, matchHeightConstraintsFirst = availableRatio > safeAspect)
                .background(FlowColors.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "Fotograma actual",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "Cargando fotograma…",
                    style = FlowTypography.labelMedium,
                    color = FlowColors.TextTertiary,
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!enabled) {
                            Modifier
                        } else {
                            Modifier.pointerInput(touchRadiusPx) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val area = Size(size.width.toFloat(), size.height.toFloat())
                                    if (area.width <= 0f || area.height <= 0f) return@awaitEachGesture
                                    val start = currentCrop
                                    val handle = hitTestCrop(down.position, start, area, touchRadiusPx)
                                        ?: return@awaitEachGesture
                                    down.consume()

                                    var total = Offset.Zero
                                    var latest = start
                                    drag(down.id) { change ->
                                        total += change.positionChange()
                                        change.consume()
                                        latest = resizeCrop(
                                            start = start,
                                            handle = handle,
                                            dx = total.x / area.width,
                                            dy = total.y / area.height,
                                            ratio = currentRatio,
                                        )
                                        dragCrop = latest
                                        currentOnCropChange(latest, false)
                                    }
                                    if (latest != start) currentOnCropChange(latest, true)
                                    dragCrop = null
                                }
                            }
                        },
                    ),
            ) {
                drawCropOverlay(dragCrop ?: crop, enabled)
            }
        }
    }
}

private fun hitTestCrop(point: Offset, crop: CropRect, area: Size, radius: Float): CropHandle? {
    val l = crop.left * area.width
    val t = crop.top * area.height
    val r = crop.right * area.width
    val b = crop.bottom * area.height

    fun near(x: Float, y: Float) = abs(point.x - x) <= radius && abs(point.y - y) <= radius

    when {
        near(l, t) -> return CropHandle.TopLeft
        near(r, t) -> return CropHandle.TopRight
        near(r, b) -> return CropHandle.BottomRight
        near(l, b) -> return CropHandle.BottomLeft
    }

    val withinX = point.x in (l - radius)..(r + radius)
    val withinY = point.y in (t - radius)..(b + radius)
    return when {
        withinY && abs(point.x - l) <= radius -> CropHandle.Left
        withinY && abs(point.x - r) <= radius -> CropHandle.Right
        withinX && abs(point.y - t) <= radius -> CropHandle.Top
        withinX && abs(point.y - b) <= radius -> CropHandle.Bottom
        point.x in l..r && point.y in t..b -> CropHandle.Move
        else -> null
    }
}

private fun DrawScope.drawCropOverlay(crop: CropRect, enabled: Boolean) {
    val l = crop.left * size.width
    val t = crop.top * size.height
    val r = crop.right * size.width
    val b = crop.bottom * size.height
    val shade = Color.Black.copy(alpha = 0.6f)

    drawRect(shade, topLeft = Offset.Zero, size = Size(size.width, t))
    drawRect(shade, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
    drawRect(shade, topLeft = Offset(0f, t), size = Size(l, b - t))
    drawRect(shade, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

    val thirdsColor = Color.White.copy(alpha = 0.3f)
    val thin = 1.dp.toPx()
    for (i in 1..2) {
        val x = l + (r - l) * i / 3f
        val y = t + (b - t) * i / 3f
        drawLine(thirdsColor, Offset(x, t), Offset(x, b), strokeWidth = thin)
        drawLine(thirdsColor, Offset(l, y), Offset(r, y), strokeWidth = thin)
    }

    val frameColor = if (enabled) Color.White else FlowColors.TextTertiary
    drawRect(
        color = frameColor,
        topLeft = Offset(l, t),
        size = Size(r - l, b - t),
        style = Stroke(width = 1.5.dp.toPx()),
    )

    if (!enabled) return
    val arm = minOf(14.dp.toPx(), (r - l) / 3f, (b - t) / 3f)
    val thick = 3.dp.toPx()
    val accent = FlowColors.Accent
    listOf(
        Triple(Offset(l, t), 1f, 1f),
        Triple(Offset(r, t), -1f, 1f),
        Triple(Offset(r, b), -1f, -1f),
        Triple(Offset(l, b), 1f, -1f),
    ).forEach { (corner, sx, sy) ->
        drawLine(accent, corner, Offset(corner.x + arm * sx, corner.y), strokeWidth = thick)
        drawLine(accent, corner, Offset(corner.x, corner.y + arm * sy), strokeWidth = thick)
    }
}

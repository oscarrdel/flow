package com.rainyworks.flow.feature.editor.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.rainyworks.flow.core.model.CropRect
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.Transform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val MIN_TRANSFORM_SCALE = 0.1f
internal const val MAX_TRANSFORM_SCALE = 5f
private const val MIN_CROP_SIZE = 0.08f
private const val CROP_EPSILON = 0.001f

/**
 * On-canvas placement of a clip: a rectangle of [width] x [height] centered at [center],
 * rotated clockwise by [rotationDegrees]. All values are in canvas pixels.
 */
internal data class ClipFrame(
    val center: Offset,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float,
) {
    /** Corners in clockwise order starting at the (unrotated) top-left one. */
    fun corners(): List<Offset> {
        val radians = rotationDegrees * PI.toFloat() / 180f
        val c = cos(radians)
        val s = sin(radians)
        val hw = width / 2f
        val hh = height / 2f
        return listOf(
            Offset(-hw, -hh),
            Offset(hw, -hh),
            Offset(hw, hh),
            Offset(-hw, hh),
        ).map { p -> Offset(center.x + p.x * c - p.y * s, center.y + p.x * s + p.y * c) }
    }

    /** Hit test in canvas pixels, expanding every side by [slop]. */
    fun contains(point: Offset, slop: Float = 0f): Boolean {
        val radians = -rotationDegrees * PI.toFloat() / 180f
        val c = cos(radians)
        val s = sin(radians)
        val d = point - center
        val localX = d.x * c - d.y * s
        val localY = d.x * s + d.y * c
        return abs(localX) <= width / 2f + slop && abs(localY) <= height / 2f + slop
    }
}

/**
 * Size of the visible source (after the crop) in source pixels. Clips without intrinsic
 * dimensions take the canvas proportions.
 */
internal fun croppedSourceSize(media: MediaRef?, crop: CropRect, canvasSize: Size): Size {
    val sourceWidth = media?.displayWidth?.takeIf { it > 0 }?.toFloat() ?: canvasSize.width
    val sourceHeight = media?.displayHeight?.takeIf { it > 0 }?.toFloat() ?: canvasSize.height
    return Size(
        width = (sourceWidth * (crop.right - crop.left)).coerceAtLeast(1f),
        height = (sourceHeight * (crop.bottom - crop.top)).coerceAtLeast(1f),
    )
}

/**
 * Computes where a clip lands on the canvas: the cropped source is fitted inside the canvas
 * (contain) at scale 1, then scaled, rotated and moved by the transform offsets, which are
 * fractions of the canvas width/height measured from its center.
 */
internal fun clipFrameFor(canvasSize: Size, media: MediaRef?, transform: Transform): ClipFrame {
    val source = croppedSourceSize(media, transform.crop, canvasSize)
    val fit = minOf(canvasSize.width / source.width, canvasSize.height / source.height)
    val scale = transform.scale.coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
    return ClipFrame(
        center = Offset(
            x = canvasSize.width / 2f + transform.offsetX * canvasSize.width,
            y = canvasSize.height / 2f + transform.offsetY * canvasSize.height,
        ),
        width = source.width * fit * scale,
        height = source.height * fit * scale,
        rotationDegrees = transform.rotationDegrees,
    )
}

/** Wraps an angle into (-180, 180]. */
internal fun normalizeDegrees(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/** Returns the nearest right angle when [degrees] is within [threshold] of it, otherwise null. */
internal fun snapToRightAngle(degrees: Float, threshold: Float): Float? {
    val normalized = normalizeDegrees(degrees)
    val nearest = (normalized / 90f).roundToInt() * 90f
    return if (abs(normalized - nearest) < threshold) normalizeDegrees(nearest) else null
}

// region Crop math

internal enum class CropHandle(
    val left: Boolean,
    val top: Boolean,
    val right: Boolean,
    val bottom: Boolean,
) {
    TopLeft(left = true, top = true, right = false, bottom = false),
    Top(left = false, top = true, right = false, bottom = false),
    TopRight(left = false, top = true, right = true, bottom = false),
    Right(left = false, top = false, right = true, bottom = false),
    BottomRight(left = false, top = false, right = true, bottom = true),
    Bottom(left = false, top = false, right = false, bottom = true),
    BottomLeft(left = true, top = false, right = false, bottom = true),
    Left(left = true, top = false, right = false, bottom = false),
    Move(left = false, top = false, right = false, bottom = false),
    ;

    val isCorner: Boolean get() = (left || right) && (top || bottom)
}

/** Builds a valid [CropRect], clamping into the unit square with a minimal positive size. */
internal fun safeCropRect(left: Float, top: Float, right: Float, bottom: Float): CropRect {
    val l = left.coerceIn(0f, 1f - CROP_EPSILON)
    val t = top.coerceIn(0f, 1f - CROP_EPSILON)
    val r = right.coerceIn(l + CROP_EPSILON, 1f)
    val b = bottom.coerceIn(t + CROP_EPSILON, 1f)
    return CropRect(l, t, r, b)
}

/**
 * Largest crop with the normalized proportion [ratio] (width/height in unit-square space)
 * that fits the frame, centered as close as possible to the center of [current].
 */
internal fun fitCropToRatio(current: CropRect, ratio: Float): CropRect {
    val k = ratio.coerceAtLeast(0.01f)
    val w = if (k >= 1f) 1f else k
    val h = if (k >= 1f) 1f / k else 1f
    val cx = (current.left + current.right) / 2f
    val cy = (current.top + current.bottom) / 2f
    val l = (cx - w / 2f).coerceIn(0f, 1f - w)
    val t = (cy - h / 2f).coerceIn(0f, 1f - h)
    return safeCropRect(l, t, l + w, t + h)
}

/**
 * Applies a drag of ([dx], [dy]) (normalized, total since the gesture started) on [handle]
 * to [start]. When [ratio] is not null the normalized width/height proportion is kept.
 */
internal fun resizeCrop(start: CropRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float?): CropRect {
    if (handle == CropHandle.Move) {
        val w = start.right - start.left
        val h = start.bottom - start.top
        val l = (start.left + dx).coerceIn(0f, 1f - w)
        val t = (start.top + dy).coerceIn(0f, 1f - h)
        return safeCropRect(l, t, l + w, t + h)
    }
    if (ratio == null) {
        var l = start.left
        var t = start.top
        var r = start.right
        var b = start.bottom
        if (handle.left) l = (l + dx).coerceIn(0f, r - MIN_CROP_SIZE)
        if (handle.right) r = (r + dx).coerceIn(l + MIN_CROP_SIZE, 1f)
        if (handle.top) t = (t + dy).coerceIn(0f, b - MIN_CROP_SIZE)
        if (handle.bottom) b = (b + dy).coerceIn(t + MIN_CROP_SIZE, 1f)
        return safeCropRect(l, t, r, b)
    }
    val k = ratio.coerceAtLeast(0.01f)
    return if (handle.isCorner) resizeCornerLocked(start, handle, dx, dy, k) else resizeEdgeLocked(start, handle, dx, dy, k)
}

private fun resizeCornerLocked(start: CropRect, handle: CropHandle, dx: Float, dy: Float, k: Float): CropRect {
    val anchorX = if (handle.left) start.right else start.left
    val anchorY = if (handle.top) start.bottom else start.top
    val movingX = (if (handle.left) start.left else start.right) + dx
    val movingY = (if (handle.top) start.top else start.bottom) + dy
    val maxW = if (handle.left) anchorX else 1f - anchorX
    val maxH = if (handle.top) anchorY else 1f - anchorY
    var w = (if (handle.left) anchorX - movingX else movingX - anchorX).coerceAtLeast(MIN_CROP_SIZE)
    var h = (if (handle.top) anchorY - movingY else movingY - anchorY).coerceAtLeast(MIN_CROP_SIZE)
    if (w / h > k) h = w / k else w = h * k
    val (fw, fh) = fitLocked(w, h, maxW, maxH, k)
    val l = if (handle.left) anchorX - fw else anchorX
    val t = if (handle.top) anchorY - fh else anchorY
    return safeCropRect(l, t, l + fw, t + fh)
}

private fun resizeEdgeLocked(start: CropRect, handle: CropHandle, dx: Float, dy: Float, k: Float): CropRect {
    val cx = (start.left + start.right) / 2f
    val cy = (start.top + start.bottom) / 2f
    return if (handle.left || handle.right) {
        val anchorX = if (handle.left) start.right else start.left
        val maxW = if (handle.left) anchorX else 1f - anchorX
        val moving = (if (handle.left) start.left else start.right) + dx
        val proposed = (if (handle.left) anchorX - moving else moving - anchorX).coerceAtLeast(MIN_CROP_SIZE)
        val (w, h) = fitLocked(proposed, proposed / k, maxW, 1f, k)
        val l = if (handle.left) anchorX - w else anchorX
        val t = (cy - h / 2f).coerceIn(0f, 1f - h)
        safeCropRect(l, t, l + w, t + h)
    } else {
        val anchorY = if (handle.top) start.bottom else start.top
        val maxH = if (handle.top) anchorY else 1f - anchorY
        val moving = (if (handle.top) start.top else start.bottom) + dy
        val proposed = (if (handle.top) anchorY - moving else moving - anchorY).coerceAtLeast(MIN_CROP_SIZE)
        val (w, h) = fitLocked(proposed * k, proposed, 1f, maxH, k)
        val t = if (handle.top) anchorY - h else anchorY
        val l = (cx - w / 2f).coerceIn(0f, 1f - w)
        safeCropRect(l, t, l + w, t + h)
    }
}

private fun fitLocked(width: Float, height: Float, maxWidth: Float, maxHeight: Float, k: Float): Pair<Float, Float> {
    var w = width
    var h = height
    if (w > maxWidth) {
        w = maxWidth
        h = w / k
    }
    if (h > maxHeight) {
        h = maxHeight
        w = h * k
    }
    return w to h
}

// endregion

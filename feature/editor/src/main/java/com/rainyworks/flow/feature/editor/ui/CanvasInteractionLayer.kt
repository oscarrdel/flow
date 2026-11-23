package com.rainyworks.flow.feature.editor.ui

import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.roundToInt
import kotlin.math.atan2
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipAnimations
import com.rainyworks.flow.core.model.Mask
import com.rainyworks.flow.core.model.MaskShape
import com.rainyworks.flow.core.model.Transform
import com.rainyworks.flow.feature.editor.EditorAction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val CENTER_SNAP_DISTANCE = 8.dp
private val HIT_SLOP = 28.dp
/** Touch radius of the corner and rotation handles. */
private val HANDLE_TOUCH = 26.dp
private val ROTATE_HANDLE_GAP = 30.dp
/** Within this many degrees of a right angle the clip stays put at the right angle. */
private const val ROTATION_SNAP_DEGREES = 6f

private const val SAFE_TOP_FRACTION = 0.12f
private const val SAFE_BOTTOM_FRACTION = 0.20f
private const val SAFE_RIGHT_FRACTION = 0.15f

/**
 * Transparent layer laid exactly over the preview canvas. It outlines the selected clip,
 * lets the user move it with one finger and scale/rotate it with two, snapping to the
 * canvas center lines and to right angles, and optionally shows social media safe zones.
 *
 * The layer never consumes the initial down event, so the double tap handled by the
 * preview container keeps toggling playback.
 *
 * @param clip selected visual clip visible under the playhead, or null when nothing can be edited.
 * @param showSafeZones draws the typical vertical video safe zones.
 */
@Composable
internal fun CanvasInteractionLayer(
    clip: Clip?,
    showSafeZones: Boolean,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
    textRenderer: TextRenderer = rememberTextRenderer(),
    playheadUs: () -> Long = { 0L },
    editMask: Boolean = false,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val snapDistancePx = with(density) { CENTER_SNAP_DISTANCE.toPx() }
    val hitSlopPx = with(density) { HIT_SLOP.toPx() }

    val currentClip by rememberUpdatedState(clip)
    val currentOnAction by rememberUpdatedState(onAction)
    val currentTextRenderer by rememberUpdatedState(textRenderer)
    val currentPlayheadUs by rememberUpdatedState(playheadUs)
    val currentEditMask by rememberUpdatedState(editMask)

    var gestureTransform by remember { mutableStateOf<Transform?>(null) }
    var gestureMask by remember { mutableStateOf<Mask?>(null) }
    var guideVertical by remember { mutableStateOf(false) }
    var guideHorizontal by remember { mutableStateOf(false) }
    var rotationGuide by remember { mutableStateOf(false) }
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    val textMeasurer = rememberTextMeasurer()

    val clipId = clip?.id
    val gestureModifier = if (clipId == null) {
        Modifier
    } else {
        Modifier.pointerInput(clipId, snapDistancePx, hitSlopPx, editMask) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val target = currentClip ?: return@awaitEachGesture
                val canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                if (canvasSize.width <= 0f || canvasSize.height <= 0f) return@awaitEachGesture

                val now = currentPlayheadUs()
                // Gestures edit the clip's own (keyframed) transform; entrance/exit motion is a
                // temporary offset and must not be baked into it.
                val (startTransform, _) = ClipAnimations.renderedTransform(
                    target.copy(enterAnimation = null, exitAnimation = null),
                    now,
                )
                // Touches are tested against what is visible (with entrance/exit motion), while
                // the edit applies to the clip's own transform.
                val clipFrame = frameForClip(
                    target,
                    ClipAnimations.renderedTransform(target, now).first,
                    canvasSize,
                    currentTextRenderer,
                )
                val activeMask = if (currentEditMask) target.animatedMaskAt(now) else null

                if (activeMask != null) {
                    var active = false
                    var slop = Offset.Zero
                    var rawOffsetX = activeMask.offsetX
                    var rawOffsetY = activeMask.offsetY
                    var rawScale = activeMask.scale
                    var rawRotation = activeMask.rotationDegrees
                    var latestMask = activeMask

                    val clipRad = -clipFrame.rotationDegrees * (PI.toFloat() / 180f)
                    val cosClip = cos(clipRad)
                    val sinClip = sin(clipRad)
                    val safeClipW = clipFrame.width.coerceAtLeast(1f)
                    val safeClipH = clipFrame.height.coerceAtLeast(1f)

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }

                        if (!active) {
                            if (pressedCount >= 2) {
                                active = true
                            } else {
                                slop += event.calculatePan()
                                if (slop.getDistance() > viewConfiguration.touchSlop) active = true
                            }
                        }

                        if (active && pressedCount > 0) {
                            val pan = event.calculatePan()
                            val localPanX = pan.x * cosClip - pan.y * sinClip
                            val localPanY = pan.x * sinClip + pan.y * cosClip
                            rawOffsetX = (rawOffsetX + localPanX / safeClipW).coerceIn(-1f, 1f)
                            rawOffsetY = (rawOffsetY + localPanY / safeClipH).coerceIn(-1f, 1f)
                            rawScale = (rawScale * event.calculateZoom())
                                .coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
                            rawRotation = normalizeDegrees(rawRotation + event.calculateRotation())

                            latestMask = activeMask.copy(
                                offsetX = rawOffsetX,
                                offsetY = rawOffsetY,
                                scale = rawScale,
                                rotationDegrees = rawRotation,
                            )
                            gestureMask = latestMask
                            currentOnAction(EditorAction.SetMask(target.id, latestMask, final = false))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (active) {
                        currentOnAction(EditorAction.SetMask(target.id, latestMask, final = true))
                    }
                    gestureMask = null
                    return@awaitEachGesture
                }

                val handleSlopPx = HANDLE_TOUCH.toPx()
                val rotateKnob = rotateHandleCenter(clipFrame, ROTATE_HANDLE_GAP.toPx(), canvasSize)
                val mode = when {
                    (down.position - rotateKnob).getDistance() <= handleSlopPx -> DragMode.Rotate
                    clipFrame.corners().any { (down.position - it).getDistance() <= handleSlopPx } -> DragMode.Resize
                    clipFrame.contains(down.position, hitSlopPx) -> DragMode.Move
                    else -> DragMode.None
                }
                val center = clipFrame.center
                val startAngle = angleDegrees(down.position - center)
                val startDistance = (down.position - center).getDistance().coerceAtLeast(1f)

                var active = false
                var pinching = false
                var slop = Offset.Zero
                var rawOffsetX = startTransform.offsetX
                var rawOffsetY = startTransform.offsetY
                var rawScale = startTransform.scale
                var rawRotation = startTransform.rotationDegrees
                var latest = startTransform

                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }

                    if (pressed.size >= 2 && !pinching) {
                        // A second finger always means pinch: continue from where the drag is.
                        pinching = true
                        active = true
                    }
                    if (!active && mode != DragMode.None) {
                        slop += event.calculatePan()
                        if (slop.getDistance() > viewConfiguration.touchSlop) active = true
                    }

                    if (active && pressed.isNotEmpty()) {
                        val pointer = pressed.first().position
                        when {
                            pinching || mode == DragMode.Move -> {
                                val pan = event.calculatePan()
                                rawOffsetX += pan.x / canvasSize.width
                                rawOffsetY += pan.y / canvasSize.height
                                rawScale = (rawScale * event.calculateZoom())
                                    .coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
                                rawRotation += event.calculateRotation()
                            }
                            mode == DragMode.Rotate -> {
                                rawRotation = startTransform.rotationDegrees +
                                    shortestDelta(startAngle, angleDegrees(pointer - center))
                            }
                            mode == DragMode.Resize -> {
                                val ratio = (pointer - center).getDistance() / startDistance
                                rawScale = (startTransform.scale * ratio)
                                    .coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
                            }
                        }

                        val moving = pinching || mode == DragMode.Move
                        val snapX = moving && abs(rawOffsetX * canvasSize.width) < snapDistancePx
                        val snapY = moving && abs(rawOffsetY * canvasSize.height) < snapDistancePx
                        val turning = pinching || mode == DragMode.Rotate
                        val snappedRotation = if (turning) {
                            snapToRightAngle(rawRotation, ROTATION_SNAP_DEGREES)
                        } else {
                            null
                        }

                        val enteredGuide = (snapX && !guideVertical) ||
                            (snapY && !guideHorizontal) ||
                            (snappedRotation != null && !rotationGuide)
                        if (enteredGuide) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        guideVertical = snapX
                        guideHorizontal = snapY
                        rotationGuide = snappedRotation != null

                        latest = startTransform.copy(
                            offsetX = if (snapX) 0f else rawOffsetX,
                            offsetY = if (snapY) 0f else rawOffsetY,
                            scale = rawScale,
                            rotationDegrees = snappedRotation ?: normalizeDegrees(rawRotation),
                        )
                        gestureTransform = latest
                        gestureLabel = when {
                            turning -> "${latest.rotationDegrees.roundToInt()}°"
                            mode == DragMode.Resize -> "${(latest.scale * 100).roundToInt()} %"
                            else -> null
                        }
                        currentOnAction(EditorAction.SetTransform(target.id, latest, final = false))
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })

                if (active) {
                    currentOnAction(EditorAction.SetTransform(target.id, latest, final = true))
                }
                rotationGuide = false
                gestureLabel = null
                gestureTransform = null
                guideVertical = false
                guideHorizontal = false
            }
        }
    }

    Canvas(modifier = modifier.then(gestureModifier)) {
        if (showSafeZones) {
            drawSafeZones()
        }

        val target = clip ?: return@Canvas
        val now = playheadUs()
        val (renderedTransform, opacity) = ClipAnimations.renderedTransform(target, now)
        val transform = gestureTransform ?: renderedTransform
        val frame = frameForClip(target, transform, size, textRenderer)
        drawClipFrame(frame, alpha = opacity.coerceIn(0.35f, 1f), showHandles = !editMask)
        if (rotationGuide) drawRightAngleCross(frame)
        gestureLabel?.let { label ->
            val knob = rotateHandleCenter(frame, ROTATE_HANDLE_GAP.toPx(), size)
            drawGestureLabel(textMeasurer, label, Offset(knob.x, knob.y - 26.dp.toPx()))
        }

        if (editMask) {
            val maskToDraw = gestureMask ?: target.animatedMaskAt(now)
            if (maskToDraw != null) {
                drawMaskFrame(frame, maskToDraw)
            }
        }

        val guideEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        val guideStroke = 1.dp.toPx()
        if (guideVertical) {
            drawLine(
                color = FlowColors.Accent,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = guideStroke,
                pathEffect = guideEffect,
            )
        }
        if (guideHorizontal) {
            drawLine(
                color = FlowColors.Accent,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = guideStroke,
                pathEffect = guideEffect,
            )
        }
    }
}

private fun frameForClip(
    clip: Clip,
    transform: Transform,
    canvasSize: Size,
    textRenderer: TextRenderer,
): ClipFrame = textRenderer.frameFor(clip, transform, canvasSize)
    ?: clipFrameFor(canvasSize, clip.media, transform)

private enum class DragMode { None, Move, Resize, Rotate }

/**
 * Knob above the top edge of the frame, turned with the clip. When that spot is off the canvas
 * (a clip filling the frame) it moves below the bottom edge, and failing that just inside the top.
 */
private fun rotateHandleCenter(frame: ClipFrame, gap: Float, canvas: Size): Offset {
    val rad = frame.rotationDegrees * (PI.toFloat() / 180f)
    // "Up" in the clip's own space is (0, -1), rotated clockwise by the clip angle.
    val up = Offset(sin(rad), -cos(rad))
    val half = frame.height / 2f
    val margin = 12f
    fun inside(p: Offset) = p.x in margin..(canvas.width - margin) && p.y in margin..(canvas.height - margin)
    val above = frame.center + up * (half + gap)
    if (inside(above)) return above
    val below = frame.center - up * (half + gap)
    if (inside(below)) return below
    return frame.center + up * (half - gap).coerceAtLeast(0f)
}

/** Point on the frame edge facing the knob, where its stem starts. */
private fun stemStart(frame: ClipFrame, knob: Offset): Offset {
    val v = knob - frame.center
    val len = v.getDistance().coerceAtLeast(1f)
    return frame.center + v * ((frame.height / 2f).coerceAtMost(len) / len)
}

private fun angleDegrees(v: Offset): Float = atan2(v.y, v.x) * (180f / PI.toFloat())

/** Signed change from [from] to [to] in (-180, 180], so crossing ±180 does not jump. */
private fun shortestDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

private fun DrawScope.drawClipFrame(frame: ClipFrame, alpha: Float = 1f, showHandles: Boolean = true) {
    val corners = frame.corners()
    val outline = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
        close()
    }
    drawPath(outline, color = Color.Black.copy(alpha = 0.35f * alpha), style = Stroke(width = 3.dp.toPx()))
    drawPath(outline, color = FlowColors.Accent.copy(alpha = alpha), style = Stroke(width = 1.5.dp.toPx()))
    if (!showHandles) return

    // Corners: drag to resize.
    val handleRadius = 7.dp.toPx()
    for (corner in corners) {
        drawCircle(color = Color.Black.copy(alpha = 0.3f * alpha), radius = handleRadius + 1.5.dp.toPx(), center = corner)
        drawCircle(color = Color.White.copy(alpha = alpha), radius = handleRadius, center = corner)
        drawCircle(
            color = FlowColors.Accent.copy(alpha = alpha),
            radius = handleRadius,
            center = corner,
            style = Stroke(width = 2.dp.toPx()),
        )
    }

    // Stem and knob: drag to turn.
    val knob = rotateHandleCenter(frame, ROTATE_HANDLE_GAP.toPx(), size)
    drawLine(FlowColors.Accent.copy(alpha = alpha), stemStart(frame, knob), knob, strokeWidth = 1.5.dp.toPx())
    val knobRadius = 11.dp.toPx()
    drawCircle(color = Color.Black.copy(alpha = 0.3f * alpha), radius = knobRadius + 1.5.dp.toPx(), center = knob)
    drawCircle(color = FlowColors.Accent.copy(alpha = alpha), radius = knobRadius, center = knob)
    // Circular arrow glyph.
    val r = 5.dp.toPx()
    drawArc(
        color = FlowColors.OnAccent.copy(alpha = alpha),
        startAngle = -60f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(knob.x - r, knob.y - r),
        size = Size(r * 2, r * 2),
        style = Stroke(width = 1.8.dp.toPx()),
    )
    val tipAngle = (-60f) * (PI.toFloat() / 180f)
    val tip = Offset(knob.x + r * cos(tipAngle), knob.y + r * sin(tipAngle))
    val arrow = Path().apply {
        moveTo(tip.x + 2.5.dp.toPx(), tip.y - 1.dp.toPx())
        lineTo(tip.x - 1.dp.toPx(), tip.y - 2.5.dp.toPx())
        lineTo(tip.x + 0.5.dp.toPx(), tip.y + 2.5.dp.toPx())
        close()
    }
    drawPath(arrow, color = FlowColors.OnAccent.copy(alpha = alpha))
}

/** Cross through the clip center along the screen axes: the clip sits exactly at a right angle. */
private fun DrawScope.drawRightAngleCross(frame: ClipFrame) {
    val reach = maxOf(frame.width, frame.height) / 2f + 36.dp.toPx()
    val c = frame.center
    val stroke = 1.5.dp.toPx()
    val color = FlowColors.Turquesa
    clipRect {
        for ((a, b) in listOf(
            Offset(c.x - reach, c.y) to Offset(c.x + reach, c.y),
            Offset(c.x, c.y - reach) to Offset(c.x, c.y + reach),
        )) {
            drawLine(Color.Black.copy(alpha = 0.4f), a, b, strokeWidth = stroke + 2.dp.toPx())
            drawLine(color, a, b, strokeWidth = stroke)
        }
        drawCircle(color, radius = 4.dp.toPx(), center = c)
    }
}

private fun DrawScope.drawGestureLabel(measurer: TextMeasurer, text: String, anchor: Offset) {
    val layout = measurer.measure(
        text,
        style = TextStyle(color = FlowColors.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold),
    )
    val padX = 8.dp.toPx()
    val padY = 3.dp.toPx()
    val w = layout.size.width + padX * 2
    val h = layout.size.height + padY * 2
    val left = (anchor.x - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val top = (anchor.y - h / 2f).coerceIn(0f, (size.height - h).coerceAtLeast(0f))
    drawRoundRect(
        color = FlowColors.Accent,
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f),
    )
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}

private fun DrawScope.drawMaskFrame(clipFrame: ClipFrame, mask: Mask) {
    val clipRad = clipFrame.rotationDegrees * (PI.toFloat() / 180f)
    val cosC = cos(clipRad)
    val sinC = sin(clipRad)
    val localX = mask.offsetX * clipFrame.width
    val localY = mask.offsetY * clipFrame.height
    val maskCenter = Offset(
        x = clipFrame.center.x + localX * cosC - localY * sinC,
        y = clipFrame.center.y + localX * sinC + localY * cosC,
    )
    val totalRotation = clipFrame.rotationDegrees + mask.rotationDegrees
    val scale = mask.scale.coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
    val baseRadius = (minOf(clipFrame.width, clipFrame.height) * 0.35f * scale).coerceAtLeast(10.dp.toPx())
    val underStroke = Stroke(width = 3.dp.toPx())
    val mainStroke = Stroke(width = 1.75.dp.toPx())

    withTransform({
        translate(maskCenter.x, maskCenter.y)
        rotate(totalRotation, pivot = Offset.Zero)
    }) {
        when (mask.shape) {
            MaskShape.SPLIT -> {
                val halfLen = maxOf(clipFrame.width, clipFrame.height) * 0.75f
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(-halfLen, 0f),
                    end = Offset(halfLen, 0f),
                    strokeWidth = 3.dp.toPx(),
                )
                drawLine(
                    color = FlowColors.Keyframe,
                    start = Offset(-halfLen, 0f),
                    end = Offset(halfLen, 0f),
                    strokeWidth = 1.75.dp.toPx(),
                )
                if (mask.feather > 0.01f) {
                    val featherOffset = mask.feather * baseRadius * 0.6f
                    val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                    drawLine(
                        color = FlowColors.Keyframe.copy(alpha = 0.65f),
                        start = Offset(-halfLen, -featherOffset),
                        end = Offset(halfLen, -featherOffset),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                    drawLine(
                        color = FlowColors.Keyframe.copy(alpha = 0.65f),
                        start = Offset(-halfLen, featherOffset),
                        end = Offset(halfLen, featherOffset),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }
            }
            MaskShape.CIRCLE -> {
                drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = baseRadius, center = Offset.Zero, style = underStroke)
                drawCircle(color = FlowColors.Keyframe, radius = baseRadius, center = Offset.Zero, style = mainStroke)
            }
            MaskShape.RECTANGLE -> {
                val rectW = (clipFrame.width * 0.6f * scale).coerceAtLeast(16.dp.toPx())
                val rectH = (clipFrame.height * 0.6f * scale).coerceAtLeast(16.dp.toPx())
                val path = buildRectangleMaskPath(Offset.Zero, rectW, rectH, mask.cornerRadius)
                drawPath(path, color = Color.Black.copy(alpha = 0.5f), style = underStroke)
                drawPath(path, color = FlowColors.Keyframe, style = mainStroke)
            }
            MaskShape.HEART -> {
                val path = buildHeartPath(Offset.Zero, baseRadius)
                drawPath(path, color = Color.Black.copy(alpha = 0.5f), style = underStroke)
                drawPath(path, color = FlowColors.Keyframe, style = mainStroke)
            }
            MaskShape.STAR -> {
                val path = buildStarPath(Offset.Zero, baseRadius, baseRadius * 0.44f)
                drawPath(path, color = Color.Black.copy(alpha = 0.5f), style = underStroke)
                drawPath(path, color = FlowColors.Keyframe, style = mainStroke)
            }
        }
        drawCircle(color = Color.White, radius = 4.5.dp.toPx(), center = Offset.Zero)
        drawCircle(
            color = FlowColors.Keyframe,
            radius = 4.5.dp.toPx(),
            center = Offset.Zero,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

private fun DrawScope.drawSafeZones() {
    val zoneColor = FlowColors.Marker.copy(alpha = 0.16f)
    val topBottom = size.height * SAFE_TOP_FRACTION
    val bottomTop = size.height * (1f - SAFE_BOTTOM_FRACTION)
    val rightLeft = size.width * (1f - SAFE_RIGHT_FRACTION)

    drawRect(color = zoneColor, topLeft = Offset.Zero, size = Size(size.width, topBottom))
    drawRect(color = zoneColor, topLeft = Offset(0f, bottomTop), size = Size(size.width, size.height - bottomTop))
    drawRect(
        color = zoneColor,
        topLeft = Offset(rightLeft, topBottom),
        size = Size(size.width - rightLeft, bottomTop - topBottom),
    )

    val safeArea = Rect(left = 0f, top = topBottom, right = rightLeft, bottom = bottomTop)
    drawRect(
        color = Color.White.copy(alpha = 0.55f),
        topLeft = safeArea.topLeft,
        size = safeArea.size,
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        ),
    )
}

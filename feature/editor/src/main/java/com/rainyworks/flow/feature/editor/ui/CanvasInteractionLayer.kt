package com.rainyworks.flow.feature.editor.ui

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
private val HIT_SLOP = 20.dp
private const val ROTATION_SNAP_DEGREES = 4f

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

                val startedOnClip = clipFrame.contains(down.position, hitSlopPx)

                var active = false
                var slop = Offset.Zero
                var rawOffsetX = startTransform.offsetX
                var rawOffsetY = startTransform.offsetY
                var rawScale = startTransform.scale
                var rawRotation = startTransform.rotationDegrees
                var rotationSnapped = false
                var latest = startTransform

                do {
                    val event = awaitPointerEvent()
                    val pressedCount = event.changes.count { it.pressed }

                    if (!active) {
                        if (pressedCount >= 2) {
                            active = true
                        } else if (startedOnClip) {
                            slop += event.calculatePan()
                            if (slop.getDistance() > viewConfiguration.touchSlop) active = true
                        }
                    }

                    if (active && pressedCount > 0) {
                        val pan = event.calculatePan()
                        rawOffsetX += pan.x / canvasSize.width
                        rawOffsetY += pan.y / canvasSize.height
                        rawScale = (rawScale * event.calculateZoom())
                            .coerceIn(MIN_TRANSFORM_SCALE, MAX_TRANSFORM_SCALE)
                        rawRotation += event.calculateRotation()

                        val snapX = abs(rawOffsetX * canvasSize.width) < snapDistancePx
                        val snapY = abs(rawOffsetY * canvasSize.height) < snapDistancePx
                        val snappedRotation = snapToRightAngle(rawRotation, ROTATION_SNAP_DEGREES)

                        val enteredGuide = (snapX && !guideVertical) ||
                            (snapY && !guideHorizontal) ||
                            (snappedRotation != null && !rotationSnapped)
                        if (enteredGuide) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        guideVertical = snapX
                        guideHorizontal = snapY
                        rotationSnapped = snappedRotation != null

                        latest = startTransform.copy(
                            offsetX = if (snapX) 0f else rawOffsetX,
                            offsetY = if (snapY) 0f else rawOffsetY,
                            scale = rawScale,
                            rotationDegrees = snappedRotation ?: normalizeDegrees(rawRotation),
                        )
                        gestureTransform = latest
                        currentOnAction(EditorAction.SetTransform(target.id, latest, final = false))
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })

                if (active) {
                    currentOnAction(EditorAction.SetTransform(target.id, latest, final = true))
                }
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
        drawClipFrame(frame, alpha = opacity.coerceIn(0.35f, 1f))

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

private fun DrawScope.drawClipFrame(frame: ClipFrame, alpha: Float = 1f) {
    val corners = frame.corners()
    val outline = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
        close()
    }
    drawPath(outline, color = Color.Black.copy(alpha = 0.35f * alpha), style = Stroke(width = 3.dp.toPx()))
    drawPath(outline, color = FlowColors.Accent.copy(alpha = alpha), style = Stroke(width = 1.5.dp.toPx()))

    val handleRadius = 5.dp.toPx()
    for (corner in corners) {
        drawCircle(color = Color.White.copy(alpha = alpha), radius = handleRadius, center = corner)
        drawCircle(
            color = FlowColors.Accent.copy(alpha = alpha),
            radius = handleRadius,
            center = corner,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
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

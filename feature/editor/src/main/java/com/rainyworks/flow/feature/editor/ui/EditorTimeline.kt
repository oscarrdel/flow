package com.rainyworks.flow.feature.editor.ui

import com.rainyworks.flow.core.designsystem.components.glassBackdropSource
import com.rainyworks.flow.core.designsystem.components.GlassBackdrop
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.designsystem.theme.TimecodeStyle
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipAnimations
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.EffectCatalog
import com.rainyworks.flow.core.model.Marker
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.Timecode
import com.rainyworks.flow.core.model.Track
import com.rainyworks.flow.core.model.TrackKind
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorUiState
import com.rainyworks.flow.feature.editor.TimelineMediaSource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val RULER_HEIGHT = 28.dp
private val MAIN_TRACK_HEIGHT = 62.dp
private val SECONDARY_TRACK_HEIGHT = 38.dp
private val THUMB_TILE_WIDTH = 52.dp
private val TRIM_HANDLE_WIDTH = 14.dp
private const val SNAP_THRESHOLD_DP = 7f
private const val WAVEFORM_BUCKETS_PER_SEC = 20

/**
 * Multi-track timeline with a fixed center playhead, pinch-to-zoom, horizontal scrub,
 * adaptive ruler with markers, lazy clip thumbnail strips, audio waveforms, edge trimming
 * with haptic snap, and long-press clip reordering on the main track.
 *
 * [playheadUs] is read lazily: horizontal positions are resolved in the layout and draw
 * phases, so playback only re-lays out and redraws instead of recomposing every clip.
 */
@Composable
internal fun EditorTimeline(
    project: Project?,
    playheadUs: () -> Long,
    selectedClipId: String?,
    pixelsPerSecond: Float,
    onAction: (EditorAction) -> Unit,
    mediaSource: TimelineMediaSource,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    backdrop: GlassBackdrop? = null,
) {
    val density = LocalDensity.current
    val spacing = FlowTheme.spacing

    val clampedPps = pixelsPerSecond.coerceIn(
        EditorUiState.MIN_PIXELS_PER_SECOND,
        EditorUiState.MAX_PIXELS_PER_SECOND,
    )
    val ppsPx = with(density) { clampedPps.dp.toPx() }.coerceAtLeast(1f)
    val maxTimelineUs = maxOf(project?.durationUs ?: 0L, 5_000_000L)

    // Local scrub playhead tracks user drag immediately; otherwise the external playhead is used.
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPlayheadUs by remember { mutableLongStateOf(0L) }
    val externalPlayheadUs by rememberUpdatedState(playheadUs)
    val activePlayheadUs: () -> Long = remember {
        { if (isScrubbing) scrubPlayheadUs else externalPlayheadUs() }
    }

    val currentOnAction by rememberUpdatedState(onAction)
    val currentPps by rememberUpdatedState(clampedPps)
    val currentPpsPx by rememberUpdatedState(ppsPx)
    val currentMaxTimelineUs by rememberUpdatedState(maxTimelineUs)

    // Clip boundaries used as trim snap points; the playhead is added when a trim happens.
    val snapPointsUs = remember(project) {
        buildList {
            project?.tracks?.forEach { track ->
                track.clips.forEach { clip ->
                    add(clip.startUs)
                    add(clip.endUs)
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnAction(EditorAction.SelectClip(null)) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var scrubbingGesture = false
                    var childOwnsGesture = false
                    var pendingPanX = 0f
                    var pendingPanY = 0f
                    val touchSlop = viewConfiguration.touchSlop
                    var latestScrubUs = activePlayheadUs()
                    var zoomAccumulator = currentPps

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        // Trim handles and clip reordering handle the event first (children run
                        // before parents). Once they claim a drag, the timeline never scrubs.
                        if (!scrubbingGesture && event.changes.any { it.isConsumed }) childOwnsGesture = true
                        if (childOwnsGesture) {
                            continue
                        } else if (pressedCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            if (abs(zoomChange - 1f) > 0.004f) {
                                zoomAccumulator = (zoomAccumulator * zoomChange).coerceIn(
                                    EditorUiState.MIN_PIXELS_PER_SECOND,
                                    EditorUiState.MAX_PIXELS_PER_SECOND,
                                )
                                currentOnAction(EditorAction.Zoom(zoomAccumulator))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressedCount == 1) {
                            val pan = event.calculatePan()
                            var panX = pan.x
                            if (!scrubbingGesture) {
                                // Wait for the same touch slop the handles use, so they get the first chance.
                                pendingPanX += pan.x
                                pendingPanY += pan.y
                                val horizontal = abs(pendingPanX) >= abs(pendingPanY) * 0.7f
                                if (abs(pendingPanX) <= touchSlop || !horizontal) continue
                                scrubbingGesture = true
                                latestScrubUs = activePlayheadUs()
                                scrubPlayheadUs = latestScrubUs
                                isScrubbing = true
                                panX = pendingPanX - touchSlop * kotlin.math.sign(pendingPanX)
                            }
                            val deltaUs = ((-panX / currentPpsPx) * Timecode.US_PER_SECOND).roundToLong()
                            latestScrubUs = (latestScrubUs + deltaUs).coerceIn(0L, currentMaxTimelineUs)
                            scrubPlayheadUs = latestScrubUs
                            currentOnAction(EditorAction.Seek(latestScrubUs, final = false))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (scrubbingGesture) {
                        isScrubbing = false
                        currentOnAction(EditorAction.Seek(latestScrubUs, final = true))
                    }
                }
            },
    ) {
        val viewportWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val centerOffsetPx = viewportWidthPx / 2f

        // Visible time range around the playhead, evaluated lazily by its readers.
        val halfWindowUs = ((viewportWidthPx / 2f) / ppsPx * Timecode.US_PER_SECOND).toLong() + 1_500_000L
        val visibleRangeUs: () -> LongRange = remember(halfWindowUs) {
            {
                val playhead = activePlayheadUs()
                (playhead - halfWindowUs).coerceAtLeast(0L)..(playhead + halfWindowUs)
            }
        }

        val tracksScrollState = rememberScrollState()

        Column(modifier = Modifier.fillMaxSize()) {
            TimelineRuler(
                playheadUs = activePlayheadUs,
                pixelsPerSecond = clampedPps,
                ppsPx = ppsPx,
                centerOffsetPx = centerOffsetPx,
                visibleRangeUs = visibleRangeUs,
                markers = project?.markers.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RULER_HEIGHT),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (backdrop != null) Modifier.glassBackdropSource(backdrop, hideBottom = bottomInset) else Modifier)
                    .verticalScroll(tracksScrollState)
                    .padding(top = spacing.sm, bottom = spacing.sm + bottomInset),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                val tracks = project?.tracks ?: listOf(Track.main())
                val mainTrack = tracks.firstOrNull { it.kind == TrackKind.MAIN } ?: Track.main()
                val secondaryTracks = tracks.filter { it.kind != TrackKind.MAIN }

                MainTrackLane(
                    track = mainTrack,
                    playheadUs = activePlayheadUs,
                    ppsPx = ppsPx,
                    centerOffsetPx = centerOffsetPx,
                    visibleRangeUs = visibleRangeUs,
                    selectedClipId = selectedClipId,
                    snapPointsUs = snapPointsUs,
                    mediaSource = mediaSource,
                    onAction = onAction,
                )

                val laneStepPx = with(LocalDensity.current) { (SECONDARY_TRACK_HEIGHT + spacing.sm).toPx() }
                secondaryTracks.forEachIndexed { laneIndex, track ->
                    key(track.id) {
                        SecondaryTrackLane(
                            track = track,
                            laneIndex = laneIndex,
                            lanes = secondaryTracks,
                            laneStepPx = laneStepPx,
                            playheadUs = activePlayheadUs,
                            ppsPx = ppsPx,
                            centerOffsetPx = centerOffsetPx,
                            visibleRangeUs = visibleRangeUs,
                            selectedClipId = selectedClipId,
                            snapPointsUs = snapPointsUs,
                            mediaSource = mediaSource,
                            onAction = onAction,
                        )
                    }
                }
            }
        }

        // Fixed center playhead line + cap
        FixedCenterPlayhead(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .padding(bottom = bottomInset),
        )
    }
}

@Composable
private fun FixedCenterPlayhead(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(24.dp)) {
        val centerX = size.width / 2f
        val top = 4.dp.toPx()
        val color = FlowColors.Accent
        // Glow first, then the line and its round cap on top.
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(color.copy(alpha = 0f), color.copy(alpha = 0.35f), color.copy(alpha = 0f)),
                startX = centerX - 8.dp.toPx(),
                endX = centerX + 8.dp.toPx(),
            ),
            start = Offset(centerX, top),
            end = Offset(centerX, size.height),
            strokeWidth = 16.dp.toPx(),
        )
        drawLine(
            color = color,
            start = Offset(centerX, top),
            end = Offset(centerX, size.height),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(color.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = Offset(centerX, top + 6.dp.toPx()))
        drawCircle(color, radius = 6.dp.toPx(), center = Offset(centerX, top + 6.dp.toPx()))
    }
}

@Composable
private fun TimelineRuler(
    playheadUs: () -> Long,
    pixelsPerSecond: Float,
    ppsPx: Float,
    centerOffsetPx: Float,
    visibleRangeUs: () -> LongRange,
    markers: List<Marker>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TimecodeStyle.copy(color = FlowColors.TextSecondary)

    val (majorStepUs, minorStepUs) = remember(pixelsPerSecond) {
        when {
            pixelsPerSecond >= 500f -> 200_000L to 50_000L
            pixelsPerSecond >= 220f -> 500_000L to 100_000L
            pixelsPerSecond >= 90f -> 1_000_000L to 200_000L
            pixelsPerSecond >= 45f -> 2_000_000L to 500_000L
            pixelsPerSecond >= 20f -> 5_000_000L to 1_000_000L
            else -> 10_000_000L to 2_000_000L
        }
    }

    Canvas(
        modifier = modifier,
    ) {
        val playhead = playheadUs()
        val range = visibleRangeUs()
        val firstTickUs = (range.first / minorStepUs) * minorStepUs
        val lastTickUs = range.last

        var tickUs = firstTickUs
        while (tickUs <= lastTickUs) {
            val x = centerOffsetPx + ((tickUs - playhead).toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat()
            if (x in -40f..(size.width + 40f)) {
                val isMajor = tickUs % majorStepUs == 0L
                val tickHeight = if (isMajor) 10.dp.toPx() else 5.dp.toPx()
                drawLine(
                    color = if (isMajor) FlowColors.Outline else FlowColors.Steel,
                    start = Offset(x, size.height - tickHeight),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                if (isMajor) {
                    val label = formatRulerLabel(tickUs, majorStepUs)
                    drawRulerLabel(
                        textMeasurer = textMeasurer,
                        text = label,
                        style = labelStyle,
                        x = x + 4.dp.toPx(),
                        y = 3.dp.toPx(),
                    )
                }
            }
            tickUs += minorStepUs
        }

        // Draw project markers as triangles of FlowColors.Marker
        val triHalfWidth = 5.dp.toPx()
        val triHeight = 8.dp.toPx()
        for (marker in markers) {
            if (marker.timeUs in range) {
                val markerX = centerOffsetPx +
                    ((marker.timeUs - playhead).toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat()
                val path = Path().apply {
                    moveTo(markerX - triHalfWidth, size.height - triHeight)
                    lineTo(markerX + triHalfWidth, size.height - triHeight)
                    lineTo(markerX, size.height)
                    close()
                }
                drawPath(path = path, color = FlowColors.Marker)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRulerLabel(
    textMeasurer: TextMeasurer,
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    x: Float,
    y: Float,
) {
    val result = textMeasurer.measure(text = text, style = style)
    if (x + result.size.width <= size.width) {
        drawText(textLayoutResult = result, topLeft = Offset(x, y))
    }
}

private fun formatRulerLabel(timeUs: Long, majorStepUs: Long): String {
    return if (majorStepUs < 1_000_000L) {
        val totalTenths = (timeUs / 100_000L)
        val seconds = (totalTenths / 10) % 60
        val minutes = (totalTenths / 10) / 60
        val tenths = totalTenths % 10
        String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
    } else {
        Timecode.formatShort(timeUs)
    }
}

@Composable
private fun MainTrackLane(
    track: Track,
    playheadUs: () -> Long,
    ppsPx: Float,
    centerOffsetPx: Float,
    visibleRangeUs: () -> LongRange,
    selectedClipId: String?,
    snapPointsUs: List<Long>,
    mediaSource: TimelineMediaSource,
    onAction: (EditorAction) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var draggingClipId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MAIN_TRACK_HEIGHT),
    ) {
        // Subtle track baseline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MAIN_TRACK_HEIGHT)
                .background(Color.White.copy(alpha = 0.06f)),
        )

        track.clips.forEachIndexed { index, clip ->
            key(clip.id) {
                val isSelected = clip.id == selectedClipId
                val isBeingReordered = clip.id == draggingClipId
                val clipWidthPx = ((clip.durationUs.toDouble() / Timecode.US_PER_SECOND) * ppsPx)
                    .toFloat()
                    .coerceAtLeast(with(density) { 18.dp.toPx() })
                val clipWidthDp = with(density) { clipWidthPx.toDp() }
                val baseStartPx: () -> Float = {
                    centerOffsetPx +
                        ((clip.startUs - playheadUs()).toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat()
                }

                val currentClips by rememberUpdatedState(track.clips)
                val currentOnAction by rememberUpdatedState(onAction)
                val currentPpsPx by rememberUpdatedState(ppsPx)

                TimelineClipItem(
                    clip = clip,
                    trackKind = TrackKind.MAIN,
                    trackHeight = MAIN_TRACK_HEIGHT,
                    clipWidthDp = clipWidthDp,
                    clipWidthPx = clipWidthPx,
                    playheadUs = playheadUs,
                    ppsPx = ppsPx,
                    visibleRangeUs = visibleRangeUs,
                    isSelected = isSelected,
                    snapPointsUs = snapPointsUs,
                    mediaSource = mediaSource,
                    onAction = onAction,
                    modifier = Modifier
                        .zIndex(if (isBeingReordered) 10f else if (isSelected) 5f else 1f)
                        .offset {
                            IntOffset(
                                x = (baseStartPx() + if (isBeingReordered) dragOffsetPx else 0f).roundToInt(),
                                y = 0,
                            )
                        }
                        .graphicsLayer {
                            if (isBeingReordered) {
                                scaleX = 1.04f
                                scaleY = 1.06f
                                alpha = 0.88f
                            }
                        }
                        .pointerInput(clip.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingClipId = clip.id
                                    dragOffsetPx = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragCancel = {
                                    draggingClipId = null
                                    dragOffsetPx = 0f
                                },
                                onDragEnd = {
                                    val draggedCenterTimeUs = clip.startUs + (clip.durationUs / 2L) +
                                        ((dragOffsetPx / currentPpsPx) * Timecode.US_PER_SECOND).roundToLong()
                                    val targetIndex = computeReorderTargetIndex(
                                        clips = currentClips,
                                        draggedClipId = clip.id,
                                        targetTimeUs = draggedCenterTimeUs,
                                    )
                                    draggingClipId = null
                                    dragOffsetPx = 0f
                                    if (targetIndex != index) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentOnAction(EditorAction.MoveInMain(clip.id, targetIndex))
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetPx += dragAmount.x
                                },
                            )
                        },
                )
            }
        }

        // Transition zones and cut buttons between consecutive MAIN-track clips
        if (draggingClipId == null && track.clips.size > 1) {
            val buttonSize = 22.dp
            val halfButtonPx = with(density) { (buttonSize / 2).toPx() }
            val minZonePx = with(density) { 6.dp.toPx() }
            val buttonShape = RoundedCornerShape(6.dp)
            val zoneShape = RoundedCornerShape(4.dp)

            for (index in 1..track.clips.lastIndex) {
                val rightClip = track.clips[index]
                key("transition-${rightClip.id}") {
                    val cutUs = rightClip.startUs
                    val transition = rightClip.transitionIn
                    val cutX: () -> Float = {
                        centerOffsetPx +
                            ((cutUs - playheadUs()).toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat()
                    }

                    if (transition != null) {
                        val zoneWidthPx = ((transition.durationUs.toDouble() / Timecode.US_PER_SECOND) * ppsPx)
                            .toFloat()
                            .coerceAtLeast(minZonePx)
                        val zoneWidthDp = with(density) { zoneWidthPx.toDp() }

                        Box(
                            modifier = Modifier
                                .zIndex(6f)
                                .offset {
                                    IntOffset(
                                        x = (cutX() - zoneWidthPx / 2f).roundToInt(),
                                        y = 0,
                                    )
                                }
                                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                                .width(zoneWidthDp)
                                .height(MAIN_TRACK_HEIGHT)
                                .clip(zoneShape)
                                .background(FlowColors.Accent.copy(alpha = 0.24f))
                                .border(1.dp, FlowColors.Accent.copy(alpha = 0.7f), zoneShape),
                        )
                    }

                    val hasTransition = transition != null
                    Box(
                        modifier = Modifier
                            .zIndex(8f)
                            .offset {
                                IntOffset(
                                    x = (cutX() - halfButtonPx).roundToInt(),
                                    y = 0,
                                )
                            }
                            .align(Alignment.CenterStart)
                            .size(buttonSize)
                            .clip(buttonShape)
                            .background(if (hasTransition) FlowColors.Accent else FlowColors.Slate)
                            .border(
                                width = 1.dp,
                                color = if (hasTransition) FlowColors.Accent else FlowColors.Outline,
                                shape = buttonShape,
                            )
                            .clickable { onAction(EditorAction.OpenTransition(rightClip.id)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = "Transición",
                            tint = if (hasTransition) FlowColors.OnAccent else FlowColors.TextPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // "+" button at the end of the MAIN track to emit AddMedia
        val addButtonGapPx = with(density) { 10.dp.toPx() }
        val trackEndUs = track.endUs

        Box(
            modifier = Modifier
                .offset {
                    val x = centerOffsetPx +
                        ((trackEndUs - playheadUs()).toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat() +
                        addButtonGapPx
                    IntOffset(x.roundToInt(), 0)
                }
                .align(Alignment.CenterStart)
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(FlowColors.Slate)
                .border(1.dp, FlowColors.Outline, RoundedCornerShape(10.dp))
                .clickable { onAction(EditorAction.AddMedia) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Añadir medios",
                tint = FlowColors.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun computeReorderTargetIndex(
    clips: List<Clip>,
    draggedClipId: String,
    targetTimeUs: Long,
): Int {
    val others = clips.filterNot { it.id == draggedClipId }
    val insertPos = others.indexOfFirst { targetTimeUs < it.startUs + it.durationUs / 2L }
    return if (insertPos < 0) others.size else insertPos
}

@Composable
private fun SecondaryTrackLane(
    track: Track,
    laneIndex: Int,
    lanes: List<Track>,
    laneStepPx: Float,
    playheadUs: () -> Long,
    ppsPx: Float,
    centerOffsetPx: Float,
    visibleRangeUs: () -> LongRange,
    selectedClipId: String?,
    snapPointsUs: List<Long>,
    mediaSource: TimelineMediaSource,
    onAction: (EditorAction) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val snapDistancePx = with(density) { 10.dp.toPx() }
    // Long-press drag: horizontal moves the clip in time, vertical to another track of its kind.
    var draggingClipId by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var snappedUs by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SECONDARY_TRACK_HEIGHT)
            .zIndex(if (draggingClipId != null) 20f else 0f)
            .background(Color.White.copy(alpha = 0.04f)),
    ) {
        for (clip in track.clips) {
            key(clip.id) {
                val isDragged = clip.id == draggingClipId
                val currentOnAction by rememberUpdatedState(onAction)
                val currentPpsPx by rememberUpdatedState(ppsPx)
                val currentSnaps by rememberUpdatedState(snapPointsUs)
                val currentLanes by rememberUpdatedState(lanes)
                val currentLaneIndex by rememberUpdatedState(laneIndex)
                val currentClip by rememberUpdatedState(clip)
                val isSelected = clip.id == selectedClipId
                val clipWidthPx = ((clip.durationUs.toDouble() / Timecode.US_PER_SECOND) * ppsPx)
                    .toFloat()
                    .coerceAtLeast(with(density) { 16.dp.toPx() })
                val clipWidthDp = with(density) { clipWidthPx.toDp() }
                val baseStartPx: () -> Float = {
                    centerOffsetPx +
                        ((clip.startUs - playheadUs()).toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat()
                }

                TimelineClipItem(
                    clip = clip,
                    trackKind = track.kind,
                    trackHeight = SECONDARY_TRACK_HEIGHT,
                    clipWidthDp = clipWidthDp,
                    clipWidthPx = clipWidthPx,
                    playheadUs = playheadUs,
                    ppsPx = ppsPx,
                    visibleRangeUs = visibleRangeUs,
                    isSelected = isSelected,
                    snapPointsUs = snapPointsUs,
                    mediaSource = mediaSource,
                    onAction = onAction,
                    modifier = Modifier
                        .zIndex(if (isDragged) 10f else if (isSelected) 5f else 1f)
                        .offset {
                            if (!isDragged) return@offset IntOffset(baseStartPx().roundToInt(), 0)
                            val x = snappedUs?.let { snap ->
                                centerOffsetPx + ((snap - playheadUs()).toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat()
                            } ?: (baseStartPx() + dragX)
                            IntOffset(x.roundToInt(), dragY.roundToInt())
                        }
                        .graphicsLayer {
                            if (isDragged) {
                                scaleX = 1.04f
                                scaleY = 1.06f
                                alpha = 0.88f
                            }
                        }
                        .pointerInput(clip.id) {
                            fun proposedStartUs(): Long {
                                val c = currentClip
                                return (c.startUs + (dragX / currentPpsPx * Timecode.US_PER_SECOND).roundToLong()).coerceAtLeast(0L)
                            }
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingClipId = clip.id
                                    dragX = 0f
                                    dragY = 0f
                                    snappedUs = null
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnAction(EditorAction.SelectClip(clip.id))
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragX += amount.x
                                    dragY += amount.y
                                    // Snap the start or the end to cuts, markers and the playhead.
                                    val c = currentClip
                                    val start = proposedStartUs()
                                    val toleranceUs = (snapDistancePx / currentPpsPx * Timecode.US_PER_SECOND).toLong()
                                    val snap = currentSnaps.filter { it != c.startUs && it != c.endUs }.flatMap { point ->
                                        listOf(point, point - c.durationUs)
                                    }.filter { it >= 0L }.minByOrNull { abs(it - start) }?.takeIf { abs(it - start) <= toleranceUs }
                                    if (snap != snappedUs && snap != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    snappedUs = snap
                                },
                                onDragEnd = {
                                    val startUs = snappedUs ?: proposedStartUs()
                                    val laneShift = (dragY / laneStepPx).roundToInt()
                                    val target = currentLanes.getOrNull(currentLaneIndex + laneShift)
                                    draggingClipId = null
                                    dragX = 0f
                                    dragY = 0f
                                    snappedUs = null
                                    currentOnAction(EditorAction.PlaceInLayers(clip.id, target?.id, startUs))
                                },
                                onDragCancel = {
                                    draggingClipId = null
                                    dragX = 0f
                                    dragY = 0f
                                    snappedUs = null
                                },
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun TimelineClipItem(
    clip: Clip,
    trackKind: TrackKind,
    trackHeight: Dp,
    clipWidthDp: Dp,
    clipWidthPx: Float,
    playheadUs: () -> Long,
    ppsPx: Float,
    visibleRangeUs: () -> LongRange,
    isSelected: Boolean,
    snapPointsUs: List<Long>,
    mediaSource: TimelineMediaSource,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val trackColor = trackKind.trackColor()
    val clipShape = RoundedCornerShape(8.dp)
    val thumbTileWidthPx = with(density) { THUMB_TILE_WIDTH.toPx() }
    val thumbHeightPx = with(density) { trackHeight.roundToPx() }.coerceAtLeast(32)

    // Visibility only flips when the clip enters or leaves the window, not on every playhead tick.
    val isVisible by remember(clip.startUs, clip.endUs, visibleRangeUs) {
        derivedStateOf {
            val range = visibleRangeUs()
            clip.endUs >= range.first && clip.startUs <= range.last
        }
    }

    // Visible thumbnail tiles; recomposition happens only when the tile range actually changes.
    val visibleTileIndices by remember(
        clip.startUs,
        clip.durationUs,
        clipWidthPx,
        thumbTileWidthPx,
        visibleRangeUs,
        ppsPx,
    ) {
        derivedStateOf {
            val range = visibleRangeUs()
            val totalTiles = max(1, ceil(clipWidthPx / thumbTileWidthPx).toInt())
            val visStartOffsetUs = (range.first - clip.startUs).coerceAtLeast(0L)
            val visEndOffsetUs = (range.last - clip.startUs).coerceAtMost(clip.durationUs)
            if (visEndOffsetUs < visStartOffsetUs) {
                IntRange.EMPTY
            } else {
                val startPx = (visStartOffsetUs.toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat()
                val endPx = (visEndOffsetUs.toDouble() / Timecode.US_PER_SECOND * ppsPx).toFloat()
                val firstTile = floor(startPx / thumbTileWidthPx).toInt().coerceIn(0, totalTiles - 1)
                val lastTile = ceil(endPx / thumbTileWidthPx).toInt().coerceIn(firstTile, totalTiles - 1)
                firstTile..lastTile
            }
        }
    }

    val media = clip.media
    val showThumbnails = media != null && media.hasVideo &&
        (trackKind == TrackKind.MAIN || trackKind == TrackKind.OVERLAY)
    val showWaveform = media != null && media.hasAudio

    val waveform by produceState<FloatArray?>(initialValue = null, key1 = media, key2 = showWaveform) {
        value = if (showWaveform) {
            mediaSource.waveform(media, WAVEFORM_BUCKETS_PER_SEC)
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            // Long clips are wider than the viewport: let them overflow to the right instead of
            // being clamped (and re-centered) by the parent's constraints.
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .width(clipWidthDp)
            .height(trackHeight)
            .clip(clipShape)
            .background(trackColor.copy(alpha = if (trackKind == TrackKind.MAIN) 0.35f else 0.72f))
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, FlowColors.Accent, clipShape)
                } else {
                    Modifier.border(1.dp, trackColor.copy(alpha = 0.9f), clipShape)
                }
            )
            .clickable { onAction(EditorAction.SelectClip(clip.id)) },
    ) {
        if (isVisible && showThumbnails) {
            val tileDurationUs = ((THUMB_TILE_WIDTH.value / (ppsPx / density.density)) * Timecode.US_PER_SECOND)
                .toLong()
                .coerceAtLeast(33_333L)

            for (tileIndex in visibleTileIndices) {
                key(tileIndex) {
                    val sampleTimelineUs = (clip.startUs + tileIndex * tileDurationUs + tileDurationUs / 2L)
                        .coerceIn(clip.startUs, clip.endUs)
                    // Quantize source time to 50ms buckets so small zoom/scroll adjustments hit the cache
                    val rawSourceUs = clip.sourceTimeAt(sampleTimelineUs)
                    val quantizedSourceUs = (rawSourceUs / 50_000L) * 50_000L

                    val bitmap by produceState<ImageBitmap?>(
                        initialValue = null,
                        key1 = media.uri,
                        key2 = quantizedSourceUs,
                        key3 = thumbHeightPx,
                    ) {
                        value = mediaSource.thumbnail(media, quantizedSourceUs, thumbHeightPx)
                    }

                    val currentBitmap = bitmap
                    if (currentBitmap != null) {
                        Image(
                            bitmap = currentBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .offset(x = THUMB_TILE_WIDTH * tileIndex)
                                .width(THUMB_TILE_WIDTH)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }

        // Waveform overlay at bottom of video clips or full height on audio tracks
        val peaks = waveform
        val hasAudioFades = showWaveform && (clip.fadeInUs > 0L || clip.fadeOutUs > 0L)
        if (isVisible && ((peaks != null && peaks.isNotEmpty()) || hasAudioFades)) {
            val waveformHeight = if (trackKind == TrackKind.AUDIO) trackHeight else 18.dp
            ClipWaveformStrip(
                clip = clip,
                peaks = peaks ?: FloatArray(0),
                ppsPx = ppsPx,
                color = if (trackKind == TrackKind.AUDIO) {
                    FlowColors.TextPrimary.copy(alpha = 0.85f)
                } else {
                    FlowColors.Accent.copy(alpha = 0.8f)
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(waveformHeight)
                    .background(
                        if (trackKind == TrackKind.MAIN) {
                            FlowColors.Black.copy(alpha = 0.45f)
                        } else {
                            Color.Transparent
                        }
                    ),
            )
        }

        // Entrance and exit animation ramps/arrows at the start and end of the clip
        if (isVisible && (clip.enterAnimation != null || clip.exitAnimation != null)) {
            ClipInOutRampsOverlay(
                clip = clip,
                ppsPx = ppsPx,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Clip label & status badges
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = if (isSelected) TRIM_HANDLE_WIDTH + 4.dp else 6.dp,
                    end = 6.dp,
                    top = 3.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (trackKind != TrackKind.MAIN) {
                Icon(
                    imageVector = trackKind.trackIcon(),
                    contentDescription = null,
                    tint = FlowColors.TextPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
            val effectTitle = if (trackKind == TrackKind.EFFECT || clip.kind == ClipKind.EFFECT) {
                clip.effects.firstOrNull()?.let { EffectCatalog.find(it.type)?.name }
            } else {
                null
            }
            val title = clip.text?.text?.replace('\n', ' ')?.ifBlank { null }
                ?: effectTitle?.ifBlank { null }
                ?: clip.media?.displayName?.ifBlank { trackKind.spanishTitle() }
                ?: trackKind.spanishTitle()
            Text(
                text = title,
                style = FlowTypography.labelSmall,
                color = FlowColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (clip.speed != 1f) {
                Text(
                    text = String.format(Locale.US, "%.1fx", clip.speed),
                    style = FlowTypography.labelSmall,
                    color = FlowColors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(FlowColors.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (clip.muted || clip.volume == 0f) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                    contentDescription = "Silenciado",
                    tint = FlowColors.Error,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        // Keyframe diamonds on the selected clip; tapping one seeks to that keyframe time.
        if (isSelected) {
            val keyframeTimes = remember(clip.keyframes, clip.startUs) {
                MediaEdits.keyframeTimes(clip)
            }
            val hitSize = 18.dp
            val halfHit = 9.dp
            for (kfTimeUs in keyframeTimes) {
                if (kfTimeUs in clip.startUs..clip.endUs) {
                    key(kfTimeUs) {
                        val xPx = (((kfTimeUs - clip.startUs).toDouble() / Timecode.US_PER_SECOND) * ppsPx).toFloat()
                        val xDp = with(density) { xPx.toDp() }
                        val maxOffset = (clipWidthDp - hitSize).coerceAtLeast(0.dp)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = (xDp - halfHit).coerceIn(0.dp, maxOffset))
                                .size(hitSize)
                                .clickable {
                                    onAction(EditorAction.Seek(kfTimeUs, final = true))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(10.dp)) {
                                val r = size.minDimension / 2f - 1.dp.toPx()
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val diamond = Path().apply {
                                    moveTo(cx, cy - r)
                                    lineTo(cx + r, cy)
                                    lineTo(cx, cy + r)
                                    lineTo(cx - r, cy)
                                    close()
                                }
                                drawPath(
                                    path = diamond,
                                    color = Color.Black.copy(alpha = 0.65f),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                                drawPath(path = diamond, color = FlowColors.Keyframe)
                            }
                        }
                    }
                }
            }
        }

        // Left & Right trim handles when selected
        if (isSelected) {
            TrimHandle(
                isStart = true,
                clip = clip,
                ppsPx = ppsPx,
                playheadUs = playheadUs,
                snapPointsUs = snapPointsUs,
                onAction = onAction,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(TRIM_HANDLE_WIDTH)
                    .fillMaxHeight(),
            )
            TrimHandle(
                isStart = false,
                clip = clip,
                ppsPx = ppsPx,
                playheadUs = playheadUs,
                snapPointsUs = snapPointsUs,
                onAction = onAction,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(TRIM_HANDLE_WIDTH)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun TrimHandle(
    isStart: Boolean,
    clip: Clip,
    ppsPx: Float,
    playheadUs: () -> Long,
    snapPointsUs: List<Long>,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val snapThresholdUs = remember(ppsPx, density) {
        val thresholdPx = with(density) { SNAP_THRESHOLD_DP.dp.toPx() }
        ((thresholdPx / ppsPx) * Timecode.US_PER_SECOND).roundToLong().coerceAtLeast(15_000L)
    }

    val currentClip by rememberUpdatedState(clip)
    val currentPpsPx by rememberUpdatedState(ppsPx)
    val currentSnapPoints by rememberUpdatedState(snapPointsUs)
    val currentPlayheadUs by rememberUpdatedState(playheadUs)
    val currentSnapThresholdUs by rememberUpdatedState(snapThresholdUs)
    val currentOnAction by rememberUpdatedState(onAction)

    val handleShape = if (isStart) {
        RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
    } else {
        RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    }

    Box(
        modifier = modifier
            .clip(handleShape)
            .background(FlowColors.Accent)
            .pointerInput(clip.id, isStart) {
                var initialBoundaryUs = 0L
                var fixedOppositeUs = 0L
                var accumulatedPx = 0f
                var latestTargetUs = 0L
                var lastSnappedPointUs: Long? = null
                var minBoundaryUs = 0L
                var maxBoundaryUs = Long.MAX_VALUE
                var atLimit = false

                detectHorizontalDragGestures(
                    onDragStart = {
                        val c = currentClip
                        initialBoundaryUs = if (isStart) c.startUs else c.endUs
                        fixedOppositeUs = if (isStart) c.endUs else c.startUs
                        accumulatedPx = 0f
                        latestTargetUs = initialBoundaryUs
                        lastSnappedPointUs = null
                        atLimit = false
                        // How far the handle may go: the media that is left and the shortest clip.
                        val mediaUs = c.media?.durationUs?.takeIf {
                            it > 0L && (c.media?.type == MediaType.VIDEO || c.media?.type == MediaType.AUDIO)
                        }
                        val speed = c.speed.coerceAtLeast(Edits.MIN_SPEED)
                        if (isStart) {
                            val sourceBefore = if (c.reversed) (mediaUs ?: 0L) - c.trimOutUs else c.trimInUs
                            minBoundaryUs = if (mediaUs == null || c.speedCurve != null) 0L
                            else (c.startUs - (sourceBefore / speed).toLong()).coerceAtLeast(0L)
                            maxBoundaryUs = (fixedOppositeUs - Edits.MIN_CLIP_US).coerceAtLeast(0L)
                        } else {
                            val sourceAfter = if (c.reversed) c.trimInUs else (mediaUs ?: 0L) - c.trimOutUs
                            minBoundaryUs = fixedOppositeUs + Edits.MIN_CLIP_US
                            maxBoundaryUs = if (mediaUs == null || c.speedCurve != null) Long.MAX_VALUE
                            else c.endUs + (sourceAfter / speed).toLong()
                        }
                    },
                    onDragEnd = {
                        if (isStart) {
                            currentOnAction(
                                EditorAction.TrimStart(
                                    clipId = currentClip.id,
                                    newStartUs = latestTargetUs,
                                    final = true,
                                )
                            )
                        } else {
                            currentOnAction(
                                EditorAction.TrimEnd(
                                    clipId = currentClip.id,
                                    newEndUs = latestTargetUs,
                                    final = true,
                                )
                            )
                        }
                    },
                    onDragCancel = {
                        if (isStart) {
                            currentOnAction(
                                EditorAction.TrimStart(
                                    clipId = currentClip.id,
                                    newStartUs = latestTargetUs,
                                    final = true,
                                )
                            )
                        } else {
                            currentOnAction(
                                EditorAction.TrimEnd(
                                    clipId = currentClip.id,
                                    newEndUs = latestTargetUs,
                                    final = true,
                                )
                            )
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedPx += dragAmount
                        val deltaUs = ((accumulatedPx / currentPpsPx) * Timecode.US_PER_SECOND).roundToLong()
                        val unclamped = initialBoundaryUs + deltaUs
                        val rawCandidateUs = unclamped.coerceIn(minBoundaryUs, maxOf(minBoundaryUs, maxBoundaryUs))
                        // One firm tick when the handle reaches the end of the media or the shortest clip.
                        val limited = rawCandidateUs != unclamped
                        if (limited && !atLimit) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        atLimit = limited
                        if (limited) {
                            // Keep the finger offset in step with the limit so moving back responds at once.
                            accumulatedPx = ((rawCandidateUs - initialBoundaryUs).toFloat() / Timecode.US_PER_SECOND) * currentPpsPx
                        }

                        // Check proximity to other clip boundaries or the playhead for haptic snap
                        val selfBoundary = if (isStart) currentClip.startUs else currentClip.endUs
                        val nearestSnap = (currentSnapPoints.asSequence() + currentPlayheadUs())
                            .filter { it != selfBoundary }
                            .minByOrNull { abs(it - rawCandidateUs) }

                        val candidateUs = if (nearestSnap != null &&
                            abs(nearestSnap - rawCandidateUs) <= currentSnapThresholdUs
                        ) {
                            if (lastSnappedPointUs != nearestSnap) {
                                lastSnappedPointUs = nearestSnap
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            nearestSnap
                        } else {
                            lastSnappedPointUs = null
                            rawCandidateUs
                        }

                        latestTargetUs = candidateUs
                        if (isStart) {
                            currentOnAction(
                                EditorAction.TrimStart(
                                    clipId = currentClip.id,
                                    newStartUs = candidateUs,
                                    final = false,
                                )
                            )
                        } else {
                            currentOnAction(
                                EditorAction.TrimEnd(
                                    clipId = currentClip.id,
                                    newEndUs = candidateUs,
                                    final = false,
                                )
                            )
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .width(2.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(FlowColors.OnAccent),
        )
    }
}

@Composable
private fun ClipWaveformStrip(
    clip: Clip,
    peaks: FloatArray,
    ppsPx: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        if (peaks.isNotEmpty()) {
            val barWidth = 2.dp.toPx()
            val gap = 1.5.dp.toPx()
            val step = barWidth + gap
            val barCount = max(1, (size.width / step).toInt())

            val startBucket = ((clip.trimInUs.toDouble() / Timecode.US_PER_SECOND) * WAVEFORM_BUCKETS_PER_SEC).toInt()
            val endBucket = ((clip.trimOutUs.toDouble() / Timecode.US_PER_SECOND) * WAVEFORM_BUCKETS_PER_SEC)
                .toInt()
                .coerceAtLeast(startBucket + 1)
            val bucketSpan = (endBucket - startBucket).coerceAtLeast(1)

            for (i in 0 until barCount) {
                val fraction = if (clip.reversed) {
                    1f - (i.toFloat() / barCount.toFloat())
                } else {
                    i.toFloat() / barCount.toFloat()
                }
                val bucketIndex = (startBucket + (fraction * bucketSpan).toInt())
                    .coerceIn(0, peaks.lastIndex)
                val amplitude = peaks[bucketIndex].coerceIn(0.08f, 1f)
                val barHeight = (size.height * 0.85f * amplitude).coerceAtLeast(2.dp.toPx())
                val x = i * step
                val top = (size.height - barHeight) / 2f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }

        // Semi-transparent triangular fade-in and fade-out ramps over the audio waveform
        val fadeInPx = ((clip.fadeInUs.toDouble() / Timecode.US_PER_SECOND) * ppsPx)
            .toFloat()
            .coerceIn(0f, size.width)
        val fadeOutPx = ((clip.fadeOutUs.toDouble() / Timecode.US_PER_SECOND) * ppsPx)
            .toFloat()
            .coerceIn(0f, size.width)
        val rampFillColor = FlowColors.Accent.copy(alpha = 0.28f)
        val rampStrokeColor = FlowColors.Accent.copy(alpha = 0.9f)
        val rampStrokeWidth = 1.5.dp.toPx()

        if (fadeInPx > 0.5f) {
            val fadeInRamp = Path().apply {
                moveTo(0f, size.height)
                lineTo(fadeInPx, 0f)
                lineTo(fadeInPx, size.height)
                close()
            }
            drawPath(path = fadeInRamp, color = rampFillColor)
            drawLine(
                color = rampStrokeColor,
                start = Offset(0f, size.height),
                end = Offset(fadeInPx, 0f),
                strokeWidth = rampStrokeWidth,
            )
        }

        if (fadeOutPx > 0.5f) {
            val fadeOutStartX = (size.width - fadeOutPx).coerceAtLeast(0f)
            val fadeOutRamp = Path().apply {
                moveTo(fadeOutStartX, 0f)
                lineTo(size.width, size.height)
                lineTo(fadeOutStartX, size.height)
                close()
            }
            drawPath(path = fadeOutRamp, color = rampFillColor)
            drawLine(
                color = rampStrokeColor,
                start = Offset(fadeOutStartX, 0f),
                end = Offset(size.width, size.height),
                strokeWidth = rampStrokeWidth,
            )
        }
    }
}

@Composable
private fun ClipInOutRampsOverlay(
    clip: Clip,
    ppsPx: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val maxAnimUs = clip.durationUs / 2L
        val enterUs = clip.enterAnimation?.let {
            min(
                it.durationUs.coerceIn(ClipAnimations.MIN_DURATION_US, ClipAnimations.MAX_DURATION_US),
                maxAnimUs,
            )
        } ?: 0L
        val exitUs = clip.exitAnimation?.let {
            min(
                it.durationUs.coerceIn(ClipAnimations.MIN_DURATION_US, ClipAnimations.MAX_DURATION_US),
                maxAnimUs,
            )
        } ?: 0L

        val enterPx = ((enterUs.toDouble() / Timecode.US_PER_SECOND) * ppsPx)
            .toFloat()
            .coerceIn(0f, size.width / 2f)
        val exitPx = ((exitUs.toDouble() / Timecode.US_PER_SECOND) * ppsPx)
            .toFloat()
            .coerceIn(0f, size.width / 2f)

        val fillColor = FlowColors.Accent.copy(alpha = 0.24f)
        val strokeColor = FlowColors.Accent.copy(alpha = 0.8f)
        val strokeWidth = 1.5.dp.toPx()
        val arrowY = (size.height - 6.dp.toPx()).coerceAtLeast(size.height * 0.5f)
        val arrowHead = 3.5.dp.toPx()

        if (enterPx > 1f) {
            val enterRamp = Path().apply {
                moveTo(0f, size.height)
                lineTo(enterPx, 0f)
                lineTo(0f, 0f)
                close()
            }
            drawPath(path = enterRamp, color = fillColor)
            drawLine(
                color = strokeColor,
                start = Offset(0f, size.height),
                end = Offset(enterPx, 0f),
                strokeWidth = strokeWidth,
            )
            if (enterPx >= arrowHead * 2f) {
                val endX = (enterPx - 2.dp.toPx()).coerceAtLeast(arrowHead)
                drawLine(
                    color = strokeColor,
                    start = Offset(2.dp.toPx(), arrowY),
                    end = Offset(endX, arrowY),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(endX, arrowY),
                    end = Offset(endX - arrowHead, arrowY - arrowHead),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(endX, arrowY),
                    end = Offset(endX - arrowHead, arrowY + arrowHead),
                    strokeWidth = strokeWidth,
                )
            }
        }

        if (exitPx > 1f) {
            val startX = (size.width - exitPx).coerceAtLeast(0f)
            val exitRamp = Path().apply {
                moveTo(startX, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path = exitRamp, color = fillColor)
            drawLine(
                color = strokeColor,
                start = Offset(startX, 0f),
                end = Offset(size.width, size.height),
                strokeWidth = strokeWidth,
            )
            if (exitPx >= arrowHead * 2f) {
                val arrowStartX = (startX + 2.dp.toPx()).coerceAtMost(size.width - arrowHead)
                val arrowEndX = (size.width - 2.dp.toPx()).coerceAtLeast(arrowStartX)
                drawLine(
                    color = strokeColor,
                    start = Offset(arrowStartX, arrowY),
                    end = Offset(arrowEndX, arrowY),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(arrowEndX, arrowY),
                    end = Offset(arrowEndX - arrowHead, arrowY - arrowHead),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(arrowEndX, arrowY),
                    end = Offset(arrowEndX - arrowHead, arrowY + arrowHead),
                    strokeWidth = strokeWidth,
                )
            }
        }
    }
}

private fun TrackKind.trackColor(): Color = when (this) {
    TrackKind.MAIN -> FlowColors.TrackVideo
    TrackKind.OVERLAY -> FlowColors.TrackOverlay
    TrackKind.TEXT -> FlowColors.TrackText
    TrackKind.AUDIO -> FlowColors.TrackAudio
    TrackKind.EFFECT -> FlowColors.TrackEffect
    TrackKind.ADJUSTMENT -> FlowColors.TrackAdjustment
}

private fun TrackKind.spanishTitle(): String = when (this) {
    TrackKind.MAIN -> "Video"
    TrackKind.OVERLAY -> "Superposición"
    TrackKind.TEXT -> "Texto"
    TrackKind.AUDIO -> "Audio"
    TrackKind.EFFECT -> "Efecto"
    TrackKind.ADJUSTMENT -> "Ajuste"
}

private fun TrackKind.trackIcon() = when (this) {
    TrackKind.MAIN -> Icons.Rounded.Layers
    TrackKind.OVERLAY -> Icons.Rounded.Layers
    TrackKind.TEXT -> Icons.Rounded.Title
    TrackKind.AUDIO -> Icons.Rounded.MusicNote
    TrackKind.EFFECT -> Icons.Rounded.AutoAwesome
    TrackKind.ADJUSTMENT -> Icons.Rounded.Tune
}

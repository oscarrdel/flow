package com.rainyworks.flow.feature.editor.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import com.rainyworks.flow.core.designsystem.components.FlowAurora
import com.rainyworks.flow.core.designsystem.components.glass
import androidx.compose.animation.core.animateFloatAsState

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.designsystem.theme.TimecodeStyle
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.Canvas
import com.rainyworks.flow.core.model.CanvasBackground
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Marker
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.Timecode
import com.rainyworks.flow.core.model.Track
import com.rainyworks.flow.core.model.TrackKind
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorSheet
import com.rainyworks.flow.feature.editor.EditorUiState
import com.rainyworks.flow.feature.editor.TimelineMediaSource
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

private const val FULLSCREEN_CONTROLS_TIMEOUT_MS = 3_000L

/**
 * Stateless root UI for the video editor screen.
 *
 * Adapts between portrait (top preview + lower timeline/toolbar) and landscape
 * (left larger preview + right wide timeline/toolbar), while keeping anchored bottom
 * panels strictly inside the control area so the preview is never covered.
 */
@Composable
fun EditorContent(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    mediaSource: TimelineMediaSource,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aspectRatio = state.project?.canvas?.aspectRatio ?: AspectRatio.R9_16
    val canvasBackground = state.project?.canvas?.background ?: CanvasBackground.Solid(0xFF000000)
    val totalDurationUs = state.project?.durationUs ?: 0L
    val loading = state.loading && state.project == null

    // Stable callbacks so children that only depend on them skip recomposition on playhead ticks.
    val latestOnAction by rememberUpdatedState(onAction)
    val dispatch: (EditorAction) -> Unit = remember { { action -> latestOnAction(action) } }
    val playheadState = rememberUpdatedState(state.playheadUs)
    val playheadProvider: () -> Long = remember { { playheadState.value } }

    val isVerticalCanvas = aspectRatio.value < 1f
    var showSafeZones by rememberSaveable { mutableStateOf(false) }
    val textRenderer = rememberTextRenderer()

    val selection = remember(state.project, state.selectedClipId) {
        state.selectedClipId?.let { id -> state.project?.findClip(id) }
    }
    val selectedClip = selection?.second
    val editableClip = selection
        ?.takeIf { (track, clip) -> isVisualClip(track, clip) && clip.contains(state.playheadUs) }
        ?.second

    BackHandler(enabled = state.fullscreenPreview) { dispatch(EditorAction.ToggleFullscreen) }

    if (state.fullscreenPreview) {
        FullscreenPreview(
            aspectRatio = aspectRatio,
            background = canvasBackground,
            loading = loading,
            project = state.project,
            playheadUs = state.playheadUs,
            playheadProvider = playheadProvider,
            textRenderer = textRenderer,
            durationUs = totalDurationUs,
            fps = state.fps,
            isPlaying = state.isPlaying,
            onAction = dispatch,
            preview = preview,
            modifier = modifier,
        )
        return
    }

    val previewPane: @Composable (Modifier) -> Unit = { paneModifier ->
        EditorPreviewArea(
            aspectRatio = aspectRatio,
            background = canvasBackground,
            loading = loading,
            onDoubleTap = { dispatch(EditorAction.TogglePlay) },
            preview = preview,
            modifier = paneModifier,
            overlay = {
                val liveClip = state.liveClipId?.let { id -> state.project?.findClip(id)?.second }
                if (liveClip != null) {
                    LiveClipProxy(
                        clip = liveClip,
                        playheadUs = state.playheadUs,
                        mediaSource = mediaSource,
                        canvasHeightPx = LIVE_PROXY_HEIGHT_PX,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                TextOverlayLayer(
                    project = state.project,
                    playheadUs = playheadProvider,
                    renderer = textRenderer,
                    modifier = Modifier.fillMaxSize(),
                )
                CanvasInteractionLayer(
                    clip = editableClip,
                    showSafeZones = showSafeZones && isVerticalCanvas,
                    onAction = dispatch,
                    textRenderer = textRenderer,
                    playheadUs = playheadProvider,
                    editMask = state.activeSheet == EditorSheet.Mask,
                    modifier = Modifier.fillMaxSize(),
                )
                val pickingClipId = state.pickingChromaFor
                if (pickingClipId != null) {
                    ChromaEyedropperOverlay(
                        clipId = pickingClipId,
                        project = state.project,
                        playheadUs = state.playheadUs,
                        mediaSource = mediaSource,
                        onAction = dispatch,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    }

    val playbackBar: @Composable () -> Unit = {
        EditorPlaybackBar(
            playheadUs = state.playheadUs,
            durationUs = totalDurationUs,
            fps = state.fps,
            isPlaying = state.isPlaying,
            safeZonesVisible = showSafeZones,
            onToggleSafeZones = if (isVerticalCanvas) {
                { showSafeZones = !showSafeZones }
            } else {
                null
            },
            onAction = dispatch,
        )
    }

    /** [sheetMaxHeight] caps an open panel (landscape), which then scrolls, so the timeline stays visible. */
    val controls: @Composable ColumnScope.(sheetMaxHeight: Dp?) -> Unit = { sheetMaxHeight ->
        EditorTimeline(
            project = state.project,
            playheadUs = playheadProvider,
            selectedClipId = state.selectedClipId,
            pixelsPerSecond = state.pixelsPerSecond,
            onAction = dispatch,
            mediaSource = mediaSource,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp)
                .padding(top = 6.dp)
                .glass(TimelineGlassShape)
                .clip(TimelineGlassShape)
                .papelPicado()
                .padding(top = 10.dp),
        )

        val activeSheet = state.activeSheet
        if (activeSheet != null) {
            EditorActiveSheet(
                sheet = activeSheet,
                state = state,
                mediaSource = mediaSource,
                onAction = dispatch,
                modifier = if (sheetMaxHeight == null) Modifier else Modifier
                    .heightIn(max = sheetMaxHeight)
                    .verticalScroll(rememberScrollState()),
            )
        } else {
            EditorBottomToolbar(
                selectedClipId = state.selectedClipId,
                selectedClipKind = selectedClip?.kind,
                selectedTrackKind = selection?.first?.kind,
                selectedClipHasAudio = selectedClip?.hasEditableAudio() == true,
                onAction = dispatch,
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(FlowColors.Carbon),
    ) {
        FlowAurora(still = state.isPlaying)
        val isLandscape = maxWidth > maxHeight
        val landscapeSheetMax = maxHeight * LANDSCAPE_SHEET_SHARE

        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                aspectRatio = aspectRatio,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onAction = dispatch,
            )

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // Left column: large preview + playback controls
                    Column(
                        modifier = Modifier
                            .weight(0.44f)
                            .fillMaxHeight(),
                    ) {
                        previewPane(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        playbackBar()
                    }

                    // Right column: wide timeline + contextual toolbar or anchored sheet
                    Column(
                        modifier = Modifier
                            .weight(0.56f)
                            .fillMaxHeight(),
                    ) {
                        controls(landscapeSheetMax)
                    }
                }
            } else {
                // Portrait layout: upper preview area + playback bar, lower timeline + toolbar/sheet.
                // While a panel is open the preview shrinks so the timeline keeps usable room.
                val panelOpen = state.activeSheet != null
                val previewShare by animateFloatAsState(if (panelOpen) 0.30f else 0.45f, label = "previewShare")
                previewPane(
                    Modifier
                        .fillMaxWidth()
                        .weight(previewShare),
                )

                playbackBar()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f - previewShare),
                ) {
                    controls(null)
                }
            }
        }
    }
}

/** True for clips that render pixels on the canvas and can therefore be transformed. */
private val TimelineGlassShape = RoundedCornerShape(26.dp)

/** A row of papel picado hung along the top edge of the timeline glass. */
private fun Modifier.papelPicado(): Modifier = drawWithContent {
    drawContent()
    val step = 14.dp.toPx()
    val depth = 8.dp.toPx()
    val r = 5.5.dp.toPx()
    val color = FlowColors.Rosa.copy(alpha = 0.85f)
    var x = 0f
    while (x < size.width) {
        val path = Path().apply {
            moveTo(x, 0f)
            lineTo(x + step, 0f)
            lineTo(x + step, depth)
            // scalloped bottom: a half-circle bite out of each cell
            arcTo(Rect(x + step / 2 - r, depth - r * 2, x + step / 2 + r, depth), 0f, 180f, false)
            lineTo(x, depth)
            close()
        }
        drawPath(path, color)
        x += step
    }
}

private fun isVisualClip(track: Track, clip: Clip): Boolean =
    track.kind != TrackKind.AUDIO &&
        clip.kind != ClipKind.AUDIO &&
        clip.kind != ClipKind.ADJUSTMENT &&
        clip.kind != ClipKind.EFFECT &&
        clip.media?.type != MediaType.AUDIO

@Composable
private fun EditorTopBar(
    aspectRatio: AspectRatio,
    canUndo: Boolean,
    canRedo: Boolean,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onAction(EditorAction.Close) },
            modifier = Modifier.size(42.dp).glass(CircleShape, elevation = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cerrar editor",
                tint = FlowColors.TextPrimary,
            )
        }

        // Aspect ratio indicator pill opening EditorSheet.AspectRatio
        Row(
            modifier = Modifier
                .padding(start = spacing.sm)
                .glass(CircleShape, elevation = 6.dp)
                .clip(CircleShape)
                .clickable { onAction(EditorAction.OpenSheet(EditorSheet.AspectRatio)) }
                .padding(horizontal = spacing.md, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AspectRatio,
                contentDescription = "Relación de aspecto",
                tint = FlowColors.TextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = aspectRatio.label,
                style = FlowTypography.labelLarge,
                color = FlowColors.TextPrimary,
            )
        }

        Spacer(Modifier.weight(1f))

        IconButton(
            onClick = { onAction(EditorAction.Undo) },
            enabled = canUndo,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = "Deshacer",
                tint = if (canUndo) FlowColors.TextPrimary else FlowColors.TextTertiary,
            )
        }

        IconButton(
            onClick = { onAction(EditorAction.Redo) },
            enabled = canRedo,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Redo,
                contentDescription = "Rehacer",
                tint = if (canRedo) FlowColors.TextPrimary else FlowColors.TextTertiary,
            )
        }

        Spacer(Modifier.width(spacing.xs))

        Button(
            onClick = { onAction(EditorAction.Export) },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = 6.dp),
            modifier = Modifier.height(40.dp).glass(CircleShape, tint = FlowColors.Rosa, elevation = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.IosShare,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Exportar",
                style = FlowTypography.labelLarge,
            )
        }
    }
}

/**
 * Letterboxed preview canvas. [overlay] is laid out over exactly the same rectangle as the
 * rendered canvas, so its coordinates map one to one to canvas coordinates.
 */
@Composable
private fun EditorPreviewArea(
    aspectRatio: AspectRatio,
    background: CanvasBackground,
    loading: Boolean,
    onDoubleTap: () -> Unit,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    overlay: (@Composable () -> Unit)? = null,
) {
    val spacing = FlowTheme.spacing
    val targetRatio = aspectRatio.value.coerceAtLeast(0.1f)

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .padding(spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        val availableRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
        val matchHeightFirst = availableRatio > targetRatio

        Box(
            modifier = Modifier.aspectRatio(
                ratio = targetRatio,
                matchHeightConstraintsFirst = matchHeightFirst,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = FlowColors.GlassShadow, spotColor = FlowColors.GlassShadow)
                    .clip(RoundedCornerShape(18.dp))
                    .canvasBackgroundModifier(background)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                preview(Modifier.fillMaxSize())

                if (loading) {
                    CircularProgressIndicator(
                        color = FlowColors.Accent,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            overlay?.invoke()
        }
    }
}

private fun Modifier.canvasBackgroundModifier(background: CanvasBackground): Modifier = when (background) {
    is CanvasBackground.Solid -> this.background(Color(background.argb))
    is CanvasBackground.Gradient -> this.background(
        Brush.linearGradient(
            colors = listOf(Color(background.startArgb), Color(background.endArgb)),
        )
    )
    is CanvasBackground.Blur -> this.background(FlowColors.Graphite)
    is CanvasBackground.Image -> this.background(FlowColors.Black)
}

@Composable
private fun EditorPlaybackBar(
    playheadUs: Long,
    durationUs: Long,
    fps: Int,
    isPlaying: Boolean,
    safeZonesVisible: Boolean,
    onToggleSafeZones: (() -> Unit)?,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .height(52.dp)
            .glass(CircleShape)
            .padding(start = spacing.lg, end = 6.dp),
    ) {
        PlaybackTimecode(
            playheadUs = playheadUs,
            durationUs = durationUs,
            fps = fps,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        PlayPauseButton(
            isPlaying = isPlaying,
            onClick = { onAction(EditorAction.TogglePlay) },
            modifier = Modifier.align(Alignment.Center),
        )

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onToggleSafeZones != null) {
                IconButton(
                    onClick = onToggleSafeZones,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = if (safeZonesVisible) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
                        contentDescription = if (safeZonesVisible) {
                            "Ocultar zonas seguras"
                        } else {
                            "Mostrar zonas seguras"
                        },
                        tint = if (safeZonesVisible) FlowColors.Accent else FlowColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(
                onClick = { onAction(EditorAction.SaveFrame) },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = "Guardar fotograma como foto",
                    tint = FlowColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { onAction(EditorAction.AddMarker) },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkAdd,
                    contentDescription = "Añadir marcador",
                    tint = FlowColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { onAction(EditorAction.ToggleFullscreen) },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Fullscreen,
                    contentDescription = "Pantalla completa",
                    tint = FlowColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaybackTimecode(
    playheadUs: Long,
    durationUs: Long,
    fps: Int,
    modifier: Modifier = Modifier,
) {
    val safeFps = fps.coerceAtLeast(1)
    val currentFormatted = remember(playheadUs, safeFps) { Timecode.format(playheadUs, safeFps) }
    val totalFormatted = remember(durationUs, safeFps) { Timecode.format(durationUs, safeFps) }

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = FlowColors.TextPrimary)) {
                append(currentFormatted)
            }
            withStyle(SpanStyle(color = FlowColors.TextSecondary)) {
                append(" / ")
                append(totalFormatted)
            }
        },
        style = TimecodeStyle.copy(fontSize = 15.sp, lineHeight = 18.sp),
        modifier = modifier,
    )
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(42.dp)
            .glass(CircleShape, tint = FlowColors.Accent, elevation = 8.dp, strong = true)
            .clip(CircleShape),
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
            tint = FlowColors.OnAccent,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Preview filling the whole screen. Overlaid controls fade out after a few seconds without
 * touches and come back on the next touch; double tap keeps toggling playback.
 */
@Composable
private fun FullscreenPreview(
    aspectRatio: AspectRatio,
    background: CanvasBackground,
    loading: Boolean,
    project: Project?,
    playheadUs: Long,
    playheadProvider: () -> Long,
    textRenderer: TextRenderer,
    durationUs: Long,
    fps: Int,
    isPlaying: Boolean,
    onAction: (EditorAction) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(interactionCount) {
        controlsVisible = true
        delay(FULLSCREEN_CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlowColors.Black)
            .pointerInput(Unit) {
                // Observe every touch without consuming it, so children keep working.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    interactionCount++
                }
            },
    ) {
        EditorPreviewArea(
            aspectRatio = aspectRatio,
            background = background,
            loading = loading,
            onDoubleTap = { onAction(EditorAction.TogglePlay) },
            preview = preview,
            modifier = Modifier.fillMaxSize(),
            overlay = {
                TextOverlayLayer(
                    project = project,
                    playheadUs = playheadProvider,
                    renderer = textRenderer,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(spacing.sm),
        ) {
            IconButton(
                onClick = { onAction(EditorAction.ToggleFullscreen) },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(FlowColors.Scrim),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FullscreenExit,
                    contentDescription = "Salir de pantalla completa",
                    tint = FlowColors.TextPrimary,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, FlowColors.Scrim),
                        ),
                    )
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                PlayPauseButton(
                    isPlaying = isPlaying,
                    onClick = { onAction(EditorAction.TogglePlay) },
                )
                PlaybackTimecode(
                    playheadUs = playheadUs,
                    durationUs = durationUs,
                    fps = fps,
                )
            }
        }
    }
}

// region Preview Fakes

private const val LIVE_PROXY_HEIGHT_PX = 720

/** Share of the landscape height an open panel may take, leaving room for the timeline. */
private const val LANDSCAPE_SHEET_SHARE = 0.6f

private class FakePreviewTimelineMediaSource : TimelineMediaSource {
    private val bitmapCache = HashMap<Int, ImageBitmap>()

    override suspend fun thumbnail(
        media: MediaRef,
        sourceTimeUs: Long,
        heightPx: Int,
    ): ImageBitmap {
        val bucket = ((sourceTimeUs / 500_000L).toInt() + media.uri.hashCode()) and 0x7FFFFFFF
        return bitmapCache.getOrPut(bucket) {
            val size = 64
            val bmp = ImageBitmap(size, size)
            val canvas = ComposeCanvas(bmp)
            val hueShift = (bucket % 5) * 18
            val bgPaint = Paint().apply {
                color = Color(
                    red = (45 + hueShift).coerceIn(0, 255) / 255f,
                    green = (95 + hueShift).coerceIn(0, 255) / 255f,
                    blue = (155 + hueShift / 2).coerceIn(0, 255) / 255f,
                )
            }
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)
            val accentPaint = Paint().apply {
                color = FlowColors.Accent.copy(alpha = 0.35f)
            }
            canvas.drawCircle(
                center = Offset(size * 0.5f, size * 0.5f),
                radius = size * 0.28f,
                paint = accentPaint,
            )
            bmp
        }
    }

    override suspend fun waveform(media: MediaRef, bucketsPerSecond: Int): FloatArray {
        val seconds = (media.durationUs / Timecode.US_PER_SECOND).toInt().coerceAtLeast(4)
        val count = seconds * bucketsPerSecond
        return FloatArray(count) { i ->
            (0.25f + 0.65f * abs(sin(i * 0.42f))).coerceIn(0.1f, 1f)
        }
    }
}

private fun createFakeEditorProject(): Project {
    val clip1Media = MediaRef(
        uri = "file:///fake/intro.mp4",
        type = MediaType.VIDEO,
        durationUs = 6_000_000L,
        width = 1080,
        height = 1920,
        hasVideo = true,
        hasAudio = true,
        displayName = "Intro_ciudad.mp4",
    )
    val clip2Media = MediaRef(
        uri = "file:///fake/broll.mp4",
        type = MediaType.VIDEO,
        durationUs = 8_000_000L,
        width = 1080,
        height = 1920,
        hasVideo = true,
        hasAudio = true,
        displayName = "Plano_detalle.mp4",
    )
    val audioMedia = MediaRef(
        uri = "file:///fake/beat.m4a",
        type = MediaType.AUDIO,
        durationUs = 12_000_000L,
        hasVideo = false,
        hasAudio = true,
        displayName = "Synthwave_Beat.m4a",
    )

    val mainClip1 = Clip(
        id = "clip_1",
        kind = ClipKind.VIDEO,
        media = clip1Media,
        startUs = 0L,
        trimInUs = 0L,
        trimOutUs = 4_000_000L,
    )
    val mainClip2 = Clip(
        id = "clip_2",
        kind = ClipKind.VIDEO,
        media = clip2Media,
        startUs = 4_000_000L,
        trimInUs = 500_000L,
        trimOutUs = 6_500_000L,
        speed = 1.5f,
    )
    val textClip = Clip(
        id = "clip_text_1",
        kind = ClipKind.TEXT,
        startUs = 1_000_000L,
        trimInUs = 0L,
        trimOutUs = 3_500_000L,
    )
    val audioClip = Clip(
        id = "clip_audio_1",
        kind = ClipKind.AUDIO,
        media = audioMedia,
        startUs = 0L,
        trimInUs = 0L,
        trimOutUs = 8_000_000L,
    )

    return Project(
        id = "proj_preview",
        name = "Demo Flow",
        createdAtMs = 1_730_000_000_000L,
        updatedAtMs = 1_730_000_500_000L,
        canvas = Canvas(aspectRatio = AspectRatio.R9_16),
        tracks = listOf(
            Track(
                id = "main",
                kind = TrackKind.MAIN,
                clips = listOf(mainClip1, mainClip2),
            ),
            Track(
                id = "track_text",
                kind = TrackKind.TEXT,
                clips = listOf(textClip),
            ),
            Track(
                id = "track_audio",
                kind = TrackKind.AUDIO,
                clips = listOf(audioClip),
            ),
        ),
        markers = listOf(
            Marker(id = "m1", timeUs = 2_000_000L, label = "Corte"),
            Marker(id = "m2", timeUs = 4_000_000L, label = "Drop"),
        ),
    )
}

@Composable
private fun FakeVideoPreviewSurface(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF132238), Color(0xFF0A101A)),
            ),
        )
        drawCircle(
            color = FlowColors.Accent.copy(alpha = 0.25f),
            radius = size.minDimension * 0.28f,
            center = Offset(size.width * 0.5f, size.height * 0.45f),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 412, heightDp = 892)
@Composable
private fun EditorContentPortraitPreview() {
    val project = remember { createFakeEditorProject() }
    val fakeMediaSource = remember { FakePreviewTimelineMediaSource() }

    FlowTheme {
        EditorContent(
            state = EditorUiState(
                project = project,
                loading = false,
                playheadUs = 2_250_000L,
                isPlaying = false,
                selectedClipId = "clip_1",
                canUndo = true,
                canRedo = false,
                pixelsPerSecond = 64f,
            ),
            onAction = {},
            mediaSource = fakeMediaSource,
            preview = { mod -> FakeVideoPreviewSurface(mod) },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 412, heightDp = 892)
@Composable
private fun EditorContentSpeedSheetPreview() {
    val project = remember { createFakeEditorProject() }
    val fakeMediaSource = remember { FakePreviewTimelineMediaSource() }

    FlowTheme {
        EditorContent(
            state = EditorUiState(
                project = project,
                loading = false,
                playheadUs = 4_500_000L,
                isPlaying = false,
                selectedClipId = "clip_2",
                canUndo = true,
                canRedo = true,
                activeSheet = EditorSheet.Speed,
            ),
            onAction = {},
            mediaSource = fakeMediaSource,
            preview = { mod -> FakeVideoPreviewSurface(mod) },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 840, heightDp = 412)
@Composable
private fun EditorContentLandscapePreview() {
    val project = remember {
        createFakeEditorProject().copy(
            canvas = Canvas(aspectRatio = AspectRatio.R16_9),
        )
    }
    val fakeMediaSource = remember { FakePreviewTimelineMediaSource() }

    FlowTheme {
        EditorContent(
            state = EditorUiState(
                project = project,
                loading = false,
                playheadUs = 1_500_000L,
                isPlaying = true,
                selectedClipId = null,
                canUndo = true,
                canRedo = true,
            ),
            onAction = {},
            mediaSource = fakeMediaSource,
            preview = { mod -> FakeVideoPreviewSurface(mod) },
        )
    }
}

// endregion

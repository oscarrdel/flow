package com.rainyworks.flow.feature.editor

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.feature.editor.audio.AudioPanel
import com.rainyworks.flow.feature.editor.audio.VoiceOverPanel
import com.rainyworks.flow.feature.editor.ui.EditorContent

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    mediaSource: TimelineMediaSource,
    onClose: () -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val close by rememberUpdatedState(onClose)
    val export by rememberUpdatedState(onExport)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        viewModel.onMediaPicked(uris)
    }

    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.onMediaPicked(listOf(uri))
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        viewModel.onMediaPicked(uris)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onMicPermissionResult(granted)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorEvent.PickMedia -> when (event.purpose) {
                    PickPurpose.Audio -> audioPicker.launch(arrayOf("audio/*"))
                    PickPurpose.Replace -> singlePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                    else -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                EditorEvent.Closed -> close()
                is EditorEvent.OpenExport -> export(event.projectId)
                EditorEvent.RequestMicPermission -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                EditorEvent.Tick -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        viewModel.onAction(EditorAction.NoticeShown)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onStop()
    }

    Box(modifier.fillMaxSize()) {
        EditorContent(
            state = state,
            onAction = viewModel::onAction,
            modifier = Modifier.systemBarsPadding(),
            mediaSource = mediaSource,
            preview = { previewModifier ->
                PlayerSurface(
                    player = viewModel.preview.player,
                    modifier = previewModifier,
                    surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                )
            },
        )
        val panelModifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
        val voiceOver = state.voiceOver
        if (voiceOver != null) {
            VoiceOverPanel(state = voiceOver, onAction = viewModel::onAction, modifier = panelModifier)
        } else if (state.audioPanelOpen) {
            val selected = state.selectedClipId?.let { id -> state.project?.findClip(id)?.second }
            AudioPanel(
                canExtract = selected?.kind == ClipKind.VIDEO && selected.media?.hasAudio == true,
                onAction = viewModel::onAction,
                modifier = panelModifier,
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
    }
}

private const val MAX_PICK = 50

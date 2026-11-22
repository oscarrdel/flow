package com.rainyworks.flow

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.rainyworks.flow.core.audio.VoiceRecorder
import com.rainyworks.flow.core.engine.PreviewController
import com.rainyworks.flow.feature.editor.EditorScreen
import com.rainyworks.flow.feature.editor.EditorStrings
import com.rainyworks.flow.feature.editor.EditorViewModel
import com.rainyworks.flow.feature.export.ExportScreen
import com.rainyworks.flow.feature.export.ExportViewModel
import com.rainyworks.flow.feature.home.HomeScreen
import com.rainyworks.flow.feature.home.HomeStrings
import com.rainyworks.flow.feature.home.HomeViewModel
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import com.rainyworks.flow.feature.editor.R as EditorR
import com.rainyworks.flow.feature.home.R as HomeR

@Serializable
object HomeRoute

@Serializable
data class EditorRoute(val projectId: String)

@Serializable
data class ExportRoute(val projectId: String)

@Serializable
object AboutRoute

@Composable
fun FlowNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val context = LocalContext.current
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            val viewModel: HomeViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { HomeViewModel(container.projectRepository, homeStrings(context)) }
                },
            )
            HomeScreen(
                viewModel = viewModel,
                onOpenProject = { id -> navController.navigate(EditorRoute(id)) { launchSingleTop = true } },
                onOpenAbout = { navController.navigate(AboutRoute) { launchSingleTop = true } },
            )
        }
        composable<EditorRoute> { entry ->
            val route = entry.toRoute<EditorRoute>()
            val viewModel: EditorViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        EditorViewModel(
                            projectId = route.projectId,
                            repository = container.projectRepository,
                            preview = PreviewController(container.application),
                            probe = container.mediaProbe,
                            thumbnails = container.thumbnails,
                            strings = editorStrings(context),
                            recorder = VoiceRecorder(container.application),
                            fonts = container.fontStore,
                            contentResolver = container.application.contentResolver,
                            previewShortSide = container.editorPreferences.previewQuality.map { it.shortSidePx },
                            reverser = { media, progress -> container.reverseProxy.create(media, progress) },
                            stickers = { spec -> container.stickers.render(spec) },
                        )
                    }
                },
            )
            EditorScreen(
                viewModel = viewModel,
                mediaSource = container.timelineMediaSource,
                onClose = { navController.popBackStack() },
                onExport = { id -> navController.navigate(ExportRoute(id)) { launchSingleTop = true } },
            )
        }
        composable<AboutRoute> {
            AboutScreen(preferences = container.editorPreferences, onBack = { navController.popBackStack() })
        }
        composable<ExportRoute> { entry ->
            val route = entry.toRoute<ExportRoute>()
            val viewModel: ExportViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ExportViewModel(route.projectId, container.projectRepository, container.exportManager) }
                },
            )
            ExportScreen(viewModel = viewModel, onDone = { navController.popBackStack() })
        }
    }
}

private fun homeStrings(context: Context) = object : HomeStrings {
    override fun defaultProjectName(index: Int) = context.getString(HomeR.string.home_default_project_name, index)
    override val copySuffix = context.getString(HomeR.string.home_copy_suffix)
}

private fun editorStrings(context: Context) = EditorStrings(
    untitledProject = context.getString(EditorR.string.editor_untitled_project),
    nothingToExport = context.getString(EditorR.string.editor_nothing_to_export),
    someMediaFailed = context.getString(EditorR.string.editor_some_media_failed),
    cannotSplitHere = context.getString(EditorR.string.editor_cannot_split_here),
    comingSoon = context.getString(EditorR.string.editor_coming_soon),
    cannotReplace = context.getString(EditorR.string.editor_cannot_replace),
    cannotFreezeHere = context.getString(EditorR.string.editor_cannot_freeze_here),
    noAudioToExtract = context.getString(EditorR.string.editor_no_audio_to_extract),
    micPermissionDenied = context.getString(EditorR.string.editor_mic_permission_denied),
    micUnavailable = context.getString(EditorR.string.editor_mic_unavailable),
    recordingTooShort = context.getString(EditorR.string.editor_recording_too_short),
    voiceOverName = context.getString(EditorR.string.editor_voice_over_name),
    defaultText = context.getString(EditorR.string.editor_default_text),
    selectClipFirst = context.getString(EditorR.string.editor_select_clip_first),
    gradeAppliedToAll = context.getString(EditorR.string.editor_grade_applied_to_all),
    invalidLut = context.getString(EditorR.string.editor_invalid_lut),
    invalidFont = context.getString(EditorR.string.editor_invalid_font),
    invalidSrt = context.getString(EditorR.string.editor_invalid_srt),
    noSubtitles = context.getString(EditorR.string.editor_no_subtitles),
    subtitleOverlaps = context.getString(EditorR.string.editor_subtitle_overlaps),
    subtitlesImported = context.getString(EditorR.string.editor_subtitles_imported),
    subtitlesExported = context.getString(EditorR.string.editor_subtitles_exported),
    transitionAppliedToAll = context.getString(EditorR.string.editor_transition_applied_to_all),
    reversing = context.getString(EditorR.string.editor_reversing),
    reverseFailed = context.getString(EditorR.string.editor_reverse_failed),
    reverseRemoved = context.getString(EditorR.string.editor_reverse_removed),
    cannotReverse = context.getString(EditorR.string.editor_cannot_reverse),
)

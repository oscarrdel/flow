package com.rainyworks.flow.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.google.common.util.concurrent.ListenableFuture
import com.rainyworks.flow.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Se crea en el hilo principal. Los controles también se pueden invocar desde otros hilos. */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class PreviewController @MainThread constructor(context: Context) {
    private val main = Handler(Looper.getMainLooper())

    init {
        check(Looper.myLooper() == main.looper) { "La vista previa debe crearse en el hilo principal." }
    }

    private val app = context.applicationContext

    private fun createPlayer() = CompositionPlayer.Builder(app)
        .setLooper(main.looper)
        .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        .build()

    private var preview = createPlayer()
    private val output = PreviewOutput(preview)
    val player: Player get() = output

    private val position = MutableStateFlow(0L)
    val positionUs: StateFlow<Long> = position.asStateFlow()
    private val playing = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = playing.asStateFlow()
    private val playbackError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = playbackError.asStateFlow()
    private var released = false
    private var current: Project? = null
    private var retried = false
    private var playerFailed = false
    private var pending: Project? = null
    private val rebuild = Runnable { applyPending() }

    // Media3 1.11 can deadlock when an image sequence and a video graph wait on each other
    // after a gap; playback then sits in BUFFERING forever. Rebuild the player when the
    // position stops moving while the user wants playback.
    private var watchedPositionMs = -1L
    private var watchedSinceMs = 0L
    private var stallRecoveries = 0
    private var stallPositionMs = -1L
    /** Only playback that started and then stopped counts as a stall, not slow first loads. */
    private var startedSincePrepare = false
    private val stallWatch = object : Runnable {
        override fun run() {
            if (released) return
            if (!preview.playWhenReady || preview.playbackState == Player.STATE_ENDED || current?.isEmpty != false) {
                watchedPositionMs = -1L
                return
            }
            val positionMs = preview.currentPosition
            if (!startedSincePrepare) {
                watchedPositionMs = -1L
                main.postDelayed(this, STALL_CHECK_MS)
                return
            }
            // Real progress well past the last stall ends that episode and its retry budget.
            if (stallPositionMs >= 0 && positionMs > stallPositionMs + STALL_PROGRESS_MS) {
                stallPositionMs = -1L
                stallRecoveries = 0
            }
            val now = SystemClock.elapsedRealtime()
            if (positionMs != watchedPositionMs) {
                watchedPositionMs = positionMs
                watchedSinceMs = now
            } else if (now - watchedSinceMs >= STALL_TIMEOUT_MS) {
                watchedPositionMs = -1L
                // Recovery prepares a new player, which schedules the next check itself.
                if (recoverFromStall(positionMs)) return
            }
            main.postDelayed(this, STALL_CHECK_MS)
        }
    }

    private fun watchForStalls() {
        main.removeCallbacks(stallWatch)
        watchedPositionMs = -1L
        main.postDelayed(stallWatch, STALL_CHECK_MS)
    }

    /** Returns true when a new player was prepared. */
    private fun recoverFromStall(positionMs: Long): Boolean {
        val project = current ?: return false
        // One budget per stall episode; nudged positions belong to the same episode.
        if (stallPositionMs < 0 || positionMs > stallPositionMs + STALL_PROGRESS_MS) {
            stallPositionMs = positionMs
            stallRecoveries = 0
        }
        if (stallRecoveries >= MAX_STALL_RECOVERIES) return false
        stallRecoveries++
        // The second attempt also nudges past the boundary that stalled.
        val resumeAtMs = if (stallRecoveries > 1) positionMs + STALL_NUDGE_MS else positionMs
        prepareProject(project, startMs = resumeAtMs, forcePlay = true)
        return true
    }

    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            if (!released && preview.isPlaying) main.postDelayed(this, 33)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startedSincePrepare = true
            playing.value = isPlaying
            main.removeCallbacks(tick)
            tick.run()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = updatePosition()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!playerFailed && playbackState == Player.STATE_READY) playbackError.value = null
            updatePosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            handleFailure(error, "No se pudo reproducir la vista previa (código ${error.errorCode}).")
        }
    }

    init {
        preview.addListener(listener)
    }

    private fun handleFailure(cause: Throwable, message: String) {
        if (playerFailed) return
        playerFailed = true
        playbackError.value = if (retried) message else "$message Reintentando…"
        val project = current ?: return
        if (retried) return
        retried = true
        val failed = preview
        main.post {
            if (released || failed !== preview || project != current) return@post
            GoldfishAvc.onPreviewFailure(cause)
            if (pending != null && pending != project) applyPending() else prepareProject(project)
        }
    }

    private var previewShortSidePx = DEFAULT_PREVIEW_SHORT_SIDE

    /** Lower values make heavy projects play smoothly; the current project is rebuilt at once. */
    fun setPreviewResolution(shortSidePx: Int) = onMain {
        val value = shortSidePx.coerceIn(MIN_PREVIEW_SHORT_SIDE, MAX_PREVIEW_SHORT_SIDE)
        if (value == previewShortSidePx) return@onMain
        previewShortSidePx = value
        val project = current ?: return@onMain
        // A non-null current project makes prepareProject recreate the player, which Media3
        // needs whenever the composition changes.
        prepareProject(project)
    }

    private val live = LiveEdit()
    /** Rebuild even if the project did not change, to show a clip hidden for a gesture again. */
    private var forceRebuild = false

    /**
     * Leaves [clipId] out of the preview while the editor draws it under the user's finger.
     * The player is rebuilt once now, and once more after [endLiveEdit].
     */
    fun beginLiveEdit(clipId: String) = onMain {
        if (live.hiddenClipId == clipId) return@onMain
        live.hide(clipId)
        val project = pending ?: current ?: return@onMain
        pending = null
        main.removeCallbacks(rebuild)
        prepareProject(project)
    }

    /** Shows the dragged clip in the preview again; the next rebuild includes it. */
    fun endLiveEdit() = onMain {
        if (!live.isActive) return@onMain
        live.clear()
        forceRebuild = true
        if (pending == null) pending = current
        if (!main.hasCallbacks(rebuild)) main.postDelayed(rebuild, 50)
    }

    fun setProject(project: Project) = onMain {
        pending = project
        if (current == null) applyPending()
        else if (!main.hasCallbacks(rebuild)) main.postDelayed(rebuild, 50)
    }

    private fun applyPending() {
        main.removeCallbacks(rebuild)
        val project = pending ?: return
        pending = null
        if (project == current && !forceRebuild) return
        forceRebuild = false
        retried = false
        prepareProject(project)
    }

    private fun prepareProject(project: Project, startMs: Long? = null, forcePlay: Boolean = false) {
        val positionMs = (startMs ?: preview.currentPosition).coerceIn(0L, project.durationUs / 1_000)
        val resume = (forcePlay || preview.playWhenReady) && !project.isEmpty
        if (current != null || forcePlay) {
            // setComposition retains the previous video graph in Media3 1.11.1. After a
            // paused edit its replacement renderer can wait indefinitely for the first frame.
            val volume = preview.volume
            main.removeCallbacks(tick)
            playing.value = false
            preview.removeListener(listener)
            preview.release()
            preview = createPlayer().also { it.volume = volume }
            output.attach(preview)
            preview.addListener(listener)
            playerFailed = false
        }
        current = project
        startedSincePrepare = false
        if (project.isEmpty) {
            // Nothing to play: keep the player idle instead of reporting a build failure.
            main.removeCallbacks(stallWatch)
            preview.pause()
            playerFailed = false
            playbackError.value = null
            position.value = 0L
            return
        }
        try {
            preview.setComposition(CompositionFactory.build(project, ExportSettings(heightPx = previewShortSidePx), live = live), positionMs)
            preview.prepare()
            preview.playWhenReady = resume
            if (resume) watchForStalls()
        } catch (failure: RuntimeException) {
            preview.pause()
            handleFailure(failure, "No se pudo preparar la composición para la vista previa.")
        }
        updatePosition()
    }

    fun play() = onMain {
        applyPending()
        if (current?.isEmpty == false) {
            if (preview.playbackState == Player.STATE_ENDED) preview.seekTo(0)
            preview.play()
            watchForStalls()
        }
    }

    fun pause() = onMain {
        main.removeCallbacks(stallWatch)
        preview.pause()
    }

    fun seekTo(timelineUs: Long) = onMain {
        applyPending()
        val project = current ?: return@onMain
        preview.seekTo(timelineUs.coerceIn(0L, project.durationUs) / 1_000)
        updatePosition()
    }

    private fun updatePosition() {
        position.value = (preview.currentPosition * 1_000).coerceIn(0L, current?.durationUs ?: 0L)
    }

    fun release() = onMain {
        released = true
        main.removeCallbacksAndMessages(null)
        preview.removeListener(listener)
        output.release()
        pending = null
        current = null
        playing.value = false
        position.value = 0L
        playbackError.value = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) {
            if (!released) block()
        } else {
            main.post { if (!released) block() }
        }
    }

    /** Mantiene los listeners del editor y reconecta la superficie al recuperar el reproductor. */
    private class PreviewOutput(player: Player) : ForwardingSimpleBasePlayer(player) {
        private var videoOutput: Any? = null

        override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> =
            super.handleSetVideoOutput(videoOutput).also { this.videoOutput = videoOutput }

        override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> =
            super.handleClearVideoOutput(videoOutput).also {
                if (videoOutput == null || videoOutput === this.videoOutput) this.videoOutput = null
            }

        fun attach(replacement: Player) {
            setPlayer(replacement)
            videoOutput?.let { super.handleSetVideoOutput(it) }
        }

        override fun handleRelease(): ListenableFuture<*> {
            videoOutput = null
            return super.handleRelease()
        }
    }
}

private const val STALL_CHECK_MS = 500L
private const val STALL_TIMEOUT_MS = 1_500L
private const val STALL_NUDGE_MS = 34L
private const val MAX_STALL_RECOVERIES = 2
private const val STALL_PROGRESS_MS = 500L
private const val DEFAULT_PREVIEW_SHORT_SIDE = 720
private const val MIN_PREVIEW_SHORT_SIDE = 240
private const val MAX_PREVIEW_SHORT_SIDE = 1080

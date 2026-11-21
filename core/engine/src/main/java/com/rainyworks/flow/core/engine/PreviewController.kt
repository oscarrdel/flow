package com.rainyworks.flow.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            if (!released && preview.isPlaying) main.postDelayed(this, 33)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
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

    fun setProject(project: Project) = onMain {
        pending = project
        if (current == null) applyPending()
        else if (!main.hasCallbacks(rebuild)) main.postDelayed(rebuild, 50)
    }

    private fun applyPending() {
        main.removeCallbacks(rebuild)
        val project = pending ?: return
        pending = null
        if (project == current) return
        retried = false
        prepareProject(project)
    }

    private fun prepareProject(project: Project) {
        val positionMs = preview.currentPosition.coerceIn(0L, project.durationUs / 1_000)
        val resume = preview.playWhenReady && !project.isEmpty
        if (playerFailed) {
            // En 1.11.1 la excepción queda asociada a la instancia incluso después de prepare().
            preview.removeListener(listener)
            preview.release()
            preview = createPlayer()
            output.attach(preview)
            preview.addListener(listener)
            playerFailed = false
        }
        current = project
        try {
            preview.setComposition(CompositionFactory.build(project, forExport = false), positionMs)
            preview.prepare()
            preview.playWhenReady = resume
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
        }
    }

    fun pause() = onMain { preview.pause() }

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

package com.rainyworks.flow.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
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

    private val preview = CompositionPlayer.Builder(context.applicationContext)
        .setLooper(main.looper)
        .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        .build()
    val player: Player get() = preview

    private val position = MutableStateFlow(0L)
    val positionUs: StateFlow<Long> = position.asStateFlow()
    private val playing = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = playing.asStateFlow()
    private var released = false
    private var current: Project? = null
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

        override fun onPlaybackStateChanged(playbackState: Int) = updatePosition()
    }

    init {
        preview.addListener(listener)
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
        val positionMs = preview.currentPosition.coerceIn(0L, project.durationUs / 1_000)
        val resume = preview.playWhenReady && !project.isEmpty
        val composition = CompositionFactory.build(project, forExport = false)
        current = project
        preview.setComposition(composition, positionMs)
        preview.prepare()
        preview.playWhenReady = resume
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
        preview.release()
        pending = null
        current = null
        playing.value = false
        position.value = 0L
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) {
            if (!released) block()
        } else {
            main.post { if (!released) block() }
        }
    }
}

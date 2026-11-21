package com.rainyworks.flow

import android.app.Application
import com.rainyworks.flow.core.data.DataModule
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.engine.MediaProbe
import com.rainyworks.flow.core.engine.ThumbnailProvider
import com.rainyworks.flow.core.engine.WaveformProvider
import com.rainyworks.flow.feature.editor.EngineTimelineMediaSource

class FlowApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(val application: Application) {
    private val data = DataModule(application)
    val projectRepository get() = data.projectRepository

    val mediaProbe by lazy { MediaProbe(application) }
    val thumbnails by lazy { ThumbnailProvider(application) }
    val waveforms by lazy { WaveformProvider(application) }
    val exporter by lazy { Exporter(application) }
    val timelineMediaSource by lazy { EngineTimelineMediaSource(thumbnails, waveforms) }
}

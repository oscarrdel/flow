package com.rainyworks.flow

import android.app.Application
import com.rainyworks.flow.core.data.DataModule
import com.rainyworks.flow.core.engine.Exporter
import com.rainyworks.flow.core.engine.MediaProbe
import com.rainyworks.flow.core.engine.reverse.ReverseProxy
import com.rainyworks.flow.core.engine.ThumbnailProvider
import com.rainyworks.flow.core.engine.WaveformProvider
import com.rainyworks.flow.feature.editor.EngineTimelineMediaSource
import com.rainyworks.flow.feature.export.ExportManager
import com.rainyworks.flow.feature.export.ExportManagerHolder

class FlowApplication : Application(), ExportManagerHolder {
    lateinit var container: AppContainer
        private set

    override val exportManager: ExportManager get() = container.exportManager

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(val application: Application) {
    private val data = DataModule(application)
    val projectRepository get() = data.projectRepository
    val fontStore get() = data.fontStore
    val editorPreferences get() = data.editorPreferences

    val mediaProbe by lazy { MediaProbe(application) }
    val thumbnails by lazy { ThumbnailProvider(application) }
    val waveforms by lazy { WaveformProvider(application) }
    val exporter by lazy { Exporter(application) }
    val reverseProxy by lazy { ReverseProxy(application) }
    val stickers by lazy { com.rainyworks.flow.core.engine.stickers.StickerRenderer(application) }
    val exportManager by lazy { ExportManager(application, exporter) }
    val timelineMediaSource by lazy { EngineTimelineMediaSource(thumbnails, waveforms) }
}

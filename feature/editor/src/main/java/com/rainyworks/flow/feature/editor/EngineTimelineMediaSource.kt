package com.rainyworks.flow.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.rainyworks.flow.core.engine.ThumbnailProvider
import com.rainyworks.flow.core.engine.WaveformProvider
import com.rainyworks.flow.core.model.MediaRef

class EngineTimelineMediaSource(
    private val thumbnails: ThumbnailProvider,
    private val waveforms: WaveformProvider,
) : TimelineMediaSource {
    override suspend fun thumbnail(media: MediaRef, sourceTimeUs: Long, heightPx: Int): ImageBitmap? =
        thumbnails.frameAt(media, sourceTimeUs, heightPx)?.asImageBitmap()

    override suspend fun waveform(media: MediaRef, bucketsPerSecond: Int): FloatArray? =
        waveforms.peaks(media, bucketsPerSecond)
}

package com.rainyworks.flow.core.engine

import com.rainyworks.flow.core.model.Clip

/**
 * The clip the user is dragging right now. A paused Media3 player cannot draw new frames
 * fast enough to follow a finger, so while a gesture is in progress the editor draws that
 * clip itself and the preview leaves it out; the composition is rebuilt with the final
 * values once the gesture ends.
 */
class LiveEdit {
    @Volatile var hiddenClipId: String? = null
        private set

    fun hide(clipId: String) {
        hiddenClipId = clipId
    }

    fun clear() {
        hiddenClipId = null
    }

    val isActive: Boolean get() = hiddenClipId != null

    fun hides(clip: Clip): Boolean = clip.id == hiddenClipId
}

/**
 * Preview only: the latest picture time the layer compositor produced. When the playback clock
 * keeps moving but no new picture arrives, the video graph has stalled.
 */
class FrameClock {
    @Volatile var lastFrameUs: Long = -1L
        private set

    fun onFrame(presentationTimeUs: Long) {
        lastFrameUs = presentationTimeUs
    }

    fun reset() {
        lastFrameUs = -1L
    }
}

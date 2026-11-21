package com.rainyworks.flow.core.model

object Timecode {
    const val US_PER_SECOND = 1_000_000L

    fun frameDurationUs(fps: Int): Long = US_PER_SECOND / fps

    /** Snaps a time to the nearest frame boundary. */
    fun snapToFrame(timeUs: Long, fps: Int): Long {
        val frame = frameDurationUs(fps)
        return ((timeUs + frame / 2) / frame) * frame
    }

    /** Formats as MM:SS:FF (minutes, seconds, frames). */
    fun format(timeUs: Long, fps: Int = 30): String {
        val clamped = timeUs.coerceAtLeast(0L)
        val totalSeconds = clamped / US_PER_SECOND
        val frames = ((clamped % US_PER_SECOND) * fps) / US_PER_SECOND
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(minutes, seconds, frames)
    }

    /** Formats as M:SS for compact duration labels. */
    fun formatShort(timeUs: Long): String {
        val totalSeconds = (timeUs.coerceAtLeast(0L) + US_PER_SECOND / 2) / US_PER_SECOND
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}

package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable

const val CURRENT_SCHEMA_VERSION = 1

/**
 * Root of the editable document. Every edit produces a new immutable [Project];
 * source media files are only referenced, never modified.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val canvas: Canvas = Canvas(),
    val tracks: List<Track> = listOf(Track.main()),
    val markers: List<Marker> = emptyList(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    val mainTrack: Track
        get() = tracks.first { it.kind == TrackKind.MAIN }

    val durationUs: Long
        get() = tracks.maxOfOrNull { it.endUs } ?: 0L

    val isEmpty: Boolean
        get() = tracks.all { it.clips.isEmpty() }

    fun findClip(clipId: String): Pair<Track, Clip>? {
        for (track in tracks) {
            val clip = track.clips.firstOrNull { it.id == clipId }
            if (clip != null) return track to clip
        }
        return null
    }

    fun track(trackId: String): Track? = tracks.firstOrNull { it.id == trackId }
}

@Serializable
data class Marker(
    val id: String,
    val timeUs: Long,
    val label: String = "",
)

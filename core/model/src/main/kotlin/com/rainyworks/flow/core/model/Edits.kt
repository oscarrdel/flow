package com.rainyworks.flow.core.model

import java.util.UUID
import kotlin.math.roundToLong

/**
 * Pure editing operations. Each returns a new [Project]; invalid requests return the
 * project unchanged so callers never have to deal with partial edits.
 */
object Edits {
    /** Shortest clip the editor allows, roughly one frame at 30 fps. */
    const val MIN_CLIP_US = 33_333L

    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 100f

    fun newId(): String = UUID.randomUUID().toString()

    /** Inserts clips into the main track at the clip boundary closest to [atUs], or at the end. */
    fun insertIntoMain(project: Project, clips: List<Clip>, atUs: Long? = null): Project {
        if (clips.isEmpty()) return project
        val main = project.mainTrack
        if (main.locked) return project
        val index = if (atUs == null) main.clips.size else insertionIndex(main, atUs)
        val newClips = main.clips.toMutableList().apply { addAll(index, clips) }
        var updated = project.replaceTrack(main.copy(clips = layoutMagnetic(newClips)))
        if (project.canvas.followFirstClip && main.clips.isEmpty()) {
            val first = clips.first().media
            if (first != null && first.displayWidth > 0 && first.displayHeight > 0) {
                updated = updated.copy(
                    canvas = updated.canvas.copy(
                        aspectRatio = AspectRatio.fromSize(first.displayWidth, first.displayHeight),
                    ),
                )
            }
        }
        return updated
    }

    /** Adds a clip to a free-placement track, creating a new track of [kind] when needed. */
    fun addToLayer(project: Project, clip: Clip, kind: TrackKind, trackId: String? = null): Project {
        require(kind != TrackKind.MAIN) { "Use insertIntoMain for the main track" }
        val existing = trackId?.let(project::track)?.takeIf { !it.locked && it.kind == kind }
            ?: project.tracks.firstOrNull { it.kind == kind && !it.locked && it.isFree(clip.startUs, clip.endUs) }
        return if (existing != null && existing.isFree(clip.startUs, clip.endUs)) {
            project.replaceTrack(existing.copy(clips = (existing.clips + clip).sortedBy { it.startUs }))
        } else {
            project.copy(tracks = project.tracks + Track.new(kind).copy(clips = listOf(clip)))
        }
    }

    fun split(project: Project, clipId: String, atUs: Long): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        if (atUs - clip.startUs < MIN_CLIP_US || clip.endUs - atUs < MIN_CLIP_US) return project
        val sourceAt = clip.sourceTimeAt(atUs)
        val (first, second) = if (!clip.reversed) {
            clip.copy(trimOutUs = sourceAt, fadeOutUs = 0L) to
                clip.copy(id = newId(), startUs = atUs, trimInUs = sourceAt, fadeInUs = 0L)
        } else {
            clip.copy(trimInUs = sourceAt, fadeOutUs = 0L) to
                clip.copy(id = newId(), startUs = atUs, trimOutUs = sourceAt, fadeInUs = 0L)
        }
        val clips = track.clips.flatMap { if (it.id == clipId) listOf(first, second) else listOf(it) }
        return project.replaceTrack(track.withClips(clips))
    }

    /** Moves the clip's left edge to [newStartUs] (timeline time), keeping its right edge fixed. */
    fun trimStart(project: Project, clipId: String, newStartUs: Long): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val minStart = if (track.isMagnetic) clip.startUs - maxExtensionAtStart(clip) else
            maxOf(previousEnd(track, clip), clip.startUs - maxExtensionAtStart(clip))
        val maxStart = clip.endUs - MIN_CLIP_US
        if (minStart > maxStart) return project
        val start = newStartUs.coerceIn(minStart, maxStart)
        val trimmed = when {
            clip.hasUnboundedSource -> clip.copy(
                startUs = start,
                trimInUs = 0L,
                trimOutUs = ((clip.endUs - start) * clip.speed.toDouble()).roundToLong(),
            )
            !clip.reversed -> {
                val sourceDelta = ((start - clip.startUs) * clip.speed.toDouble()).roundToLong()
                clip.copy(startUs = start, trimInUs = (clip.trimInUs + sourceDelta).coerceAtLeast(0L))
            }
            else -> {
                val sourceDelta = ((start - clip.startUs) * clip.speed.toDouble()).roundToLong()
                clip.copy(startUs = start, trimOutUs = (clip.trimOutUs - sourceDelta).coerceAtMost(mediaDuration(clip)))
            }
        }
        if (trimmed.sourceDurationUs <= 0L) return project
        return project.replaceTrack(track.withClips(track.clips.map { if (it.id == clipId) trimmed else it }))
    }

    /** Moves the clip's right edge to [newEndUs] (timeline time), keeping its left edge fixed. */
    fun trimEnd(project: Project, clipId: String, newEndUs: Long): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val maxEnd = if (track.isMagnetic) clip.endUs + maxExtensionAtEnd(clip) else
            minOf(nextStart(track, clip), clip.endUs + maxExtensionAtEnd(clip))
        val minEnd = clip.startUs + MIN_CLIP_US
        if (minEnd > maxEnd) return project
        val end = newEndUs.coerceIn(minEnd, maxEnd)
        val sourceDelta = ((end - clip.endUs) * clip.speed.toDouble()).roundToLong()
        val trimmed = if (clip.hasUnboundedSource) {
            clip.copy(trimInUs = 0L, trimOutUs = ((end - clip.startUs) * clip.speed.toDouble()).roundToLong())
        } else if (!clip.reversed) {
            clip.copy(trimOutUs = (clip.trimOutUs + sourceDelta).coerceAtMost(mediaDuration(clip)))
        } else {
            clip.copy(trimInUs = (clip.trimInUs - sourceDelta).coerceAtLeast(0L))
        }
        if (trimmed.sourceDurationUs <= 0L) return project
        return project.replaceTrack(track.withClips(track.clips.map { if (it.id == clipId) trimmed else it }))
    }

    fun delete(project: Project, clipId: String): Project {
        val (track, _) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val remaining = track.withClips(track.clips.filterNot { it.id == clipId })
        val tracks = project.tracks.mapNotNull {
            when {
                it.id != track.id -> it
                remaining.clips.isEmpty() && !remaining.isMagnetic -> null
                else -> remaining
            }
        }
        return project.copy(tracks = tracks)
    }

    /** Duplicates a clip right after itself. On free tracks the copy goes to a free slot or a new track. */
    fun duplicate(project: Project, clipId: String): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val copy = clip.copy(id = newId(), startUs = clip.endUs)
        return if (track.isMagnetic) {
            val index = track.clips.indexOfFirst { it.id == clipId } + 1
            project.replaceTrack(track.withClips(track.clips.toMutableList().apply { add(index, copy) }))
        } else {
            addToLayer(project, copy, track.kind, track.id.takeIf { track.isFree(copy.startUs, copy.endUs) })
        }
    }

    /** Reorders a clip inside the main track. */
    fun moveInMain(project: Project, clipId: String, toIndex: Int): Project {
        val main = project.mainTrack
        if (main.locked) return project
        val from = main.clips.indexOfFirst { it.id == clipId }
        if (from < 0) return project
        val clips = main.clips.toMutableList()
        val clip = clips.removeAt(from)
        clips.add(toIndex.coerceIn(0, clips.size), clip)
        return project.replaceTrack(main.withClips(clips))
    }

    /**
     * Moves a clip on or between free-placement tracks. Returns the project unchanged
     * when the destination overlaps another clip.
     */
    fun moveToLayer(project: Project, clipId: String, toTrackId: String, toStartUs: Long): Project {
        val (fromTrack, clip) = project.findClip(clipId) ?: return project
        val toTrack = project.track(toTrackId) ?: return project
        if (fromTrack.isMagnetic || toTrack.isMagnetic || fromTrack.locked || toTrack.locked) return project
        val moved = clip.copy(startUs = toStartUs.coerceAtLeast(0L))
        if (!toTrack.isFree(moved.startUs, moved.endUs, ignoreClipId = clipId)) return project
        val tracks = project.tracks.mapNotNull { track ->
            val clips = track.clips.filterNot { it.id == clipId }.let { if (track.id == toTrackId) it + moved else it }
            if (clips.isEmpty() && !track.isMagnetic) null else track.copy(clips = clips.sortedBy { it.startUs })
        }
        return project.copy(tracks = tracks)
    }

    fun setSpeed(project: Project, clipId: String, speed: Float): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val updated = clip.copy(speed = speed.coerceIn(MIN_SPEED, MAX_SPEED))
        if (!track.isMagnetic && !track.isFree(updated.startUs, updated.endUs, ignoreClipId = clipId)) return project
        return project.replaceTrack(track.withClips(track.clips.map { if (it.id == clipId) updated else it }))
    }

    fun updateClip(project: Project, clipId: String, transform: (Clip) -> Clip): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val updated = transform(clip).copy(id = clip.id)
        return project.replaceTrack(track.withClips(track.clips.map { if (it.id == clipId) updated else it }))
    }

    fun updateTrack(project: Project, trackId: String, transform: (Track) -> Track): Project {
        val track = project.track(trackId) ?: return project
        return project.replaceTrack(transform(track).copy(id = track.id, kind = track.kind))
    }

    fun addMarker(project: Project, timeUs: Long): Project =
        project.copy(markers = (project.markers + Marker(newId(), timeUs.coerceAtLeast(0L))).sortedBy { it.timeUs })

    fun removeMarker(project: Project, markerId: String): Project =
        project.copy(markers = project.markers.filterNot { it.id == markerId })

    fun setAspectRatio(project: Project, ratio: AspectRatio): Project =
        project.copy(canvas = project.canvas.copy(aspectRatio = ratio, followFirstClip = false))

    fun setBackground(project: Project, background: CanvasBackground): Project =
        project.copy(canvas = project.canvas.copy(background = background))

    // region helpers

    /** Lays out clips back to back starting at zero. */
    fun layoutMagnetic(clips: List<Clip>): List<Clip> {
        var cursor = 0L
        return clips.map { clip ->
            val placed = if (clip.startUs == cursor) clip else clip.copy(startUs = cursor)
            cursor = placed.endUs
            placed
        }
    }

    private fun insertionIndex(track: Track, atUs: Long): Int {
        val index = track.clips.indexOfFirst { atUs < it.startUs + it.durationUs / 2 }
        return if (index < 0) track.clips.size else index
    }

    private fun maxExtensionAtStart(clip: Clip): Long {
        if (clip.hasUnboundedSource) return Long.MAX_VALUE / 4
        val sourceRoom = if (!clip.reversed) clip.trimInUs else mediaDuration(clip) - clip.trimOutUs
        return (sourceRoom.coerceAtLeast(0L) / clip.speed.toDouble()).roundToLong()
    }

    private fun maxExtensionAtEnd(clip: Clip): Long {
        if (clip.hasUnboundedSource) return Long.MAX_VALUE / 4
        val sourceRoom = if (!clip.reversed) mediaDuration(clip) - clip.trimOutUs else clip.trimInUs
        return (sourceRoom.coerceAtLeast(0L) / clip.speed.toDouble()).roundToLong()
    }

    private fun mediaDuration(clip: Clip): Long = clip.media?.durationUs?.takeIf { it > 0L } ?: clip.trimOutUs

    private fun previousEnd(track: Track, clip: Clip): Long =
        track.clips.filter { it.id != clip.id && it.endUs <= clip.startUs }.maxOfOrNull { it.endUs } ?: 0L

    private fun nextStart(track: Track, clip: Clip): Long =
        track.clips.filter { it.id != clip.id && it.startUs >= clip.endUs }.minOfOrNull { it.startUs }
            ?: Long.MAX_VALUE / 4

    // endregion
}

internal fun Project.replaceTrack(track: Track): Project =
    copy(tracks = tracks.map { if (it.id == track.id) track else it })

internal fun Track.withClips(clips: List<Clip>): Track =
    copy(clips = if (isMagnetic) Edits.layoutMagnetic(clips) else clips.sortedBy { it.startUs })

fun Track.isFree(startUs: Long, endUs: Long, ignoreClipId: String? = null): Boolean =
    clips.none { it.id != ignoreClipId && startUs < it.endUs && endUs > it.startUs }

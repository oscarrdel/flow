package com.rainyworks.flow.core.model

/** Builds a project that contains only the part of another project between two timeline times. */
object ProjectSlice {
    /**
     * Returns a copy of [project] limited to [startUs, endUs): clips outside the range are
     * dropped, clips crossing an edge are trimmed through the same logic as user trims, and
     * everything is shifted so the range starts at zero.
     */
    fun slice(project: Project, startUs: Long, endUs: Long): Project {
        val start = startUs.coerceIn(0L, project.durationUs)
        val end = endUs.coerceIn(start, project.durationUs)
        if (end - start < Edits.MIN_CLIP_US) return project.copy(tracks = project.tracks.map { it.copy(clips = emptyList()) })

        var result = project.copy(tracks = project.tracks.map { it.copy(locked = false) })
        // Split at both edges so every remaining clip is either fully inside or fully outside.
        for (cut in listOf(start, end)) {
            val crossing = result.tracks.flatMap { t -> t.clips.filter { cut > it.startUs && cut < it.endUs }.map { it.id } }
            for (id in crossing) {
                val before = result
                result = Edits.split(result, id, cut)
                if (result === before) result = trimToCut(result, id, cut)
            }
        }
        val tracks = result.tracks.map { track ->
            val kept = track.clips.filter { it.startUs >= start && it.endUs <= end }
                .map { it.copy(startUs = it.startUs - start) }
            track.copy(clips = if (track.isMagnetic) Edits.layoutMagnetic(kept) else kept)
        }
        val originalLocks = project.tracks.associate { it.id to it.locked }
        return result.copy(
            tracks = tracks.map { it.copy(locked = originalLocks[it.id] ?: false) },
            markers = project.markers.filter { it.timeUs in start until end }.map { it.copy(timeUs = it.timeUs - start) },
        )
    }

    /** Fallback when a split is refused because one side would be shorter than a frame. */
    private fun trimToCut(project: Project, clipId: String, cut: Long): Project {
        val clip = project.findClip(clipId)?.second ?: return project
        return if (cut - clip.startUs < clip.endUs - cut) Edits.trimStart(project, clipId, cut) else Edits.trimEnd(project, clipId, cut)
    }
}

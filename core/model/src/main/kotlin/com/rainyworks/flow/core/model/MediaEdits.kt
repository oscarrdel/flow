package com.rainyworks.flow.core.model

/** Edits that bring new media into an existing project. */
object MediaEdits {
    /**
     * Splits the clip at [atUs] and inserts a still image (the frame captured at that time)
     * between both halves. Only for main-track video clips.
     */
    fun insertFreezeFrame(project: Project, clipId: String, atUs: Long, still: MediaRef, durationUs: Long): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (!track.isMagnetic || track.locked || clip.kind != ClipKind.VIDEO || !clip.contains(atUs)) return project
        val freeze = Clip(
            id = Edits.newId(),
            kind = ClipKind.IMAGE,
            media = still,
            trimOutUs = durationUs.coerceAtLeast(Edits.MIN_CLIP_US),
            transform = clip.transform,
            color = clip.color,
            effects = clip.effects,
            blendMode = clip.blendMode,
            mask = clip.mask,
            chromaKey = clip.chromaKey,
        )
        val split = Edits.split(project, clipId, atUs)
        val main = split.mainTrack
        val index = if (split === project) {
            // Too close to an edge to split: place the still before or after the clip.
            val i = main.clips.indexOfFirst { it.id == clipId }
            if (atUs - clip.startUs < clip.endUs - atUs) i else i + 1
        } else {
            main.clips.indexOfFirst { it.id == clipId } + 1
        }
        val clips = main.clips.toMutableList().apply { add(index, freeze) }
        return split.replaceTrack(main.withClips(clips))
    }

    /**
     * Swaps the media of a clip keeping its position, duration and every effect. The new
     * media is trimmed from its start; if it is shorter, the clip gets shorter.
     */
    fun replaceMedia(project: Project, clipId: String, media: MediaRef): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked) return project
        val isStill = media.type == MediaType.IMAGE || (media.type == MediaType.GIF && media.durationUs <= 0L)
        val kind = when (media.type) {
            MediaType.VIDEO -> ClipKind.VIDEO
            MediaType.AUDIO -> ClipKind.AUDIO
            MediaType.IMAGE, MediaType.GIF -> ClipKind.IMAGE
        }
        if ((kind == ClipKind.AUDIO) != (clip.kind == ClipKind.AUDIO)) return project
        val wantedSource = clip.sourceDurationUs
        val trimOut = if (isStill) wantedSource else minOf(wantedSource, media.durationUs)
        if (trimOut <= 0L) return project
        val replaced = clip.copy(
            kind = kind,
            media = media,
            trimInUs = 0L,
            trimOutUs = trimOut,
            reversed = clip.reversed && !isStill,
            freezeAtUs = null,
        )
        return project.replaceTrack(track.withClips(track.clips.map { if (it.id == clipId) replaced else it }))
    }

    /** Places media on an overlay layer starting at [atUs]. */
    fun addOverlay(project: Project, media: MediaRef, atUs: Long): Project {
        val clip = ProjectFactory.clipFor(media).copy(startUs = atUs.coerceAtLeast(0L), transform = Transform(scale = 0.5f))
        val kind = if (media.type == MediaType.AUDIO) TrackKind.AUDIO else TrackKind.OVERLAY
        return Edits.addToLayer(project, clip, kind)
    }

    fun setTransform(project: Project, clipId: String, transform: Transform): Project =
        Edits.updateClip(project, clipId) { it.copy(transform = transform) }

    fun setOpacity(project: Project, clipId: String, opacity: Float): Project =
        Edits.updateClip(project, clipId) { it.copy(opacity = opacity.coerceIn(0f, 1f)) }

    fun toggleReverse(project: Project, clipId: String): Project =
        Edits.updateClip(project, clipId) { if (it.kind == ClipKind.VIDEO || it.kind == ClipKind.AUDIO) it.copy(reversed = !it.reversed) else it }

    /**
     * Copies the sound of a video clip onto an audio track, aligned with the clip, and
     * mutes the original so the audio can be edited independently.
     */
    fun extractAudio(project: Project, clipId: String): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked || clip.kind != ClipKind.VIDEO || clip.media?.hasAudio != true) return project
        val audio = clip.copy(
            id = Edits.newId(),
            kind = ClipKind.AUDIO,
            transform = Transform(),
            keyframes = clip.keyframes.filterKeys { it == AnimatedProperty.VOLUME },
            color = ColorGrade(),
            effects = emptyList(),
            transitionIn = null,
            mask = null,
            chromaKey = null,
            blendMode = BlendMode.NORMAL,
            opacity = 1f,
            muted = false,
        )
        val muted = Edits.updateClip(project, clipId) { it.copy(muted = true) }
        return Edits.addToLayer(muted, audio, TrackKind.AUDIO)
    }

    /** Adds an audio file (music, effect or voice-over) at [atUs] on a free audio track. */
    fun addAudio(project: Project, media: MediaRef, atUs: Long): Project {
        if (media.type != MediaType.AUDIO || media.durationUs <= 0L) return project
        return Edits.addToLayer(project, ProjectFactory.clipFor(media).copy(startUs = atUs.coerceAtLeast(0L)), TrackKind.AUDIO)
    }
}

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
            originalMedia = null,
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

    /** Adds a text clip at [atUs]; returns the new project and the clip id. */
    fun addText(project: Project, atUs: Long, content: TextContent, durationUs: Long = DEFAULT_TEXT_DURATION_US): Pair<Project, String> {
        val clip = Clip(
            id = Edits.newId(),
            kind = ClipKind.TEXT,
            startUs = atUs.coerceAtLeast(0L),
            trimOutUs = durationUs,
            text = content,
        )
        return Edits.addToLayer(project, clip, TrackKind.TEXT) to clip.id
    }

    /** Adds an adjustment layer covering [durationUs] (or the rest of the project) from [atUs]. */
    fun addAdjustmentLayer(project: Project, atUs: Long): Pair<Project, String> {
        val start = atUs.coerceAtLeast(0L)
        val duration = (project.durationUs - start).takeIf { it >= Edits.MIN_CLIP_US } ?: DEFAULT_TEXT_DURATION_US
        val clip = Clip(id = Edits.newId(), kind = ClipKind.ADJUSTMENT, startUs = start, trimOutUs = duration)
        return Edits.addToLayer(project, clip, TrackKind.ADJUSTMENT) to clip.id
    }

    fun setColorGrade(project: Project, clipId: String, grade: ColorGrade): Project =
        Edits.updateClip(project, clipId) { it.copy(color = grade) }

    /** Copies one clip's grade to every video and image clip on the main track. */
    fun applyGradeToMain(project: Project, fromClipId: String): Project {
        val grade = project.findClip(fromClipId)?.second?.color ?: return project
        val main = project.mainTrack
        if (main.locked) return project
        return project.replaceTrack(main.copy(clips = main.clips.map { it.copy(color = grade) }))
    }

    fun setTextContent(project: Project, clipId: String, content: TextContent): Project =
        Edits.updateClip(project, clipId) { if (it.kind == ClipKind.TEXT) it.copy(text = content) else it }

    /** Moves and retimes a subtitle block and updates its text. */
    fun updateSubtitle(project: Project, clipId: String, cue: SubtitleCue): Project {
        val (track, clip) = project.findClip(clipId) ?: return project
        if (track.locked || clip.text == null) return project
        val duration = cue.endUs - cue.startUs
        if (duration < Edits.MIN_CLIP_US || cue.text.isBlank()) return project
        val updated = clip.copy(startUs = cue.startUs.coerceAtLeast(0L), trimInUs = 0L, trimOutUs = duration, text = clip.text.copy(text = cue.text))
        if (!track.isFree(updated.startUs, updated.endUs, ignoreClipId = clipId)) return project
        return project.replaceTrack(track.withClips(track.clips.map { if (it.id == clipId) updated else it }))
    }

    const val DEFAULT_TEXT_DURATION_US = 3_000_000L

    /** Current (static or animated) value of a keyframeable property at [timelineUs]. */
    fun propertyValue(clip: Clip, property: String, timelineUs: Long): Float {
        val static = when (property) {
            AnimatedProperty.OFFSET_X -> clip.transform.offsetX
            AnimatedProperty.OFFSET_Y -> clip.transform.offsetY
            AnimatedProperty.SCALE -> clip.transform.scale
            AnimatedProperty.ROTATION -> clip.transform.rotationDegrees
            AnimatedProperty.OPACITY -> clip.opacity
            AnimatedProperty.VOLUME -> clip.volume
            AnimatedProperty.MASK_OFFSET_X -> clip.mask?.offsetX ?: 0f
            AnimatedProperty.MASK_OFFSET_Y -> clip.mask?.offsetY ?: 0f
            AnimatedProperty.MASK_SCALE -> clip.mask?.scale ?: 1f
            AnimatedProperty.MASK_ROTATION -> clip.mask?.rotationDegrees ?: 0f
            AnimatedProperty.MASK_FEATHER -> clip.mask?.feather ?: 0f
            else -> 0f
        }
        return clip.animatedValue(property, timelineUs, static)
    }

    /**
     * Adds a keyframe with the current value at [timelineUs], or removes the keyframe that is
     * already there (within one frame).
     */
    fun toggleKeyframe(project: Project, clipId: String, property: String, timelineUs: Long): Project {
        val clip = project.findClip(clipId)?.second ?: return project
        if (!clip.contains(timelineUs) && timelineUs != clip.endUs) return project
        val relative = timelineUs - clip.startUs
        val existing = clip.keyframes[property].orEmpty()
        val without = Animator.removeKeyframeNear(existing, relative, KEYFRAME_TOLERANCE_US)
        val updated = if (without.size != existing.size) {
            without
        } else {
            Animator.setKeyframe(existing, Keyframe(relative, propertyValue(clip, property, timelineUs)))
        }
        val keyframes = if (updated.isEmpty()) clip.keyframes - property else clip.keyframes + (property to updated)
        return Edits.updateClip(project, clipId) { it.copy(keyframes = keyframes) }
    }

    fun setKeyframeEasing(project: Project, clipId: String, property: String, relativeUs: Long, easing: Easing): Project =
        Edits.updateClip(project, clipId) { clip ->
            val list = clip.keyframes[property] ?: return@updateClip clip
            clip.copy(
                keyframes = clip.keyframes + (property to list.map {
                    if (kotlin.math.abs(it.timeUs - relativeUs) <= KEYFRAME_TOLERANCE_US) it.copy(easing = easing) else it
                }),
            )
        }

    /** Timeline times of every keyframe of a clip, sorted and de-duplicated. */
    fun keyframeTimes(clip: Clip): List<Long> =
        clip.keyframes.values.flatten().map { clip.startUs + it.timeUs }.distinct().sorted()

    const val KEYFRAME_TOLERANCE_US = 33_333L

    /** Properties that move together: one keyframe records all of them, like in most editors. */
    val MOTION_PROPERTIES = listOf(
        AnimatedProperty.OFFSET_X,
        AnimatedProperty.OFFSET_Y,
        AnimatedProperty.SCALE,
        AnimatedProperty.ROTATION,
        AnimatedProperty.OPACITY,
    )

    fun hasMotionKeyframes(clip: Clip): Boolean = MOTION_PROPERTIES.any { !clip.keyframes[it].isNullOrEmpty() }

    /** Relative times (to the clip start) of every motion keyframe, sorted. */
    private fun motionTimes(clip: Clip): List<Long> =
        MOTION_PROPERTIES.flatMap { clip.keyframes[it].orEmpty() }.map { it.timeUs }.distinct().sorted()

    /**
     * Writes [values] as keyframes at [relativeUs]. A property that was not animated yet is
     * first pinned to its static value at the existing keyframe times, so frames before and
     * after keep looking the way they did.
     */
    private fun writeMotionKeyframe(clip: Clip, relativeUs: Long, values: Map<String, Float>): Clip {
        val existingTimes = motionTimes(clip)
        var keyframes = clip.keyframes
        for ((property, value) in values) {
            var list = keyframes[property].orEmpty()
            if (list.isEmpty()) {
                val staticValue = propertyValue(clip.copy(keyframes = emptyMap()), property, clip.startUs)
                list = existingTimes.fold(list) { acc, t -> Animator.setKeyframe(acc, Keyframe(t, staticValue)) }
            }
            val nearest = list.minByOrNull { kotlin.math.abs(it.timeUs - relativeUs) }
            // Replacing a keyframe keeps its curve (including custom bezier handles).
            val keyframe = if (nearest != null && kotlin.math.abs(nearest.timeUs - relativeUs) <= KEYFRAME_TOLERANCE_US) {
                nearest.copy(timeUs = relativeUs, value = value)
            } else {
                Keyframe(relativeUs, value, nearest?.easing ?: Easing.LINEAR)
            }
            keyframes = keyframes + (property to Animator.setKeyframe(list, keyframe))
        }
        return clip.copy(keyframes = keyframes)
    }

    /** Adds a motion keyframe at [timelineUs] with the current values, or removes the one there. */
    fun toggleMotionKeyframe(project: Project, clipId: String, timelineUs: Long): Project =
        Edits.updateClip(project, clipId) { clip ->
            if (!clip.contains(timelineUs) && timelineUs != clip.endUs) return@updateClip clip
            val relative = timelineUs - clip.startUs
            val hasHere = MOTION_PROPERTIES.any { property ->
                clip.keyframes[property].orEmpty().any { kotlin.math.abs(it.timeUs - relative) <= KEYFRAME_TOLERANCE_US }
            }
            if (hasHere) {
                var keyframes = clip.keyframes
                for (property in MOTION_PROPERTIES) {
                    val remaining = Animator.removeKeyframeNear(keyframes[property].orEmpty(), relative, KEYFRAME_TOLERANCE_US)
                    keyframes = if (remaining.isEmpty()) keyframes - property else keyframes + (property to remaining)
                }
                clip.copy(keyframes = keyframes)
            } else {
                writeMotionKeyframe(clip, relative, MOTION_PROPERTIES.associateWith { propertyValue(clip, it, timelineUs) })
            }
        }

    /**
     * Applies a new transform. Once a clip has motion keyframes, a change at any time adds or
     * updates the keyframe at [timelineUs] for the whole transform instead of moving the clip
     * for its entire duration.
     */
    fun setTransformAt(project: Project, clipId: String, transform: Transform, timelineUs: Long): Project =
        Edits.updateClip(project, clipId) { clip ->
            if (!hasMotionKeyframes(clip)) return@updateClip clip.copy(transform = transform)
            val relative = (timelineUs - clip.startUs).coerceIn(0L, clip.durationUs)
            val withKeyframe = writeMotionKeyframe(
                clip,
                relative,
                mapOf(
                    AnimatedProperty.OFFSET_X to transform.offsetX,
                    AnimatedProperty.OFFSET_Y to transform.offsetY,
                    AnimatedProperty.SCALE to transform.scale,
                    AnimatedProperty.ROTATION to transform.rotationDegrees,
                ),
            )
            // Flips and crop are not animatable; they apply to the whole clip.
            withKeyframe.copy(
                transform = clip.transform.copy(
                    flipHorizontal = transform.flipHorizontal,
                    flipVertical = transform.flipVertical,
                    crop = transform.crop,
                ),
            )
        }

    /** Like [setTransformAt] for opacity. */
    fun setOpacityAt(project: Project, clipId: String, opacity: Float, timelineUs: Long): Project =
        Edits.updateClip(project, clipId) { clip ->
            val value = opacity.coerceIn(0f, 1f)
            if (!hasMotionKeyframes(clip)) return@updateClip clip.copy(opacity = value)
            val relative = (timelineUs - clip.startUs).coerceIn(0L, clip.durationUs)
            writeMotionKeyframe(clip, relative, mapOf(AnimatedProperty.OPACITY to value))
        }

    /** Sets the easing of every motion keyframe at [relativeUs]. */
    fun setMotionEasing(project: Project, clipId: String, relativeUs: Long, easing: Easing): Project =
        MOTION_PROPERTIES.fold(project) { acc, property -> setKeyframeEasing(acc, clipId, property, relativeUs, easing) }

    fun setEnterAnimation(project: Project, clipId: String, animation: ClipAnimation?): Project =
        Edits.updateClip(project, clipId) { it.copy(enterAnimation = animation?.clamped()) }

    fun setExitAnimation(project: Project, clipId: String, animation: ClipAnimation?): Project =
        Edits.updateClip(project, clipId) { it.copy(exitAnimation = animation?.clamped()) }

    private fun ClipAnimation.clamped() =
        copy(durationUs = durationUs.coerceIn(ClipAnimations.MIN_DURATION_US, ClipAnimations.MAX_DURATION_US))

    /** True when the clip currently plays a reversed copy of its media. */
    fun isReversed(clip: Clip): Boolean = clip.originalMedia != null

    /**
     * Swaps a clip to its reversed copy (or back to the original). The used part of the
     * source is mirrored, so the clip shows the same footage backwards with the same length;
     * a speed curve is mirrored too. Keyframes are timeline-relative and stay as they are.
     */
    fun swapReversed(project: Project, clipId: String, replacement: MediaRef): Project =
        Edits.updateClip(project, clipId) { clip ->
            val current = clip.media ?: return@updateClip clip
            val restoring = clip.originalMedia != null
            // Mirror around the original's length both ways, so reverse + restore is exact even
            // when the generated copy is a frame longer or shorter.
            val original = if (restoring) clip.originalMedia!! else current
            val duration = original.durationUs.takeIf { it > 0L } ?: return@updateClip clip
            val targetDuration = replacement.durationUs.takeIf { it > 0L } ?: duration
            val trimIn = (duration - clip.trimOutUs).coerceIn(0L, targetDuration)
            val trimOut = (duration - clip.trimInUs).coerceIn(trimIn + 1, maxOf(trimIn + 1, targetDuration))
            clip.copy(
                media = replacement,
                originalMedia = if (restoring) null else current,
                trimInUs = trimIn,
                trimOutUs = trimOut,
                speedCurve = clip.speedCurve?.let { curve ->
                    curve.copy(points = curve.points.map { it.copy(position = 1f - it.position) }.sortedBy { it.position })
                },
                freezeAtUs = clip.freezeAtUs?.let { duration - it },
                reversed = false,
            )
        }
}

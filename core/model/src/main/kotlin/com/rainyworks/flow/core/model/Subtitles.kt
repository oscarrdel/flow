package com.rainyworks.flow.core.model

/** One subtitle block: text shown between [startUs] and [endUs] on the timeline. */
data class SubtitleCue(val startUs: Long, val endUs: Long, val text: String)

/** SubRip (.srt) reading and writing. */
object Srt {
    private val timing = Regex(
        """^\s*(\d{1,3}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,3}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""",
    )
    private val tags = Regex("""</?(?:b|i|u|font)(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)

    /**
     * Parses SRT text leniently: tolerates a BOM, CRLF line endings, missing or wrong
     * sequence numbers, '.' as millisecond separator, extra blank lines and basic HTML
     * styling tags (which are stripped). Cues with an invalid range are skipped.
     */
    fun parse(content: String): List<SubtitleCue> {
        val lines = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = mutableListOf<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val match = timing.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val g = match.groupValues
            val start = toUs(g[1], g[2], g[3], g[4])
            val end = toUs(g[5], g[6], g[7], g[8])
            i++
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                // A timing line without a blank separator starts the next cue.
                if (timing.containsMatchIn(lines[i])) break
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[i].trim())
                i++
            }
            // Drop a trailing sequence number that belongs to the next cue.
            val body = text.toString().lines().let { textLines ->
                if (textLines.size > 1 && textLines.last().trim().all(Char::isDigit) &&
                    i < lines.size && timing.containsMatchIn(lines[i])
                ) {
                    textLines.dropLast(1)
                } else {
                    textLines
                }
            }.joinToString("\n").replace(tags, "").trim()
            if (end > start && body.isNotEmpty()) cues += SubtitleCue(start, end, body)
        }
        return cues.sortedBy { it.startUs }
    }

    fun format(cues: List<SubtitleCue>): String = buildString {
        cues.sortedBy { it.startUs }.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(stamp(cue.startUs)).append(" --> ").append(stamp(cue.endUs)).append('\n')
            append(cue.text.trim()).append("\n\n")
        }
    }

    private fun toUs(h: String, m: String, s: String, ms: String): Long {
        val millis = ms.padEnd(3, '0').take(3).toLong()
        return ((h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + millis) * 1000
    }

    private fun stamp(us: Long): String {
        val totalMs = us.coerceAtLeast(0L) / 1000
        val ms = totalMs % 1000
        val totalS = totalMs / 1000
        return "%02d:%02d:%02d,%03d".format(totalS / 3600, (totalS / 60) % 60, totalS % 60, ms)
    }
}

/** Subtitle editing on the project: subtitle blocks live on TEXT tracks with isSubtitle = true. */
object SubtitleEdits {
    val DefaultStyle = TextContent(
        text = "",
        size = 0.045f,
        background = TextBackground(argb = 0x99000000, cornerRadius = 0.15f, padding = 0.2f),
        isSubtitle = true,
    )

    /** Bottom-centered placement used for new subtitle blocks. */
    val DefaultTransform = Transform(offsetY = 0.38f)

    fun cues(project: Project): List<SubtitleCue> = project.tracks
        .filter { it.kind == TrackKind.TEXT }
        .flatMap { it.clips }
        .filter { it.text?.isSubtitle == true }
        .map { SubtitleCue(it.startUs, it.endUs, it.text!!.text) }
        .sortedBy { it.startUs }

    fun addCue(project: Project, cue: SubtitleCue, style: TextContent = DefaultStyle): Project {
        if (cue.endUs - cue.startUs < Edits.MIN_CLIP_US || cue.text.isBlank()) return project
        val clip = Clip(
            id = Edits.newId(),
            kind = ClipKind.TEXT,
            startUs = cue.startUs.coerceAtLeast(0L),
            trimOutUs = cue.endUs - cue.startUs.coerceAtLeast(0L),
            transform = DefaultTransform,
            text = style.copy(text = cue.text, isSubtitle = true),
        )
        val track = subtitleTrack(project)
        return if (track != null && track.isFree(clip.startUs, clip.endUs)) {
            Edits.addToLayer(project, clip, TrackKind.TEXT, track.id)
        } else {
            Edits.addToLayer(project, clip, TrackKind.TEXT)
        }
    }

    /** Imports every cue, keeping the style of existing subtitles when there are any. */
    fun import(project: Project, cues: List<SubtitleCue>): Project {
        val style = subtitleTrack(project)?.clips?.firstNotNullOfOrNull { it.text }?.copy(text = "") ?: DefaultStyle
        return cues.fold(project) { acc, cue -> addCue(acc, cue, style) }
    }

    fun export(project: Project): String = Srt.format(cues(project))

    private fun subtitleTrack(project: Project): Track? = project.tracks.firstOrNull { track ->
        track.kind == TrackKind.TEXT && !track.locked && track.clips.any { it.text?.isSubtitle == true }
    }
}

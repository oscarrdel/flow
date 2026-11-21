package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtTest {
    @Test
    fun parsesStandardFile() {
        val cues = Srt.parse(
            """
            1
            00:00:01,000 --> 00:00:02,500
            Hola

            2
            00:00:03,000 --> 00:00:04,000
            Dos
            líneas
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals(SubtitleCue(1_000_000, 2_500_000, "Hola"), cues[0])
        assertEquals("Dos\nlíneas", cues[1].text)
    }

    @Test
    fun toleratesBomCrlfDotsTagsAndMissingNumbers() {
        val text = "﻿00:00:01.5 --> 00:00:02.000\r\n<i>Hola</i> <b>mundo</b>\r\n\r\n\r\n" +
            "7\r\n00:01:00,000 --> 00:01:01,000\r\nFinal"
        val cues = Srt.parse(text)
        assertEquals(2, cues.size)
        assertEquals(1_500_000L, cues[0].startUs)
        assertEquals("Hola mundo", cues[0].text)
        assertEquals(60_000_000L, cues[1].startUs)
    }

    @Test
    fun handlesCuesWithoutBlankSeparator() {
        val cues = Srt.parse("1\n00:00:01,000 --> 00:00:02,000\nA\n2\n00:00:02,000 --> 00:00:03,000\nB\n")
        assertEquals(listOf("A", "B"), cues.map { it.text })
    }

    @Test
    fun skipsInvalidRangesAndEmptyText() {
        val cues = Srt.parse("00:00:05,000 --> 00:00:04,000\nMal\n\n00:00:06,000 --> 00:00:07,000\n\n")
        assertTrue(cues.isEmpty())
    }

    @Test
    fun formatRoundTrips() {
        val cues = listOf(SubtitleCue(3_723_004_000, 3_724_000_000, "Una hora"), SubtitleCue(0, 1_000_000, "Inicio"))
        val text = Srt.format(cues)
        assertTrue(text.startsWith("1\n00:00:00,000 --> 00:00:01,000\nInicio\n\n2\n01:02:03,004"))
        assertEquals(cues.sortedBy { it.startUs }, Srt.parse(text))
    }

    @Test
    fun importPlacesSubtitlesOnOneTextTrackAndExportsBack() {
        var project = ProjectFactory.create("p", 0L)
        val cues = listOf(SubtitleCue(0, 1_000_000, "a"), SubtitleCue(1_000_000, 2_000_000, "b"))
        project = SubtitleEdits.import(project, cues)
        val textTracks = project.tracks.filter { it.kind == TrackKind.TEXT }
        assertEquals(1, textTracks.size)
        assertEquals(cues, SubtitleEdits.cues(project))
        assertEquals(cues, Srt.parse(SubtitleEdits.export(project)))
    }

    @Test
    fun overlappingCuesGoToAnotherTrack() {
        var project = ProjectFactory.create("p", 0L)
        project = SubtitleEdits.import(project, listOf(SubtitleCue(0, 2_000_000, "a"), SubtitleCue(1_000_000, 3_000_000, "b")))
        assertEquals(2, project.tracks.count { it.kind == TrackKind.TEXT })
        assertEquals(2, SubtitleEdits.cues(project).size)
    }
}

class ExtractAudioTest {
    @Test
    fun extractsAlignedAudioAndMutesVideo() {
        val media = MediaRef("v", MediaType.VIDEO, durationUs = 5_000_000, width = 10, height = 10, hasAudio = true)
        var project = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(ProjectFactory.clipFor(media)))
        val video = project.mainTrack.clips.single()
        project = MediaEdits.extractAudio(project, video.id)
        val audio = project.tracks.single { it.kind == TrackKind.AUDIO }.clips.single()
        assertTrue(project.mainTrack.clips.single().muted)
        assertEquals(ClipKind.AUDIO, audio.kind)
        assertEquals(video.startUs, audio.startUs)
        assertEquals(video.durationUs, audio.durationUs)
    }

    @Test
    fun ignoresVideosWithoutSound() {
        val media = MediaRef("v", MediaType.VIDEO, durationUs = 5_000_000, hasAudio = false)
        val project = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(ProjectFactory.clipFor(media)))
        assertEquals(project, MediaEdits.extractAudio(project, project.mainTrack.clips.single().id))
    }
}

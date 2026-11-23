package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FitToBeatsTest {
    private fun photo(n: Int) = ProjectFactory.clipFor(MediaRef("p$n", MediaType.IMAGE, width = 10, height = 10), imageDurationUs = 3_000_000)

    private fun withBeats(p: Project, vararg times: Long) =
        p.copy(markers = times.map { Marker(Edits.newId(), it, Edits.BEAT_MARKER_LABEL) })

    @Test
    fun photosChangeOnTheBeatsSkippingOnesTooClose() {
        var p = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(photo(1), photo(2), photo(3)))
        // Beats every 0.5 s: too fast, so every other beat is used (0.7 s minimum).
        p = withBeats(p, *LongArray(20) { (it + 1) * 500_000L })
        val fitted = Edits.fitToBeats(p).mainTrack.clips
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), fitted.map { it.endUs })
    }

    @Test
    fun videoOnlyUsesFootageItHas() {
        val video = ProjectFactory.clipFor(MediaRef("v", MediaType.VIDEO, durationUs = 1_500_000))
        var p = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(video, photo(1)))
        p = withBeats(p, 2_000_000, 2_600_000)
        val fitted = Edits.fitToBeats(p).mainTrack.clips
        // No beat within the 1.5 s of footage: the video keeps its length.
        assertEquals(1_500_000L, fitted[0].endUs)
        assertEquals(2_600_000L, fitted[1].endUs)
    }

    @Test
    fun withoutBeatsNothingChanges() {
        val p = Edits.insertIntoMain(ProjectFactory.create("p", 0L), listOf(photo(1)))
        assertEquals(p, Edits.fitToBeats(p))
    }
}

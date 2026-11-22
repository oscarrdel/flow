package com.rainyworks.flow.core.engine.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class SpeechSegmenterTest {
    private val rate = 16_000

    /** Quiet hiss with "speech" (a warbling tone) during the given spans in seconds. */
    private fun audio(seconds: Double, vararg spans: Pair<Double, Double>): FloatArray {
        val random = Random(5)
        return FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            val voiced = spans.any { (a, b) -> t in a..b }
            val hiss = (random.nextFloat() - 0.5f) * 0.004f
            if (voiced) hiss + (0.3 * sin(2 * PI * (180 + 60 * sin(2 * PI * 3 * t)) * t)).toFloat() else hiss
        }
    }

    private fun seconds(range: IntRange) = range.first.toDouble() / rate to range.last.toDouble() / rate

    @Test
    fun findsEachPhraseWithItsTimes() {
        val phrases = SpeechSegmenter.phrases(audio(8.0, 1.0 to 2.5, 4.0 to 5.2), rate).map(::seconds)
        assertEquals(2, phrases.size)
        assertTrue(abs(phrases[0].first - 1.0) < 0.2 && abs(phrases[0].second - 2.5) < 0.2)
        assertTrue(abs(phrases[1].first - 4.0) < 0.2 && abs(phrases[1].second - 5.2) < 0.2)
    }

    @Test
    fun shortPausesStayInsideOnePhrase() {
        val phrases = SpeechSegmenter.phrases(audio(5.0, 1.0 to 1.8, 2.0 to 3.0), rate)
        assertEquals(1, phrases.size)
    }

    @Test
    fun longSpeechIsSplitAndSilenceGivesNothing() {
        val long = SpeechSegmenter.phrases(audio(16.0, 1.0 to 15.0), rate)
        assertTrue("${long.size}", long.size >= 3 && long.all { it.count() <= 6.3 * rate })
        assertEquals(0, SpeechSegmenter.phrases(audio(4.0), rate).size)
    }
}

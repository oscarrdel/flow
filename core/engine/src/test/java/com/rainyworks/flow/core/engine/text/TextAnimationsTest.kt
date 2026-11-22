package com.rainyworks.flow.core.engine.text

import com.rainyworks.flow.core.model.TextAnimation
import com.rainyworks.flow.core.model.TextAnimationType
import com.rainyworks.flow.core.model.TextAnimationType.*
import com.rainyworks.flow.core.model.TextContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAnimationsTest {
    @Test fun fadeScaleAndTypewriterFollowPlaybackInBothDirections() {
        assertEquals(0f, TextAnimations.transition(FADE, -1f).alpha, 0f)
        assertEquals(1f, TextAnimations.transition(FADE, 2f).alpha, 0f)
        assertEquals(0.25f, TextAnimations.transition(FADE, 0.25f).alpha, 0f)
        assertEquals(0.75f, TextAnimations.transition(FADE, 0.25f, true).alpha, 0f)
        assertEquals(0.25f, TextAnimations.transition(SCALE, 0.25f).scale, 0f)
        assertEquals(0.75f, TextAnimations.transition(SCALE, 0.25f, true).scale, 0f)
        assertEquals(2, TextAnimations.transition(TYPEWRITER, 0.25f).visibleCharacters(9))
        assertEquals(6, TextAnimations.transition(TYPEWRITER, 0.25f, true).visibleCharacters(9))
        assertEquals(0, TextAnimations.transition(TYPEWRITER, 1f, true).visibleCharacters(9))
        assertEquals(9, TextAnimations.transition(TYPEWRITER, 1f).visibleCharacters(9))
    }

    @Test fun bounceOvershootsAndSettles() {
        assertEquals(0f, TextAnimations.transition(BOUNCE, 0f).scale, 1e-6f)
        assertTrue(TextAnimations.transition(BOUNCE, 0.6f).scale > 1f)
        assertEquals(1f, TextAnimations.transition(BOUNCE, 1f).scale, 0f)
        assertEquals(0f, TextAnimations.transition(BOUNCE, 1f, true).scale, 1e-6f)
    }

    @Test fun slidesMoveInTheNamedDirectionWithAFade() {
        for ((type, start) in mapOf(
            SLIDE_UP to (0f to 0.2f), SLIDE_DOWN to (0f to -0.2f),
            SLIDE_LEFT to (0.2f to 0f), SLIDE_RIGHT to (-0.2f to 0f),
        )) {
            val enter = TextAnimations.transition(type, 0f)
            assertEquals(start.first, enter.dx, 0f)
            assertEquals(start.second, enter.dy, 0f)
            assertEquals(0f, enter.alpha, 0f)
            val exit = TextAnimations.transition(type, 0.5f, true)
            assertEquals(-start.first / 2, exit.dx, 0f)
            assertEquals(-start.second / 2, exit.dy, 0f)
            assertEquals(0.5f, exit.alpha, 0f)
        }
    }

    @Test fun loopsArePeriodicAndUseTheirOwnChannels() {
        for (type in TextAnimationType.entries) {
            assertEquals(TextAnimations.loop(type, 0.25f), TextAnimations.loop(type, 1.25f))
        }
        assertFalse(TextAnimations.loop(TYPEWRITER, 0.5f).cursor)
        assertTrue(TextAnimations.loop(TYPEWRITER, 0.25f).cursor)
        assertEquals(0.5f, TextAnimations.loop(FADE, 0.5f).alpha, 0f)
        assertEquals(1.12f, TextAnimations.loop(BOUNCE, 0.5f).scale, 1e-6f)
        assertEquals(1.06f, TextAnimations.loop(SCALE, 0.5f).scale, 1e-6f)
        assertEquals(-0.03f, TextAnimations.loop(SLIDE_UP, 0.25f).dy, 1e-6f)
        assertEquals(0.03f, TextAnimations.loop(SLIDE_DOWN, 0.25f).dy, 1e-6f)
        assertEquals(-0.03f, TextAnimations.loop(SLIDE_LEFT, 0.25f).dx, 1e-6f)
        assertEquals(0.03f, TextAnimations.loop(SLIDE_RIGHT, 0.25f).dx, 1e-6f)
    }

    @Test fun clipTimeCombinesOverlappingWindowsAndIgnoresZeroDuration() {
        val content = TextContent("Flow", enter = TextAnimation(FADE, 100),
            exit = TextAnimation(TYPEWRITER, 100), loop = TextAnimation(SCALE, 100))
        val overlap = TextAnimations.at(content, 50, 125)
        assertEquals(0.5f, overlap.alpha, 0f)
        assertEquals(3, overlap.visibleCharacters(4))
        assertEquals(1.06f, overlap.scale, 1e-6f)
        val disabled = TextAnimation(SLIDE_UP, 0)
        assertEquals(TextMotion(), TextAnimations.at(content.copy(enter = disabled, exit = disabled, loop = disabled), 0, 1))
        assertEquals(TextMotion(), TextAnimations.at(content.copy(loop = disabled), 100, 200))
    }
}

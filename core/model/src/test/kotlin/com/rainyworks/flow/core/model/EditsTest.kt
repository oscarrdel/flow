package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditsTest {
    @Test
    fun insertIntoMainUsesRotatedDimensionsOfFirstClipAndLaysOutClips() {
        val first = testVideo("first", startUs = 900_000L).let {
            it.copy(media = requireNotNull(it.media).copy(rotationDegrees = 90))
        }
        val second = testVideo("second", startUs = 300_000L, trimOutUs = 500_000L)
        val project = testProject().copy(canvas = Canvas(aspectRatio = AspectRatio.R1_1))

        val result = Edits.insertIntoMain(project, listOf(first, second))

        assertEquals(listOf(first.copy(startUs = 0L), second.copy(startUs = 1_000_000L)), result.mainTrack.clips)
        assertEquals(AspectRatio.R9_16, result.canvas.aspectRatio)
        assertTrue(result.canvas.followFirstClip)
    }

    @Test
    fun insertIntoMainUsesNearestBoundaryAndPreservesAspectRatioAfterFirstImport() {
        val first = testVideo("first")
        val last = testVideo("last", startUs = 1_000_000L)
        val inserted = testVideo("inserted", trimOutUs = 250_000L)
        val project = testProject(main = Track.main().copy(clips = listOf(first, last)))
            .copy(canvas = Canvas(aspectRatio = AspectRatio.R4_5))

        val result = Edits.insertIntoMain(project, listOf(inserted), atUs = 800_000L)

        assertEquals(listOf(first, inserted.copy(startUs = 1_000_000L), last.copy(startUs = 1_250_000L)), result.mainTrack.clips)
        assertEquals(project.canvas, result.canvas)
    }

    @Test
    fun insertIntoMainPreservesManualAspectRatioAndHandlesEmptyInput() {
        val project = testProject().copy(canvas = Canvas(aspectRatio = AspectRatio.R1_1, followFirstClip = false))

        assertEquals(project, Edits.insertIntoMain(project, emptyList()))
        assertEquals(project.canvas, Edits.insertIntoMain(project, listOf(testVideo("first"))).canvas)
    }

    @Test
    fun insertIntoMainPreservesAspectRatioWhenFirstClipHasUnknownDimensions() {
        val clip = testVideo("first").let { it.copy(media = requireNotNull(it.media).copy(width = 0)) }
        val project = testProject()

        assertEquals(project.canvas, Edits.insertIntoMain(project, listOf(clip)).canvas)
    }

    @Test
    fun splitPreservesOuterFadesAndSourceRanges() {
        val clip = testVideo("clip", trimInUs = 100_000L, trimOutUs = 1_100_000L)
            .copy(fadeInUs = 70_000L, fadeOutUs = 90_000L)
        val next = testVideo("next", startUs = 1_000_000L)
        val project = testProject(main = Track.main().copy(clips = listOf(clip, next)))

        val result = Edits.split(project, clip.id, atUs = 400_000L)
        val (first, second, remaining) = result.mainTrack.clips

        assertEquals(3, result.mainTrack.clips.size)
        assertNotEquals(clip.id, second.id)
        assertNotEquals(next.id, second.id)
        assertEquals(clip.copy(trimOutUs = 500_000L, fadeOutUs = 0L), first)
        assertEquals(clip.copy(id = second.id, startUs = 400_000L, trimInUs = 500_000L, fadeInUs = 0L), second)
        assertEquals(next, remaining)
    }

    @Test
    fun splitReversedClipKeepsHigherSourceTimesInFirstPiece() {
        val clip = testVideo("clip", startUs = 100_000L, trimInUs = 200_000L, trimOutUs = 1_200_000L)
            .copy(reversed = true)
        val track = testLayer("overlay", clip)

        val result = Edits.split(testProject(track), clip.id, atUs = 400_000L)
        val (first, second) = requireNotNull(result.track(track.id)).clips

        assertNotEquals(clip.id, second.id)
        assertEquals(clip.copy(trimInUs = 900_000L), first)
        assertEquals(clip.copy(id = second.id, startUs = 400_000L, trimOutUs = 900_000L), second)
        assertEquals(first.endUs, second.startUs)
        assertEquals(clip.endUs, second.endUs)
    }

    @Test
    fun splitAtDoubleSpeedConvertsTimelineOffsetIntoSourceTime() {
        val clip = testVideo("clip", trimInUs = 200_000L, trimOutUs = 1_200_000L).copy(speed = 2f)
        val project = testProject(main = Track.main().copy(clips = listOf(clip)))

        val (first, second) = Edits.split(project, clip.id, atUs = 150_000L).mainTrack.clips

        assertEquals(500_000L, first.trimOutUs)
        assertEquals(500_000L, second.trimInUs)
        assertEquals(150_000L, second.startUs)
        assertEquals(350_000L, second.durationUs)
        assertEquals(clip.endUs, second.endUs)
    }

    @Test
    fun splitRequiresBothPiecesToMeetMinimumTimelineDuration() {
        val minimum = Edits.MIN_CLIP_US
        val clip = testVideo("clip", trimOutUs = minimum * 4).copy(speed = 2f)
        val project = testProject(main = Track.main().copy(clips = listOf(clip)))

        assertEquals(project, Edits.split(project, clip.id, atUs = minimum - 1))
        assertEquals(project, Edits.split(project, clip.id, atUs = minimum + 1))
        assertEquals(listOf(minimum, minimum), Edits.split(project, clip.id, atUs = minimum).mainTrack.clips.map { it.durationUs })
    }

    @Test
    fun trimsClampToBothEndsOfSourceMediaAtDoubleSpeed() {
        val clip = testVideo("clip", startUs = 500_000L, trimInUs = 400_000L, trimOutUs = 2_000_000L)
            .copy(speed = 2f)
        val project = testProject(testLayer("overlay", clip))

        val startTrim = requireNotNull(Edits.trimStart(project, clip.id, 0L).findClip(clip.id)).second
        val endTrim = requireNotNull(Edits.trimEnd(project, clip.id, 9_000_000L).findClip(clip.id)).second

        assertEquals(clip.copy(startUs = 300_000L, trimInUs = 0L), startTrim)
        assertEquals(clip.endUs, startTrim.endUs)
        assertEquals(clip.copy(trimOutUs = 3_000_000L), endTrim)
    }

    @Test
    fun reversedTrimsClampToOppositeEndsOfSourceMedia() {
        val clip = testVideo("clip", startUs = 1_500_000L, trimInUs = 200_000L, trimOutUs = 2_500_000L)
            .copy(reversed = true)
        val project = testProject(testLayer("overlay", clip))

        val startTrim = requireNotNull(Edits.trimStart(project, clip.id, 0L).findClip(clip.id)).second
        val endTrim = requireNotNull(Edits.trimEnd(project, clip.id, 9_000_000L).findClip(clip.id)).second

        assertEquals(clip.copy(startUs = 1_000_000L, trimOutUs = 3_000_000L), startTrim)
        assertEquals(clip.endUs, startTrim.endUs)
        assertEquals(clip.copy(trimInUs = 0L), endTrim)
    }

    @Test
    fun trimsCannotShrinkBelowMinimumTimelineDuration() {
        val clip = testVideo("clip", startUs = 500_000L).copy(speed = 2f)
        val project = testProject(testLayer("overlay", clip))

        val startTrim = requireNotNull(Edits.trimStart(project, clip.id, clip.endUs).findClip(clip.id)).second
        val endTrim = requireNotNull(Edits.trimEnd(project, clip.id, clip.startUs).findClip(clip.id)).second

        assertEquals(Edits.MIN_CLIP_US, startTrim.durationUs)
        assertEquals(clip.endUs, startTrim.endUs)
        assertEquals(Edits.MIN_CLIP_US, endTrim.durationUs)
        assertEquals(clip.startUs, endTrim.startUs)
    }

    @Test
    fun trimStartLeavesExistingShortClipUnchangedWhenItCannotExtend() {
        val clip = testVideo("clip", trimOutUs = 3_000_000L).copy(speed = Edits.MAX_SPEED)
        val project = testProject(testLayer("overlay", clip))

        assertEquals(project, Edits.trimStart(project, clip.id, 1_000L))
    }

    @Test
    fun trimEndLeavesExistingShortClipUnchangedWhenItCannotExtend() {
        val clip = testVideo("clip", trimOutUs = 3_000_000L).copy(speed = Edits.MAX_SPEED)
        val project = testProject(testLayer("overlay", clip))

        assertEquals(project, Edits.trimEnd(project, clip.id, 29_000L))
    }

    @Test
    fun imageTrimEndCanExtendBeyondMediaDuration() {
        val image = testImage()
        val project = testProject(testLayer("overlay", image))

        val result = requireNotNull(Edits.trimEnd(project, image.id, 11_000_000L).findClip(image.id)).second

        assertEquals(image.copy(trimOutUs = 10_000_000L), result)
    }

    @Test
    fun imageTrimStartCanExtendWithoutMovingRightEdge() {
        val image = testImage()
        val project = testProject(testLayer("overlay", image))

        val result = requireNotNull(Edits.trimStart(project, image.id, 0L).findClip(image.id)).second

        assertEquals(0L, result.startUs)
        assertEquals(image.endUs, result.endUs)
    }

    @Test
    fun acceleratedTrimEndDoesNotExtendPastMediaDurationAfterRounding() {
        val clip = testVideo("clip", trimOutUs = 2_999_999L).copy(speed = 2f)
        val project = testProject(testLayer("overlay", clip))

        val result = requireNotNull(Edits.trimEnd(project, clip.id, 2_000_000L).findClip(clip.id)).second

        assertEquals(3_000_000L, result.trimOutUs)
    }

    @Test
    fun freeTrackTrimsStopAtNeighborsAndLeaveThemUnchanged() {
        val previous = testVideo("previous")
        val clip = testVideo("clip", startUs = 1_200_000L, trimInUs = 500_000L, trimOutUs = 1_500_000L)
        val next = testVideo("next", startUs = 2_400_000L)
        val track = testLayer("overlay", previous, clip, next)
        val project = testProject(track)

        val startTrim = Edits.trimStart(project, clip.id, 0L)
        val endTrim = Edits.trimEnd(project, clip.id, 9_000_000L)

        assertEquals(track.copy(clips = listOf(previous, clip.copy(startUs = previous.endUs, trimInUs = 300_000L), next)), startTrim.track(track.id))
        assertEquals(track.copy(clips = listOf(previous, clip.copy(trimOutUs = 1_700_000L), next)), endTrim.track(track.id))
    }

    @Test
    fun addToLayerCreatesTrackOnCollisionAndReusesTrackAtBoundary() {
        val existing = testVideo("existing")
        val track = testLayer("overlay", existing)
        val project = testProject(track)
        val colliding = testVideo("colliding", startUs = 400_000L)
        val adjacent = testVideo("adjacent", startUs = existing.endUs)

        val collision = Edits.addToLayer(project, colliding, TrackKind.OVERLAY, track.id)
        val reuse = Edits.addToLayer(project, adjacent, TrackKind.OVERLAY)

        assertEquals(3, collision.tracks.size)
        assertEquals(track, collision.track(track.id))
        val (created, placed) = requireNotNull(collision.findClip(colliding.id))
        assertNotEquals(track.id, created.id)
        assertEquals(TrackKind.OVERLAY, created.kind)
        assertEquals(colliding, placed)
        assertEquals(listOf(project.mainTrack, track.copy(clips = listOf(existing, adjacent))), reuse.tracks)
    }

    @Test
    fun deleteRemovesEmptyFreeTrackAndKeepsEmptyMainTrack() {
        val main = testVideo("main-clip")
        val first = testVideo("first")
        val last = testVideo("last", startUs = 2_000_000L)
        val overlay = testLayer("overlay", first, last)
        val project = testProject(overlay, main = Track.main().copy(clips = listOf(main)))

        val oneLeft = Edits.delete(project, first.id)
        val mainOnly = Edits.delete(oneLeft, last.id)

        assertEquals(overlay.copy(clips = listOf(last)), oneLeft.track(overlay.id))
        assertNull(mainOnly.track(overlay.id))
        assertEquals(listOf(Track.main()), Edits.delete(mainOnly, main.id).tracks)
    }

    @Test
    fun duplicateInMainInsertsCopyAndShiftsFollowingClip() {
        val clip = testVideo("clip").copy(speed = 2f, muted = true, opacity = 0.5f)
        val next = testVideo("next", startUs = clip.endUs)
        val project = testProject(main = Track.main().copy(clips = listOf(clip, next)))

        val (original, copy, shifted) = Edits.duplicate(project, clip.id).mainTrack.clips

        assertEquals(clip, original)
        assertNotEquals(clip.id, copy.id)
        assertNotEquals(next.id, copy.id)
        assertEquals(clip.copy(id = copy.id, startUs = clip.endUs), copy)
        assertEquals(next.copy(startUs = copy.endUs), shifted)
    }

    @Test
    fun duplicateOnFreeTrackFindsAnotherTrackWhenAdjacentSlotOverlaps() {
        val clip = testVideo("clip")
        val next = testVideo("next", startUs = clip.endUs)
        val track = testLayer("occupied", clip, next)
        val available = testLayer("available")
        val result = Edits.duplicate(testProject(track, available), clip.id)

        assertEquals(3, result.tracks.size)
        assertEquals(track, result.track(track.id))
        val copy = requireNotNull(result.track(available.id)).clips.single()
        assertNotEquals(clip.id, copy.id)
        assertNotEquals(next.id, copy.id)
        assertEquals(clip.copy(id = copy.id, startUs = clip.endUs), copy)
    }

    @Test
    fun moveInMainClampsIndexAndReflowsMagneticTrack() {
        val first = testVideo("first", trimOutUs = 500_000L)
        val middle = testVideo("middle", startUs = first.endUs)
        val last = testVideo("last", startUs = middle.endUs)
        val project = testProject(main = Track.main().copy(clips = listOf(first, middle, last)))

        val moved = Edits.moveInMain(project, first.id, toIndex = 99)
        val restored = Edits.moveInMain(moved, first.id, toIndex = -1)

        assertEquals(
            listOf(middle.copy(startUs = 0L), last.copy(startUs = 1_000_000L), first.copy(startUs = 2_000_000L)),
            moved.mainTrack.clips,
        )
        assertEquals(project, restored)
    }

    @Test
    fun moveToLayerRejectsOverlapAndAcceptsTouchingBoundary() {
        val clip = testVideo("clip")
        val stationary = testVideo("stationary", startUs = 1_000_000L)
        val source = testLayer("source", clip)
        val destination = testLayer("destination", stationary)
        val project = testProject(source, destination)

        assertEquals(project, Edits.moveToLayer(project, clip.id, destination.id, 999_999L))

        val moved = Edits.moveToLayer(project, clip.id, destination.id, stationary.endUs)
        assertNull(moved.track(source.id))
        assertEquals(destination.copy(clips = listOf(stationary, clip.copy(startUs = stationary.endUs))), moved.track(destination.id))
    }

    @Test
    fun moveToLayerCanRepositionTheOnlyClipOnItsOwnTrack() {
        val clip = testVideo("clip")
        val track = testLayer("overlay", clip)
        val project = testProject(track)

        val result = Edits.moveToLayer(project, clip.id, track.id, 500_000L)

        assertEquals(track.copy(clips = listOf(clip.copy(startUs = 500_000L))), result.track(track.id))
    }

    @Test
    fun setSpeedClampsBothBoundsAndReflowsMainTrack() {
        val clip = testVideo("clip")
        val next = testVideo("next", startUs = clip.endUs)
        val project = testProject(main = Track.main().copy(clips = listOf(clip, next)))

        val slow = Edits.setSpeed(project, clip.id, 0f).mainTrack.clips
        val fast = Edits.setSpeed(project, clip.id, 120f).mainTrack.clips

        assertEquals(clip.copy(speed = Edits.MIN_SPEED), slow[0])
        assertEquals(10_000_000L, slow[1].startUs)
        assertEquals(clip.copy(speed = Edits.MAX_SPEED), fast[0])
        assertEquals(10_000L, fast[1].startUs)
    }

    @Test
    fun setSpeedRejectsAChangeThatOverlapsAnotherClipOnFreeTrack() {
        val clip = testVideo("clip")
        val next = testVideo("next", startUs = clip.endUs)
        val project = testProject(testLayer("overlay", clip, next))

        assertEquals(project, Edits.setSpeed(project, clip.id, 0.5f))
    }

    @Test
    fun lockedMainTrackRejectsClipChangesAndReordering() {
        val clip = testVideo("clip")
        val next = testVideo("next", startUs = clip.endUs)
        val project = testProject(main = Track.main().copy(clips = listOf(clip, next), locked = true))

        assertEquals(project, Edits.split(project, clip.id, 500_000L))
        assertEquals(project, Edits.trimStart(project, clip.id, 100_000L))
        assertEquals(project, Edits.trimEnd(project, clip.id, 500_000L))
        assertEquals(project, Edits.delete(project, clip.id))
        assertEquals(project, Edits.duplicate(project, clip.id))
        assertEquals(project, Edits.moveInMain(project, clip.id, toIndex = 1))
        assertEquals(project, Edits.setSpeed(project, clip.id, 2f))
        assertEquals(project, Edits.updateClip(project, clip.id) { it.copy(muted = true) })
    }

    @Test
    fun insertIntoMainDoesNotModifyLockedTrack() {
        val project = testProject(main = Track.main().copy(locked = true))

        assertEquals(project, Edits.insertIntoMain(project, listOf(testVideo("clip"))))
    }

    @Test
    fun addToLayerDoesNotPlaceClipOnLockedTrack() {
        val locked = testLayer("locked").copy(locked = true)
        val project = testProject(locked)
        val clip = testVideo("clip")

        val result = Edits.addToLayer(project, clip, TrackKind.OVERLAY)

        assertEquals(locked, result.track(locked.id))
        assertEquals(clip, result.findClip(clip.id)?.second)
    }

    @Test
    fun moveToLayerRejectsLockedSourceOrDestination() {
        val clip = testVideo("clip")
        val source = testLayer("source", clip)
        val destination = testLayer("destination")
        val lockedSource = testProject(source.copy(locked = true), destination)
        val lockedDestination = testProject(source, destination.copy(locked = true))

        assertEquals(lockedSource, Edits.moveToLayer(lockedSource, clip.id, destination.id, 500_000L))
        assertEquals(lockedDestination, Edits.moveToLayer(lockedDestination, clip.id, destination.id, 500_000L))
    }

    private fun testImage(): Clip = Clip(
        id = "image",
        kind = ClipKind.IMAGE,
        media = MediaRef("content://flow/image", MediaType.IMAGE),
        startUs = 1_000_000L,
        trimOutUs = 2_000_000L,
    )
}

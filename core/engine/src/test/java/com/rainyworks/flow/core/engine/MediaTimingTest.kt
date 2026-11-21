package com.rainyworks.flow.core.engine

import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTimingTest {
    @Test fun gifSumsFrameDelaysOnceAndSkipsColorTablesAndSubBlocks() {
        val header = "GIF89a".encodeToByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0) + ByteArray(6)
        val comment = byteArrayOf(0x21, 0xfe.toByte(), 2, 7, 8, 1, 9, 0)
        fun frame(delay: Int) =
            byteArrayOf(0x21, 0xf9.toByte(), 4, 0, delay.toByte(), (delay shr 8).toByte(), 0, 0) +
                byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0x80.toByte()) + ByteArray(6) +
                byteArrayOf(2, 2, 0x44, 1, 0)
        val gif = header + frame(5) + comment + frame(258) + byteArrayOf(0x3b)
        assertEquals(2_630_000L, gifDurationUs(gif.inputStream()))
    }

    @Test fun estimateUsesEditedDurationAndIncludesAudioOnlyWhenAudible() {
        val clip = Clip("clip", ClipKind.VIDEO, MediaRef("video", MediaType.VIDEO, hasAudio = true),
            trimInUs = 1_000_000, trimOutUs = 5_000_000, speed = 2f)
        val track = Track.main().copy(clips = listOf(clip))
        val project = ProjectFactory.create("Prueba", 0).copy(tracks = listOf(track))
        val settings = ExportSettings(videoBitrate = 8_000_000)
        assertEquals(2_052_320L, estimateSizeBytes(project, settings))
        assertEquals(2_020_000L, estimateSizeBytes(project.copy(tracks = listOf(track.copy(muted = true))), settings))
        assertEquals(0L, estimateSizeBytes(project.copy(tracks = listOf(Track.main())), settings))
    }
}

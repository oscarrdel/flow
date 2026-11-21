package com.rainyworks.flow.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {
    @Test
    fun writesValidHeaderAndDuration() {
        val file = File.createTempFile("wav", ".wav")
        val writer = WavWriter(file, 48_000, 1)
        val second = ByteArray(96_000)
        writer.write(second, second.size)
        assertEquals(1_000_000L, writer.durationUs)
        writer.close()
        val bytes = file.readBytes()
        assertEquals(44 + 96_000, bytes.size)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + 96_000, header.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(48_000, header.getInt(24))
        assertEquals(96_000, header.getInt(40))
        file.delete()
    }

    @Test
    fun peakReadsSignedLittleEndianSamples() {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(100).putShort(-16384).putShort(200).array()
        assertEquals(0.5f, VoiceRecorder.peak(buffer, buffer.size), 1e-4f)
    }
}

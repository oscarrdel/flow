package com.rainyworks.flow.core.mp3

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mp3EncoderTest {
    @Test fun stereoCbr() = verifyTone(2, 192, "Info")

    @Test fun monoAbr() = verifyTone(1, 100, "Xing")

    private fun verifyTone(channels: Int, kbps: Int, tag: String) {
        val rate = 44_100
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("tone", ".mp3", context.cacheDir)
        val extractor = MediaExtractor()
        try {
            RandomAccessFile(file, "rw").use { out ->
                Mp3Encoder(rate, channels, kbps).use { encoder ->
                    var frame = 0
                    while (frame < rate * 2) {
                        val count = minOf(1024, rate * 2 - frame)
                        val block = ShortArray(count * channels) { i ->
                            (12_000 * sin(2 * PI * 440 * (frame + i / channels) / rate)).toInt().toShort()
                        }
                        out.write(encoder.encode(block, count))
                        frame += count
                    }
                    out.write(encoder.flush())
                    encoder.writeFinalTag(out)
                }
                out.seek(0)
                assertEquals(0xffe0, out.readUnsignedShort() and 0xffe0)
                out.seek(if (channels == 2) 36L else 21L)
                val marker = ByteArray(4).also(out::readFully)
                assertEquals(tag, marker.decodeToString())
            }
            extractor.setDataSource(file.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mpeg", format.getString(MediaFormat.KEY_MIME))
            assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertEquals(rate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(2.0, format.getLong(MediaFormat.KEY_DURATION) / 1e6, 0.08)
            extractor.selectTrack(0)
            val decodedFrames = decodeFrames(extractor, format, channels)
            assertEquals(2.0, decodedFrames.toDouble() / rate, 0.08)
        } finally {
            extractor.release()
            file.delete()
        }
    }

    private fun decodeFrames(extractor: MediaExtractor, format: MediaFormat, channels: Int): Long {
        val codec = MediaCodec.createDecoderByType("audio/mpeg")
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var bytes = 0L
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val input = if (inputDone) -1 else codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    bytes += info.size
                    codec.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return bytes / (2 * channels)
                }
            }
            error("El decodificador MP3 no terminó en 15 segundos.")
        } finally {
            codec.release()
        }
    }
}

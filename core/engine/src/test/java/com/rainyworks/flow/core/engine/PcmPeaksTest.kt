package com.rainyworks.flow.core.engine

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmPeaksTest {
    @Test fun stereoPeakUsesBothChannelsAndKeepsTimelineGaps() {
        val peaks = PcmPeaks(2, 2_000_000)
        val stereo = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(-16384).putShort(8192).putShort(0)
        stereo.flip()
        peaks.append(stereo, 500_000, 2, 2, AudioFormat.ENCODING_PCM_16BIT)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 0.25f, 0f), peaks.finish(), 0f)
    }

    @Test fun overlappingBuffersKeepMaximumAndClampFloatPcm() {
        val peaks = PcmPeaks(1, 0)
        val samples = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(-1.2f)
        samples.flip()
        peaks.append(samples, 0, 2, 1, AudioFormat.ENCODING_PCM_FLOAT)
        peaks.append(ByteBuffer.wrap(byteArrayOf(128.toByte())), 500_000, 1, 1, AudioFormat.ENCODING_PCM_8BIT)
        assertArrayEquals(floatArrayOf(1f), peaks.finish(), 0f)
    }

    @Test fun twentyFourBitPcmIsSignExtendedAndGrowsPastDeclaredDuration() {
        val peaks = PcmPeaks(2, 500_000)
        peaks.append(ByteBuffer.wrap(byteArrayOf(0, 0, 0xc0.toByte())), 1_000_000, 4, 1, AudioFormat.ENCODING_PCM_24BIT_PACKED)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f), peaks.finish(), 0f)
    }

    @Test fun emptyDecoderOutputHasNoWaveform() {
        assertNull(PcmPeaks(60, 500_000).finish())
    }
}

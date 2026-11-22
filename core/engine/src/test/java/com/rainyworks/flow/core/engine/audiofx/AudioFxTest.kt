package com.rainyworks.flow.core.engine.audiofx

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import com.rainyworks.flow.core.model.AnimatedProperty
import com.rainyworks.flow.core.model.AudioEffects
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Easing
import com.rainyworks.flow.core.model.Keyframe
import com.rainyworks.flow.core.model.VoicePreset
import com.rainyworks.flow.core.model.animatedValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

@OptIn(UnstableApi::class)
class AudioFxTest {
    private val clip = Clip("tone", ClipKind.AUDIO, trimOutUs = 1_000_000)

    private fun buffer(size: Int) = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    private fun start(processor: AudioProcessor, rate: Int, encoding: Int = C.ENCODING_PCM_FLOAT) {
        processor.configure(AudioFormat(rate, 2, encoding))
        processor.flush(StreamMetadata.DEFAULT)
    }

    private fun render(processor: AudioProcessor, frames: Int, input: (Int, Int) -> Float): FloatArray {
        val result = FloatArray(frames * 2)
        val block = buffer(257 * 8)
        var written = 0
        for (first in 0 until frames step 257) {
            block.clear()
            for (frame in first until minOf(frames, first + 257)) {
                block.putFloat(input(frame, 0)).putFloat(input(frame, 1))
            }
            block.flip()
            processor.queueInput(block)
            assertFalse(block.hasRemaining())
            val output = processor.output.asFloatBuffer()
            val count = output.remaining()
            output.get(result, written, count)
            written += count
        }
        assertEquals(result.size, written)
        return result
    }

    @Test fun neutralChainAndBypassesAreBitExact() {
        val neutral = clip.copy(audioEffects = AudioEffects(equalizer = List(10) { 0f }))
        assertTrue(AudioFx.processorsFor(neutral).isEmpty())
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            for (p in listOf(ClipGainProcessor(neutral), EqualizerProcessor(List(10) { 0f }),
                EchoProcessor(0f), ReverbProcessor(0f))) {
                start(p, 48_000, encoding)
                assertFalse(p.isActive)
                val bytes = ByteArray(1024) { (it * 73).toByte() }
                val input = buffer(bytes.size).put(bytes)
                input.flip()
                p.queueInput(input)
                val actual = ByteArray(bytes.size)
                p.output.get(actual)
                assertArrayEquals(bytes, actual)
            }
        }
    }

    @Test fun peakingGainAtAllTenCenters() {
        val rate = 48_000
        val centers = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        for ((index, hz) in centers.withIndex()) {
            for (db in listOf(-12, 6, 12)) {
                val eq = EqualizerProcessor(List(10) { if (it == index) db.toFloat() else 0f })
                start(eq, rate)
                val output = render(eq, rate) { frame, channel ->
                    if (channel == 0) (0.1 * sin(2 * PI * hz * frame / rate)).toFloat() else 0f
                }
                var energy = 0.0
                for (frame in rate / 2 until rate) {
                    val value = output[frame * 2].toDouble()
                    energy += value * value
                    assertEquals(0f, output[frame * 2 + 1], 0f)
                }
                val gainDb = 10 * log10(energy / (rate / 2 * 0.005))
                assertEquals("$hz Hz, $db dB", db.toDouble(), gainDb, 0.5)
            }
        }
        val ultrasonic = EqualizerProcessor(List(10) { if (it == 9) 6f else 0f })
        start(ultrasonic, 32000)
        assertFalse(ultrasonic.isActive)
    }

    @Test fun fadesAndAnimatedGainFollowPlayoutFramesAcrossBuffersAndSeek() {
        val moving = clip.copy(
            startUs = 9_000_000, trimInUs = 3_000_000, trimOutUs = 5_000_000, speed = 2f,
            fadeInUs = 600_000, fadeOutUs = 700_000,
            keyframes = mapOf(AnimatedProperty.VOLUME to listOf(
                Keyframe(700_000, 0.2f), Keyframe(0, 0.1f), Keyframe(500_000, 1f, Easing.EASE_OUT),
            )),
        )
        val p = ClipGainProcessor(moving)
        start(p, 1000)
        val output = render(p, 1000) { _, _ -> 0.5f }
        for (frame in 0 until 1000) {
            val animated = moving.animatedValue(AnimatedProperty.VOLUME, moving.startUs + 1000L * frame, moving.volume)
            val fadeIn = sin(PI / 2 * minOf(1.0, frame / 600.0))
            val fadeOut = sin(PI / 2 * minOf(1.0, (1000 - frame) / 700.0))
            assertEquals(0.5 * animated * fadeIn * fadeOut, output[frame * 2].toDouble(), 1e-6)
            assertEquals(output[frame * 2], output[frame * 2 + 1], 0f)
        }
        p.flush(StreamMetadata.Builder().setPositionOffsetUs(657_000).build())
        assertArrayEquals(output.copyOfRange(657 * 2, 2000), render(p, 343) { _, _ -> 0.5f }, 0f)
    }

    @Test fun boostedPcmUsesASmoothMonotonicKneeOnBothPolarities() {
        val p = ClipGainProcessor(clip.copy(volume = 2f))
        start(p, 48000, C.ENCODING_PCM_16BIT)
        val input = buffer(65536 * 4)
        for (i in -32768..32767) input.putShort(i.toShort()).putShort(i.toShort())
        input.flip()
        p.queueInput(input)
        val output = p.output.asShortBuffer()
        var previous = Int.MIN_VALUE
        for (i in -32768..32767) {
            val value = output.get().toInt()
            assertEquals(value, output.get().toInt())
            assertTrue(value >= previous && value in -32767..32766)
            if (abs(i) <= 8192) assertEquals(2 * i, value)
            previous = value
        }
        start(p, 48000)
        val floats = render(p, 2001) { i, _ -> (i - 1000) / 1000f }
        assertTrue(floats.all { abs(it) < 1f })
        assertTrue(floats.last() > floats[3900])
    }

    @Test fun echoDelayFeedbackAndFlushAtTwoSampleRates() {
        val p = EchoProcessor(0.5f)
        for (rate in listOf(44100, 48000)) {
            start(p, rate)
            val delay = 3 * rate / 10
            val output = render(p, 2 * delay + 1) { frame, channel -> if (frame == 0 && channel == 0) 1f else 0f }
            for (frame in 0..2 * delay) {
                val expected = when (frame) { 0, delay -> 0.5f; 2 * delay -> 0.2f; else -> 0f }
                assertEquals(expected, output[frame * 2], 1e-6f)
                assertEquals(0f, output[frame * 2 + 1], 0f)
            }
            p.queueEndOfStream()
            assertTrue(p.isEnded)
            p.flush(StreamMetadata.DEFAULT)
            assertTrue(render(p, delay + 1) { _, _ -> 0f }.all { it == 0f })
        }
    }

    @Test fun pitchShiftChangesFrequencyAndKeepsDuration() {
        val rate = 48000
        val p = AudioFx.processorsFor(clip.copy(audioEffects = AudioEffects(pitchSemitones = 12f))).single()
        start(p, rate, C.ENCODING_PCM_16BIT)
        val input = buffer(rate * 4)
        for (i in 0 until rate) {
            val s = (12000 * sin(2 * PI * 440 * i / rate)).toInt().toShort()
            input.putShort(s).putShort(s)
        }
        input.flip()
        p.queueInput(input)
        p.queueEndOfStream()
        val out = p.output.asShortBuffer()
        val frames = out.remaining() / 2
        assertEquals(rate.toDouble(), frames.toDouble(), rate * 0.02)
        val crossings = (rate / 4 until rate * 3 / 4).count { out[it * 2] > 0 && out[(it - 1) * 2] <= 0 }
        assertEquals(880.0, crossings * 2.0, 5.0)
        for (keep in listOf(true, false)) {
            val sped = clip.copy(speed = 2f, preservePitch = keep)
            assertTrue(AudioFx.processorsFor(sped).isEmpty())
            val parameters = AudioFx.speedParametersFor(sped)
            assertEquals(keep, parameters.shouldMaintainPitch)
            assertEquals(2f, parameters.speedProvider.getSpeed(1234), 0f)
        }
    }

    @Test fun presetsProcessStereoAndResetTheirHistory() {
        for (preset in VoicePreset.entries.filter { it != VoicePreset.NONE }) {
            val p = AudioFx.processorsFor(clip.copy(audioEffects = AudioEffects(preset = preset))).single()
            start(p, 48000)
            val output = render(p, 6000) { frame, channel -> if (frame == 0 && channel == 1) 0.8f else 0f }
            assertTrue((1 until 6000).any { abs(output[it * 2 + 1]) > 1e-4 })
            assertTrue((0 until 6000).all { output[it * 2] == 0f })
            p.flush(StreamMetadata.DEFAULT)
            assertTrue(render(p, 6000) { _, _ -> 0f }.all { it == 0f })
        }
    }
}

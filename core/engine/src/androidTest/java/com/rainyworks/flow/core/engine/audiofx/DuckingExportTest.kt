package com.rainyworks.flow.core.engine.audiofx

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.audio.AudioExportSettings
import com.rainyworks.flow.core.engine.audio.AudioFormat
import com.rainyworks.flow.core.engine.audio.convertAac
import com.rainyworks.flow.core.engine.audio.renderAac
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class DuckingExportTest {
    @Test fun musicDropsAboutTwelveDecibelsWhileDucked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("duck-tone", ".wav", context.cacheDir)
        val aac = File.createTempFile("duck-mix", ".m4a", context.cacheDir)
        val decoded = File.createTempFile("duck-decoded", ".wav", context.cacheDir)
        try {
            val rate = 48000
            val seconds = 3
            val pcm = ByteBuffer.allocate(44 + rate * 4 * seconds).order(ByteOrder.LITTLE_ENDIAN)
            pcm.put("RIFF".encodeToByteArray()).putInt(pcm.capacity() - 8)
            pcm.put("WAVEfmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(2)
            pcm.putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            pcm.put("data".encodeToByteArray()).putInt(rate * 4 * seconds)
            for (i in 0 until rate * seconds) {
                val sample = (8000 * sin(2 * PI * 440 * i / rate)).toInt().toShort()
                pcm.putShort(sample).putShort(sample)
            }
            source.writeBytes(pcm.array())
            val clip = Clip("music", ClipKind.AUDIO, trimOutUs = seconds * 1_000_000L, duckUnderVoice = true)
            // Someone speaks from 1 s to 2 s.
            val ducking = Ducking(listOf(1_000_000L..2_000_000L))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setRemoveVideo(true)
                .setEffects(Effects(AudioFx.processorsFor(clip, ducking), emptyList()))
                .build()
            val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(item).build()
            val settings = AudioExportSettings(AudioFormat.WAV, sampleRate = rate)
            withTimeout(60_000) {
                renderAac(context, Composition.Builder(sequence).build(), aac, settings) {}
                convertAac(aac, decoded, settings, clip.durationUs) {}
            }
            val wav = ByteBuffer.wrap(decoded.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            fun energy(fromS: Double, toS: Double): Double {
                var e = 0.0
                for (frame in (fromS * rate).toInt() until (toS * rate).toInt()) {
                    val value = wav.getShort(44 + 4 * frame).toDouble()
                    e += value * value
                }
                return e
            }
            val drop = 10 * log10(energy(1.3, 1.7) / energy(0.2, 0.6))
            assertEquals(20 * log10(Ducking.LEVEL), drop, 1.0)
            // Back to full level well after the release.
            assertEquals(0.0, 10 * log10(energy(2.6, 2.9) / energy(0.2, 0.5)), 1.0)
        } finally {
            source.delete()
            aac.delete()
            decoded.delete()
        }
    }
}

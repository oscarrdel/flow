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
import com.rainyworks.flow.core.model.AudioEffects
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
class EqualizerExportTest {
    @Test fun boostsOneKhzInDecodedAac() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("eq-tone", ".wav", context.cacheDir)
        val aac = File.createTempFile("eq-mix", ".m4a", context.cacheDir)
        val decoded = File.createTempFile("eq-decoded", ".wav", context.cacheDir)
        try {
            val rate = 48000
            val pcm = ByteBuffer.allocate(44 + rate * 4).order(ByteOrder.LITTLE_ENDIAN)
            pcm.put("RIFF".encodeToByteArray()).putInt(pcm.capacity() - 8)
            pcm.put("WAVEfmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(2)
            pcm.putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            pcm.put("data".encodeToByteArray()).putInt(rate * 4)
            for (i in 0 until rate) {
                val sample = (3000 * sin(2 * PI * 1000 * i / rate)).toInt().toShort()
                pcm.putShort(sample).putShort(sample)
            }
            source.writeBytes(pcm.array())
            val clip = Clip("tone", ClipKind.AUDIO, trimOutUs = 1_000_000,
                audioEffects = AudioEffects(equalizer = List(10) { if (it == 5) 6f else 0f }))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setRemoveVideo(true)
                .setEffects(Effects(AudioFx.processorsFor(clip), emptyList()))
                .build()
            val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(item).build()
            val settings = AudioExportSettings(AudioFormat.WAV, sampleRate = rate)
            withTimeout(60_000) {
                renderAac(context, Composition.Builder(sequence).build(), aac, settings) {}
                convertAac(aac, decoded, settings, clip.durationUs) {}
            }
            val wav = ByteBuffer.wrap(decoded.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            var energy = 0.0
            for (frame in rate / 4 until 3 * rate / 4) {
                val value = wav.getShort(44 + 4 * frame).toDouble()
                energy += value * value
            }
            val gainDb = 10 * log10(energy / (rate / 2 * (3000.0 * 3000.0 / 2)))
            assertEquals(6.0, gainDb, 0.5)
        } finally {
            source.delete()
            aac.delete()
            decoded.delete()
        }
    }
}

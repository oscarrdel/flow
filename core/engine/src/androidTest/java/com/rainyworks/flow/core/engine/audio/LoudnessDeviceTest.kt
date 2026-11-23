package com.rainyworks.flow.core.engine.audio

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class LoudnessDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun wav(amplitude: Double): File {
        val rate = 44_100
        val frames = rate * 2
        val pcm = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".encodeToByteArray()).putInt(pcm.capacity() - 8)
        pcm.put("WAVEfmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(2)
        pcm.putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        pcm.put("data".encodeToByteArray()).putInt(frames * 4)
        for (i in 0 until frames) {
            val v = (32767 * amplitude * sin(2 * PI * 330 * i / rate)).toInt().toShort()
            pcm.putShort(v).putShort(v)
        }
        return File.createTempFile("loud", ".wav", context.cacheDir).apply { writeBytes(pcm.array()) }
    }

    private fun clip(file: File) = Clip(
        "a", ClipKind.AUDIO,
        media = MediaRef(Uri.fromFile(file).toString(), MediaType.AUDIO, durationUs = 2_000_000, hasAudio = true, hasVideo = false),
        trimOutUs = 2_000_000,
    )

    @Test
    fun quietRecordingIsRaisedAndLoudOneLowered() = runBlocking {
        val meter = Loudness(context)
        val quiet = meter.volumeFor(clip(wav(0.03)))
        val loud = meter.volumeFor(clip(wav(0.9)))
        assertTrue("quiet $quiet", quiet > 1.5f)
        assertTrue("loud $loud", loud < 0.3f)
    }
}

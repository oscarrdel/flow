package com.rainyworks.flow.core.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.model.EffectEdits
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaEdits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.Transition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Plays projects the way the editor preview does and checks they reach the end. */
@OptIn(UnstableApi::class)
class PreviewPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun video(name: String): MediaRef {
        val file = File(context.cacheDir, "preview-$name")
        if (file.length() <= 0) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Movies/$name")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return runBlocking { checkNotNull(MediaProbe(context).probe(Uri.fromFile(file))) }
    }

    /**
     * A 5 s VP9 clip made from a still: the emulator's H.264 decoder fails inside
     * CompositionPlayer, which cannot be given another decoder.
     */
    private fun vp9Video(): MediaRef = generatedVideo(
        // The emulator's decoders fail inside CompositionPlayer; real phones use H.264.
        if (android.os.Build.HARDWARE == "ranchu") androidx.media3.common.MimeTypes.VIDEO_VP9 else androidx.media3.common.MimeTypes.VIDEO_H264,
    )

    private fun generatedVideo(mime: String): MediaRef {
        val file = File(context.cacheDir, "preview-4k-audio-${mime.substringAfter('/')}.mp4")
        if (file.length() <= 0) runBlocking {
            val still = File(context.cacheDir, "preview-still.png")
            // Phones get a 4K clip, as heavy to decode as camera footage.
            val (w, h) = if (android.os.Build.HARDWARE == "ranchu") 320 to 240 else 3840 to 2160
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(200, 60, 40))
                // Some detail so the encoder produces real work for the decoder.
                val c = android.graphics.Canvas(this)
                val paint = android.graphics.Paint().apply { color = Color.WHITE; textSize = h / 6f }
                for (i in 0 until 12) c.drawText("Flow $i", (i * w / 12).toFloat(), (i % 6 + 1) * h / 7f, paint)
            }
                .also { bmp -> still.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            val done = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                androidx.media3.transformer.Transformer.Builder(context)
                    .setVideoMimeType(mime)
                    .addListener(object : androidx.media3.transformer.Transformer.Listener {
                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                        ) { done.complete(Unit) }
                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException,
                        ) { done.completeExceptionally(exportException) }
                    })
                    .build()
                    .start(
                        androidx.media3.transformer.Composition.Builder(
                            androidx.media3.transformer.EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO))
                                .addItem(
                                    androidx.media3.transformer.EditedMediaItem.Builder(
                                        androidx.media3.common.MediaItem.Builder().setUri(Uri.fromFile(still)).setImageDurationMs(5_000).build(),
                                    ).setFrameRate(30).build(),
                                ).build(),
                            // Camera clips carry sound; so does this one.
                            androidx.media3.transformer.EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO))
                                .addItem(
                                    androidx.media3.transformer.EditedMediaItem.Builder(
                                        androidx.media3.common.MediaItem.fromUri(Uri.fromFile(tone())),
                                    ).build(),
                                ).build(),
                        ).build(),
                        file.absolutePath,
                    )
            }
            done.await()
        }
        return runBlocking { checkNotNull(MediaProbe(context).probe(Uri.fromFile(file))) }
    }

    /** Five seconds of a 440 Hz tone as a WAV file. */
    private fun tone(): File {
        val file = File(context.cacheDir, "preview-tone.wav")
        if (file.length() > 0) return file
        val rate = 48_000
        val frames = rate * 5
        val pcm = java.nio.ByteBuffer.allocate(44 + frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".encodeToByteArray()).putInt(pcm.capacity() - 8)
        pcm.put("WAVEfmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(2)
        pcm.putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        pcm.put("data".encodeToByteArray()).putInt(frames * 4)
        for (i in 0 until frames) {
            val v = (6000 * kotlin.math.sin(2 * Math.PI * 440 * i / rate)).toInt().toShort()
            pcm.putShort(v).putShort(v)
        }
        file.writeBytes(pcm.array())
        return file
    }

    private fun photo(): MediaRef {
        val file = File(context.cacheDir, "preview-photo-big.jpg")
        Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        return MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 4000, height = 3000, mimeType = "image/jpeg")
    }

    /** Plays [project] from [fromMs] until it ends; returns the error, if any. */
    private fun play(project: Project, fromMs: Long): PlaybackException? = runBlocking {
        val reader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, android.os.Handler(android.os.Looper.getMainLooper()))
        val ended = CompletableDeferred<PlaybackException?>()
        val player = withContext(Dispatchers.Main) {
            CompositionPlayer.Builder(context)
                .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
                .build().apply {
                    setVideoSurface(reader.surface, androidx.media3.common.util.Size(640, 360))
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { ended.complete(error) }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) ended.complete(null)
                        }
                    })
                    setComposition(CompositionFactory.build(project, ExportSettings(heightPx = 360)), fromMs)
                    prepare()
                    play()
                }
        }
        try {
            // Scrub around like a user before letting it play to the end.
            for (target in listOf(4_500L, 3_000L, 5_200L, fromMs)) {
                delay(700)
                withContext(Dispatchers.Main) { player.seekTo(target) }
            }
            val result = kotlinx.coroutines.withTimeoutOrNull(project.durationUs / 1000 + 15_000) { ended.await() }
            val position = withContext(Dispatchers.Main) { player.currentPosition }
            if (result == null) throw AssertionError("Playback did not end; stuck at $position ms of ${project.durationUs / 1000}")
            result
        } finally {
            withContext(Dispatchers.Main) { player.release() }
            delay(200)
            reader.close()
        }
    }

    @Test
    fun videoThenPhotoWithDissolvePlaysThrough() {
        var p = ProjectFactory.create("t", 0L)
        val clip = vp9Video()
        p = Edits.insertIntoMain(p, listOf(ProjectFactory.clipFor(clip), ProjectFactory.clipFor(photo(), imageDurationUs = 3_000_000L)))
        p = Edits.trimEnd(p, p.mainTrack.clips[0].id, minOf(clip.durationUs, 5_000_000L))
        p = EffectEdits.setTransition(p, p.mainTrack.clips[1].id, Transition("dissolve", 1_300_000))
        val (withText, _) = MediaEdits.addText(p, 1_000_000L, TextContent("Hola"))
        assertNull(play(withText, fromMs = 2_000))
    }

    private fun main(vararg clips: com.rainyworks.flow.core.model.Clip, transition: Boolean): Project {
        var p = ProjectFactory.create("t", 0L)
        p = Edits.insertIntoMain(p, clips.toList())
        if (transition) p = EffectEdits.setTransition(p, p.mainTrack.clips[1].id, Transition("dissolve", 1_300_000))
        return p
    }

    @Test
    fun photoThenPhotoWithDissolve() {
        assertNull(play(main(ProjectFactory.clipFor(photo(), imageDurationUs = 4_000_000L), ProjectFactory.clipFor(photo(), imageDurationUs = 3_000_000L), transition = true), 2_000))
    }

    @Test
    fun videoThenVideoWithDissolve() {
        assertNull(play(main(ProjectFactory.clipFor(vp9Video()), ProjectFactory.clipFor(vp9Video()), transition = true), 2_000))
    }

    @Test
    fun videoThenPhotoWithoutTransition() {
        assertNull(play(main(ProjectFactory.clipFor(vp9Video()), ProjectFactory.clipFor(photo(), imageDurationUs = 3_000_000L), transition = false), 2_000))
    }

    @Test
    fun videoThenPhotoWithDissolveNoText() {
        assertNull(play(main(ProjectFactory.clipFor(vp9Video()), ProjectFactory.clipFor(photo(), imageDurationUs = 3_000_000L), transition = true), 2_000))
    }
}

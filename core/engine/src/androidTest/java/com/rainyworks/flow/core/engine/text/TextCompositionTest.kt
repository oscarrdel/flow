package com.rainyworks.flow.core.engine.text

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.CompositionFactory
import com.rainyworks.flow.core.engine.ExportSettings
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.Canvas
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.Track
import com.rainyworks.flow.core.model.TrackKind
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class TextCompositionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportedTextUsesCompositionTimeAcrossTwoMediaItems() = runBlocking {
        val image = File.createTempFile("text-input-", ".png", context.cacheDir)
        val output = File.createTempFile("text-output-", ".mp4", context.cacheDir)
        try {
            val side = 320
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val media = MediaRef(Uri.fromFile(image).toString(), MediaType.IMAGE, width = side, height = side)
            val first = Clip("first", ClipKind.IMAGE, media = media, trimOutUs = 1_000_000)
            val main = Track.main().copy(clips = listOf(first, first.copy(id = "second", startUs = first.endUs)))
            val project = Project("text", "", 0, 0, canvas = Canvas(AspectRatio.R1_1), tracks = listOf(main))
            val base = CompositionFactory.build(project, ExportSettings(heightPx = side))
            val caption = Clip("caption", ClipKind.TEXT, startUs = 600_000, trimOutUs = 800_000,
                text = TextContent("FLOW", size = 0.15f, bold = true))
            val track = Track("titles", TrackKind.TEXT, listOf(caption))
            val titles = TextOverlays.forProject(project.copy(tracks = listOf(main, track)), TextRenderer(context), side, side)
            val composition = Composition.Builder(base.sequences)
                .setEffects(Effects(base.effects.audioProcessors, base.effects.videoEffects + OverlayEffect(titles)))
                .build()
            export(composition, output)

            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(output.absolutePath)
                for ((timeUs, expected) in listOf(250_000L to false, 750_000L to true, 1_250_000L to true, 1_750_000L to false)) {
                    val frame = checkNotNull(reader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
                    val pixels = IntArray(frame.width * frame.height)
                    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    frame.recycle()
                    assertEquals("White lettering at ${timeUs}us", expected, pixels.count { Color.red(it) > 150 } > 200)
                }
            }
        } finally {
            image.delete()
            output.delete()
        }
    }

    private suspend fun export(composition: Composition, output: File) = withContext(Dispatchers.Main.immediate) {
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                done.complete(Unit)
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                done.completeExceptionally(exportException)
            }
        }).build()
        try {
            transformer.start(composition, output.absolutePath)
            withTimeout(60_000) { done.await() }
        } finally {
            if (!done.isCompleted) transformer.cancel()
        }
    }
}

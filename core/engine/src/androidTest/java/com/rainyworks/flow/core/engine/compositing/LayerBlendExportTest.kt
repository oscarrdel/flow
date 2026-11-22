package com.rainyworks.flow.core.engine.compositing

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.model.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class LayerBlendExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val canvas = Size(160, 120)
    private val background = Color.rgb(160, 80, 48)

    @Test fun transformerMixesTracksAndEvaluatesMaskAfterLeadingGap() = verifyExport(BlendMode.MULTIPLY)

    @Test fun nativeNormalMixPreservesPngAlphaAndMaskTiming() = verifyExport(BlendMode.NORMAL)

    private fun verifyExport(mode: BlendMode) = runBlocking {
        val dir = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "blend-").toFile()
        try {
            fun image(name: String, height: Int = canvas.height, pixel: (Int) -> Int): MediaRef {
                val file = File(dir, "$name.png")
                val data = IntArray(canvas.width * height) { pixel(it % canvas.width) }
                val bitmap = Bitmap.createBitmap(data, canvas.width, height, Bitmap.Config.ARGB_8888)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                return MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = canvas.width, height = height)
            }
            val bg = Clip("base", ClipKind.IMAGE, image("base") { background }, trimOutUs = 2_000_000)
            val fg = Clip("layer", ClipKind.IMAGE, image("layer", height = 90) { x ->
                when (x) {
                    in 0..37 -> Color.rgb(80, 150, 220)
                    in 38..55 -> Color.argb(128, 255, 128, 192)
                    in 56..104 -> Color.GREEN
                    else -> Color.rgb(0, 128, 0)
                }
            }, startUs = 600_000, trimOutUs = 1_000_000, opacity = 0.75f, blendMode = mode,
                chromaKey = ChromaKey(tolerance = 0.08f, softness = 0f, shadows = 1f),
                mask = Mask(MaskShape.SPLIT), keyframes = mapOf(AnimatedProperty.MASK_OFFSET_Y to
                    listOf(Keyframe(0, -0.3f), Keyframe(1_000_000, 0.3f))))
            val layers = LayerBlendCompositor(canvas, listOf(Track("over", TrackKind.OVERLAY, listOf(fg)),
                Track.main().copy(clips = listOf(bg))))
            fun sequence(c: Clip): EditedMediaItemSequence {
                val items = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                if (c.startUs > 0) items.addCanvasGap(c.startUs, canvas)
                val item = MediaItem.Builder().setUri(c.media!!.uri).setImageDurationMs(c.durationUs / 1000).build()
                val size = Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT)
                val effects = clipVideoEffects(c, emptyList(), size, hasVideoLayers = true)
                items.addItem(EditedMediaItem.Builder(item).setFrameRate(10).setEffects(Effects(emptyList(), effects)).build())
                if (c.endUs < bg.endUs) items.addCanvasGap(bg.endUs - c.endUs, canvas)
                return items.build()
            }
            val composition = Composition.Builder(listOf(sequence(fg), sequence(bg)))
                .setVideoCompositorSettings(layers).setEffects(Effects(emptyList(), listOfNotNull(layers.effect))).build()
            val file = File(dir, "blend.mp4")
            withContext(Dispatchers.Main.immediate) {
                val finished = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.complete(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finished.completeExceptionally(exportException)
                    }
                }).build()
                try {
                    transformer.start(composition, file.path)
                    withTimeout(60_000) { finished.await() }
                } finally {
                    if (!finished.isCompleted) transformer.cancel()
                }
            }
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(file.path)
                val differences = mutableListOf<String>()
                for (t in listOf(300_000L, 700_000L, 1_200_000L, 1_400_000L, 1_500_000L, 1_800_000L)) {
                    val frame = checkNotNull(reader.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        assertEquals(canvas.width, frame.width)
                        assertEquals(canvas.height, frame.height)
                        fun checkPixel(x: Int, expected: Int, y: Int = 60) {
                            val pixel = frame.getPixel(x, y)
                            for (shift in listOf(0, 8, 16)) {
                                if (kotlin.math.abs(((pixel shr shift) and 255) - ((expected shr shift) and 255)) > 9) {
                                    differences += "t=$t x=$x y=$y expected=%08x actual=%08x".format(expected, pixel)
                                    break
                                }
                            }
                        }
                        val active = t in 1_200_000L..1_500_000L
                        val solid = if (mode == BlendMode.NORMAL) Color.rgb(100, 133, 177) else Color.rgb(78, 55, 43)
                        val translucent = if (mode == BlendMode.NORMAL) Color.rgb(196, 98, 102) else Color.rgb(160, 65, 44)
                        checkPixel(25, if (active) solid else background)
                        checkPixel(46, if (active) translucent else background)
                        checkPixel(25, background, y = 4)
                        checkPixel(80, background)
                        checkPixel(130, if (active) Color.rgb(100, 50, 30) else background)
                    } finally {
                        frame.recycle()
                    }
                }
                org.junit.Assert.assertTrue(differences.joinToString(), differences.isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

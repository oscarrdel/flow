package com.rainyworks.flow.core.engine.reverse

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.MediaProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReverseProxyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun frame(path: String, timeUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
            return Bitmap.createScaledBitmap(bitmap, 48, 64, true)
        } finally {
            retriever.release()
        }
    }

    private fun difference(a: Bitmap, b: Bitmap): Double {
        var total = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            total += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
        }
        return total.toDouble() / (a.width * a.height * 3)
    }

    @Test
    fun reversesVideoAndAudio(): Unit = runBlocking {
        val input = File(context.cacheDir, "reverse-input.mp4")
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Movies/baseline_test.mp4"))
            .use { stream -> input.outputStream().use { stream.copyTo(it) } }
        File(context.cacheDir, "reverse").deleteRecursively()
        val media = checkNotNull(MediaProbe(context).probe(Uri.fromFile(input)))
        var lastProgress = 0f
        val started = System.currentTimeMillis()
        val reversed = ReverseProxy(context).create(media) { lastProgress = it }
        val took = System.currentTimeMillis() - started
        val path = Uri.parse(reversed.uri).path!!
        Log.i("FlowReverse", "took ${took} ms, duration ${reversed.durationUs} vs ${media.durationUs}, audio=${reversed.hasAudio}")

        assertTrue("progress reached the end", lastProgress >= 0.99f)
        assertTrue("same length", abs(reversed.durationUs - media.durationUs) <= 100_000)
        assertTrue("keeps audio", reversed.hasAudio)

        val d = media.durationUs
        for (t in listOf(500_000L, 2_000_000L, 4_000_000L)) {
            val mirrored = difference(frame(path, t), frame(input.path, d - t - 33_333))
            val same = difference(frame(path, t), frame(input.path, t))
            Log.i("FlowReverse", "t=$t mirrored=$mirrored same=$same")
            assertTrue("t=$t mirrored=$mirrored same=$same", mirrored < same && mirrored < 12.0)
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val audioTrack = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
            }
            assertTrue(extractor.getTrackFormat(audioTrack).getLong(MediaFormat.KEY_DURATION) > d - 300_000)
        } finally {
            extractor.release()
        }

        val cachedStart = System.currentTimeMillis()
        ReverseProxy(context).create(media) { }
        assertTrue("second call uses the cache", System.currentTimeMillis() - cachedStart < 3_000)
    }
}

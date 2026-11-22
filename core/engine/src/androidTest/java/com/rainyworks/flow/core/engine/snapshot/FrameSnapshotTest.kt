package com.rainyworks.flow.core.engine.snapshot

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.model.AspectRatio
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.ProjectFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FrameSnapshotTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun solid(name: String, color: Int): MediaRef {
        val file = File(context.cacheDir, "$name.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return MediaRef(Uri.fromFile(file).toString(), MediaType.IMAGE, width = 64, height = 64, mimeType = "image/png")
    }

    @Test
    fun capturesThePictureAtTheRequestedMoment() = runBlocking {
        var p = ProjectFactory.create("t", 0L)
        p = p.copy(canvas = p.canvas.copy(aspectRatio = AspectRatio.R1_1, followFirstClip = false))
        p = Edits.insertIntoMain(p, listOf(
            ProjectFactory.clipFor(solid("snap-red", Color.RED), imageDurationUs = 1_000_000L),
            ProjectFactory.clipFor(solid("snap-blue", Color.BLUE), imageDurationUs = 1_000_000L),
        ))
        val snapshot = FrameSnapshot(context)
        val first = snapshot.capture(p, 500_000, heightPx = 240)
        val second = snapshot.capture(p, 1_500_000, heightPx = 240)
        assertEquals(240, first.height)
        val a = first.getPixel(first.width / 2, first.height / 2)
        val b = second.getPixel(second.width / 2, second.height / 2)
        assertTrue("first ${Integer.toHexString(a)}", Color.red(a) > 200 && Color.blue(a) < 60)
        assertTrue("second ${Integer.toHexString(b)}", Color.blue(b) > 200 && Color.red(b) < 60)
    }
}

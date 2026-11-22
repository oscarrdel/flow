package com.rainyworks.flow.core.engine.captions

import android.Manifest
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainyworks.flow.core.engine.MediaProbe
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AutoCaptionerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Spoken Spanish from the system text-to-speech engine, or null when there is none. */
    private suspend fun speech(text: String): File? = withContext(Dispatchers.Main) {
        val ready = CompletableDeferred<Boolean>()
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        try {
            if (!withTimeout(20_000) { ready.await() }) return@withContext null
            if (tts.setLanguage(Locale("es", "MX")) < TextToSpeech.LANG_AVAILABLE) return@withContext null
            val file = File(context.cacheDir, "speech-${System.nanoTime()}.wav")
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete(false) }
            })
            tts.synthesizeToFile(text, null, file, "u")
            if (withTimeout(30_000) { done.await() } && file.length() > 0) file else null
        } finally {
            tts.shutdown()
        }
    }

    @Test
    fun writesSubtitlesForSpeech() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val wav = speech("Hola, esta es una prueba de subtítulos automáticos.")
        assumeTrue("No text-to-speech voice for Spanish", wav != null)
        val media = MediaProbe(context).probe(Uri.fromFile(wav))!!
        val clip = Clip("speech", ClipKind.AUDIO, media = media, trimOutUs = media.durationUs)
        val cues = try {
            AutoCaptioner(context).captions(clip, Locale("es", "MX")) {}
        } catch (downloading: AutoCaptioner.LanguageDownloadingException) {
            assumeTrue("Speech model downloading: ${downloading.languageTag}", false)
            emptyList()
        } catch (unavailable: AutoCaptioner.UnavailableException) {
            assumeTrue("Speech recognition unavailable: ${unavailable.message}", false)
            emptyList()
        }
        android.util.Log.i("CAPTIONS", cues.joinToString { "${it.startUs}-${it.endUs}: ${it.text}" })
        assertTrue("cues: $cues", cues.isNotEmpty())
        val words = cues.joinToString(" ") { it.text }.lowercase()
        assertTrue(words, "prueba" in words || "subtítulos" in words || "hola" in words)
        assertTrue(cues.all { it.startUs >= 0 && it.endUs <= media.durationUs + 100_000 })
    }
}

package com.rainyworks.flow.core.engine.captions

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.SubtitleCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.rainyworks.flow.core.engine.audio.MonoAudioDecoder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Writes subtitles for the speech in a clip using the phone's own speech recognizer (offline
 * when the language is installed). The audio is split into phrases first, so every subtitle
 * gets exact start and end times, and each phrase is recognized on its own.
 */
class AutoCaptioner(context: Context) {
    private val app = context.applicationContext

    /** Thrown when this phone cannot recognize speech from audio files. */
    class UnavailableException(message: String) : Exception(message)

    /**
     * Subtitle cues on the timeline for [clip]'s speech. [onProgress] gets 0..1 as phrases
     * are recognized.
     */
    suspend fun captions(clip: Clip, locale: Locale = Locale.getDefault(), onProgress: (Float) -> Unit): List<SubtitleCue> {
        val media = clip.media ?: return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw UnavailableException("Requires Android 13")
        }
        val (samples, firstSourceUs) = withContext(Dispatchers.IO) {
            MonoAudioDecoder.decode(app, Uri.parse(media.uri), RATE, clip.trimInUs, clip.trimOutUs)
        }
        val phrases = SpeechSegmenter.phrases(samples, RATE)
        if (phrases.isEmpty()) return emptyList()
        onProgress(0.05f)
        val cues = mutableListOf<SubtitleCue>()
        withContext(Dispatchers.Main) {
            var (recognizer, languageTag) = chooseRecognizer(locale)
            try {
                var preferOffline = true
                phrases.forEachIndexed { index, phrase ->
                    currentCoroutineContext().ensureActive()
                    val text = try {
                        recognize(recognizer, samples, phrase, languageTag, preferOffline)
                    } catch (offline: OfflineLanguageMissing) {
                        // No offline model for this language: use the online recognizer from now on.
                        if (!preferOffline || !SpeechRecognizer.isRecognitionAvailable(app)) {
                            throw UnavailableException("Language unavailable")
                        }
                        preferOffline = false
                        // A fresh session: the failed one is still closing in the service.
                        recognizer.destroy()
                        kotlinx.coroutines.delay(300)
                        recognizer = SpeechRecognizer.createSpeechRecognizer(app)
                        recognize(recognizer, samples, phrase, languageTag, preferOffline = false)
                    }
                    if (!text.isNullOrBlank()) {
                        // Padding can reach just past the trimmed part; keep the times inside it.
                        fun timeline(sample: Int) = clip.timelineTimeOfSource(
                            (firstSourceUs + sample * 1_000_000L / RATE).coerceIn(clip.trimInUs, clip.trimOutUs),
                        )
                        val a = timeline(phrase.first)
                        val b = timeline(phrase.last + 1)
                        if (a != null && b != null && a != b) {
                            cues += SubtitleCue(minOf(a, b), maxOf(a, b), tidy(text))
                        }
                    }
                    onProgress(0.05f + 0.95f * (index + 1) / phrases.size)
                }
            } finally {
                recognizer.destroy()
            }
        }
        return cues.sortedBy { it.startUs }
    }

    /**
     * The on-device recognizer when it has (a variant of) the language installed; otherwise
     * the regular one. A language that can be downloaded but is not installed yet starts
     * downloading and reports [LanguageDownloadingException].
     */
    private suspend fun chooseRecognizer(locale: Locale): Pair<SpeechRecognizer, String> {
        val wanted = locale.toLanguageTag()
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) {
            val onDevice = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            val support = support(onDevice, wanted)
            val installed = support?.installedOnDeviceLanguages.orEmpty()
            val supported = support?.supportedOnDeviceLanguages.orEmpty()
            bestMatch(locale, installed)?.let { return onDevice to it }
            val downloadable = bestMatch(locale, supported)
            if (downloadable != null && !SpeechRecognizer.isRecognitionAvailable(app)) {
                onDevice.triggerModelDownload(intentFor(downloadable))
                onDevice.destroy()
                throw LanguageDownloadingException(downloadable)
            }
            if (support == null && !SpeechRecognizer.isRecognitionAvailable(app)) return onDevice to wanted
            onDevice.destroy()
            if (downloadable != null) {
                // Use the regular recognizer now, and get the offline model for next time.
                SpeechRecognizer.createOnDeviceSpeechRecognizer(app).also {
                    it.triggerModelDownload(intentFor(downloadable))
                    it.destroy()
                }
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(app)) return SpeechRecognizer.createSpeechRecognizer(app) to wanted
        throw UnavailableException("No speech recognizer")
    }

    private suspend fun support(recognizer: SpeechRecognizer, tag: String): android.speech.RecognitionSupport? =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { continuation ->
                recognizer.checkRecognitionSupport(
                    intentFor(tag),
                    { it.run() },
                    object : android.speech.RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: android.speech.RecognitionSupport) {
                            if (continuation.isActive) continuation.resume(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        }

    /** The exact language tag if listed, else another region of the same language. */
    private fun bestMatch(locale: Locale, tags: List<String>): String? {
        val wanted = locale.toLanguageTag()
        tags.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        val sameLanguage = tags.filter { Locale.forLanguageTag(it).language == locale.language }
        return sameLanguage.firstOrNull { Locale.forLanguageTag(it).country == "US" } ?: sameLanguage.firstOrNull()
    }

    private fun intentFor(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
    }

    /** The offline model for [languageTag] is downloading; try again when it finishes. */
    class LanguageDownloadingException(val languageTag: String) : Exception("Downloading $languageTag")

    private class OfflineLanguageMissing : Exception()

    private suspend fun recognize(
        recognizer: SpeechRecognizer,
        samples: FloatArray,
        phrase: IntRange,
        languageTag: String,
        preferOffline: Boolean,
    ): String? {
        // 16-bit little endian PCM, with a little silence at the end so the phrase is closed.
        val count = phrase.count() + TAIL_SAMPLES
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in phrase) bytes.putShort((samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "caption-audio") {
            try {
                // The recognizer's end-of-speech detection follows the clock, so feed the audio
                // in small chunks at (a multiple of) the speed a microphone would.
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
                    val data = bytes.array()
                    val chunk = RATE / 50 * 2
                    var offset = 0
                    while (offset < data.size) {
                        val n = minOf(chunk, data.size - offset)
                        stream.write(data, offset, n)
                        offset += n
                        Thread.sleep(20L / FEED_SPEEDUP)
                    }
                }
            } catch (_: IOException) {
                // The recognizer stopped reading; nothing else to do.
            }
        }
        var lastPartial: String? = null
        return try {
            withTimeoutOrNull(PHRASE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle) {
                            // Some recognizers return the final words only as the last partial result.
                            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                                ?.takeIf { it.isNotBlank() } ?: lastPartial
                            if (continuation.isActive) continuation.resume(text)
                        }

                        override fun onError(error: Int) {
                            if (!continuation.isActive) return
                            when (error) {
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                -> continuation.resumeWithException(
                                    if (preferOffline) OfflineLanguageMissing() else UnavailableException("Recognizer error $error"),
                                )
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                                -> continuation.resumeWithException(UnavailableException("Recognizer error $error"))
                                // No words in this phrase (music, noise): skip it.
                                else -> continuation.resume(null)
                            }
                        }

                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!partial.isNullOrBlank()) lastPartial = partial
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    val intent = intentFor(languageTag).apply {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                    }
                    continuation.invokeOnCancellation { recognizer.cancel() }
                    recognizer.startListening(intent)
                }
            }.also { if (it == null) recognizer.cancel() }
        } finally {
            pipe[0].close()
        }
    }

    /** Capital first letter and no trailing spaces, as subtitles are usually written. */
    private fun tidy(text: String): String =
        text.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    private companion object {
        const val RATE = 16_000
        const val TIMEOUT_US = 10_000L
        const val TAIL_SAMPLES = RATE / 2
        const val PHRASE_TIMEOUT_MS = 20_000L
        /** How many times faster than real time the audio is fed. */
        const val FEED_SPEEDUP = 4L
    }
}

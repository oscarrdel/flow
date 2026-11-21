package com.rainyworks.flow.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Result of a finished recording. */
data class Recording(val file: File, val durationUs: Long)

/**
 * Records the microphone to a mono 16-bit WAV file. One recording at a time; [level]
 * reports the current peak (0..1) for a live meter.
 */
class VoiceRecorder(private val context: Context) {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _elapsedUs = MutableStateFlow(0L)
    val elapsedUs: StateFlow<Long> = _elapsedUs.asStateFlow()

    private var job: Job? = null
    private var output: File? = null
    private var writer: WavWriter? = null
    private var discard = false

    val isRecording: Boolean get() = job?.isActive == true

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts recording into [file]. Returns false when the microphone is unavailable. */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, file: File): Boolean {
        if (isRecording || !hasPermission()) return false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 10 * WavWriter.BYTES_PER_SAMPLE)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return false
        }
        val wav = WavWriter(file, SAMPLE_RATE, 1)
        output = file
        writer = wav
        discard = false
        _elapsedUs.value = 0L
        record.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            try {
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    wav.write(buffer, read)
                    _level.value = peak(buffer, read)
                    _elapsedUs.value = wav.durationUs
                }
            } finally {
                record.stop()
                record.release()
                _level.value = 0f
            }
        }
        return true
    }

    /** Stops and returns the recording, or null if nothing usable was captured. */
    suspend fun stop(): Recording? {
        val running = job ?: return null
        running.cancel()
        running.join()
        job = null
        return withContext(Dispatchers.IO) {
            val wav = writer ?: return@withContext null
            val file = output ?: return@withContext null
            val duration = wav.durationUs
            wav.close()
            writer = null
            if (discard || duration < MIN_DURATION_US) {
                file.delete()
                null
            } else {
                Recording(file, duration)
            }
        }
    }

    /** Stops and deletes the current recording. */
    suspend fun cancel() {
        discard = true
        stop()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val MIN_DURATION_US = 200_000L

        internal fun peak(buffer: ByteArray, length: Int): Float {
            var max = 0
            var i = 0
            while (i + 1 < length) {
                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                max = maxOf(max, abs(sample.toShort().toInt()))
                i += 2
            }
            return (max / 32768f).coerceIn(0f, 1f)
        }
    }
}

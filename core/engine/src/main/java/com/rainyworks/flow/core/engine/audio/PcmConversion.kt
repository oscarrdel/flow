package com.rainyworks.flow.core.engine.audio

import android.media.AudioFormat.ENCODING_PCM_16BIT
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.rainyworks.flow.core.mp3.Mp3Encoder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun convertAac(
    source: File,
    destination: File,
    settings: AudioExportSettings,
    durationUs: Long,
    progress: (Float) -> Unit,
) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(source.path)
        val format = extractor.getTrackFormat(0)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        check(rate == settings.sampleRate) { "El codificador AAC entregó $rate Hz en vez de ${settings.sampleRate} Hz." }
        check(channels in 1..2) { "La mezcla AAC contiene $channels canales." }
        extractor.selectTrack(0)
        val frameSize = channels * Short.SIZE_BYTES
        val targetFrames = (durationUs / 1_000_000.0 * rate).roundToLong().coerceAtLeast(1)
        RandomAccessFile(destination, "rw").use { out ->
            val mp3 = if (settings.format == AudioFormat.MP3) Mp3Encoder(rate, channels, settings.bitrateKbps) else null
            mp3.use { encoder ->
                if (encoder == null) out.seek(44)
                var framesWritten = 0L
                var samples = ShortArray(0)
                var lastPercent = -1
                decodePcm(extractor, format, rate, channels) { pcm ->
                    check(pcm.remaining() % frameSize == 0) { "El decodificador entregó una trama PCM incompleta." }
                    // El último paquete AAC puede contener relleno hasta completar 1024 muestras.
                    val frames = minOf((pcm.remaining() / frameSize).toLong(), targetFrames - framesWritten).toInt()
                    if (frames == 0) return@decodePcm
                    pcm.limit(pcm.position() + frames * frameSize)
                    if (encoder == null) {
                        if ((framesWritten + frames) * frameSize > 0xffff_ffffL - 36) {
                            throw IOException("La mezcla supera el tamaño máximo de un archivo WAV RIFF (4 GiB).")
                        }
                        while (pcm.hasRemaining()) out.channel.write(pcm)
                    } else {
                        val count = frames * channels
                        if (samples.size < count) samples = ShortArray(count)
                        pcm.asShortBuffer().get(samples, 0, count)
                        out.write(encoder.encode(samples, frames))
                    }
                    framesWritten += frames
                    val percent = (100.0 * framesWritten / targetFrames).toInt()
                    if (percent > lastPercent) {
                        progress(percent / 100f)
                        lastPercent = percent
                    }
                }
                check(framesWritten > 0) { "No se recibieron muestras de audio." }
                if (encoder == null) {
                    writeWavHeader(out, rate, channels, framesWritten * frameSize)
                } else {
                    out.write(encoder.flush())
                    encoder.writeFinalTag(out)
                }
            }
        }
    } finally {
        extractor.release()
    }
}

private suspend fun decodePcm(
    extractor: MediaExtractor,
    format: MediaFormat,
    rate: Int,
    channels: Int,
    consume: (ByteBuffer) -> Unit,
) {
    val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
    try {
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        val context = currentCoroutineContext()
        var inputDone = false
        while (true) {
            context.ensureActive()
            val input = if (inputDone) -1 else decoder.dequeueInputBuffer(10_000)
            if (input >= 0) {
                val size = extractor.readSampleData(checkNotNull(decoder.getInputBuffer(input)), 0)
                if (size < 0) {
                    decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            val output = decoder.dequeueOutputBuffer(info, 10_000)
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val pcm = decoder.outputFormat
                check(pcm.getInteger(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT) == ENCODING_PCM_16BIT &&
                    pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate &&
                    pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels) {
                    "El formato PCM del decodificador AAC es incompatible."
                }
            } else if (output >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = checkNotNull(decoder.getOutputBuffer(output)).order(ByteOrder.LITTLE_ENDIAN)
                        data.limit(info.offset + info.size)
                        data.position(info.offset)
                        consume(data)
                    }
                } finally {
                    decoder.releaseOutputBuffer(output, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    } finally {
        decoder.release()
    }
}

private fun writeWavHeader(out: RandomAccessFile, rate: Int, channels: Int, size: Long) {
    val frameSize = channels * Short.SIZE_BYTES
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".encodeToByteArray())
        putInt((36 + size).toInt())
        put("WAVEfmt ".encodeToByteArray())
        putInt(16)
        putShort(1) // WAVE_FORMAT_PCM
        putShort(channels.toShort())
        putInt(rate)
        putInt(rate * frameSize)
        putShort(frameSize.toShort())
        putShort(16)
        put("data".encodeToByteArray())
        putInt(size.toInt())
    }
    out.seek(0)
    out.write(header.array())
}

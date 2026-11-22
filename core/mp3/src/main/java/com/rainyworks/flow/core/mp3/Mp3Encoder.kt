package com.rainyworks.flow.core.mp3

import java.io.RandomAccessFile

/** Codifica PCM de 16 bits entrelazado. Escribe los bloques en orden, luego [flush] y [writeFinalTag]. */
class Mp3Encoder(
    sampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int = 192,
    quality: Int = 2,
) : AutoCloseable {
    private var handle: Long
    private var flushed = false

    init {
        require(channels in 1..2) { "MP3 requiere uno o dos canales." }
        require(quality in 0..9) { "La calidad LAME debe estar entre 0 y 9." }
        val bitrates = when (sampleRate) {
            32_000, 44_100, 48_000 -> 32..320
            16_000, 22_050, 24_000 -> 8..160
            8_000, 11_025, 12_000 -> 8..64
            else -> throw IllegalArgumentException("Frecuencia MP3 incompatible: $sampleRate Hz.")
        }
        require(bitrateKbps in bitrates) { "Para $sampleRate Hz, el bitrate MP3 debe estar en $bitrates kbps." }
        // Las tasas intermedias y las tramas CBR demasiado pequeñas para Info usan ABR con Xing.
        val useAbr = bitrateKbps !in setOf(64, 80, 96, 112, 128, 160, 192, 224, 256, 320) &&
            !(sampleRate in 16_000..24_000 && bitrateKbps == 144)
        try {
            System.loadLibrary("flowmp3")
            handle = nativeOpen(sampleRate, channels, bitrateKbps, quality, useAbr)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("No se pudo cargar el codificador MP3 en este dispositivo.", e)
        }
    }

    /** [frames] cuenta muestras por canal, no el número total de elementos de [pcm]. */
    @Synchronized
    fun encode(pcm: ShortArray, frames: Int): ByteArray {
        check(handle != 0L && !flushed) { "El codificador MP3 ya se cerró o finalizó." }
        require(frames in 0..pcm.size / channels) { "El bloque PCM no contiene los frames indicados." }
        return if (frames == 0) byteArrayOf() else nativeEncode(handle, pcm, frames)
    }

    @Synchronized
    fun flush(): ByteArray {
        check(handle != 0L && !flushed) { "El codificador MP3 ya se cerró o finalizó." }
        flushed = true
        return nativeFlush(handle)
    }

    /** Reemplaza la primera trama reservada por LAME. El flujo MP3 debe empezar en el byte cero. */
    @Synchronized
    fun writeFinalTag(file: RandomAccessFile) {
        check(handle != 0L && flushed) { "Escribe flush() antes de finalizar la etiqueta MP3." }
        val tag = nativeTag(handle)
        require(file.length() >= tag.size) { "El archivo está incompleto." }
        val position = file.filePointer
        try {
            file.seek(0)
            file.write(tag)
        } finally {
            file.seek(position)
        }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeOpen(rate: Int, channels: Int, kbps: Int, quality: Int, abr: Boolean): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int): ByteArray
    private external fun nativeFlush(handle: Long): ByteArray
    private external fun nativeTag(handle: Long): ByteArray
    private external fun nativeClose(handle: Long)
}

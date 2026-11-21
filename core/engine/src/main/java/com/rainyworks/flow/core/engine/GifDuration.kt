package com.rainyworks.flow.core.engine

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Cuenta una vuelta de la animación sin descomprimir los fotogramas. */
internal fun gifDurationUs(input: InputStream): Long {
    val data = DataInputStream(input.buffered())
    val header = ByteArray(6).also(data::readFully).decodeToString()
    if (header != "GIF89a" && header != "GIF87a") return 0
    data.skipFully(4)
    val screenFlags = data.readUnsignedByte()
    data.skipFully(2)
    data.skipColorTable(screenFlags)
    var durationUs = 0L
    var delayUs = 0L
    while (true) {
        when (data.readUnsignedByte()) {
            0x21 -> {
                if (data.readUnsignedByte() == 0xf9) {
                    if (data.readUnsignedByte() != 4) return 0
                    data.readUnsignedByte()
                    val hundredths = data.readUnsignedByte() or (data.readUnsignedByte() shl 8)
                    delayUs = hundredths * 10_000L
                    data.skipFully(2)
                } else {
                    data.skipSubBlocks()
                }
            }
            0x2c -> {
                data.skipFully(8)
                data.skipColorTable(data.readUnsignedByte())
                data.readUnsignedByte()
                data.skipSubBlocks()
                durationUs += delayUs
                delayUs = 0
            }
            0x3b -> return durationUs
            else -> return 0
        }
    }
}

private fun DataInputStream.skipColorTable(flags: Int) {
    if (flags and 0x80 != 0) skipFully(3 * (2 shl (flags and 7)))
}

private fun DataInputStream.skipSubBlocks() {
    var size = readUnsignedByte()
    while (size != 0) {
        skipFully(size)
        size = readUnsignedByte()
    }
}

private fun DataInputStream.skipFully(count: Int) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skipBytes(remaining)
        if (skipped == 0) {
            if (read() == -1) throw EOFException()
            remaining--
        } else {
            remaining -= skipped
        }
    }
}

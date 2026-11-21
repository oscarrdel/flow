package com.rainyworks.flow.core.engine

import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.google.common.collect.ImmutableList

@OptIn(UnstableApi::class)
internal object GoldfishAvc {
    private const val DECODER = "c2.goldfish.h264.decoder"
    val affectedDevice get() = Build.VERSION.SDK_INT == 37 && Build.HARDWARE == "ranchu"

    val selector = MediaCodecSelector { mime, secure, tunneled ->
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneled)
        if (mime == MimeTypes.VIDEO_H264 && !secure && !tunneled) preferWorkingDecoder(infos) else infos
    }

    private fun preferWorkingDecoder(infos: List<MediaCodecInfo>): List<MediaCodecInfo> {
        if (infos.firstOrNull()?.name != DECODER || infos.none { it.softwareOnly }) return infos
        return infos.sortedByDescending { it.softwareOnly }
    }

    fun onPreviewFailure(error: Throwable) {
        if (!affectedDevice) return
        val failedDecoder = generateSequence(error) { it.cause }
            .filterIsInstance<MediaCodecDecoderException>().firstOrNull()?.codecInfo
        if (failedDecoder?.name != DECODER) return

        // CompositionPlayer 1.11.1 fija MediaCodecSelector.DEFAULT en SequenceVideoRenderer.
        // Cambiar sólo su entrada AVC normal, bajo el mismo monitor que getDecoderInfos().
        try {
            synchronized(MediaCodecUtil::class.java) {
                val infos = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
                val ordered = preferWorkingDecoder(infos)
                if (ordered === infos) return
                val field = MediaCodecUtil::class.java.getDeclaredField("decoderInfosCache")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val cache = field.get(null) as MutableMap<*, List<MediaCodecInfo>>
                cache.entries.single { it.value === infos }.setValue(ImmutableList.copyOf(ordered))
            }
        } catch (failure: Exception) {
            Log.w("FlowPreview", "No se pudo cambiar de decodificador AVC en el emulador.", failure)
        }
    }
}

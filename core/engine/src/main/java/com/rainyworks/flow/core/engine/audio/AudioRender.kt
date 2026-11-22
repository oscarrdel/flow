package com.rainyworks.flow.core.engine.audio

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.rainyworks.flow.core.engine.CompositionFactory
import com.rainyworks.flow.core.model.Project
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(UnstableApi::class)
internal fun audioComposition(project: Project, sampleRate: Int): Composition {
    val original = CompositionFactory.build(project, forExport = true)
    val clips = project.tracks.flatMap { it.clips }.associateBy { it.id }
    val sequences = original.sequences.filter { C.TRACK_TYPE_AUDIO in it.trackTypes }.map { sequence ->
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        for (item in sequence.editedMediaItems) {
            if (item.mediaItem.localConfiguration == null) {
                builder.addSilence(item.durationUs)
            } else if (item.removeAudio) {
                // Una imagen o un clip silenciado ocupa su duración editada en la mezcla.
                builder.addSilence(clips.getValue(item.mediaItem.mediaId).durationUs)
            } else {
                builder.addItem(item.buildUpon()
                    .setRemoveVideo(true)
                    .setEffects(Effects(item.effects.audioProcessors, emptyList()))
                    .build())
            }
        }
        builder.setIsLooping(sequence.isLooping).build()
    }.ifEmpty {
        listOf(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addSilence(project.durationUs).build())
    }
    val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(sampleRate) }
    return Composition.Builder(sequences)
        .setEffects(Effects(original.effects.audioProcessors + resampler, emptyList()))
        .build()
}

// Transformer puede drenar un hueco entero antes de atender la cancelación.
@OptIn(UnstableApi::class)
private fun EditedMediaItemSequence.Builder.addSilence(durationUs: Long) = apply {
    var remainingUs = durationUs
    while (remainingUs > 0) {
        val partUs = minOf(remainingUs, 10_000_000L)
        addGap(partUs)
        remainingUs -= partUs
    }
}

@OptIn(UnstableApi::class)
internal suspend fun renderAac(
    context: Context,
    composition: Composition,
    destination: File,
    settings: AudioExportSettings,
    progress: (Float) -> Unit,
) {
    val thread = HandlerThread("Flow-AAC").apply { start() }
    try {
        withContext(Handler(thread.looper).asCoroutineDispatcher()) {
            runTransformer(context, composition, destination, settings, progress)
        }
    } finally {
        thread.quitSafely()
    }
}

@OptIn(UnstableApi::class)
private suspend fun runTransformer(
    context: Context,
    composition: Composition,
    destination: File,
    settings: AudioExportSettings,
    progress: (Float) -> Unit,
) {
    val completed = CompletableDeferred<Unit>()
    val bitrate = when (settings.format) {
        AudioFormat.M4A -> settings.bitrateKbps * 1000
        else -> minOf(320_000, settings.sampleRate * 6)
    }
    val encoderFactory = DefaultEncoderFactory.Builder(context)
        .setEnableFallback(false)
        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrate).build())
        .build()
    val transformer = Transformer.Builder(context)
        .setLooper(checkNotNull(Looper.myLooper()))
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setEncoderFactory(encoderFactory)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (exportResult.sampleRate == settings.sampleRate) {
                    completed.complete(Unit)
                } else {
                    completed.completeExceptionally(IllegalStateException(
                        "El codificador AAC entregó ${exportResult.sampleRate} Hz en vez de ${settings.sampleRate} Hz.",
                    ))
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                completed.completeExceptionally(exportException)
            }
        }).build()
    val holder = ProgressHolder()
    try {
        transformer.start(composition, destination.path)
        while (withTimeoutOrNull(200L) { completed.await() } == null) {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                progress(holder.progress.coerceIn(0, 100) / 100f)
            }
        }
    } finally {
        if (!completed.isCompleted) transformer.cancel()
    }
}

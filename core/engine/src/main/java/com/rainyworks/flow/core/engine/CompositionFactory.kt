package com.rainyworks.flow.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Effect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.rainyworks.flow.core.engine.audiofx.AudioFx
import com.rainyworks.flow.core.engine.color.ColorEffects
import com.rainyworks.flow.core.engine.compositing.LayerBlendCompositor
import com.rainyworks.flow.core.engine.compositing.addCanvasGap
import com.rainyworks.flow.core.engine.compositing.clipVideoEffects
import com.rainyworks.flow.core.engine.color.LutCache
import com.rainyworks.flow.core.engine.text.TextOverlays
import com.rainyworks.flow.core.engine.text.TextRenderer
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.Track
import com.rainyworks.flow.core.model.TrackKind
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
object CompositionFactory {
    fun build(project: Project, forExport: Boolean): Composition =
        if (forExport) build(project, ExportSettings())
        else compose(project, ExportSettings(heightPx = 720), titles = null)

    /**
     * [titles] draws text clips over the final frame. The editor preview leaves it null
     * because it draws titles itself for instant feedback while typing.
     */
    fun build(project: Project, settings: ExportSettings, titles: TextRenderer? = null): Composition {
        require(project.durationUs > 0) { "El proyecto está vacío." }
        return compose(project, settings, titles)
    }

    private fun compose(project: Project, settings: ExportSettings, titles: TextRenderer?): Composition {
        val canvas = Size(settings.width(project), settings.height(project))
        val durationUs = project.durationUs.coerceAtLeast(1_000L)
        val overlays = project.tracks.filter {
            it.kind == TrackKind.OVERLAY && !it.hidden && it.clips.any { clip -> clip.hasPicture }
        }.asReversed()
        // Media3 dibuja la primera secuencia de video encima de las siguientes.
        val hasVideoLayers = overlays.isNotEmpty()
        val sequences = overlays.map { sequence(it, durationUs, canvas, settings.frameRate, hasVideoLayers) }.toMutableList()
        sequences += sequence(project.mainTrack, durationUs, canvas, settings.frameRate, hasVideoLayers)
        project.tracks.filter {
            (it.kind == TrackKind.AUDIO || (it.kind == TrackKind.OVERLAY && it !in overlays)) &&
                !it.hidden && it.clips.any(it::isAudible)
        }.forEach { sequences += sequence(it, durationUs, canvas, settings.frameRate, hasVideoLayers, audioOnly = true) }
        // Overlays first (foreground), main track last, matching the video sequence order.
        val layers = LayerBlendCompositor(canvas, overlays + project.mainTrack)

        val textOverlays = titles?.let { TextOverlays.forProject(project, it, canvas.width, canvas.height) }.orEmpty()
        val finalEffects = buildList<Effect> {
            layers.effect?.let(::add)
            add(fit(canvas))
            if (textOverlays.isNotEmpty()) add(OverlayEffect(textOverlays))
        }
        val builder = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), finalEffects))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        builder.setVideoCompositorSettings(layers)
        return builder.build()
    }

    private fun sequence(
        track: Track,
        durationUs: Long,
        canvas: Size,
        fps: Int,
        hasVideoLayers: Boolean,
        audioOnly: Boolean = false,
    ): EditedMediaItemSequence {
        val types = buildSet {
            if (!audioOnly) add(C.TRACK_TYPE_VIDEO)
            if (audioOnly || (!track.hidden && track.clips.any(track::isAudible))) add(C.TRACK_TYPE_AUDIO)
        }
        val sequence = EditedMediaItemSequence.Builder(types)
        var cursorUs = 0L
        for (clip in track.clips.sortedBy { it.startUs }) {
            val audible = !track.hidden && track.isAudible(clip)
            val visible = !audioOnly && !track.hidden && clip.hasPicture
            val media = clip.media ?: continue
            if (!audible && !visible) continue
            require(clip.startUs >= cursorUs) { "Hay clips superpuestos en la misma pista." }
            if (clip.startUs > cursorUs) gap(sequence, types, clip.startUs - cursorUs, canvas)
            sequence.addItem(editedItem(clip, media, fps, audible, visible, fit(canvas), hasVideoLayers))
            cursorUs = clip.endUs
        }
        if (cursorUs < durationUs) gap(sequence, types, durationUs - cursorUs, canvas)
        return sequence.build()
    }

    /** Video-only sequences keep a canvas-sized texture across gaps, as the layer compositor expects. */
    private fun gap(sequence: EditedMediaItemSequence.Builder, types: Set<Int>, durationUs: Long, canvas: Size) {
        if (types == setOf(C.TRACK_TYPE_VIDEO)) sequence.addCanvasGap(durationUs, canvas) else sequence.addGap(durationUs)
    }

    private fun editedItem(
        clip: Clip,
        media: MediaRef,
        fps: Int,
        audible: Boolean,
        visible: Boolean,
        presentation: Presentation,
        hasVideoLayers: Boolean,
    ): EditedMediaItem {
        val image = media.type == MediaType.IMAGE || media.type == MediaType.GIF
        val item = MediaItem.Builder().setUri(media.uri).setMediaId(clip.id)
        if (media.mimeType.isNotBlank()) item.setMimeType(media.mimeType)
        val durationUs = if (image) {
            // Una imagen estática ya puede ocupar su duración final, incluso con velocidad distinta de 1.
            item.setImageDurationMs(((clip.durationUs + 500) / 1_000).coerceAtLeast(1))
            clip.durationUs
        } else {
            item.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.trimInUs)
                    .setEndPositionUs(clip.trimOutUs)
                    .build(),
            )
            maxOf(media.durationUs, clip.trimOutUs)
        }
        val processors = if (media.hasAudio || media.type == MediaType.AUDIO) {
            // CompositionPlayer puede seleccionar el audio de un clip silenciado en una secuencia mixta.
            // The clip volume (including keyframes and fades) is applied by AudioFx, so the mixer stays at unity.
            buildList<AudioProcessor> {
                add(ToInt16PcmAudioProcessor())
                add(stereoMixer(if (audible) 1f else 0f))
                if (audible) addAll(AudioFx.processorsFor(clip))
            }
        } else emptyList()
        val builder = EditedMediaItem.Builder(item.build())
            .setDurationUs(durationUs)
            .setFrameRate(fps)
            .setRemoveAudio(!audible)
            .setRemoveVideo(!visible)
            .setEffects(
                Effects(
                    processors,
                    if (visible) {
                        clipVideoEffects(clip, ColorEffects.forGrade(clip.color, LutCache::load), presentation, hasVideoLayers)
                    } else {
                        emptyList()
                    },
                ),
            )
        if (!image && clip.speed != 1f) {
            builder.setSpeed(AudioFx.speedParametersFor(clip))
        }
        return builder.build()
    }

    private fun stereoMixer(volume: Float) = ChannelMixingAudioProcessor().apply {
        for (channels in 1..6) {
            val matrix = if (channels <= 2) ChannelMixingMatrix.createForConstantGain(channels, 2)
            else ChannelMixingMatrix.createForConstantPower(channels, 2)
            putChannelMixingMatrix(matrix.scaleBy(volume))
        }
        // En 6.1 el canal trasero central se reparte. En 7.1 hay un par trasero independiente.
        for (channels in 7..8) {
            val coefficients = floatArrayOf(
                1f, 0f, 0f, 1f, 0.707f, 0.707f, 0.5f, 0.5f, 0.707f, 0f, 0f, 0.707f,
            ) + if (channels == 7) floatArrayOf(0.5f, 0.5f) else floatArrayOf(0.707f, 0f, 0f, 0.707f)
            putChannelMixingMatrix(ChannelMixingMatrix(channels, 2, coefficients).scaleBy(volume))
        }
    }

    private val Clip.hasPicture: Boolean get() =
        kind != ClipKind.AUDIO && media?.hasVideo == true

    private fun fit(size: Size) =
        Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT)
}

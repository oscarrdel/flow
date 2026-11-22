package com.rainyworks.flow.core.engine.audiofx

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.VoicePreset
import kotlin.math.pow

/**
 * In CompositionFactory.editedItem, build the audio list for each item with an audio track:
 *
 *     buildList<AudioProcessor> {
 *         add(ToInt16PcmAudioProcessor())
 *         add(stereoMixer(if (audible) 1f else 0f))
 *         if (audible) addAll(AudioFx.processorsFor(clip))
 *     }
 *
 * Keep setRemoveAudio(!audible) and the existing media and video checks. Replace the existing
 * constant-speed builder.setSpeed(provider) with builder.setSpeed(AudioFx.speedParametersFor(clip)).
 * Media3 1.11.1 applies speed before the item's Effects. Its SpeedProvider overload sets
 * shouldMaintainPitch to false. Sonic here changes pitch independently of that speed step.
 *
 * A volume keyframe overrides the base volume. When integrating, Track.isAudible must allow clips
 * with positive volume keys even when the base volume is zero, while respecting track/clip mute.
 *
 * Frame zero in a full export corresponds to trimInUs in the source and startUs on the timeline.
 * At constant speed s, frame n at rate r represents n/r seconds of playout and s*n/r seconds of
 * source. After a seek the frame clock resumes at the item's playout offset in StreamMetadata.
 * Echo and reverb end with the item, so the last gain stage fades their tails as well as the dry audio.
 */
@OptIn(UnstableApi::class)
object AudioFx {
    fun processorsFor(clip: Clip): List<AudioProcessor> = buildList {
        if (clip.muted) {
            add(ClipGainProcessor(clip))
            return@buildList
        }
        val fx = clip.audioEffects
        val pitch = 2.0.pow(fx.pitchSemitones / 12.0).toFloat()
        require(pitch.isFinite() && pitch > 0f) { "Pitch must give a finite, positive frequency ratio" }
        if (pitch != 1f) {
            add(SonicAudioProcessor().apply { setPitch(pitch) })
        }
        when (fx.preset) {
            VoicePreset.NONE -> Unit
            VoicePreset.ROOM -> add(ReverbProcessor(0.24f, shortRoom = true))
            else -> add(VoiceProcessor(fx.preset))
        }
        if (fx.equalizer.take(EqualizerProcessor.FREQUENCIES.size).any { it != 0f }) {
            add(EqualizerProcessor(fx.equalizer))
        }
        if (fx.echo > 0f) add(EchoProcessor(fx.echo))
        if (fx.reverb > 0f) add(ReverbProcessor(fx.reverb))
        if (ClipGainProcessor.needsGain(clip)) add(ClipGainProcessor(clip))
    }

    /** Supply to EditedMediaItem.Builder.setSpeed for the constant-speed path in editedItem. */
    fun speedParametersFor(clip: Clip): SpeedParameters = SpeedParameters(
        object : SpeedProvider {
            override fun getSpeed(timeUs: Long) = clip.speed
            override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
        },
        clip.preservePitch,
    )
}

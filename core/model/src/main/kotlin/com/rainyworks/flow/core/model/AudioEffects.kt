package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable

enum class VoicePreset { NONE, RADIO, PHONE, MEGAPHONE, ROOM }

@Serializable
data class AudioEffects(
    /** Gains in dB for the 10-band equalizer (31 Hz .. 16 kHz). */
    val equalizer: List<Float> = emptyList(),
    /** Pitch shift in semitones, independent of speed. */
    val pitchSemitones: Float = 0f,
    /** 0..1 */
    val echo: Float = 0f,
    /** 0..1 */
    val reverb: Float = 0f,
    val preset: VoicePreset = VoicePreset.NONE,
) {
    val isIdentity: Boolean get() = this == AudioEffects()
}

package com.rainyworks.flow.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class TrackKind {
    /** Magnetic primary track: clips are laid out back to back with no gaps. */
    MAIN,
    OVERLAY,
    TEXT,
    AUDIO,
    EFFECT,
    ADJUSTMENT,
}

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
    val locked: Boolean = false,
    val hidden: Boolean = false,
) {
    val endUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0L

    val isMagnetic: Boolean get() = kind == TrackKind.MAIN

    fun clipAt(timeUs: Long): Clip? = clips.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }

    companion object {
        fun main(): Track = Track(id = "main", kind = TrackKind.MAIN)
        fun new(kind: TrackKind): Track = Track(id = UUID.randomUUID().toString(), kind = kind)
    }
}

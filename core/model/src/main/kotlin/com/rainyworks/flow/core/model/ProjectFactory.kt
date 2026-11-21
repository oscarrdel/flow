package com.rainyworks.flow.core.model

object ProjectFactory {
    fun create(name: String, nowMs: Long, id: String = Edits.newId()): Project =
        Project(id = id, name = name, createdAtMs = nowMs, updatedAtMs = nowMs)

    fun clipFor(media: MediaRef, imageDurationUs: Long = DEFAULT_IMAGE_DURATION_US): Clip = when (media.type) {
        MediaType.VIDEO -> Clip(id = Edits.newId(), kind = ClipKind.VIDEO, media = media, trimOutUs = media.durationUs)
        MediaType.AUDIO -> Clip(id = Edits.newId(), kind = ClipKind.AUDIO, media = media, trimOutUs = media.durationUs)
        MediaType.GIF -> Clip(
            id = Edits.newId(),
            kind = ClipKind.IMAGE,
            media = media,
            trimOutUs = media.durationUs.takeIf { it > 0L } ?: imageDurationUs,
        )
        MediaType.IMAGE -> Clip(id = Edits.newId(), kind = ClipKind.IMAGE, media = media, trimOutUs = imageDurationUs)
    }

    const val DEFAULT_IMAGE_DURATION_US = 3_000_000L
}

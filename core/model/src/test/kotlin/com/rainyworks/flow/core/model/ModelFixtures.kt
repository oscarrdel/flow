package com.rainyworks.flow.core.model

internal fun testProject(vararg layers: Track, main: Track = Track.main()): Project =
    Project(
        id = "project",
        name = "Proyecto de prueba",
        createdAtMs = 100L,
        updatedAtMs = 200L,
        tracks = listOf(main) + layers,
    )

internal fun testVideo(
    id: String,
    startUs: Long = 0L,
    trimInUs: Long = 0L,
    trimOutUs: Long = 1_000_000L,
): Clip = Clip(
    id = id,
    kind = ClipKind.VIDEO,
    media = MediaRef(
        uri = "content://flow/$id",
        type = MediaType.VIDEO,
        durationUs = 3_000_000L,
        width = 1920,
        height = 1080,
    ),
    startUs = startUs,
    trimInUs = trimInUs,
    trimOutUs = trimOutUs,
)

internal fun testLayer(id: String, vararg clips: Clip): Track =
    Track(id = id, kind = TrackKind.OVERLAY, clips = clips.toList())

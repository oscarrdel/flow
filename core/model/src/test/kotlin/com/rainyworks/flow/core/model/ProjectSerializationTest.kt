package com.rainyworks.flow.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSerializationTest {
    @Test
    fun projectRoundTripsWithEveryPolymorphicCanvasBackground() {
        val backgrounds = mapOf(
            "solid" to CanvasBackground.Solid(0xFF112233),
            "gradient" to CanvasBackground.Gradient(0xFF112233, 0x80445566, angleDegrees = 45f),
            "image" to CanvasBackground.Image("content://flow/background"),
            "blur" to CanvasBackground.Blur(radius = 0.75f),
        )
        val baseProject = populatedProject()

        for ((type, background) in backgrounds) {
            val project = baseProject.copy(canvas = baseProject.canvas.copy(background = background))

            val encoded = Json.encodeToString(Project.serializer(), project)
            val canvas = Json.parseToJsonElement(encoded).jsonObject.getValue("canvas").jsonObject
            val encodedBackground = canvas.getValue("background").jsonObject

            assertEquals(type, encodedBackground.getValue("type").jsonPrimitive.content)
            assertEquals(project, Json.decodeFromString(Project.serializer(), encoded))
        }
    }

    @Test
    fun defaultProjectRoundTripsWithDefaultFieldsOmitted() {
        val project = testProject()

        val encoded = Json.encodeToString(Project.serializer(), project)

        assertEquals(setOf("id", "name", "createdAtMs", "updatedAtMs"), Json.parseToJsonElement(encoded).jsonObject.keys)
        assertEquals(project, Json.decodeFromString(Project.serializer(), encoded))
    }

    private fun populatedProject(): Project {
        val video = testVideo("video", trimInUs = 200_000L, trimOutUs = 2_000_000L).let {
            it.copy(
                media = requireNotNull(it.media).copy(
                    rotationDegrees = 90,
                    hasAudio = true,
                    displayName = "Vídeo de prueba",
                    sizeBytes = 4_000_000_000L,
                    mimeType = "video/mp4",
                ),
                speed = 1.5f,
                reversed = true,
                volume = 0.65f,
                muted = true,
                fadeInUs = 50_000L,
                fadeOutUs = 100_000L,
                opacity = 0.8f,
                transform = Transform(
                    offsetX = 0.1f,
                    offsetY = -0.2f,
                    scale = 1.25f,
                    rotationDegrees = 15f,
                    flipHorizontal = true,
                    flipVertical = true,
                    crop = CropRect(0.1f, 0.2f, 0.8f, 0.9f),
                ),
            )
        }
        val title = Clip(id = "title", kind = ClipKind.TEXT, startUs = 300_000L, trimOutUs = 600_000L)
        val layer = Track(id = "titles", kind = TrackKind.TEXT, clips = listOf(title), muted = true, locked = true, hidden = true)
        return testProject(layer, main = Track.main().copy(clips = listOf(video))).copy(
            name = "Edición final",
            canvas = Canvas(aspectRatio = AspectRatio.R4_5, followFirstClip = false),
            markers = listOf(Marker("marker", 450_000L, "Introducción")),
        )
    }
}

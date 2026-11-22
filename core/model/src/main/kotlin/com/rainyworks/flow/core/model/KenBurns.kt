package com.rainyworks.flow.core.model

/** Slow zoom and pan across a still picture over the whole clip. */
enum class KenBurnsStyle { ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN }

object KenBurns {
    private const val ZOOM = 1.25f
    /** Panning zooms in a bit so the picture keeps covering the frame while it moves. */
    private const val PAN_ZOOM = 1.2f
    private const val PAN_X = 0.08f
    private const val PAN_Y = 0.07f

    /**
     * Replaces the clip's motion keyframes with a start and an end keyframe for [style],
     * based on its current transform. With null, the motion keyframes are removed.
     */
    fun apply(project: Project, clipId: String, style: KenBurnsStyle?): Project =
        Edits.updateClip(project, clipId) { clip ->
            val cleared = clip.keyframes - MediaEdits.MOTION_PROPERTIES.toSet()
            if (style == null) return@updateClip clip.copy(keyframes = cleared)
            val t = clip.transform
            val (start, end) = poses(style, t.offsetX, t.offsetY, t.scale)
            val endUs = clip.durationUs
            fun pair(a: Float, b: Float) = listOf(Keyframe(0L, a, Easing.EASE_IN_OUT), Keyframe(endUs, b, Easing.EASE_IN_OUT))
            clip.copy(
                keyframes = cleared + mapOf(
                    AnimatedProperty.OFFSET_X to pair(start.x, end.x),
                    AnimatedProperty.OFFSET_Y to pair(start.y, end.y),
                    AnimatedProperty.SCALE to pair(start.scale, end.scale),
                    AnimatedProperty.ROTATION to pair(t.rotationDegrees, t.rotationDegrees),
                    AnimatedProperty.OPACITY to pair(clip.opacity, clip.opacity),
                ),
            )
        }

    private data class Pose(val x: Float, val y: Float, val scale: Float)

    private fun poses(style: KenBurnsStyle, x: Float, y: Float, scale: Float): Pair<Pose, Pose> = when (style) {
        KenBurnsStyle.ZOOM_IN -> Pose(x, y, scale) to Pose(x, y, scale * ZOOM)
        KenBurnsStyle.ZOOM_OUT -> Pose(x, y, scale * ZOOM) to Pose(x, y, scale)
        KenBurnsStyle.PAN_LEFT -> Pose(x + PAN_X, y, scale * PAN_ZOOM) to Pose(x - PAN_X, y, scale * PAN_ZOOM)
        KenBurnsStyle.PAN_RIGHT -> Pose(x - PAN_X, y, scale * PAN_ZOOM) to Pose(x + PAN_X, y, scale * PAN_ZOOM)
        KenBurnsStyle.PAN_UP -> Pose(x, y + PAN_Y, scale * PAN_ZOOM) to Pose(x, y - PAN_Y, scale * PAN_ZOOM)
        KenBurnsStyle.PAN_DOWN -> Pose(x, y - PAN_Y, scale * PAN_ZOOM) to Pose(x, y + PAN_Y, scale * PAN_ZOOM)
    }

    /** The style the clip's motion keyframes match, if any, so the panel can show it selected. */
    fun styleOf(clip: Clip): KenBurnsStyle? {
        val scale = clip.keyframes[AnimatedProperty.SCALE]?.takeIf { it.size == 2 } ?: return null
        val x = clip.keyframes[AnimatedProperty.OFFSET_X]?.takeIf { it.size == 2 } ?: return null
        val y = clip.keyframes[AnimatedProperty.OFFSET_Y]?.takeIf { it.size == 2 } ?: return null
        if (scale[0].timeUs != 0L || scale[1].timeUs != clip.durationUs) return null
        val t = clip.transform
        return KenBurnsStyle.entries.firstOrNull { style ->
            val (a, b) = poses(style, t.offsetX, t.offsetY, t.scale)
            close(scale[0].value, a.scale) && close(scale[1].value, b.scale) &&
                close(x[0].value, a.x) && close(x[1].value, b.x) && close(y[0].value, a.y) && close(y[1].value, b.y)
        }
    }

    private fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 1e-3f
}

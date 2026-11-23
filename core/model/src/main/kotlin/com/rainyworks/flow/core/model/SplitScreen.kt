package com.rainyworks.flow.core.model

/** Ready-made arrangements for several pictures on screen at once. */
enum class SplitLayout(val cells: Int) {
    /** Two side by side. */
    SIDE_BY_SIDE(2),
    /** Two stacked, one above the other. */
    STACKED(2),
    /** Three columns. */
    THREE_COLUMNS(3),
    /** Two by two. */
    GRID(4),
    /** The first picture full screen and the second small in a corner, with rounded corners. */
    PICTURE_IN_PICTURE(2),
}

/**
 * Arranges the pictures on screen at a moment into a [SplitLayout]. Each picture is cropped
 * to the shape of its cell and scaled to fill it exactly, following the editor's framing:
 * the cropped source is fitted inside the canvas at scale 1, then scaled and moved by
 * offsets that are fractions of the canvas from its center.
 */
object SplitScreen {
    /** Fraction of the canvas a picture-in-picture inset takes per side. */
    private const val INSET = 0.34f
    private const val INSET_MARGIN = 0.04f

    /** Main-track picture first, then overlays from the top layer down, as they are drawn. */
    fun picturesAt(project: Project, timeUs: Long): List<Clip> {
        val main = project.mainTrack.clipAt(timeUs)?.takeIf { it.hasPicture() }
        val overlays = project.tracks
            .filter { it.kind == TrackKind.OVERLAY && !it.hidden }
            .mapNotNull { track -> track.clipAt(timeUs)?.takeIf { it.hasPicture() } }
        return listOfNotNull(main) + overlays
    }

    fun apply(project: Project, layout: SplitLayout, timeUs: Long): Project {
        val clips = picturesAt(project, timeUs).take(layout.cells)
        if (clips.size < 2) return project
        val canvasAspect = project.canvas.aspectRatio.width.toFloat() / project.canvas.aspectRatio.height
        var result = project
        clips.forEachIndexed { index, clip ->
            val cell = cell(layout, index)
            result = Edits.updateClip(result, clip.id) { framed(it, cell, canvasAspect, inset = layout == SplitLayout.PICTURE_IN_PICTURE && index == 1) }
        }
        return result
    }

    /** A cell as fractions of the canvas: left, top, width, height. */
    private data class Cell(val left: Float, val top: Float, val width: Float, val height: Float)

    private fun cell(layout: SplitLayout, index: Int): Cell = when (layout) {
        SplitLayout.SIDE_BY_SIDE -> Cell(index * 0.5f, 0f, 0.5f, 1f)
        SplitLayout.STACKED -> Cell(0f, index * 0.5f, 1f, 0.5f)
        SplitLayout.THREE_COLUMNS -> Cell(index / 3f, 0f, 1f / 3f, 1f)
        SplitLayout.GRID -> Cell((index % 2) * 0.5f, (index / 2) * 0.5f, 0.5f, 0.5f)
        SplitLayout.PICTURE_IN_PICTURE -> if (index == 0) Cell(0f, 0f, 1f, 1f) else
            Cell(1f - INSET - INSET_MARGIN, 1f - INSET - INSET_MARGIN, INSET, INSET)
    }

    private fun framed(clip: Clip, cell: Cell, canvasAspect: Float, inset: Boolean): Clip {
        val media = clip.media ?: return clip
        val sourceAspect = media.displayWidth.toFloat() / media.displayHeight.coerceAtLeast(1)
        val cellAspect = canvasAspect * cell.width / cell.height
        // Crop the source (centered) to the cell's shape.
        val crop = if (sourceAspect > cellAspect) {
            val keep = cellAspect / sourceAspect
            CropRect((1 - keep) / 2, 0f, (1 + keep) / 2, 1f)
        } else {
            val keep = sourceAspect / cellAspect
            CropRect(0f, (1 - keep) / 2, 1f, (1 + keep) / 2)
        }
        // Fitted at scale 1 the cropped picture fills the canvas along its tighter side;
        // with the cell's shape that side's share of the canvas is the scale needed.
        val scale = if (cellAspect >= canvasAspect) cell.width else cell.height
        val transform = clip.transform.copy(
            offsetX = cell.left + cell.width / 2 - 0.5f,
            offsetY = cell.top + cell.height / 2 - 0.5f,
            scale = scale,
            rotationDegrees = 0f,
            flipHorizontal = false,
            flipVertical = false,
            crop = crop,
        )
        val mask = if (inset) Mask(MaskShape.RECTANGLE, cornerRadius = 0.18f) else clip.mask?.takeIf { it.shape != MaskShape.RECTANGLE }
        // Fixed framing: motion keyframes would move the picture out of its cell.
        return clip.copy(transform = transform, mask = mask, keyframes = clip.keyframes - MediaEdits.MOTION_PROPERTIES.toSet())
    }

    private fun Clip.hasPicture() = kind != ClipKind.AUDIO && kind != ClipKind.TEXT &&
        kind != ClipKind.ADJUSTMENT && kind != ClipKind.EFFECT && media?.hasVideo == true
}

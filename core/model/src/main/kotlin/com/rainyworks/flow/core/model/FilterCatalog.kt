package com.rainyworks.flow.core.model

enum class FilterCategory(val label: String) {
    CINEMATIC("Cinematográfico"),
    VINTAGE("Vintage"),
    MONO("Blanco y negro"),
    PORTRAIT("Retrato"),
    LANDSCAPE("Paisajes"),
}

/**
 * A quick filter: a full-strength look described with the same tools as manual grading,
 * so it renders identically in preview and export. [ColorGrade.filterIntensity] scales it.
 */
data class FilterPreset(
    val id: String,
    val name: String,
    val category: FilterCategory,
    val adjustments: ColorAdjustments = ColorAdjustments(),
    val curves: ToneCurves = ToneCurves(),
    val hsl: Map<HslRange, HslAdjustment> = emptyMap(),
)

object FilterCatalog {
    private fun c(vararg xy: Float) = xy.toList().chunked(2).map { CurvePoint(it[0], it[1]) }

    val all: List<FilterPreset> = listOf(
        // Cinematic
        FilterPreset(
            "cine.teal_orange", "Teal & Orange", FilterCategory.CINEMATIC,
            ColorAdjustments(contrast = 0.15f, saturation = 0.1f, temperature = 0.05f),
            ToneCurves(blue = c(0f, 0.08f, 0.5f, 0.5f, 1f, 0.9f), red = c(0f, 0f, 0.5f, 0.52f, 1f, 1f)),
            mapOf(HslRange.ORANGE to HslAdjustment(saturation = 0.2f), HslRange.BLUE to HslAdjustment(hue = -0.05f, saturation = 0.15f)),
        ),
        FilterPreset(
            "cine.noir_blue", "Noche", FilterCategory.CINEMATIC,
            ColorAdjustments(exposure = -0.1f, contrast = 0.2f, saturation = -0.25f, temperature = -0.3f, vignette = 0.35f),
        ),
        FilterPreset(
            "cine.bleach", "Bleach", FilterCategory.CINEMATIC,
            ColorAdjustments(contrast = 0.3f, saturation = -0.45f, highlights = -0.1f, sharpness = 0.15f),
        ),
        FilterPreset(
            "cine.golden", "Dorado", FilterCategory.CINEMATIC,
            ColorAdjustments(temperature = 0.35f, tint = 0.05f, contrast = 0.1f, shadows = 0.1f),
            ToneCurves(master = c(0f, 0.04f, 0.5f, 0.52f, 1f, 0.97f)),
        ),
        // Vintage
        FilterPreset(
            "vintage.film", "Película", FilterCategory.VINTAGE,
            ColorAdjustments(contrast = -0.05f, saturation = -0.15f, grain = 0.35f, fade = 0.3f, temperature = 0.1f),
            ToneCurves(master = c(0f, 0.08f, 0.5f, 0.5f, 1f, 0.94f)),
        ),
        FilterPreset(
            "vintage.polaroid", "Instantánea", FilterCategory.VINTAGE,
            ColorAdjustments(fade = 0.4f, saturation = -0.1f, temperature = 0.15f, tint = 0.1f, vignette = 0.25f),
        ),
        FilterPreset(
            "vintage.70s", "Años 70", FilterCategory.VINTAGE,
            ColorAdjustments(temperature = 0.4f, saturation = 0.1f, fade = 0.2f, grain = 0.2f),
            ToneCurves(blue = c(0f, 0.12f, 1f, 0.85f)),
        ),
        // Black & white
        FilterPreset("mono.classic", "Clásico", FilterCategory.MONO, ColorAdjustments(saturation = -1f, contrast = 0.1f)),
        FilterPreset(
            "mono.contrast", "Alto contraste", FilterCategory.MONO,
            ColorAdjustments(saturation = -1f, contrast = 0.45f, sharpness = 0.2f),
            ToneCurves(master = c(0f, 0f, 0.25f, 0.18f, 0.75f, 0.84f, 1f, 1f)),
        ),
        FilterPreset("mono.soft", "Suave", FilterCategory.MONO, ColorAdjustments(saturation = -1f, contrast = -0.15f, fade = 0.25f)),
        FilterPreset(
            "mono.sepia", "Sepia", FilterCategory.MONO,
            ColorAdjustments(saturation = -0.85f, temperature = 0.45f, fade = 0.15f),
        ),
        // Portrait
        FilterPreset(
            "portrait.warm_skin", "Piel cálida", FilterCategory.PORTRAIT,
            ColorAdjustments(temperature = 0.12f, contrast = -0.05f, highlights = -0.1f),
            hsl = mapOf(HslRange.ORANGE to HslAdjustment(luminance = 0.12f, saturation = -0.05f)),
        ),
        FilterPreset(
            "portrait.soft", "Suave", FilterCategory.PORTRAIT,
            ColorAdjustments(contrast = -0.15f, sharpness = -0.2f, highlights = -0.15f, fade = 0.1f),
        ),
        FilterPreset(
            "portrait.clean", "Limpio", FilterCategory.PORTRAIT,
            ColorAdjustments(exposure = 0.08f, shadows = 0.15f, saturation = -0.05f),
            hsl = mapOf(HslRange.RED to HslAdjustment(saturation = -0.1f), HslRange.ORANGE to HslAdjustment(luminance = 0.08f)),
        ),
        // Landscape
        FilterPreset(
            "landscape.vivid", "Vívido", FilterCategory.LANDSCAPE,
            ColorAdjustments(saturation = 0.3f, contrast = 0.15f, sharpness = 0.2f),
            hsl = mapOf(HslRange.GREEN to HslAdjustment(saturation = 0.15f), HslRange.BLUE to HslAdjustment(saturation = 0.2f)),
        ),
        FilterPreset(
            "landscape.sky", "Cielo", FilterCategory.LANDSCAPE,
            ColorAdjustments(highlights = -0.25f, contrast = 0.1f),
            hsl = mapOf(HslRange.BLUE to HslAdjustment(saturation = 0.3f, luminance = -0.15f), HslRange.CYAN to HslAdjustment(saturation = 0.15f)),
        ),
        FilterPreset(
            "landscape.autumn", "Otoño", FilterCategory.LANDSCAPE,
            ColorAdjustments(temperature = 0.25f, saturation = 0.1f),
            hsl = mapOf(HslRange.GREEN to HslAdjustment(hue = -0.12f, saturation = -0.2f), HslRange.ORANGE to HslAdjustment(saturation = 0.25f)),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String?): FilterPreset? = id?.let(byId::get)

    fun byCategory(category: FilterCategory): List<FilterPreset> = all.filter { it.category == category }

    /**
     * Flattens a grade into the adjustments the renderer applies: the filter scaled by its
     * intensity, combined additively with the manual adjustments and clamped to valid ranges.
     */
    fun effectiveAdjustments(grade: ColorGrade): ColorAdjustments {
        val preset = find(grade.filterId) ?: return grade.adjustments
        val k = grade.filterIntensity.coerceIn(0f, 1f)
        val p = preset.adjustments
        val m = grade.adjustments
        fun s(a: Float, b: Float) = (a * k + b).coerceIn(-1f, 1f)
        fun u(a: Float, b: Float) = (a * k + b).coerceIn(0f, 1f)
        return ColorAdjustments(
            brightness = s(p.brightness, m.brightness),
            contrast = s(p.contrast, m.contrast),
            saturation = s(p.saturation, m.saturation),
            exposure = s(p.exposure, m.exposure),
            sharpness = s(p.sharpness, m.sharpness),
            highlights = s(p.highlights, m.highlights),
            shadows = s(p.shadows, m.shadows),
            temperature = s(p.temperature, m.temperature),
            tint = s(p.tint, m.tint),
            vignette = u(p.vignette, m.vignette),
            grain = u(p.grain, m.grain),
            fade = u(p.fade, m.fade),
        )
    }
}

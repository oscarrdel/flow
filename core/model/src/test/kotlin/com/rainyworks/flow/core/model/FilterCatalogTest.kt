package com.rainyworks.flow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCatalogTest {
    @Test
    fun idsAreUniqueAndEveryCategoryHasFilters() {
        assertEquals(FilterCatalog.all.size, FilterCatalog.all.map { it.id }.toSet().size)
        FilterCategory.entries.forEach { assertTrue(FilterCatalog.byCategory(it).isNotEmpty()) }
    }

    @Test
    fun curvesAreValid() {
        FilterCatalog.all.forEach { preset ->
            listOf(preset.curves.master, preset.curves.red, preset.curves.green, preset.curves.blue).forEach { curve ->
                assertTrue(preset.id, curve.size >= 2)
                assertTrue(preset.id, curve.zipWithNext().all { (a, b) -> b.x > a.x })
                assertTrue(preset.id, curve.all { it.x in 0f..1f && it.y in 0f..1f })
            }
        }
    }

    @Test
    fun intensityScalesFilterAndAddsManualAdjustments() {
        val grade = ColorGrade(filterId = "mono.classic", filterIntensity = 0.5f, adjustments = ColorAdjustments(saturation = 0.2f))
        val result = FilterCatalog.effectiveAdjustments(grade)
        assertEquals(-0.3f, result.saturation, 1e-6f)
        assertEquals(0.05f, result.contrast, 1e-6f)
    }

    @Test
    fun valuesAreClamped() {
        val grade = ColorGrade(filterId = "mono.classic", adjustments = ColorAdjustments(saturation = -0.8f))
        assertEquals(-1f, FilterCatalog.effectiveAdjustments(grade).saturation, 0f)
    }

    @Test
    fun unknownFilterLeavesManualAdjustments() {
        val manual = ColorAdjustments(contrast = 0.4f)
        assertEquals(manual, FilterCatalog.effectiveAdjustments(ColorGrade(filterId = "nope", adjustments = manual)))
    }
}

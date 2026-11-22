package com.rainyworks.flow.core.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Resolution of the editor preview; exports always use the quality chosen at export time. */
enum class PreviewQuality(val shortSidePx: Int, val label: String, val description: String) {
    HIGH(720, "Alta", "720p · la más nítida"),
    MEDIUM(540, "Media", "540p · más fluida en proyectos con varias capas"),
    LOW(360, "Baja", "360p · para proyectos pesados o teléfonos lentos"),
}

/** Small app-wide editor settings, persisted in SharedPreferences. */
class EditorPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("editor", Context.MODE_PRIVATE)

    private val _previewQuality = MutableStateFlow(
        runCatching { PreviewQuality.valueOf(prefs.getString(KEY_PREVIEW_QUALITY, null) ?: "") }.getOrDefault(PreviewQuality.HIGH),
    )
    val previewQuality: StateFlow<PreviewQuality> = _previewQuality.asStateFlow()

    fun setPreviewQuality(quality: PreviewQuality) {
        prefs.edit().putString(KEY_PREVIEW_QUALITY, quality.name).apply()
        _previewQuality.value = quality
    }

    private companion object {
        const val KEY_PREVIEW_QUALITY = "preview_quality"
    }
}

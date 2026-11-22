package com.rainyworks.flow.core.engine.color

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.CubeLut

@OptIn(UnstableApi::class)
object ColorEffects {
    /**
     * Efectos de video en orden de aplicación. El cargador resuelve y cachea la LUT por URI.
     * Los controles y las LUT se evalúan en el RGB con gamma del flujo SDR de Flow.
     */
    fun forGrade(grade: ColorGrade, lutLoader: (String) -> CubeLut?): List<Effect> = buildList {
        val manual = resolveGrade(grade)
        if (!manual.isIdentity) add(GradeShader(manual))
        val intensity = grade.lutIntensity.coerceIn(0f, 1f)
        if (grade.lutUri != null && intensity > 0f) {
            grade.lutUri?.let(lutLoader)?.let { add(CubeEffect(it, intensity)) }
        }
    }
}

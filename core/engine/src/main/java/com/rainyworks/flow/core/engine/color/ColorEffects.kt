package com.rainyworks.flow.core.engine.color

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import com.rainyworks.flow.core.engine.adjustment.TimedColorEffect
import com.rainyworks.flow.core.model.ColorGrade
import com.rainyworks.flow.core.model.CubeLut

@OptIn(UnstableApi::class)
object ColorEffects {
    /**
     * Efectos de video en orden de aplicación. El cargador resuelve y cachea la LUT por URI.
     * Los controles y las LUT se evalúan en el RGB con gamma del flujo SDR de Flow.
     */
    fun forGrade(grade: ColorGrade, lutLoader: (String) -> CubeLut?): List<Effect> =
        forGrade(grade, Long.MIN_VALUE, Long.MAX_VALUE, lutLoader)

    fun forGrade(grade: ColorGrade, activeFromUs: Long, activeUntilUs: Long, lutLoader: (String) -> CubeLut?): List<Effect> {
        fun gated(effect: GlEffect): GlEffect =
            if (activeFromUs == Long.MIN_VALUE && activeUntilUs == Long.MAX_VALUE) effect
            else TimedColorEffect(effect, activeFromUs, activeUntilUs)

        return buildList {
        val manual = resolveGrade(grade)
        if (!manual.isIdentity) add(gated(GradeShader(manual)))
        val intensity = grade.lutIntensity.coerceIn(0f, 1f)
        if (grade.lutUri != null && intensity > 0f) {
            grade.lutUri?.let(lutLoader)?.let { add(gated(CubeEffect(it, intensity))) }
        }
        }
    }
}

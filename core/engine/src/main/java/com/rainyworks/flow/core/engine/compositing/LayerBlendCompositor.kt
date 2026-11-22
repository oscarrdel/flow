package com.rainyworks.flow.core.engine.compositing

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.StaticOverlaySettings
import com.rainyworks.flow.core.model.AnimatedProperty
import com.rainyworks.flow.core.model.BlendMode
import com.rainyworks.flow.core.model.Clip
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Track
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tracks follow video sequence order (foreground first, main last). For custom blend modes,
 * Media3 synchronizes disjoint tiles and [effect] blends them in a canvas-sized draw.
 * For normal multi-layer mixes the native compositor positions and blends each input.
 * Give each visible item and video gap the same fitted [canvas] dimensions.
 */
@OptIn(UnstableApi::class)
class LayerBlendCompositor(
    private val canvas: Size,
    tracks: List<Track>,
    /** When the main track is split into A/B rolls for transitions, the two last layers are B then A. */
    private val transitions: List<TransitionWindow> = emptyList(),
) : VideoCompositorSettings {
    init {
        require(canvas.width > 0 && canvas.height > 0 && tracks.isNotEmpty())
    }

    private val layers = tracks.map { track ->
        track.clips.filter { !track.hidden && it.kind != ClipKind.AUDIO && it.media?.hasVideo == true }
            .sortedBy { it.startUs }
    }
    private val shaderNeeded = transitions.isNotEmpty() || if (layers.size == 1) {
        // SingleInputVideoGraph bypasses VideoCompositorSettings. Its input is already one tile.
        layers.single().any { clip ->
            val t = clip.transform
            clip.opacity != 1f || t.scale != 1f || t.offsetX != 0f || t.offsetY != 0f ||
                t.rotationDegrees != 0f || t.flipHorizontal || t.flipVertical ||
                clip.keyframes.keys.any { it in OVERLAY_PROPERTIES }
        }
    } else {
        layers.any { clips -> clips.any { it.blendMode != BlendMode.NORMAL } }
    }
    private fun rowsFor(cols: Int) = 1 + (layers.size - 1) / cols
    private val columns = (1..layers.size).minWith(compareBy(
        { maxOf(it.toLong() * canvas.width, rowsFor(it).toLong() * canvas.height) },
        { it.toLong() * rowsFor(it) },
    ))
    private val rows = rowsFor(columns)
    private val atlasSize = Size(Math.multiplyExact(columns, canvas.width), Math.multiplyExact(rows, canvas.height))
    private val transparent = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    private val tiles = layers.indices.map { i ->
        StaticOverlaySettings.Builder().setBackgroundFrameAnchor(
            (2f * (i % columns) + 1f) / columns - 1f,
            (2f * (i / columns) + 1f) / rows - 1f,
        ).build()
    }

    private fun clipAt(input: Int, timeUs: Long): Clip? {
        val clips = layers.getOrNull(input) ?: return null
        val index = clips.binarySearchBy(timeUs) { it.startUs }
        return clips.getOrNull(if (index >= 0) index else -index - 2)?.takeIf { it.contains(timeUs) }
    }

    override fun getOutputSize(inputSizes: List<Size>) = if (shaderNeeded) atlasSize else canvas

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val clip = clipAt(inputId, presentationTimeUs) ?: return transparent
        return if (shaderNeeded) tiles[inputId] else animatedOverlaySettings(clip, presentationTimeUs)
    }

    /** When present, insert first in Composition.effects.videoEffects, before presentation and titles. */
    val effect: GlEffect? = if (!shaderNeeded) null else object : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            object : CompositingProgram(useHdr, fragment()) {
                override fun configure(program: GlProgram, width: Int, height: Int): Size {
                    require(width == atlasSize.width && height == atlasSize.height) {
                        "Expected a ${atlasSize.width}x${atlasSize.height} layer atlas, received ${width}x${height}"
                    }
                    program.setFloatsUniform("uSize", floatArrayOf(canvas.width.toFloat(), canvas.height.toFloat()))
                    return canvas
                }

                override fun setFrame(program: GlProgram, presentationTimeUs: Long) {
                    if (transitions.isNotEmpty()) {
                        val window = transitions.firstOrNull { presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs }
                        program.setFloatsUniform("uTransition", if (window == null) floatArrayOf(0f, 0f, 0f, 0f) else floatArrayOf(
                            1f,
                            ((presentationTimeUs - window.startUs).toFloat() / (window.endUs - window.startUs)).coerceIn(0f, 1f),
                            TransitionShaders.index(window.type).toFloat(),
                            if (window.fromA) 0f else 1f,
                        ))
                    }
                    for (i in layers.indices) {
                        val clip = clipAt(i, presentationTimeUs)
                        val settings = clip?.let { animatedOverlaySettings(it, presentationTimeUs) } ?: transparent
                        val sx = settings.scale.first
                        val sy = settings.scale.second
                        val visible = sx != 0f && sy != 0f && settings.alphaScale > 0f
                        val angle = Math.toRadians(settings.rotationDegrees.toDouble())
                        val anchor = settings.backgroundFrameAnchor
                        program.setFloatsUniform("uPose$i", floatArrayOf(anchor.first, anchor.second,
                            cos(angle).toFloat(), sin(angle).toFloat()))
                        program.setFloatsUniform("uLayer$i", floatArrayOf(
                            if (visible) 1f / sx else 0f, if (visible) 1f / sy else 0f,
                            if (visible) settings.alphaScale else 0f, (clip?.blendMode ?: BlendMode.NORMAL).ordinal.toFloat(),
                        ))
                    }
                }
            }
    }

    private fun fragment() = buildString {
        append(FRAGMENT_HEADER)
        appendLine("uniform vec2 uSize;")
        for (i in layers.indices) appendLine("uniform vec4 uPose$i, uLayer$i;")
        appendLine(BLEND)
        if (transitions.isNotEmpty()) appendLine("uniform vec4 uTransition;")
        appendLine("""
            vec4 sampleLayerAt(vec2 tile, vec4 pose, vec4 layer, vec2 at) {
                if (any(lessThan(at, vec2(0.0))) || any(greaterThan(at, vec2(1.0)))) return vec4(0.0);
                vec2 p = (at - 0.5 - 0.5 * pose.xy) * uSize;
                vec2 uv = vec2(dot(p, pose.zw), dot(p, vec2(-pose.w, pose.z))) * layer.xy / uSize + 0.5;
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0);
                vec2 inset = 0.5 / uSize;
                uv = clamp(uv, inset, 1.0 - inset);
                return texture2D(uInput, (tile + uv) / vec2($columns.0, $rows.0)) * layer.z;
            }
            vec4 sampleLayer(vec2 tile, vec4 pose, vec4 layer) { return sampleLayerAt(tile, pose, layer, vUv); }
        """)
        val n = layers.size
        if (transitions.isNotEmpty()) {
            val (b, a) = n - 2 to n - 1
            fun tile(i: Int) = "vec2(${i % columns}.0, ${i / columns}.0)"
            appendLine("""
                vec4 layerA(vec2 at) { return uLayer$a.z > 0.0 ? sampleLayerAt(${tile(a)}, uPose$a, uLayer$a, at) : vec4(0.0); }
                vec4 layerB(vec2 at) { return uLayer$b.z > 0.0 ? sampleLayerAt(${tile(b)}, uPose$b, uLayer$b, at) : vec4(0.0); }
                vec4 outgoing(vec2 at) { return uTransition.w < 0.5 ? layerA(at) : layerB(at); }
                vec4 incoming(vec2 at) { return uTransition.w < 0.5 ? layerB(at) : layerA(at); }
            """)
            appendLine(TransitionShaders.FUNCTION)
        }
        appendLine("void main() {")
        appendLine("vec4 result = vec4(0.0);")
        val mainLayers = if (transitions.isNotEmpty()) 2 else 0
        if (transitions.isNotEmpty()) {
            appendLine("""
                if (uTransition.x > 0.5) {
                    result = transition(vUv, uTransition.y, uTransition.z);
                } else {
                    result = over(layerB(vUv), layerA(vUv), 0.0);
                }
            """)
        }
        for (i in (0 until n - mainLayers).reversed()) {
            val tile = "vec2(${i % columns}.0, ${i / columns}.0)"
            appendLine("if (uLayer$i.z > 0.0) result = over(result, sampleLayer($tile, uPose$i, uLayer$i), uLayer$i.w);")
        }
        appendLine("gl_FragColor = result; }")
    }
}

private val OVERLAY_PROPERTIES = setOf(AnimatedProperty.OFFSET_X, AnimatedProperty.OFFSET_Y,
    AnimatedProperty.SCALE, AnimatedProperty.ROTATION, AnimatedProperty.OPACITY)

private val BLEND = """
    vec3 blend(vec3 b, vec3 s, float mode) {
        if (mode == ${BlendMode.MULTIPLY.ordinal}.0) return b * s;
        if (mode == ${BlendMode.SCREEN.ordinal}.0) return b + s - b * s;
        if (mode == ${BlendMode.OVERLAY.ordinal}.0)
            return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(vec3(0.5), b));
        if (mode == ${BlendMode.DARKEN.ordinal}.0) return min(b, s);
        if (mode == ${BlendMode.LIGHTEN.ordinal}.0) return max(b, s);
        if (mode == ${BlendMode.DIFFERENCE.ordinal}.0) return abs(b - s);
        if (mode == ${BlendMode.SOFT_LIGHT.ordinal}.0) {
            vec3 d = mix(((16.0 * b - 12.0) * b + 4.0) * b, sqrt(b), step(vec3(0.25), b));
            return mix(b - (1.0 - 2.0 * s) * b * (1.0 - b),
                       b + (2.0 * s - 1.0) * (d - b), step(vec3(0.5), s));
        }
        return s;
    }

    vec4 over(vec4 dst, vec4 src, float mode) {
        if (src.a <= 0.0) return dst;
        if (dst.a <= 0.0) return src;
        vec3 overlap = blend(clamp(dst.rgb / dst.a, 0.0, 1.0), clamp(src.rgb / src.a, 0.0, 1.0), mode);
        return vec4((1.0 - src.a) * dst.rgb + (1.0 - dst.a) * src.rgb + dst.a * src.a * overlap,
                    src.a + (1.0 - src.a) * dst.a);
    }
"""

/** A window where the main track blends two clips. [fromA] is true when the outgoing clip is on roll A. */
data class TransitionWindow(val startUs: Long, val endUs: Long, val type: String, val fromA: Boolean)

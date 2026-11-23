package com.rainyworks.flow.core.designsystem.components

import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.composed
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rainyworks.flow.core.designsystem.theme.FlowColors

/** Rounded glass with Flow's usual corner. */
val GlassShape = RoundedCornerShape(24.dp)

/**
 * What a pane of glass blurs. The source content is recorded into [layer] where it is drawn;
 * glass over it draws the same layer blurred. [origin] is where the source sits on screen.
 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * Marks this content as what [backdrop] glass blurs. Bands of [hideTop] and [hideBottom] are
 * left undrawn here because glass covers them and shows the content blurred instead; drawing
 * it sharp underneath would show through the translucent glass.
 */
fun Modifier.glassBackdropSource(
    backdrop: GlassBackdrop,
    hideTop: Dp = 0.dp,
    hideBottom: Dp = 0.dp,
): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        val top = hideTop.toPx()
        val bottom = size.height - hideBottom.toPx()
        if (bottom > top) {
            clipRect(top = top, bottom = bottom) { drawLayer(backdrop.layer) }
        }
    }

private class GlassPosition {
    var origin = Offset.Zero
}

/**
 * A pane of glass: what is behind it blurred ([backdrop], Android 12 and later), a translucent
 * fill brighter at the top, a sheen along the upper part, a rim that catches the light at the
 * top-left edge and fades away, and a soft shadow underneath. [tint] colors the glass (for
 * primary buttons); [elevation] lifts it off what is behind.
 */
fun Modifier.glass(
    shape: Shape = GlassShape,
    tint: Color? = null,
    elevation: Dp = 10.dp,
    strong: Boolean = false,
    backdrop: GlassBackdrop? = null,
): Modifier = if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
    glassPane(shape, tint, elevation, strong, null, null)
} else {
    composed {
        val position = remember { GlassPosition() }
        this
            .onGloballyPositioned { position.origin = it.positionInRoot() }
            .glassPane(shape, tint, elevation, strong, backdrop, position)
    }
}

private fun Modifier.glassPane(
    shape: Shape,
    tint: Color?,
    elevation: Dp,
    strong: Boolean,
    backdrop: GlassBackdrop?,
    position: GlassPosition?,
): Modifier = this
    .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false, ambientColor = FlowColors.GlassShadow, spotColor = FlowColors.GlassShadow) else Modifier)
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        val top = tint?.copy(alpha = if (strong) 0.95f else 0.78f) ?: FlowColors.GlassTop.copy(alpha = if (strong) 0.30f else 0.18f)
        val bottom = tint?.copy(alpha = if (strong) 0.75f else 0.55f) ?: FlowColors.GlassBottom.copy(alpha = if (strong) 0.16f else 0.07f)
        val fill = Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset(size.width * 0.35f, size.height))
        val sheen = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.20f),
            0.45f to Color.White.copy(alpha = 0f),
            endY = size.height,
        )
        val rim = Brush.linearGradient(
            0f to FlowColors.GlassRim,
            0.35f to Color.White.copy(alpha = 0.10f),
            0.6f to Color.White.copy(alpha = 0f),
            1f to Color.White.copy(alpha = 0.30f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val rimWidth = 1.dp.toPx()
        val blurred = if (backdrop != null) {
            obtainGraphicsLayer().apply {
                val radius = GLASS_BLUR.toPx()
                renderEffect = BlurEffect(radius, radius, TileMode.Decal)
            }
        } else {
            null
        }
        onDrawWithContent {
            clipPath(path) {
                if (blurred != null && backdrop != null && position != null) {
                    val offset = backdrop.origin - position.origin
                    blurred.record {
                        translate(offset.x, offset.y) { drawLayer(backdrop.layer) }
                    }
                    drawLayer(blurred)
                }
                drawRect(fill)
                drawRect(sheen)
            }
            drawContent()
            drawOutline(outline, rim, style = Stroke(rimWidth))
        }
    }

private val GLASS_BLUR = 18.dp

/**
 * The light behind the glass: marigold, rosa mexicano and turquoise glows drifting slowly over
 * the violet night. With [still] the glows stop (for example while a video plays).
 */
@Composable
fun FlowAurora(modifier: Modifier = Modifier, still: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val phase = if (still) 0.5f else t
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1040), FlowColors.Carbon)))
        val w = size.width
        val h = size.height
        fun glow(color: Color, x: Float, y: Float, radius: Float, alpha: Float) = drawCircle(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                center = Offset(x, y),
                radius = radius,
            ),
            radius = radius,
            center = Offset(x, y),
        )
        glow(FlowColors.Accent, w * (0.12f + 0.18f * phase), h * (0.10f + 0.06f * phase), w * 0.85f, 0.42f)
        glow(FlowColors.Rosa, w * (0.95f - 0.20f * phase), h * (0.42f + 0.08f * phase), w * 0.8f, 0.34f)
        glow(FlowColors.Turquesa, w * (0.35f + 0.15f * phase), h * (0.98f - 0.08f * phase), w * 0.9f, 0.28f)
    }
}

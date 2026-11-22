package com.rainyworks.flow.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.model.ShapeKind
import com.rainyworks.flow.core.model.StickerSpec
import com.rainyworks.flow.feature.editor.EditorAction
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class StickerTab(val label: String) {
    Shapes("Formas"),
    Emojis("Emojis"),
}

private data class SwatchColor(val argb: Long, val label: String)

private val StickerSwatches = listOf(
    SwatchColor(0xFFFFFFFFL, "Blanco"),
    SwatchColor(0xFF000000L, "Negro"),
    SwatchColor(0xFFFF4D4DL, "Rojo"),
    SwatchColor(0xFFFF9F1CL, "Naranja"),
    SwatchColor(0xFFFFD60AL, "Amarillo"),
    SwatchColor(0xFF2EC4B6L, "Verde"),
    SwatchColor(0xFF3A86FFL, "Azul"),
    SwatchColor(0xFF9B5DE5L, "Morado"),
)

private data class EmojiGroup(val title: String, val emojis: List<String>)

private val EmojiGroups = listOf(
    EmojiGroup("Caras", listOf("😀", "😂", "😍", "😎", "🥳", "🤯", "😭", "😡")),
    EmojiGroup("Gestos", listOf("👍", "👏", "🙌", "🔥", "💯", "✨", "💪", "🙏")),
    EmojiGroup("Corazones", listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💖")),
    EmojiGroup("Fiesta y objetos", listOf("🎉", "🎊", "🎈", "🎁", "🏆", "🎵", "📸", "💡")),
    EmojiGroup("Naturaleza", listOf("⭐", "🌟", "🌈", "☀️", "🌙", "⚡", "🌸", "🍀")),
    EmojiGroup("Flechas y símbolos", listOf("➡️", "⬅️", "⬆️", "⬇️", "↗️", "✅", "❌", "❗")),
)

private const val EMOJI_GRID_COLUMNS = 6
private val EMOJI_GRID_HEIGHT = 200.dp

@Composable
internal fun StickerSheetPanel(
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    var activeTab by remember { mutableStateOf(StickerTab.Shapes) }
    var selectedArgb by remember { mutableLongStateOf(StickerSwatches.first().argb) }
    var outlineOnly by remember { mutableStateOf(false) }

    FlowBottomPanel(
        title = "Stickers",
        subtitle = "Toca una forma o un emoji para agregarlo",
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            StickerTab.entries.forEach { tab ->
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    label = {
                        Text(
                            text = tab.label,
                            style = FlowTypography.labelMedium,
                        )
                    },
                    colors = sheetChipColors(),
                )
            }
        }

        Spacer(Modifier.height(spacing.xs))

        when (activeTab) {
            StickerTab.Shapes -> {
                ShapesStickerTab(
                    selectedArgb = selectedArgb,
                    outlineOnly = outlineOnly,
                    onSelectColor = { selectedArgb = it },
                    onOutlineOnlyChange = { outlineOnly = it },
                    onAddShape = { kind ->
                        onAction(
                            EditorAction.AddSticker(
                                StickerSpec.Shape(
                                    kind = kind,
                                    argb = selectedArgb,
                                    filled = !outlineOnly,
                                ),
                            ),
                        )
                    },
                )
            }
            StickerTab.Emojis -> {
                EmojisStickerTab(
                    onAddEmoji = { emoji ->
                        onAction(EditorAction.AddSticker(StickerSpec.Emoji(emoji)))
                    },
                )
            }
        }

        Spacer(Modifier.height(spacing.sm))

        Text(
            text = "Para stickers animados importa un GIF desde Superposición.",
            style = FlowTypography.labelSmall,
            color = FlowColors.TextTertiary,
        )
    }
}

@Composable
private fun ShapesStickerTab(
    selectedArgb: Long,
    outlineOnly: Boolean,
    onSelectColor: (Long) -> Unit,
    onOutlineOnlyChange: (Boolean) -> Unit,
    onAddShape: (ShapeKind) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val previewColor = Color(selectedArgb)

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(StickerSwatches, key = { it.argb }) { swatch ->
            val selected = swatch.argb == selectedArgb
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .semantics { contentDescription = swatch.label }
                    .clip(CircleShape)
                    .background(Color(swatch.argb))
                    .border(
                        width = if (selected) 2.5.dp else 1.dp,
                        color = if (selected) FlowColors.Accent else FlowColors.Outline,
                        shape = CircleShape,
                    )
                    .clickable { onSelectColor(swatch.argb) },
            )
        }
    }

    Spacer(Modifier.height(spacing.xs))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Solo contorno",
            style = FlowTypography.labelLarge,
            color = FlowColors.TextPrimary,
        )
        Switch(
            checked = outlineOnly,
            onCheckedChange = onOutlineOnlyChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FlowColors.OnAccent,
                checkedTrackColor = FlowColors.Accent,
            ),
        )
    }

    Spacer(Modifier.height(spacing.xs))

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(ShapeKind.entries, key = { it.name }) { kind ->
            ShapeStickerCard(
                kind = kind,
                color = previewColor,
                filled = !outlineOnly,
                onClick = { onAddShape(kind) },
            )
        }
    }
}

@Composable
private fun ShapeStickerCard(
    kind: ShapeKind,
    color: Color,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .width(80.dp)
            .clip(shape)
            .background(FlowColors.Slate)
            .border(1.dp, FlowColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.xs, vertical = spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(modifier = Modifier.size(36.dp)) {
            drawShapeThumbnail(kind = kind, color = color, filled = filled)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = kind.spanishLabel(),
            style = FlowTypography.labelSmall,
            color = FlowColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmojisStickerTab(
    onAddEmoji: (String) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val tileShape = RoundedCornerShape(10.dp)

    LazyVerticalGrid(
        columns = GridCells.Fixed(EMOJI_GRID_COLUMNS),
        modifier = Modifier
            .fillMaxWidth()
            .height(EMOJI_GRID_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        EmojiGroups.forEach { group ->
            item(
                key = "header_${group.title}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Text(
                    text = group.title,
                    style = FlowTypography.labelSmall,
                    color = FlowColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            items(
                items = group.emojis,
                key = { "${group.title}_$it" },
            ) { emoji ->
                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(tileShape)
                        .background(FlowColors.Slate)
                        .border(1.dp, FlowColors.Outline, tileShape)
                        .clickable { onAddEmoji(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emoji,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun ShapeKind.spanishLabel(): String = when (this) {
    ShapeKind.RECTANGLE -> "Rectángulo"
    ShapeKind.ROUNDED -> "Redondeado"
    ShapeKind.CIRCLE -> "Círculo"
    ShapeKind.TRIANGLE -> "Triángulo"
    ShapeKind.STAR -> "Estrella"
    ShapeKind.HEART -> "Corazón"
    ShapeKind.ARROW -> "Flecha"
}

private fun DrawScope.drawShapeThumbnail(
    kind: ShapeKind,
    color: Color,
    filled: Boolean,
) {
    val style = if (filled) {
        Fill
    } else {
        Stroke(
            width = 2.25.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val pad = size.minDimension * 0.14f
    val innerW = w - 2f * pad
    val innerH = h - 2f * pad

    when (kind) {
        ShapeKind.RECTANGLE -> {
            drawRect(
                color = color,
                topLeft = Offset(pad, pad),
                size = Size(innerW, innerH),
                style = style,
            )
        }
        ShapeKind.ROUNDED -> {
            val radius = size.minDimension * 0.2f
            drawRoundRect(
                color = color,
                topLeft = Offset(pad, pad),
                size = Size(innerW, innerH),
                cornerRadius = CornerRadius(radius, radius),
                style = style,
            )
        }
        ShapeKind.CIRCLE -> {
            drawCircle(
                color = color,
                radius = size.minDimension * 0.36f,
                center = Offset(cx, cy),
                style = style,
            )
        }
        ShapeKind.TRIANGLE -> {
            val path = Path().apply {
                moveTo(cx, pad)
                lineTo(w - pad, h - pad)
                lineTo(pad, h - pad)
                close()
            }
            drawPath(path = path, color = color, style = style)
        }
        ShapeKind.STAR -> {
            val outerR = size.minDimension * 0.38f
            val innerR = outerR * 0.44f
            val path = Path().apply {
                for (i in 0 until 10) {
                    val angle = -PI / 2.0 + i * (PI / 5.0)
                    val r = if (i % 2 == 0) outerR else innerR
                    val x = cx + (cos(angle) * r).toFloat()
                    val y = cy + (sin(angle) * r).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(path = path, color = color, style = style)
        }
        ShapeKind.HEART -> {
            val topY = pad + innerH * 0.28f
            val bottomY = h - pad
            val path = Path().apply {
                moveTo(cx, bottomY)
                cubicTo(
                    pad - innerW * 0.06f, pad + innerH * 0.56f,
                    pad + innerW * 0.06f, pad,
                    cx, topY,
                )
                cubicTo(
                    w - pad - innerW * 0.06f, pad,
                    w - pad + innerW * 0.06f, pad + innerH * 0.56f,
                    cx, bottomY,
                )
                close()
            }
            drawPath(path = path, color = color, style = style)
        }
        ShapeKind.ARROW -> {
            val shaftRight = pad + innerW * 0.52f
            val shaftTop = cy - innerH * 0.18f
            val shaftBottom = cy + innerH * 0.18f
            val headTop = pad + innerH * 0.06f
            val headBottom = h - pad - innerH * 0.06f
            val path = Path().apply {
                moveTo(pad, shaftTop)
                lineTo(shaftRight, shaftTop)
                lineTo(shaftRight, headTop)
                lineTo(w - pad, cy)
                lineTo(shaftRight, headBottom)
                lineTo(shaftRight, shaftBottom)
                lineTo(pad, shaftBottom)
                close()
            }
            drawPath(path = path, color = color, style = style)
        }
    }
}

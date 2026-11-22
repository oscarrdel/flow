package com.rainyworks.flow.feature.editor.ui

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.material3.IconButton

import androidx.compose.material3.DropdownMenuItem

import androidx.compose.material3.DropdownMenu

import androidx.compose.material.icons.automirrored.rounded.List

import androidx.compose.material.icons.rounded.MoreVert

import androidx.compose.material.icons.rounded.DeleteOutline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rainyworks.flow.core.designsystem.components.FlowBottomPanel
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.designsystem.theme.TimecodeStyle
import com.rainyworks.flow.core.model.ClipKind
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.SubtitleCue
import com.rainyworks.flow.core.model.SubtitleEdits
import com.rainyworks.flow.core.model.TextAlign
import com.rainyworks.flow.core.model.TextAnimation
import com.rainyworks.flow.core.model.TextAnimationType
import com.rainyworks.flow.core.model.TextBackground
import com.rainyworks.flow.core.model.TextContent
import com.rainyworks.flow.core.model.TextFill
import com.rainyworks.flow.core.model.TextShadow
import com.rainyworks.flow.core.model.TextStroke
import com.rainyworks.flow.core.model.Timecode
import com.rainyworks.flow.feature.editor.EditorAction
import com.rainyworks.flow.feature.editor.EditorSheet
import com.rainyworks.flow.feature.editor.EditorUiState
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val TEXT_PANEL_MAX_HEIGHT = 310.dp
private val SUBTITLES_LIST_MAX_HEIGHT = 150.dp
private const val DEFAULT_SUBTITLE_TEXT = "Subtítulo"
private const val DEFAULT_SUBTITLE_DURATION_US = 2_000_000L

private val TextColorPalette = listOf(
    0xFFFFFFFFL,
    0xFF000000L,
    0xFF4BE3C1L,
    0xFFFFD60AL,
    0xFFFF5A5FL,
    0xFFFF9500L,
    0xFFFF2D92L,
    0xFFAF52DEL,
    0xFF3B8BFFL,
    0xFF32D2E6L,
    0xFF34C759L,
    0xFFF6C177L,
    0xFFA6ABB4L,
    0xFF1F2227L,
)

private enum class TextEditorTab(val label: String, val icon: ImageVector) {
    Presets("Estilos", Icons.Rounded.AutoAwesome),
    Font("Fuente", Icons.Rounded.FontDownload),
    Style("Estilo", Icons.Rounded.FormatSize),
    Color("Color", Icons.Rounded.Palette),
    Stroke("Borde", Icons.Rounded.BorderColor),
    Shadow("Sombra", Icons.Rounded.BlurOn),
    Background("Fondo", Icons.Rounded.CheckBoxOutlineBlank),
    Animation("Animación", Icons.Rounded.Animation),
}

private enum class AnimationPhase(val label: String) {
    Enter("Entrada"),
    Exit("Salida"),
    Loop("Bucle"),
}

// region Text Panel

@Composable
internal fun TextSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val clip = rememberSelectedClip(state)?.takeIf { it.kind == ClipKind.TEXT && it.text != null }
    val clipId = clip?.id
    val content = clip?.text ?: TextContent(text = "")
    val latestOnAction by rememberUpdatedState(onAction)
    val latestContent by rememberUpdatedState(content)

    var localText by remember(clipId) { mutableStateOf(content.text) }
    var textFocused by remember(clipId) { mutableStateOf(false) }
    var textDirty by remember(clipId) { mutableStateOf(false) }
    val latestLocalText by rememberUpdatedState(localText)

    LaunchedEffect(content.text, textFocused) {
        if (!textFocused && localText != content.text) {
            localText = content.text
        }
    }

    fun emitContent(updated: TextContent, final: Boolean) {
        val targetId = clipId ?: return
        latestOnAction(EditorAction.SetTextContent(targetId, updated, final))
    }

    fun flushPendingText() {
        val targetId = clipId ?: return
        if (textDirty) {
            textDirty = false
            latestOnAction(
                EditorAction.SetTextContent(
                    clipId = targetId,
                    content = latestContent.copy(text = latestLocalText),
                    final = true,
                ),
            )
        }
    }

    DisposableEffect(clipId) {
        onDispose { flushPendingText() }
    }

    var activeTab by remember(clipId, content.isSubtitle) {
        mutableStateOf(if (content.isSubtitle) TextEditorTab.Font else TextEditorTab.Presets)
    }
    val visibleTabs = remember(content.isSubtitle) {
        if (content.isSubtitle) {
            TextEditorTab.entries.filterNot { it == TextEditorTab.Presets }
        } else {
            TextEditorTab.entries
        }
    }
    val effectiveTab = if (content.isSubtitle && activeTab == TextEditorTab.Presets) {
        TextEditorTab.Font
    } else {
        activeTab
    }

    FlowBottomPanel(
        title = if (content.isSubtitle) "Editar subtítulo" else "Texto",
        subtitle = "Plantillas, tipografía, estilo y animación",
        onDismiss = {
            flushPendingText()
            onAction(EditorAction.DismissSheet)
        },
        headerTrailing = {
            SheetActionChip(
                label = "Subtítulos",
                icon = Icons.Rounded.Subtitles,
                enabled = true,
                onClick = {
                    flushPendingText()
                    onAction(EditorAction.OpenSheet(EditorSheet.Subtitles))
                },
            )
        },
        modifier = modifier,
    ) {
        if (clip == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetEmptyMessage(
                    text = "Selecciona un clip de texto o crea uno nuevo.",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.sm))
                SheetActionChip(
                    label = "Añadir texto",
                    icon = Icons.Rounded.Add,
                    enabled = true,
                    onClick = { onAction(EditorAction.AddText()) },
                )
            }
            return@FlowBottomPanel
        }

        Column(
            modifier = Modifier
                .heightIn(max = TEXT_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            MultilineTextBox(
                value = localText,
                placeholder = "Escribe tu texto…",
                onValueChange = { updatedText ->
                    localText = updatedText
                    textDirty = true
                    emitContent(latestContent.copy(text = updatedText), final = false)
                },
                onFocusLost = ::flushPendingText,
                onFocusStateChange = { textFocused = it },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                visibleTabs.forEach { tab ->
                    SheetActionChip(
                        label = tab.label,
                        icon = tab.icon,
                        enabled = true,
                        selected = tab == effectiveTab,
                        onClick = { activeTab = tab },
                    )
                }
            }

            when (effectiveTab) {
                TextEditorTab.Presets -> TextStylePresetsTab(
                    content = content,
                    onSelectPreset = { preset ->
                        val updated = preset.apply(latestContent.copy(text = latestLocalText))
                        textDirty = false
                        emitContent(updated, final = true)
                    },
                )
                TextEditorTab.Font -> TextFontTab(
                    selectedFontId = content.font,
                    customFonts = state.customFonts,
                    onSelectFont = { fontId ->
                        emitContent(latestContent.copy(text = latestLocalText, font = fontId), final = true)
                    },
                    onImportFont = { onAction(EditorAction.ImportFont) },
                )
                TextEditorTab.Style -> TextStyleTab(
                    content = content,
                    onUpdate = { updated, final ->
                        emitContent(updated.copy(text = latestLocalText), final)
                    },
                )
                TextEditorTab.Color -> TextColorTab(
                    fill = content.fill,
                    onUpdate = { updatedFill ->
                        emitContent(latestContent.copy(text = latestLocalText, fill = updatedFill), final = true)
                    },
                )
                TextEditorTab.Stroke -> TextStrokeTab(
                    stroke = content.stroke,
                    onUpdate = { updatedStroke, final ->
                        emitContent(latestContent.copy(text = latestLocalText, stroke = updatedStroke), final)
                    },
                )
                TextEditorTab.Shadow -> TextShadowTab(
                    shadow = content.shadow,
                    onUpdate = { updatedShadow, final ->
                        emitContent(latestContent.copy(text = latestLocalText, shadow = updatedShadow), final)
                    },
                )
                TextEditorTab.Background -> TextBackgroundTab(
                    background = content.background,
                    onUpdate = { updatedBg, final ->
                        emitContent(latestContent.copy(text = latestLocalText, background = updatedBg), final)
                    },
                )
                TextEditorTab.Animation -> TextAnimationTab(
                    content = content,
                    onUpdate = { updated, final ->
                        emitContent(updated.copy(text = latestLocalText), final)
                    },
                )
            }
        }
    }
}

@Composable
private fun MultilineTextBox(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onFocusLost: () -> Unit,
    onFocusStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    var hadFocus by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = FlowTypography.bodyMedium.copy(color = FlowColors.TextPrimary),
        minLines = 1,
        maxLines = 3,
        shape = shape,
        placeholder = {
            Text(
                text = placeholder,
                style = FlowTypography.bodyMedium,
                color = FlowColors.TextTertiary,
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = FlowColors.TextPrimary,
            unfocusedTextColor = FlowColors.TextPrimary,
            focusedContainerColor = FlowColors.Slate,
            unfocusedContainerColor = FlowColors.Slate,
            focusedBorderColor = FlowColors.Accent,
            unfocusedBorderColor = FlowColors.Outline,
            cursorColor = FlowColors.Accent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                onFocusStateChange(state.isFocused)
                if (hadFocus && !state.isFocused) {
                    onFocusLost()
                }
                hadFocus = state.isFocused
            },
    )
}


@Composable
private fun TextStylePresetsTab(
    content: TextContent,
    onSelectPreset: (TextStylePreset) -> Unit,
) {
    val spacing = FlowTheme.spacing
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(TextStylePresets, key = { it.id }) { preset ->
            val isSelected = content == preset.apply(content)
            TextStylePresetCard(
                preset = preset,
                selected = isSelected,
                onClick = { onSelectPreset(preset) },
            )
        }
    }
}

@Composable
private fun TextStylePresetCard(
    preset: TextStylePreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val sample = remember(preset.id) { preset.apply(TextContent(text = "Aa")) }
    val cardShape = RoundedCornerShape(10.dp)
    val previewShape = RoundedCornerShape(6.dp)
    val baseStyle = TextStyle(
        fontFamily = FlowFonts.family(sample.font),
        fontSize = 20.sp,
        fontWeight = if (sample.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (sample.italic) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = (sample.letterSpacing * 0.5f).coerceIn(-0.04f, 0.14f).em,
    )
    val previewShadow = sample.shadow?.let { sh ->
        Shadow(
            color = Color(sh.argb),
            offset = Offset(sh.offsetX * 22f, sh.offsetY * 22f),
            blurRadius = (sh.blur * 26f).coerceAtLeast(0.5f),
        )
    }

    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(cardShape)
            .background(if (selected) FlowColors.AccentDim.copy(alpha = 0.45f) else FlowColors.Slate)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) FlowColors.Accent else FlowColors.Outline,
                shape = cardShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(previewShape)
                .background(Color(0xFF14161A)),
            contentAlignment = Alignment.Center,
        ) {
            val bg = sample.background
            val badgeModifier = if (bg != null) {
                Modifier
                    .clip(RoundedCornerShape((bg.cornerRadius * 14f).dp))
                    .background(Color(bg.argb))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            } else {
                Modifier
            }
            Box(
                modifier = badgeModifier,
                contentAlignment = Alignment.Center,
            ) {
                val stroke = sample.stroke?.takeIf { it.width > 0f }
                if (stroke != null) {
                    Text(
                        text = "Aa",
                        style = baseStyle.copy(
                            color = Color(stroke.argb),
                            shadow = previewShadow,
                            drawStyle = Stroke(
                                width = (stroke.width * 24f).coerceIn(1.5f, 6f),
                                join = StrokeJoin.Round,
                            ),
                        ),
                        maxLines = 1,
                    )
                }
                val fillShadow = if (stroke == null) previewShadow else null
                val gradientEnd = sample.fill.gradientEndArgb
                val fillStyle = if (gradientEnd != null) {
                    baseStyle.copy(
                        brush = Brush.verticalGradient(
                            listOf(Color(sample.fill.argb), Color(gradientEnd)),
                        ),
                        shadow = fillShadow,
                    )
                } else {
                    baseStyle.copy(
                        color = Color(sample.fill.argb),
                        shadow = fillShadow,
                    )
                }
                Text(
                    text = "Aa",
                    style = fillStyle,
                    maxLines = 1,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = preset.name,
                style = FlowTypography.labelSmall,
                color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = ComposeTextAlign.Center,
            )
        }
    }
}

@Composable
private fun TextFontTab(
    selectedFontId: String,
    customFonts: List<Pair<String, String>>,
    onSelectFont: (String) -> Unit,
    onImportFont: () -> Unit,
) {
    val spacing = FlowTheme.spacing
    val allFonts = remember(customFonts) {
        val system = FlowFonts.System.map { (id, name) -> id to name }
        val custom = customFonts.map { (name, uri) -> uri to name }
        system + custom
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tipografía",
                style = FlowTypography.labelLarge,
                color = FlowColors.TextSecondary,
            )
            SheetActionChip(
                label = "Importar fuente",
                icon = Icons.Rounded.FileOpen,
                enabled = true,
                onClick = onImportFont,
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(allFonts, key = { it.first }) { (fontId, displayName) ->
                val selected = fontId == selectedFontId
                val shape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (selected) FlowColors.AccentDim.copy(alpha = 0.5f) else FlowColors.Slate)
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) FlowColors.Accent else FlowColors.Outline,
                            shape = shape,
                        )
                        .clickable { onSelectFont(fontId) }
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = displayName,
                        style = FlowTypography.bodyMedium.copy(
                            fontFamily = FlowFonts.family(fontId),
                        ),
                        color = if (selected) FlowColors.Accent else FlowColors.TextPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextStyleTab(
    content: TextContent,
    onUpdate: (TextContent, Boolean) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val current by rememberUpdatedState(content)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetActionChip(
                label = "Negrita",
                icon = Icons.Rounded.FormatBold,
                enabled = true,
                selected = content.bold,
                onClick = { onUpdate(current.copy(bold = !current.bold), true) },
            )
            SheetActionChip(
                label = "Cursiva",
                icon = Icons.Rounded.FormatItalic,
                enabled = true,
                selected = content.italic,
                onClick = { onUpdate(current.copy(italic = !current.italic), true) },
            )
            SheetActionChip(
                label = "Izquierda",
                icon = Icons.AutoMirrored.Rounded.FormatAlignLeft,
                enabled = true,
                selected = content.align == TextAlign.START,
                onClick = { onUpdate(current.copy(align = TextAlign.START), true) },
            )
            SheetActionChip(
                label = "Centro",
                icon = Icons.Rounded.FormatAlignCenter,
                enabled = true,
                selected = content.align == TextAlign.CENTER,
                onClick = { onUpdate(current.copy(align = TextAlign.CENTER), true) },
            )
            SheetActionChip(
                label = "Derecha",
                icon = Icons.AutoMirrored.Rounded.FormatAlignRight,
                enabled = true,
                selected = content.align == TextAlign.END,
                onClick = { onUpdate(current.copy(align = TextAlign.END), true) },
            )
        }

        ResettableSlider(
            label = "Tamaño",
            value = content.size.coerceIn(0.02f, 0.20f),
            valueRange = 0.02f..0.20f,
            valueText = { "${(it * 100f).roundToInt()}%" },
            resetValue = 0.06f,
            onValueChange = { v, final ->
                onUpdate(current.copy(size = (v * 1000f).roundToInt() / 1000f), final)
            },
        )

        ResettableSlider(
            label = "Espaciado entre letras",
            value = content.letterSpacing.coerceIn(-0.05f, 0.40f),
            valueRange = -0.05f..0.40f,
            valueText = { String.format(Locale.US, "%.2f em", it) },
            resetValue = 0f,
            onValueChange = { v, final ->
                onUpdate(current.copy(letterSpacing = (v * 100f).roundToInt() / 100f), final)
            },
        )

        ResettableSlider(
            label = "Interlineado",
            value = content.lineHeight.coerceIn(0.8f, 2.2f),
            valueRange = 0.8f..2.2f,
            valueText = { String.format(Locale.US, "%.2fx", it) },
            resetValue = 1.2f,
            onValueChange = { v, final ->
                onUpdate(current.copy(lineHeight = (v * 100f).roundToInt() / 100f), final)
            },
        )
    }
}

@Composable
private fun TextColorTab(
    fill: TextFill,
    onUpdate: (TextFill) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val hasGradient = fill.gradientEndArgb != null

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = "Color principal",
            style = FlowTypography.labelLarge,
            color = FlowColors.TextSecondary,
        )
        ColorSwatchRow(
            colors = TextColorPalette,
            selectedArgb = fill.argb,
            onSelect = { argb -> onUpdate(fill.copy(argb = argb)) },
        )

        SheetSwitchRow(
            label = "Degradado de dos colores",
            checked = hasGradient,
            onCheckedChange = { enabled ->
                val endColor = if (enabled) {
                    TextColorPalette.firstOrNull { (it and 0xFFFFFFL) != (fill.argb and 0xFFFFFFL) } ?: 0xFF4BE3C1L
                } else {
                    null
                }
                onUpdate(fill.copy(gradientEndArgb = endColor))
            },
        )

        if (hasGradient) {
            Text(
                text = "Segundo color",
                style = FlowTypography.labelLarge,
                color = FlowColors.TextSecondary,
            )
            ColorSwatchRow(
                colors = TextColorPalette,
                selectedArgb = fill.gradientEndArgb,
                onSelect = { argb -> onUpdate(fill.copy(gradientEndArgb = argb)) },
            )
        }
    }
}

@Composable
private fun TextStrokeTab(
    stroke: TextStroke?,
    onUpdate: (TextStroke?, Boolean) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val current by rememberUpdatedState(stroke ?: TextStroke())

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SheetSwitchRow(
            label = "Activar borde",
            checked = stroke != null,
            onCheckedChange = { enabled ->
                onUpdate(if (enabled) TextStroke() else null, true)
            },
        )

        if (stroke != null) {
            ColorSwatchRow(
                colors = TextColorPalette,
                selectedArgb = stroke.argb,
                onSelect = { argb -> onUpdate(current.copy(argb = argb), true) },
            )
            ResettableSlider(
                label = "Grosor",
                value = stroke.width.coerceIn(0.01f, 0.25f),
                valueRange = 0.01f..0.25f,
                valueText = ::formatHundred,
                resetValue = 0.08f,
                onValueChange = { v, final ->
                    onUpdate(current.copy(width = (v * 100f).roundToInt() / 100f), final)
                },
            )
        }
    }
}

@Composable
private fun TextShadowTab(
    shadow: TextShadow?,
    onUpdate: (TextShadow?, Boolean) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val current by rememberUpdatedState(shadow ?: TextShadow())

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SheetSwitchRow(
            label = "Activar sombra",
            checked = shadow != null,
            onCheckedChange = { enabled ->
                onUpdate(if (enabled) TextShadow() else null, true)
            },
        )

        if (shadow != null) {
            ColorSwatchRow(
                colors = TextColorPalette,
                selectedArgb = shadow.argb,
                onSelect = { argb ->
                    onUpdate(current.copy(argb = replaceRgb(current.argb, argb, defaultAlpha = 0x99L)), true)
                },
            )
            ResettableSlider(
                label = "Desplazamiento X",
                value = shadow.offsetX.coerceIn(-0.20f, 0.20f),
                valueRange = -0.20f..0.20f,
                valueText = ::formatSignedHundred,
                resetValue = 0.04f,
                onValueChange = { v, final ->
                    onUpdate(current.copy(offsetX = (v * 100f).roundToInt() / 100f), final)
                },
            )
            ResettableSlider(
                label = "Desplazamiento Y",
                value = shadow.offsetY.coerceIn(-0.20f, 0.20f),
                valueRange = -0.20f..0.20f,
                valueText = ::formatSignedHundred,
                resetValue = 0.04f,
                onValueChange = { v, final ->
                    onUpdate(current.copy(offsetY = (v * 100f).roundToInt() / 100f), final)
                },
            )
            ResettableSlider(
                label = "Desenfoque",
                value = shadow.blur.coerceIn(0f, 0.30f),
                valueRange = 0f..0.30f,
                valueText = ::formatHundred,
                resetValue = 0.08f,
                onValueChange = { v, final ->
                    onUpdate(current.copy(blur = (v * 100f).roundToInt() / 100f), final)
                },
            )
        }
    }
}

@Composable
private fun TextBackgroundTab(
    background: TextBackground?,
    onUpdate: (TextBackground?, Boolean) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val current by rememberUpdatedState(background ?: TextBackground())

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SheetSwitchRow(
            label = "Activar fondo",
            checked = background != null,
            onCheckedChange = { enabled ->
                onUpdate(if (enabled) TextBackground() else null, true)
            },
        )

        if (background != null) {
            ColorSwatchRow(
                colors = TextColorPalette,
                selectedArgb = background.argb,
                onSelect = { argb ->
                    onUpdate(current.copy(argb = replaceRgb(current.argb, argb, defaultAlpha = 0xCCL)), true)
                },
            )
            ResettableSlider(
                label = "Opacidad",
                value = argbAlpha(background.argb).coerceIn(0.05f, 1f),
                valueRange = 0.05f..1f,
                valueText = { "${(it * 100f).roundToInt()}%" },
                resetValue = 0.8f,
                onValueChange = { v, final ->
                    onUpdate(current.copy(argb = withAlpha(current.argb, v)), final)
                },
            )
            ResettableSlider(
                label = "Radio de esquinas",
                value = background.cornerRadius.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                valueText = ::formatHundred,
                resetValue = 0.2f,
                onValueChange = { v, final ->
                    onUpdate(current.copy(cornerRadius = (v * 100f).roundToInt() / 100f), final)
                },
            )
        }
    }
}

@Composable
private fun TextAnimationTab(
    content: TextContent,
    onUpdate: (TextContent, Boolean) -> Unit,
) {
    val spacing = FlowTheme.spacing
    val current by rememberUpdatedState(content)
    var phase by remember { mutableStateOf(AnimationPhase.Enter) }

    val activeAnimation = when (phase) {
        AnimationPhase.Enter -> content.enter
        AnimationPhase.Exit -> content.exit
        AnimationPhase.Loop -> content.loop
    }

    fun updatePhase(animation: TextAnimation, final: Boolean) {
        val updated = when (phase) {
            AnimationPhase.Enter -> current.copy(enter = animation)
            AnimationPhase.Exit -> current.copy(exit = animation)
            AnimationPhase.Loop -> current.copy(loop = animation)
        }
        onUpdate(updated, final)
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SheetChipRow(
            options = AnimationPhase.entries,
            selected = phase,
            label = { it.label },
            onSelect = { phase = it },
        )

        SheetChipRow(
            options = TextAnimationType.entries,
            selected = activeAnimation.type,
            label = { it.spanishLabel() },
            onSelect = { type ->
                updatePhase(activeAnimation.copy(type = type), final = true)
            },
        )

        if (activeAnimation.type != TextAnimationType.NONE) {
            val seconds = (activeAnimation.durationUs / 1_000_000f).coerceIn(0.1f, 3.0f)
            ResettableSlider(
                label = if (phase == AnimationPhase.Loop) "Velocidad de ciclo" else "Duración",
                value = seconds,
                valueRange = 0.1f..3.0f,
                valueText = { String.format(Locale.US, "%.1fs", it) },
                resetValue = 0.5f,
                onValueChange = { sec, final ->
                    val us = (sec * 1_000_000f).roundToLong().coerceIn(100_000L, 3_000_000L)
                    updatePhase(activeAnimation.copy(durationUs = us), final)
                },
            )
        }
    }
}

private fun TextAnimationType.spanishLabel(): String = when (this) {
    TextAnimationType.NONE -> "Ninguna"
    TextAnimationType.TYPEWRITER -> "Máquina de escribir"
    TextAnimationType.FADE -> "Fundido"
    TextAnimationType.BOUNCE -> "Rebote"
    TextAnimationType.SLIDE_UP -> "Deslizar arriba"
    TextAnimationType.SLIDE_DOWN -> "Deslizar abajo"
    TextAnimationType.SLIDE_LEFT -> "Deslizar izquierda"
    TextAnimationType.SLIDE_RIGHT -> "Deslizar derecha"
    TextAnimationType.SCALE -> "Escala"
}

private fun replaceRgb(currentArgb: Long, newRgb: Long, defaultAlpha: Long): Long {
    val alpha = (currentArgb ushr 24) and 0xFFL
    val effectiveAlpha = if (alpha == 0L) defaultAlpha else alpha
    return (effectiveAlpha shl 24) or (newRgb and 0xFFFFFFL)
}

private fun argbAlpha(argb: Long): Float = ((argb ushr 24) and 0xFFL) / 255f

private fun withAlpha(argb: Long, alpha: Float): Long {
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToLong().coerceIn(0L, 255L)
    return (a shl 24) or (argb and 0xFFFFFFL)
}

// endregion

// region Subtitles Panel

private data class SubtitleBlockItem(
    val clipId: String,
    val cue: SubtitleCue,
)

private fun findSubtitleBlocks(project: Project?): List<SubtitleBlockItem> {
    if (project == null) return emptyList()
    val cues = SubtitleEdits.cues(project)
    val subtitleClips = project.tracks
        .flatMap { it.clips }
        .filter { it.kind == ClipKind.TEXT && it.text?.isSubtitle == true }
        .sortedBy { it.startUs }
    return subtitleClips.mapIndexed { index, clip ->
        val cue = cues.getOrNull(index)?.takeIf { it.startUs == clip.startUs && it.endUs == clip.endUs }
            ?: SubtitleCue(clip.startUs, clip.endUs, clip.text?.text.orEmpty())
        SubtitleBlockItem(clipId = clip.id, cue = cue)
    }
}

@Composable
internal fun SubtitlesSheetPanel(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val blocks = remember(state.project) { findSubtitleBlocks(state.project) }
    val fps = state.fps.coerceAtLeast(1)
    val frameUs = remember(fps) { Timecode.frameDurationUs(fps).coerceAtLeast(1L) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val current = blocks.firstOrNull { state.playheadUs in it.cue.startUs until it.cue.endUs }

    FlowBottomPanel(
        title = "Subtítulos",
        subtitle = when (blocks.size) {
            0 -> "Aún no hay subtítulos"
            1 -> "1 subtítulo"
            else -> "${blocks.size} subtítulos"
        },
        onDismiss = { onAction(EditorAction.DismissSheet) },
        modifier = modifier,
        headerTrailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Más opciones", tint = FlowColors.TextSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Importar archivo .srt") },
                        leadingIcon = { Icon(Icons.Rounded.FileOpen, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAction(EditorAction.ImportSrt)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Guardar archivo .srt") },
                        leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                        enabled = blocks.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            onAction(EditorAction.ExportSrt)
                        },
                    )
                }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            SheetActionChip(
                label = "Agregar",
                icon = Icons.Rounded.Add,
                enabled = true,
                onClick = {
                    val startUs = state.playheadUs.coerceAtLeast(0L)
                    showList = false
                    onAction(
                        EditorAction.AddSubtitle(
                            SubtitleCue(startUs = startUs, endUs = startUs + DEFAULT_SUBTITLE_DURATION_US, text = DEFAULT_SUBTITLE_TEXT),
                        ),
                    )
                },
            )
            SheetActionChip(
                label = if (showList) "Ocultar lista" else "Ver todos (${blocks.size})",
                icon = Icons.AutoMirrored.Rounded.List,
                enabled = blocks.isNotEmpty(),
                onClick = { showList = !showList },
            )
        }

        Spacer(Modifier.height(spacing.sm))

        when {
            showList -> Column(
                modifier = Modifier
                    .heightIn(max = SUBTITLES_LIST_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                blocks.forEach { block ->
                    val active = state.playheadUs in block.cue.startUs until block.cue.endUs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showList = false
                                onAction(EditorAction.Seek(block.cue.startUs, final = true))
                            }
                            .padding(horizontal = spacing.sm, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = readableTime(block.cue.startUs),
                            style = TimecodeStyle,
                            color = if (active) FlowColors.Accent else FlowColors.TextSecondary,
                        )
                        Spacer(Modifier.size(spacing.md))
                        Text(
                            text = block.cue.text,
                            style = FlowTypography.bodyMedium,
                            color = if (active) FlowColors.Accent else FlowColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            current != null -> {
                SubtitleBlockCard(
                    block = current,
                    playheadUs = state.playheadUs,
                    fps = fps,
                    frameUs = frameUs,
                    onSeekToStart = { onAction(EditorAction.Seek(current.cue.startUs, final = true)) },
                    onUpdateCue = { updatedCue -> onAction(EditorAction.UpdateSubtitle(current.clipId, updatedCue)) },
                    onDelete = { onAction(EditorAction.RemoveSubtitle(current.clipId)) },
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = "Arrastra sus bordes en la línea de tiempo para cambiar cuánto dura.",
                    style = FlowTypography.bodySmall,
                    color = FlowColors.TextTertiary,
                )
            }
            blocks.isEmpty() -> SheetEmptyMessage("Toca «Agregar» para crear un subtítulo donde está el cabezal.")
            else -> SheetEmptyMessage("Mueve el cabezal sobre un subtítulo para editar su texto, o toca «Ver todos».")
        }
    }
}

@Composable
private fun SubtitleBlockCard(
    block: SubtitleBlockItem,
    playheadUs: Long,
    fps: Int,
    frameUs: Long,
    onSeekToStart: () -> Unit,
    onUpdateCue: (SubtitleCue) -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = FlowTheme.spacing
    val cue = block.cue
    val isActiveAtPlayhead = playheadUs in cue.startUs until cue.endUs
    val shape = RoundedCornerShape(12.dp)
    val latestCue by rememberUpdatedState(cue)
    val latestOnUpdateCue by rememberUpdatedState(onUpdateCue)

    var localText by remember(block.clipId) { mutableStateOf(cue.text) }
    var focused by remember(block.clipId) { mutableStateOf(false) }

    LaunchedEffect(cue.text, focused) {
        if (!focused && localText != cue.text) {
            localText = cue.text
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FlowColors.Slate)
            .border(
                width = if (isActiveAtPlayhead) 1.5.dp else 1.dp,
                color = if (isActiveAtPlayhead) FlowColors.Accent else FlowColors.Outline,
                shape = shape,
            )
            .clickable { onSeekToStart() }
            .padding(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${readableTime(cue.startUs)} – ${readableTime(cue.endUs)}",
                style = TimecodeStyle,
                color = if (isActiveAtPlayhead) FlowColors.Accent else FlowColors.TextPrimary,
            )
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Eliminar subtítulo",
                tint = FlowColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDelete)
                    .padding(4.dp)
                    .size(20.dp),
            )
        }

        MultilineTextBox(
            value = localText,
            placeholder = DEFAULT_SUBTITLE_TEXT,
            onValueChange = { updated ->
                localText = updated
                if (updated.isNotBlank()) {
                    latestOnUpdateCue(latestCue.copy(text = updated))
                }
            },
            onFocusLost = {
                val safeText = localText.ifBlank { DEFAULT_SUBTITLE_TEXT }
                localText = safeText
                if (safeText != latestCue.text) {
                    latestOnUpdateCue(latestCue.copy(text = safeText))
                }
            },
            onFocusStateChange = { focused = it },
        )

    }
}

/** Seconds with one decimal, e.g. 0:01.5 — easier to read than frame-based timecodes. */
private fun readableTime(us: Long): String {
    val tenths = (us.coerceAtLeast(0L) + 50_000L) / 100_000L
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}


// endregion

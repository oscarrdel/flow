package com.rainyworks.flow.feature.home.ui

import androidx.compose.ui.graphics.Color
import com.rainyworks.flow.core.designsystem.components.FlowAurora
import com.rainyworks.flow.core.designsystem.components.glass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rainyworks.flow.core.designsystem.theme.FlowColors
import com.rainyworks.flow.core.designsystem.theme.FlowTheme
import com.rainyworks.flow.core.designsystem.theme.FlowTypography
import com.rainyworks.flow.core.designsystem.theme.TimecodeStyle
import com.rainyworks.flow.core.model.ProjectSummary
import com.rainyworks.flow.core.model.Timecode
import com.rainyworks.flow.feature.home.HomeAction
import com.rainyworks.flow.feature.home.HomeUiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    var projectToRename by remember { mutableStateOf<ProjectSummary?>(null) }
    var projectToDelete by remember { mutableStateOf<ProjectSummary?>(null) }

    Box(modifier.fillMaxSize().background(FlowColors.Carbon)) {
    FlowAurora()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(FlowColors.Accent, FlowColors.Rosa))),
                        )
                        Text(
                            text = "Flow",
                            style = FlowTypography.displaySmall,
                            color = FlowColors.TextPrimary,
                        )
                    }
                },
                actions = {
                    val cacheLabel = remember(state.cacheBytes) {
                        formatStorageSize(state.cacheBytes)
                    }
                    TextButton(
                        onClick = { onAction(HomeAction.ClearCache) },
                        enabled = state.cacheBytes > 0L,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = FlowColors.TextSecondary,
                            disabledContentColor = FlowColors.TextTertiary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CleaningServices,
                            contentDescription = "Limpiar caché",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            text = "Limpiar caché ($cacheLabel)",
                            style = FlowTypography.labelLarge,
                        )
                    }
                    IconButton(onClick = { onAction(HomeAction.OpenAbout) }) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Ajustes y licencias",
                            tint = FlowColors.TextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = FlowColors.TextPrimary,
                ),
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 164.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = spacing.lg,
                end = spacing.lg,
                top = spacing.sm,
                bottom = spacing.xxl,
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(
                key = "hero_new_project",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                NewProjectBanner(
                    onClick = { onAction(HomeAction.CreateProject) },
                )
            }

            if (state.projects.isNotEmpty()) {
                item(
                    key = "section_header_drafts",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.sm, bottom = spacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Tus proyectos",
                            style = FlowTypography.headlineSmall,
                            color = FlowColors.TextPrimary,
                        )
                        Text(
                            text = "${state.projects.size} ${if (state.projects.size == 1) "proyecto" else "proyectos"}",
                            style = FlowTypography.bodySmall,
                            color = FlowColors.TextSecondary,
                        )
                    }
                }

                items(
                    items = state.projects,
                    key = { it.id },
                ) { project ->
                    ProjectDraftCard(
                        project = project,
                        onOpen = { onAction(HomeAction.OpenProject(project.id)) },
                        onRenameRequest = { projectToRename = project },
                        onDuplicate = { onAction(HomeAction.DuplicateProject(project.id)) },
                        onDeleteRequest = { projectToDelete = project },
                    )
                }
            } else if (state.loading) {
                item(
                    key = "loading_indicator",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = FlowColors.Accent,
                            strokeWidth = 3.dp,
                        )
                    }
                }
            } else {
                item(
                    key = "empty_state",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    EmptyDraftsState(
                        onCreateProject = { onAction(HomeAction.CreateProject) },
                        modifier = Modifier.padding(top = spacing.xl),
                    )
                }
            }
        }
    }

    val renaming = projectToRename
    if (renaming != null) {
        RenameProjectDialog(
            initialName = renaming.name,
            onConfirm = { newName ->
                projectToRename = null
                onAction(HomeAction.RenameProject(renaming.id, newName))
            },
            onDismiss = { projectToRename = null },
        )
    }

    val deleting = projectToDelete
    if (deleting != null) {
        DeleteProjectDialog(
            projectName = deleting.name,
            onConfirm = {
                projectToDelete = null
                onAction(HomeAction.DeleteProject(deleting.id))
            },
            onDismiss = { projectToDelete = null },
        )
    }
}
}

@Composable
private fun NewProjectBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val shape = RoundedCornerShape(28.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape, elevation = 14.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "¿Qué vas a contar hoy?",
                style = FlowTypography.titleLarge,
                color = FlowColors.TextPrimary,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = "Junta clips, fotos y música; lo demás lo armas aquí.",
                style = FlowTypography.bodySmall,
                color = FlowColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(spacing.md))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = FlowColors.OnAccent,
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
            modifier = Modifier.glass(CircleShape, tint = FlowColors.Accent, elevation = 8.dp, strong = true),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(spacing.xs))
            Text(
                text = "Nuevo",
                style = FlowTypography.labelLarge,
            )
        }
    }
}

@Composable
private fun ProjectDraftCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRenameRequest: () -> Unit,
    onDuplicate: () -> Unit,
    onDeleteRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing
    val cardShape = RoundedCornerShape(24.dp)
    var menuExpanded by remember { mutableStateOf(false) }

    val durationText = remember(project.durationUs) {
        Timecode.formatShort(project.durationUs)
    }
    val sizeText = remember(project.sizeBytes) {
        formatStorageSize(project.sizeBytes)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .glass(cardShape)
            .clip(cardShape)
            .clickable(onClick = onOpen),
        color = Color.Transparent,
        shape = cardShape,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = FlowColors.TextTertiary.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp),
                )

                if (!project.thumbnailPath.isNullOrBlank()) {
                    AsyncImage(
                        model = project.thumbnailPath,
                        contentDescription = project.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Duration badge at bottom-right
                Text(
                    text = durationText,
                    style = TimecodeStyle,
                    color = FlowColors.TextPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(spacing.sm)
                        .clip(CircleShape)
                        .background(FlowColors.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = spacing.md,
                        end = spacing.xs,
                        top = spacing.sm,
                        bottom = spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name.ifBlank { "Proyecto sin título" },
                        style = FlowTypography.titleSmall,
                        color = FlowColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = sizeText,
                        style = FlowTypography.bodySmall,
                        color = FlowColors.TextSecondary,
                        maxLines = 1,
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Opciones del proyecto",
                            tint = FlowColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = FlowColors.Slate,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Renombrar", style = FlowTypography.bodyMedium) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DriveFileRenameOutline,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRenameRequest()
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = FlowColors.TextPrimary,
                                leadingIconColor = FlowColors.TextSecondary,
                            ),
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicar", style = FlowTypography.bodyMedium) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = FlowColors.TextPrimary,
                                leadingIconColor = FlowColors.TextSecondary,
                            ),
                        )
                        DropdownMenuItem(
                            text = { Text("Eliminar", style = FlowTypography.bodyMedium) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDeleteRequest()
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = FlowColors.Error,
                                leadingIconColor = FlowColors.Error,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDraftsState(
    onCreateProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FlowTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(28.dp))
            .padding(horizontal = spacing.xl, vertical = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .glass(CircleShape, elevation = 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.VideoLibrary,
                contentDescription = null,
                tint = FlowColors.Accent,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(spacing.md))
        Text(
            text = "Aún no tienes borradores",
            style = FlowTypography.titleMedium,
            color = FlowColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = "Tus proyectos guardados aparecerán aquí con su miniatura, duración y tamaño.",
            style = FlowTypography.bodyMedium,
            color = FlowColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.lg))
        TextButton(
            onClick = onCreateProject,
            colors = ButtonDefaults.textButtonColors(contentColor = FlowColors.Accent),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(spacing.xs))
            Text(
                text = "Crear mi primer proyecto",
                style = FlowTypography.labelLarge,
            )
        }
    }
}

@Composable
private fun RenameProjectDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FlowColors.Graphite,
        titleContentColor = FlowColors.TextPrimary,
        textContentColor = FlowColors.TextSecondary,
        title = {
            Text(
                text = "Renombrar proyecto",
                style = FlowTypography.titleMedium,
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre del proyecto") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FlowColors.Accent,
                    unfocusedBorderColor = FlowColors.Outline,
                    focusedLabelColor = FlowColors.Accent,
                    unfocusedLabelColor = FlowColors.TextSecondary,
                    focusedTextColor = FlowColors.TextPrimary,
                    unfocusedTextColor = FlowColors.TextPrimary,
                    cursorColor = FlowColors.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlowColors.Accent,
                    contentColor = FlowColors.OnAccent,
                ),
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = FlowColors.TextSecondary),
            ) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun DeleteProjectDialog(
    projectName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FlowColors.Graphite,
        titleContentColor = FlowColors.TextPrimary,
        textContentColor = FlowColors.TextSecondary,
        title = {
            Text(
                text = "Eliminar proyecto",
                style = FlowTypography.titleMedium,
            )
        },
        text = {
            Text(
                text = "¿Seguro que deseas eliminar «$projectName»? Este borrador se borrará de forma permanente.",
                style = FlowTypography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlowColors.Error,
                    contentColor = FlowColors.Black,
                ),
            ) {
                Text("Eliminar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = FlowColors.TextSecondary),
            ) {
                Text("Cancelar")
            }
        },
    )
}

/**
 * Formats byte counts into human-readable Spanish MB/GB labels.
 */
internal fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        val gb = mb / 1024.0
        String.format(Locale.US, "%.2f GB", gb)
    } else if (mb >= 10.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else if (mb >= 0.1) {
        String.format(Locale.US, "%.2f MB", mb)
    } else {
        val kb = (bytes.toDouble() / 1024.0).coerceAtLeast(1.0)
        String.format(Locale.US, "%.0f KB", kb)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 412, heightDp = 892)
@Composable
private fun HomeContentPreview() {
    val fakeProjects = listOf(
        ProjectSummary(
            id = "p1",
            name = "Viaje a la Patagonia",
            updatedAtMs = 1_730_000_000_000L,
            durationUs = 142_500_000L,
            thumbnailPath = null,
            sizeBytes = 384L * 1024L * 1024L,
        ),
        ProjectSummary(
            id = "p2",
            name = "Reel Cafetería Especial",
            updatedAtMs = 1_729_900_000_000L,
            durationUs = 28_000_000L,
            thumbnailPath = null,
            sizeBytes = 64L * 1024L * 1024L,
        ),
        ProjectSummary(
            id = "p3",
            name = "Documental Nocturno 4K",
            updatedAtMs = 1_729_800_000_000L,
            durationUs = 615_000_000L,
            thumbnailPath = null,
            sizeBytes = 1_640L * 1024L * 1024L,
        ),
        ProjectSummary(
            id = "p4",
            name = "Teaser Lanzamiento",
            updatedAtMs = 1_729_700_000_000L,
            durationUs = 15_200_000L,
            thumbnailPath = null,
            sizeBytes = 24L * 1024L * 1024L,
        ),
    )

    FlowTheme {
        HomeContent(
            state = HomeUiState(
                projects = fakeProjects,
                loading = false,
                cacheBytes = 192L * 1024L * 1024L,
            ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0F12, widthDp = 412, heightDp = 720)
@Composable
private fun HomeContentEmptyPreview() {
    FlowTheme {
        HomeContent(
            state = HomeUiState(
                projects = emptyList(),
                loading = false,
                cacheBytes = 0L,
            ),
            onAction = {},
        )
    }
}

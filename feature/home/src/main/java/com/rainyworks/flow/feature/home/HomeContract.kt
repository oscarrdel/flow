package com.rainyworks.flow.feature.home

import com.rainyworks.flow.core.model.ProjectSummary

data class HomeUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val loading: Boolean = true,
    val cacheBytes: Long = 0L,
)

sealed interface HomeAction {
    data object CreateProject : HomeAction
    data class OpenProject(val id: String) : HomeAction
    data class RenameProject(val id: String, val name: String) : HomeAction
    data class DuplicateProject(val id: String) : HomeAction
    data class DeleteProject(val id: String) : HomeAction
    data object ClearCache : HomeAction
}

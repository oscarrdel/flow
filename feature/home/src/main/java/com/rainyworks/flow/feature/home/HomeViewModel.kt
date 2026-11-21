package com.rainyworks.flow.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainyworks.flow.core.data.ProjectRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: ProjectRepository,
    private val strings: HomeStrings,
) : ViewModel() {
    private val cacheBytes = MutableStateFlow(0L)
    private val navigation = Channel<String>(Channel.BUFFERED)

    /** Emits the id of the project to open in the editor. */
    val openProject: Flow<String> = navigation.receiveAsFlow()

    val state: StateFlow<HomeUiState> = combine(repository.observeSummaries(), cacheBytes) { projects, cache ->
        HomeUiState(projects = projects, loading = false, cacheBytes = cache)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refreshCacheSize()
    }

    fun onAction(action: HomeAction) {
        viewModelScope.launch {
            when (action) {
                HomeAction.CreateProject -> {
                    val count = state.value.projects.size + 1
                    val project = repository.create(strings.defaultProjectName(count))
                    navigation.send(project.id)
                }
                is HomeAction.OpenProject -> navigation.send(action.id)
                is HomeAction.RenameProject -> repository.rename(action.id, action.name)
                is HomeAction.DuplicateProject -> repository.duplicate(action.id, strings.copySuffix)
                is HomeAction.DeleteProject -> repository.delete(action.id)
                HomeAction.ClearCache -> {
                    repository.clearCache()
                    refreshCacheSize()
                }
            }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch { cacheBytes.value = repository.cacheSizeBytes() }
    }
}

/** Localized strings the view model needs, supplied from resources by the caller. */
interface HomeStrings {
    fun defaultProjectName(index: Int): String
    val copySuffix: String
}

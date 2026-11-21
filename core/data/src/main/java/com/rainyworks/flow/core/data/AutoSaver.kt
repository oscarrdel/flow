package com.rainyworks.flow.core.data

import com.rainyworks.flow.core.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Saves the latest project shortly after edits stop, and immediately on [flush]
 * (called when the app goes to the background or the editor closes).
 */
@OptIn(FlowPreview::class)
class AutoSaver(
    private val repository: ProjectRepository,
    scope: CoroutineScope,
    debounceMs: Long = 400L,
) {
    private val pending = MutableStateFlow<Project?>(null)
    private val mutex = Mutex()
    private var lastSaved: Project? = null

    init {
        scope.launch {
            pending.filterNotNull().debounce(debounceMs).collect { saveIfChanged(it) }
        }
    }

    fun submit(project: Project) {
        pending.value = project
    }

    suspend fun flush() = withContext(NonCancellable) {
        pending.value?.let { saveIfChanged(it) }
    }

    private suspend fun saveIfChanged(project: Project) = mutex.withLock {
        if (project == lastSaved) return@withLock
        repository.save(project)
        lastSaved = project
    }
}

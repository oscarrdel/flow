package com.rainyworks.flow.core.model

/**
 * Immutable undo/redo history. Consecutive edits that share a non-null [mergeKey]
 * (for example dragging a slider) collapse into a single undo step.
 */
data class EditHistory(
    val present: Project,
    private val past: List<Project> = emptyList(),
    private val future: List<Project> = emptyList(),
    private val lastMergeKey: String? = null,
    private val limit: Int = DEFAULT_LIMIT,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(next: Project, mergeKey: String? = null): EditHistory {
        if (next == present) return this
        val merge = mergeKey != null && mergeKey == lastMergeKey && past.isNotEmpty()
        val newPast = if (merge) past else (past + present).takeLast(limit)
        return copy(present = next, past = newPast, future = emptyList(), lastMergeKey = mergeKey)
    }

    /** Replaces the present without recording an undo step (e.g. metadata refresh). */
    fun replace(next: Project): EditHistory = copy(present = next)

    fun undo(): EditHistory {
        if (past.isEmpty()) return this
        return copy(present = past.last(), past = past.dropLast(1), future = listOf(present) + future, lastMergeKey = null)
    }

    fun redo(): EditHistory {
        if (future.isEmpty()) return this
        return copy(present = future.first(), past = past + present, future = future.drop(1), lastMergeKey = null)
    }

    /** Ends the current merge group so the next edit starts a new undo step. */
    fun commitGroup(): EditHistory = copy(lastMergeKey = null)

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}

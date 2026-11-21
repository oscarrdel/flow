package com.rainyworks.flow.core.data

import com.rainyworks.flow.core.data.db.ProjectIndexDao
import com.rainyworks.flow.core.data.db.ProjectIndexEntity
import com.rainyworks.flow.core.model.Edits
import com.rainyworks.flow.core.model.Project
import com.rainyworks.flow.core.model.ProjectFactory
import com.rainyworks.flow.core.model.ProjectSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ProjectRepository(
    private val store: ProjectFileStore,
    private val dao: ProjectIndexDao,
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val writeLock = Mutex()

    fun observeSummaries(): Flow<List<ProjectSummary>> = dao.observeAll().map { rows ->
        rows.map {
            ProjectSummary(
                id = it.id,
                name = it.name,
                updatedAtMs = it.updatedAtMs,
                durationUs = it.durationUs,
                thumbnailPath = it.thumbnailPath?.takeIf { path -> File(path).exists() },
                sizeBytes = it.sizeBytes,
            )
        }
    }

    suspend fun create(name: String): Project {
        val project = ProjectFactory.create(name = name, nowMs = clock())
        save(project, touch = false)
        return project
    }

    suspend fun load(id: String): Project? = withContext(io) { store.read(id) }

    /** Persists the project. [touch] refreshes updatedAtMs so it moves to the top of the list. */
    suspend fun save(project: Project, touch: Boolean = true): Project = withContext(io) {
        writeLock.withLock {
            val saved = if (touch) project.copy(updatedAtMs = clock()) else project
            store.write(saved)
            val existing = dao.get(saved.id)
            dao.upsert(
                ProjectIndexEntity(
                    id = saved.id,
                    name = saved.name,
                    updatedAtMs = saved.updatedAtMs,
                    durationUs = saved.durationUs,
                    thumbnailPath = existing?.thumbnailPath,
                    sizeBytes = store.sizeOf(saved.id),
                ),
            )
            saved
        }
    }

    suspend fun rename(id: String, name: String) = withContext(io) {
        val trimmed = name.trim().ifEmpty { return@withContext }
        writeLock.withLock {
            val project = store.read(id) ?: return@withLock
            val renamed = project.copy(name = trimmed, updatedAtMs = clock())
            store.write(renamed)
            dao.rename(id, trimmed, renamed.updatedAtMs)
        }
    }

    suspend fun duplicate(id: String, copySuffix: String): Project? = withContext(io) {
        val source = store.read(id) ?: return@withContext null
        val now = clock()
        val copy = source.copy(id = Edits.newId(), name = "${source.name} $copySuffix", createdAtMs = now, updatedAtMs = now)
        store.copyAssets(id, copy.id)
        save(copy, touch = false)
        val thumb = store.thumbnailFile(copy.id)
        if (thumb.exists()) dao.setThumbnail(copy.id, thumb.path, store.sizeOf(copy.id))
        copy
    }

    suspend fun delete(id: String) = withContext(io) {
        writeLock.withLock {
            store.delete(id)
            dao.delete(id)
        }
    }

    /** Where the editor should write the project's cover image. */
    fun thumbnailFile(id: String): File = store.thumbnailFile(id).also { it.parentFile?.mkdirs() }

    /** A file inside the project folder for media the editor generates (freeze frames, recordings). */
    fun newAssetFile(id: String, extension: String): File {
        val dir = File(store.projectDir(id), "assets").also { it.mkdirs() }
        return File(dir, "${Edits.newId()}.$extension")
    }

    suspend fun onThumbnailUpdated(id: String) = withContext(io) {
        val thumb = store.thumbnailFile(id)
        dao.setThumbnail(id, thumb.path.takeIf { thumb.exists() }, store.sizeOf(id))
    }

    suspend fun cacheSizeBytes(): Long = withContext(io) {
        cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clearCache() = withContext(io) {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}

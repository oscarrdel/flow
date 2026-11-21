package com.rainyworks.flow.core.data

import com.rainyworks.flow.core.model.Project
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Stores each project as JSON in its own folder. Writes go to a temporary file that is
 * then renamed, so a crash or a killed process never leaves a half-written project.
 */
class ProjectFileStore(private val root: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun projectDir(id: String): File = File(root, id)

    private fun projectFile(id: String) = File(projectDir(id), PROJECT_FILE)

    fun thumbnailFile(id: String): File = File(projectDir(id), THUMBNAIL_FILE)

    fun read(id: String): Project? {
        val file = projectFile(id)
        val backup = File(file.parentFile, "$PROJECT_FILE.bak")
        for (candidate in listOf(file, backup)) {
            if (!candidate.exists()) continue
            val project = runCatching { json.decodeFromString(Project.serializer(), candidate.readText()) }.getOrNull()
            if (project != null) return project
        }
        return null
    }

    fun write(project: Project) {
        val dir = projectDir(project.id)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val target = projectFile(project.id)
        val temp = File(dir, "$PROJECT_FILE.tmp")
        temp.writeText(json.encodeToString(Project.serializer(), project))
        if (target.exists()) {
            val backup = File(dir, "$PROJECT_FILE.bak")
            backup.delete()
            target.renameTo(backup)
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun delete(id: String) {
        projectDir(id).deleteRecursively()
    }

    fun copyAssets(fromId: String, toId: String) {
        val thumb = thumbnailFile(fromId)
        if (thumb.exists()) {
            projectDir(toId).mkdirs()
            thumb.copyTo(thumbnailFile(toId), overwrite = true)
        }
    }

    fun sizeOf(id: String): Long = projectDir(id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun listIds(): List<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

    private companion object {
        const val PROJECT_FILE = "project.json"
        const val THUMBNAIL_FILE = "thumbnail.jpg"
    }
}

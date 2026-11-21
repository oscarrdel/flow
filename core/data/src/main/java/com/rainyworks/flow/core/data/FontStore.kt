package com.rainyworks.flow.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Fonts imported by the user, copied into app storage so projects keep working offline. */
class FontStore(private val context: Context) {
    private val dir = File(context.filesDir, "fonts")

    suspend fun list(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension.lowercase() in EXTENSIONS }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { it.nameWithoutExtension to Uri.fromFile(it).toString() }
    }

    /** Copies a .ttf/.otf file and returns its (name, uri), or null if it is not a font. */
    suspend fun import(uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: return@withContext null
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in EXTENSIONS) return@withContext null
        dir.mkdirs()
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: return@withContext null
        if (!isFont(target)) {
            target.delete()
            return@withContext null
        }
        target.nameWithoutExtension to Uri.fromFile(target).toString()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment

    private fun isFont(file: File): Boolean {
        val head = ByteArray(4)
        file.inputStream().use { if (it.read(head) != 4) return false }
        val tag = String(head, Charsets.ISO_8859_1)
        return tag == "OTTO" || tag == "true" || tag == "ttcf" ||
            (head[0] == 0.toByte() && head[1] == 1.toByte() && head[2] == 0.toByte() && head[3] == 0.toByte())
    }

    private companion object {
        val EXTENSIONS = setOf("ttf", "otf")
    }
}

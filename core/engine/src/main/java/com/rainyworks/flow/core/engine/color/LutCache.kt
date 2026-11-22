package com.rainyworks.flow.core.engine.color

import android.net.Uri
import android.util.LruCache
import com.rainyworks.flow.core.model.CubeLut
import java.io.File

/** Parsed .cube files, keyed by URI. LUTs live in the project folder, so file URIs suffice. */
object LutCache {
    private val cache = object : LruCache<String, CubeLut>(4) {}
    private val failed = mutableSetOf<String>()

    @Synchronized
    fun load(uri: String): CubeLut? {
        cache.get(uri)?.let { return it }
        if (uri in failed) return null
        val lut = runCatching {
            val path = Uri.parse(uri).path ?: return@runCatching null
            CubeLut.parse(File(path).readText())
        }.getOrNull()
        if (lut == null) failed += uri else cache.put(uri, lut)
        return lut
    }
}

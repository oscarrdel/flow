package com.rainyworks.flow.core.data

import android.content.Context
import androidx.room.Room
import com.rainyworks.flow.core.data.db.FlowDatabase
import java.io.File

/** Builds the data-layer singletons. Call once from the Application. */
class DataModule(context: Context) {
    private val appContext = context.applicationContext

    private val database: FlowDatabase by lazy {
        Room.databaseBuilder(appContext, FlowDatabase::class.java, "flow.db").build()
    }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(
            store = ProjectFileStore(File(appContext.filesDir, "projects")),
            dao = database.projectIndexDao(),
            cacheDir = appContext.cacheDir,
        )
    }
}

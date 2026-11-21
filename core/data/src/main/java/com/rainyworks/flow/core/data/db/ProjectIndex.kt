package com.rainyworks.flow.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectIndexEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAtMs: Long,
    val durationUs: Long,
    val thumbnailPath: String?,
    val sizeBytes: Long,
)

@Dao
interface ProjectIndexDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<ProjectIndexEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectIndexEntity?

    @Upsert
    suspend fun upsert(entity: ProjectIndexEntity)

    @Query("UPDATE projects SET name = :name, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAtMs: Long)

    @Query("UPDATE projects SET thumbnailPath = :path, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun setThumbnail(id: String, path: String?, sizeBytes: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [ProjectIndexEntity::class], version = 1, exportSchema = false)
abstract class FlowDatabase : RoomDatabase() {
    abstract fun projectIndexDao(): ProjectIndexDao
}

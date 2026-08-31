package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.opentasker.core.model.Project

@Entity("projects", indices = [Index(value = ["name"], unique = true)])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int?,
    val sortOrder: Int,
    val description: String,
) {
    fun toDomain() = Project(id, name, color, sortOrder, description)
}

fun Project.toEntity() = ProjectEntity(id, name, color, sortOrder, description)

@Dao
interface ProjectDao {
    @Insert suspend fun insert(p: ProjectEntity): Long
    @Update suspend fun update(p: ProjectEntity)
    @Delete suspend fun delete(p: ProjectEntity)
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun getById(id: Long): ProjectEntity?
    @Query("SELECT * FROM projects ORDER BY sortOrder, name") suspend fun getAll(): List<ProjectEntity>
    @Query("SELECT * FROM projects ORDER BY sortOrder, name") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<ProjectEntity>>
}

package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import com.opentasker.core.model.Project
import kotlinx.coroutines.flow.Flow

@Entity("projects", indices = [Index(value = ["name"], unique = true), Index("position")])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
) {
    fun toDomain(): Project = Project(id = id, name = name, position = position)
}

fun Project.toEntity(): ProjectEntity = ProjectEntity(id = id, name = name, position = position)

@Dao
interface ProjectDao {
    @Insert suspend fun insert(project: ProjectEntity): Long
    @Update suspend fun update(project: ProjectEntity)
    @Query("SELECT * FROM projects ORDER BY position, id") fun getAllAsFlow(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects ORDER BY position, id") suspend fun getAll(): List<ProjectEntity>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun getById(id: Long): ProjectEntity?
    @Query("SELECT * FROM projects WHERE id = :id") fun observeById(id: Long): Flow<ProjectEntity?>
    @Query("SELECT COUNT(*) FROM projects") suspend fun count(): Int
    @Query("DELETE FROM projects WHERE id = :id AND id != :defaultId") suspend fun deleteIfNotDefault(id: Long, defaultId: Long = DEFAULT_PROJECT_ID): Int
}

package com.opentasker.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, TaskEntity::class, SceneEntity::class, VariableEntity::class, RunLogEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun taskDao(): TaskDao
    abstract fun sceneDao(): SceneDao
    abstract fun variableDao(): VariableDao
    abstract fun runLogDao(): RunLogDao
}

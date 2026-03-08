package com.airi.assistant.brain.memory

import androidx.room.*

@Dao
interface AiriDao {

    @Insert
    suspend fun insertApp(app: AppEntity): Long

    @Insert
    suspend fun insertScreen(screen: ScreenEntity): Long

    @Insert
    suspend fun insertNode(node: UINodeEntity)

    @Query("SELECT * FROM apps")
    suspend fun getApps(): List<AppEntity>
}

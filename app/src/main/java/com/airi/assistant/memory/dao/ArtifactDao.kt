package com.airi.assistant.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.airi.assistant.memory.entity.ArtifactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtifactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artifact: ArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artifacts: List<ArtifactEntity>)

    @Update
    suspend fun update(artifact: ArtifactEntity)

    @Query("SELECT * FROM workspace_artifact WHERE sessionId = :sessionId ORDER BY createdAtMs DESC")
    suspend fun getForSession(sessionId: String): List<ArtifactEntity>

    @Query("SELECT * FROM workspace_artifact ORDER BY createdAtMs DESC")
    suspend fun getAll(): List<ArtifactEntity>

    @Query("SELECT * FROM workspace_artifact ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM workspace_artifact WHERE id = :id")
    suspend fun getById(id: String): ArtifactEntity?

    @Query("DELETE FROM workspace_artifact WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM workspace_artifact WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM workspace_artifact")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM workspace_artifact")
    suspend fun getCount(): Int
}

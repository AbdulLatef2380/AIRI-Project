package com.airi.assistant.memory

import androidx.room.*

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScreen(screen: ScreenNode)

    @Insert
    suspend fun insertElement(element: UIElement)

    @Insert
    suspend fun insertAction(action: ActionMemory)

    @Query("SELECT * FROM ActionMemory WHERE screenHash = :hash LIMIT 1")
    suspend fun getAction(hash: String): ActionMemory?

}

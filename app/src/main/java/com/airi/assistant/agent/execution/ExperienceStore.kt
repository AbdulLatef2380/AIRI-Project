package com.airi.assistant.agent.execution

import android.content.Context
import android.util.Log
import androidx.room.*

object ExperienceStore {
    private const val TAG = "ExperienceStore"
    private var database: ExperienceDatabase? = null

    fun init(context: Context) {
        if (database == null) {
            database = Room.databaseBuilder(
                context.applicationContext,
                ExperienceDatabase::class.java,
                "airi_experience.db"
            ).build()
            Log.i(TAG, "Experience Database initialized.")
        }
    }

    suspend fun saveRecord(record: ExecutionRecord) {
        database?.executionDao()?.insert(record)
    }

    suspend fun getBestExperiences(goal: String, limit: Int = 3): List<ExecutionRecord> {
        return database?.executionDao()?.getTopRated(limit) ?: emptyList()
    }
}

@Dao
interface ExecutionDao {

    @Insert
    suspend fun insert(record: ExecutionRecord)

    @Query("SELECT * FROM execution_records ORDER BY score DESC LIMIT :limit")
    suspend fun getTopRated(limit: Int): List<ExecutionRecord>

    @Query("SELECT * FROM execution_records WHERE goal LIKE '%' || :goal || '%' ORDER BY score DESC LIMIT :limit")
    suspend fun findSimilar(goal: String, limit: Int): List<ExecutionRecord>
}

@Database(
    entities = [ExecutionRecord::class],
    version = 1,
    exportSchema = true
)
abstract class ExperienceDatabase : RoomDatabase() {
    abstract fun executionDao(): ExecutionDao
}

package com.airi.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UsageStatEntity::class, 
        ContextCacheEntity::class,
        BehaviorStatsEntity::class // 🔥 المكون الجديد: ذاكرة السلوك والتعلم
    ],
    version = 3 // 🚀 تم الرفع من 2 إلى 3 لاستيعاب الجدول الجديد
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usageStatsDao(): UsageStatsDao
    abstract fun contextCacheDao(): ContextCacheDao
    abstract fun behaviorStatsDao(): BehaviorStatsDao // 🧠 محرك التعلم المعزز

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "airi_database"
                )
                /* * تفعيل الهجرة التدميرية: 
                 * بما أننا في مرحلة التطوير، سيقوم Room بحذف قاعدة البيانات 
                 * وإعادة إنشائها لتجنب الـ Crash بسبب تغيير الـ Schema.
                 */
                .fallbackToDestructiveMigration() 
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

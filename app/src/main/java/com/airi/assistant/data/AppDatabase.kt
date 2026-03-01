package com.airi.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UsageStatEntity::class, 
        ContextCacheEntity::class // ✅ إضافة كيان ذاكرة السياق
    ],
    version = 2 // 🔥 تم رفع الإصدار من 1 إلى 2 بسبب تغيير الهيكلية
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usageStatsDao(): UsageStatsDao
    abstract fun contextCacheDao(): ContextCacheDao // ✅ إضافة الـ DAO الخاص بالسياق

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
                /* * تفعيل الهجرة التدميرية: ستقوم بحذف البيانات القديمة وإنشاء الجداول الجديدة
                 * لتجنب توقف التطبيق (Crash) بسبب اختلاف النسخ.
                 */
                .fallbackToDestructiveMigration() 
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

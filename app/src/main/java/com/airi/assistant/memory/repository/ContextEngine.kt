package com.airi.assistant.memory.repository
import android.content.Context
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.entity.ContextCacheEntity
import kotlinx.coroutines.*

object ContextEngine {

private var db: AiriDatabase? = null  

fun initialize(context: Context) {  
    db = AiriDatabase.getDatabase(context)  
}  

fun saveContext(screenText: String, sourceApp: String, detectedIntent: String) {  
    val database = db ?: return  

    CoroutineScope(Dispatchers.IO).launch {  
        database.contextCacheDao().insert(  
            ContextCacheEntity(  
                screenText = screenText.take(1500), // منع التضخم  
                sourceApp = sourceApp,  
                detectedIntent = detectedIntent,  
                timestamp = System.currentTimeMillis()  
            )  
        )  

        // حذف سياق أقدم من 10 دقائق  
        val expire = System.currentTimeMillis() - (10 * 60 * 1000)  
        database.contextCacheDao().cleanupOld(expire)  
    }  
}  

suspend fun getRecentContext(): ContextCacheEntity? {  
    val database = db ?: return null  
    val threshold = System.currentTimeMillis() - (5 * 60 * 1000)  
    return database.contextCacheDao().getRecentContext(threshold)  
}

}

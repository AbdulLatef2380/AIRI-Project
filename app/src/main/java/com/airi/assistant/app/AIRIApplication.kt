package com.airi.assistant.app

import android.app.Application
import android.util.Log
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.memory.AiriDatabase

/**
 * AIRI Application - Core initialization
 */
class AIRIApplication : Application() {

    companion object {
        private const val TAG = "AIRIApplication"
    }

    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "━━━ AIRI Starting ━━━")
        
        try {
            // Initialize ServiceLocator for global context access
            ServiceLocator.context = applicationContext
            Log.d(TAG, "✓ ServiceLocator initialized")
            
            // Initialize database
            AiriDatabase.getDatabase(this)
            Log.d(TAG, "✓ Database initialized")
            
            Log.d(TAG, "━━━ AIRI Ready ━━━")
            
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error: ${e.message}", e)
            // Fail fast on critical errors
            throw e
        }
    }
}

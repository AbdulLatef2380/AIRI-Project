package com.airi.assistant

import android.app.Application
import com.airi.assistant.accessibility.BehaviorEngine

class AIRIApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        BehaviorEngine.initialize(this)
    }
}

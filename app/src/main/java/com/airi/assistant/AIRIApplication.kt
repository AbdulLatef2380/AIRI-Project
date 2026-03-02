package com.airi.assistant

import android.app.Application
import com.airi.assistant.accessibility.BehaviorEngine
import com.airi.assistant.data.ContextEngine

class AIRIApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 🧠 تهيئة محرك السلوك (التعلم من ضغطات المستخدم)
        BehaviorEngine.initialize(this)
        
        // 🕒 تهيئة محرك السياق الزمني (الذاكرة القصيرة للشاشة)
        ContextEngine.initialize(this)
    }
}

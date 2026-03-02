package com.airi.assistant

import android.app.Application
import com.airi.assistant.accessibility.BehaviorEngine
import com.airi.assistant.data.AppDatabase
import com.airi.assistant.data.ContextEngine
import com.airi.assistant.adaptive.InteractionTracker
import com.airi.assistant.adaptive.SuggestionScoreEngine

class AIRIApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 🧠 تهيئة محرك السلوك (التعلم من ضغطات المستخدم التقليدية)
        BehaviorEngine.initialize(this)
        
        // 🕒 تهيئة محرك السياق الزمني (الذاكرة القصيرة للشاشة)
        ContextEngine.initialize(this)

        // 🧬 تهيئة طبقة التعلم المعزز (Reinforcement Learning Layer)
        // نقوم بجلب قاعدة البيانات وربطها بمحركات التتبع والتقييم
        val database = AppDatabase.getDatabase(this)
        
        // تتبع التفاعلات (عرض، قبول، تجاهل)
        InteractionTracker.initialize(database)
        
        // محرك حساب النقاط (الذي يقرر جودة الاقتراح لاحقاً)
        SuggestionScoreEngine.initialize(database)
    }
}

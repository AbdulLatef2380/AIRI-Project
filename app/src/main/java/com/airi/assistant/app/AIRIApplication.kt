package com.airi.assistant.app

import android.app.Application
import com.airi.assistant.accessibility.service.BehaviorEngine
import com.airi.assistant.agent.decision.SuggestionScoreEngine
import com.airi.assistant.agent.learning.InteractionTracker
import com.airi.assistant.memory.repository.ContextEngine
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.core.ServiceLocator
// تأكد من استيراد المسار الصحيح لـ ExperienceStore
import com.airi.assistant.core.experience.ExperienceStore 

class AIRIApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 🛠️ تهيئة ServiceLocator للوصول العالمي للسياق
        ServiceLocator.context = applicationContext
        
        // 🧠 تهيئة محرك السلوك (التعلم من ضغطات المستخدم التقليدية)
        BehaviorEngine.initialize(this)
        
        // 🕒 تهيئة محرك السياق الزمني (الذاكرة القصيرة للشاشة)
        ContextEngine.initialize(this)

        // 🧬 تهيئة طبقة التعلم المعزز (Reinforcement Learning Layer)
        val database = AiriDatabase.getDatabase(this)
        
        // تتبع التفاعلات (عرض، قبول، تجاهل)
        InteractionTracker.initialize(database)
        
        // محرك حساب النقاط (الذي يقرر جودة الاقتراح لاحقاً)
        SuggestionScoreEngine.initialize(database)

        // 📦 تهيئة مخزن الخبرات (تخزين الدروس المستفادة من التفاعلات)
        ExperienceStore.init(applicationContext)
    }
}

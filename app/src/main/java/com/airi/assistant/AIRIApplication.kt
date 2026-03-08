package com.airi.assistant

import android.app.Application
import com.airi.assistant.accessibility.BehaviorEngine
import com.airi.assistant.data.AppDatabase
import com.airi.assistant.data.ContextEngine
import com.airi.assistant.adaptive.InteractionTracker
import com.airi.assistant.adaptive.SuggestionScoreEngine
import com.airi.assistant.brain.BrainManager // تأكد من استيراد المسار الصحيح
import com.airi.assistant.brain.MemoryManager // استيراد مدير الذاكرة

class AIRIApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. تهيئة المحركات الأساسية وقاعدة البيانات
        val database = AppDatabase.getDatabase(this)
        
        // 🧠 تهيئة محركات السلوك والسياق والذاكرة
        BehaviorEngine.initialize(this)
        ContextEngine.initialize(this)
        MemoryManager.init(this)
        
        // 🧬 تهيئة طبقة التعلم المعزز (التتبع والتقييم)
        InteractionTracker.initialize(database)
        SuggestionScoreEngine.initialize(database)

        // 2. تهيئة "الدماغ" (المسؤول عن اتخاذ القرارات بناءً على السياق)
        BrainManager.init(this)

        // 3. تشغيل المتحكم العام للوكيل الذكي (Agent)
        // ملاحظة: يُفضل تشغيله بعد التأكد من جاهزية كافة المحركات أعلاه
        AgentController.start()
    }
}

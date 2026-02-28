package com.airi.assistant

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.util.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var chatParams: WindowManager.LayoutParams
    private lateinit var bubbleView: View
    private lateinit var chatView: View
    private lateinit var adapter: ChatAdapter
    
    // نستخدم LlamaManager لأنه هو الذي يحتوي على دالة generate
    private lateinit var llamaManager: LlamaManager
    
    // تحسين الأداء: الـ Scope يعمل في الخلفية لتجنب الـ Freeze
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var ttsManager: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognitionIntent: Intent

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        llamaManager = LlamaManager(this)
        setupManagers()
        initViews()
        initSpeechToText()
        setupNotification()
    }

    private fun setupClickListeners() {
        val btnLoad = chatView.findViewById<Button>(R.id.btnLoadBrain)
        val progress = chatView.findViewById<ProgressBar>(R.id.progressBar)

        btnLoad.setOnClickListener {
            btnLoad.isEnabled = false
            btnLoad.text = "جاري التحميل..."
            progress.visibility = View.VISIBLE
            
            llamaManager.initializeModel { success ->
                progress.visibility = View.GONE
                btnLoad.isEnabled = true
                if (success) {
                    btnLoad.text = "العقل جاهز ✅"
                    btnLoad.setBackgroundColor(android.graphics.Color.GREEN)
                } else {
                    btnLoad.text = "فشل! تأكد من التحميل"
                }
            }
        }

        chatView.findViewById<View>(R.id.btn_send).setOnClickListener {
            val input = chatView.findViewById<EditText>(R.id.chat_input)
            val text = input.text.toString()
            if (text.isNotBlank()) {
                input.text.clear()
                sendToAIRI(text)
            }
        }
    }

    private fun sendToAIRI(text: String) {
        adapter.addMessage(ChatModel(text, true))
        
        // التوليد يحدث في LlamaManager (الذي يستخدم Dispatchers.IO داخلياً)
        llamaManager.generate(text) { response ->
            adapter.addMessage(ChatModel(response, false))
            ttsManager.speak(response, TextToSpeech.QUEUE_FLUSH, null, "AIRI")
            
            chatView.findViewById<RecyclerView>(R.id.chat_recycler)
                .smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    // ... بقية دوال Notification و Touch تبقى كما هي
}

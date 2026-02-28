package com.airi.assistant

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.util.Log
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
    
    // نستخدم المدير الذي أصلحناه أعلاه
    private lateinit var llamaManager: LlamaManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var ttsManager: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognitionIntent: Intent
    private lateinit var btnLoadBrain: Button
    private lateinit var progressBar: ProgressBar

    private var isChatVisible = false
    private val screenWidth by lazy { resources.displayMetrics.widthPixels }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        llamaManager = LlamaManager(this)
        setupManagers()
        initViews()
        initSpeechToText()
        setupNotification()
    }

    private fun setupManagers() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ttsManager = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) ttsManager.language = Locale("ar")
        }
    }

    private fun initViews() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        chatView = LayoutInflater.from(this).inflate(R.layout.chat_layout, null)

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 500
        }

        chatParams = WindowManager.LayoutParams(
            (screenWidth * 0.85).toInt(),
            (resources.displayMetrics.heightPixels * 0.6).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        setupRecyclerView()
        setupClickListeners()
        setupTouchListener()
        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun setupRecyclerView() {
        val recyclerView = chatView.findViewById<RecyclerView>(R.id.chat_recycler)
        adapter = ChatAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        btnLoadBrain = chatView.findViewById(R.id.btnLoadBrain)
        progressBar = chatView.findViewById(R.id.progressBar)
        btnLoadBrain.setOnClickListener { loadBrain() }

        chatView.findViewById<View>(R.id.btn_send).setOnClickListener {
            val input = chatView.findViewById<EditText>(R.id.chat_input)
            val text = input.text.toString()
            if (text.isNotBlank()) {
                input.text.clear()
                sendToAIRI(text)
            }
        }
    }

    private fun loadBrain() {
        progressBar.visibility = View.VISIBLE
        btnLoadBrain.isEnabled = false
        btnLoadBrain.text = "جاري تفعيل العقل..."

        llamaManager.initializeModel { success ->
            progressBar.visibility = View.GONE
            btnLoadBrain.isEnabled = true
            if (success) {
                btnLoadBrain.text = "العقل جاهز ✅"
                btnLoadBrain.setBackgroundColor(android.graphics.Color.GREEN)
            } else {
                btnLoadBrain.text = "فشل! الملف مفقود"
                btnLoadBrain.setBackgroundColor(android.graphics.Color.RED)
            }
        }
    }

    private fun sendToAIRI(text: String) {
        adapter.addMessage(ChatModel(text, true))
        
        // استخدام الكولباك بدلاً من suspend لتجنب أخطاء الـ CI
        llamaManager.generate(text) { response ->
            adapter.addMessage(ChatModel(response, false))
            ttsManager.speak(response, TextToSpeech.QUEUE_FLUSH, null, "AIRI")
            chatView.findViewById<RecyclerView>(R.id.chat_recycler).smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    // ... (بقية دوال اللمس والتنبيهات تبقى كما هي)
    private fun setupNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("AIRI_SERVICE", "AIRI Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "AIRI_SERVICE")
            .setContentTitle("AIRI Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        ttsManager.shutdown()
    }

    // إضافة TouchListener و SnapToEdge و ToggleChat هنا كما في الكود السابق
}

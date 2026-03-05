package com.airi.assistant

import android.animation.ValueAnimator
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
import com.airi.assistant.brain.*
import com.airi.assistant.accessibility.ScreenContextHolder // إضافة الـ Import للسياق
import kotlinx.coroutines.*
import java.util.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var chatParams: WindowManager.LayoutParams
    private lateinit var bubbleView: View
    private lateinit var chatView: View
    private lateinit var adapter: ChatAdapter

    private lateinit var llamaManager: LlamaManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * ✅ الدماغ المحدث:
     * يعتمد على المكونات الأساسية لإدارة التخطيط والتنفيذ.
     */
    private val brain: AiriBrainController by lazy {
        AiriBrainController(
            planner = PlanGenerator(),
            validator = PlanValidator(),
            executor = GoalExecutor(),
            recoveryManager = RecoveryManager()
        )
    }

    private lateinit var ttsManager: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognitionIntent: Intent
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /**
     * ✅ تم التحديث: جلب سياق الشاشة قبل إرسال الطلب للدماغ
     */
    private fun sendToAIRI(userText: String) {
        adapter.addMessage(ChatModel(userText, true))
        serviceScope.launch {
            try {
                // 1. جلب آخر سياق تم التقاطه بواسطة خدمة الوصول
                val context = ScreenContextHolder.lastScreenText ?: ""
                
                // 2. تجهيز المدخلات الشاملة (نص المستخدم + ما تراه AIRI على الشاشة)
                val input = BrainInput(
                    text = userText,
                    screenContext = context
                )
                
                // 3. المعالجة والرد
                val output = brain.process(input) 
                processResponse(output.message)
            } catch (e: Exception) {
                processResponse("❌ خطأ في المعالجة: ${e.message}")
            }
        }
    }

    private fun processResponse(response: String) {
        mainHandler.post {
            adapter.addMessage(ChatModel(response, false))
            ttsManager.speak(response, TextToSpeech.QUEUE_FLUSH, null, "AIRI")
            chatView.findViewById<RecyclerView>(R.id.chat_recycler)
                .smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    // --- إدارة الواجهة الرسومية (WindowManager) ---

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
        adapter = ChatAdapter { selectedAction -> sendToAIRI(selectedAction) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        chatView.findViewById<View>(R.id.btn_send).setOnClickListener {
            val inputField = chatView.findViewById<EditText>(R.id.chat_input)
            val text = inputField.text.toString()
            if (text.isNotBlank()) {
                inputField.text.clear()
                sendToAIRI(text)
            }
        }

        chatView.findViewById<View>(R.id.mic_button).setOnClickListener {
            speechRecognizer.startListening(recognitionIntent)
        }

        chatView.findViewById<View>(R.id.btn_close_chat)?.setOnClickListener { toggleChat() }
    }

    private fun initSpeechToText() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: return
                sendToAIRI(spoken)
            }
            override fun onError(p0: Int) {}
            override fun onReadyForSpeech(p0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }

    private fun setupTouchListener() {
        val avatar = bubbleView.findViewById<ImageView>(R.id.airi_avatar)
        avatar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x; initialY = bubbleParams.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        isMoving = false; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isMoving = true
                        bubbleParams.x = initialX + dx; bubbleParams.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) toggleChat() else snapToEdge()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun snapToEdge() {
        val targetX = if (bubbleParams.x + bubbleView.width / 2 < screenWidth / 2) 0 else screenWidth - bubbleView.width
        ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                windowManager.updateViewLayout(bubbleView, bubbleParams)
            }
            duration = 300
            start()
        }
    }

    private fun toggleChat() {
        if (isChatVisible) windowManager.removeView(chatView) 
        else windowManager.addView(chatView, chatParams)
        isChatVisible = !isChatVisible
    }

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
        speechRecognizer.destroy()
    }
}

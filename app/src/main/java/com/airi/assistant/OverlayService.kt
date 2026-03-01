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
import com.airi.assistant.accessibility.ScreenContextHolder 
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

    private lateinit var ttsManager: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognitionIntent: Intent
    
    private var isChatVisible = false
    private val screenWidth by lazy { resources.displayMetrics.widthPixels }
    private var isWaitingForScreenQuestion = false

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
            if (status == TextToSpeech.SUCCESS) {
                ttsManager.language = Locale("ar")
            }
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
        val btnLoadBrain = chatView.findViewById<Button>(R.id.btnLoadBrain)
        val progressBar = chatView.findViewById<ProgressBar>(R.id.progressBar)

        btnLoadBrain.setOnClickListener {
            progressBar.visibility = View.VISIBLE
            btnLoadBrain.isEnabled = false
            btnLoadBrain.text = "جاري التفعيل..."

            llamaManager.initializeModel { success ->
                progressBar.visibility = View.GONE
                btnLoadBrain.isEnabled = true
                if (success) {
                    btnLoadBrain.text = "العقل جاهز ✅"
                    btnLoadBrain.setBackgroundColor(android.graphics.Color.GREEN)
                } else {
                    btnLoadBrain.text = "خطأ: الملف مفقود"
                    btnLoadBrain.setBackgroundColor(android.graphics.Color.RED)
                }
            }
        }

        chatView.findViewById<View>(R.id.btn_send).setOnClickListener {
            val input = chatView.findViewById<EditText>(R.id.chat_input)
            val text = input.text.toString()
            if (text.isNotBlank()) {
                input.text.clear()
                if (text.contains("شاشة") || text.contains("حلل") || isWaitingForScreenQuestion) {
                    sendToAIRIWithContext(text)
                } else {
                    sendToAIRI(text)
                }
            }
        }

        chatView.findViewById<View>(R.id.mic_button).setOnClickListener {
            if (ttsManager.isSpeaking) ttsManager.stop()
            speechRecognizer.startListening(recognitionIntent)
        }
    }

    private fun sendToAIRIWithContext(text: String) {
        val screenText = ScreenContextHolder.lastScreenText
        val enhancedPrompt = """
            [Screen Context Mode]
            User Screen Content: $screenText
            User Question: $text
        """.trimIndent()

        adapter.addMessage(ChatModel("🔍 تحليل الشاشة: $text", true))
        llamaManager.generate(enhancedPrompt) { response ->
            processResponse(response)
        }
        isWaitingForScreenQuestion = false
    }

    private fun sendToAIRI(text: String) {
        adapter.addMessage(ChatModel(text, true))
        llamaManager.generate(text) { response ->
            processResponse(response)
        }
    }

    private fun processResponse(response: String) {
        adapter.addMessage(ChatModel(response, false))
        ttsManager.speak(response, TextToSpeech.QUEUE_FLUSH, null, "AIRI")
        chatView.findViewById<RecyclerView>(R.id.chat_recycler).smoothScrollToPosition(adapter.itemCount - 1)
    }

    private fun initSpeechToText() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                if (spokenText.isNotBlank()) {
                    if (spokenText.contains("شاشة") || spokenText.contains("حلل")) {
                        sendToAIRIWithContext(spokenText)
                    } else {
                        sendToAIRI(spokenText)
                    }
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { Log.e("AIRI", "STT Error: $error") }
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
                        isMoving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isMoving = true
                        bubbleParams.x = initialX + dx
                        bubbleParams.y = initialY + dy
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
        val animator = ValueAnimator.ofInt(bubbleParams.x, targetX)
        animator.addUpdateListener {
            bubbleParams.x = it.animatedValue as Int
            windowManager.updateViewLayout(bubbleView, bubbleParams)
        }
        animator.duration = 300
        animator.start()
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

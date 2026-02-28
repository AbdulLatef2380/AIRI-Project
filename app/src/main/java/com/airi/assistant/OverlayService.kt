package com.airi.assistant

import android.animation.ValueAnimator
import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
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
    
    private lateinit var adapter: ChatAdapter // ستحتاج لإنشاء كلاس ChatAdapter
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var isChatVisible = false
    private val screenWidth by lazy { resources.displayMetrics.widthPixels }

    // محركات AIRI
    private lateinit var llama: LlamaNative
    private lateinit var ttsManager: TextToSpeech // إضافة TTS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        setupManagers()
        initViews()
        setupNotification()
        setupStreamingListener()
    }

    private fun setupManagers() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        llama = LlamaNative(this)
        // تهيئة TTS
        ttsManager = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) ttsManager.language = Locale("ar")
        }
    }

    private fun initViews() {
        // 1. إعداد الفقاعة (Bubble)
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 500
        }

        // 2. إعداد لوحة الدردشة (Chat Panel)
        chatView = LayoutInflater.from(this).inflate(R.layout.chat_layout, null)
        chatParams = WindowManager.LayoutParams(
            (screenWidth * 0.85).toInt(), // عرض 85% من الشاشة
            (resources.displayMetrics.heightPixels * 0.6).toInt(), // طول 60%
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // للسماح بالكتابة
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            windowAnimations = android.R.style.Animation_Dialog // أنيميشن افتراضي سريع
        }

        setupRecyclerView()
        setupClickListeners()
        setupTouchListener()

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun setupRecyclerView() {
        val recyclerView = chatView.findViewById<RecyclerView>(R.id.chat_recycler)
        adapter = ChatAdapter() // تأكد من بناء الـ Adapter الخاص بك
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupTouchListener() {
        val airiAvatar = bubbleView.findViewById<ImageView>(R.id.airi_avatar)
        airiAvatar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
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
                        if (!isMoving) {
                            toggleChat()
                        } else {
                            snapToEdge()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun snapToEdge() {
        val targetX = if (bubbleParams.x + bubbleView.width / 2 < screenWidth / 2) 0 
                      else screenWidth - bubbleView.width
        
        val animator = ValueAnimator.ofInt(bubbleParams.x, targetX)
        animator.addUpdateListener {
            bubbleParams.x = it.animatedValue as Int
            try { windowManager.updateViewLayout(bubbleView, bubbleParams) } catch (e: Exception) {}
        }
        animator.duration = 300
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    private fun toggleChat() {
        if (isChatVisible) {
            windowManager.removeView(chatView)
        } else {
            snapToEdge() // الالتصاق بالحافة قبل فتح الشات
            windowManager.addView(chatView, chatParams)
        }
        isChatVisible = !isChatVisible
    }

    private fun setupClickListeners() {
        chatView.findViewById<View>(R.id.btn_send).setOnClickListener {
            val input = chatView.findViewById<EditText>(R.id.chat_input)
            val text = input.text.toString()
            if (text.isNotEmpty()) {
                sendToAIRI(text)
                input.text.clear()
            }
        }
    }

    private var currentAiResponse = StringBuilder()

    private fun setupStreamingListener() {
        // الاستماع للـ StreamingBus الذي صممناه سابقاً
        StreamingBus.subscribe { token ->
            serviceScope.launch(Dispatchers.Main) {
                currentAiResponse.append(token)
                adapter.updateLastMessage(currentAiResponse.toString())
            }
        }

        StreamingBus.onCompleteListener {
            serviceScope.launch(Dispatchers.Main) {
                ttsManager.speak(currentAiResponse.toString(), TextToSpeech.QUEUE_FLUSH, null, "AIRI")
            }
        }
    }

    private fun sendToAIRI(text: String) {
        adapter.addMessage(ChatModel(text, isUser = true))
        currentAiResponse.clear()
        adapter.addMessage(ChatModel("", isUser = false)) // رسالة فارغة لـ AIRI
        
        // تشغيل الاستدلال في الخلفية
        serviceScope.launch(Dispatchers.Default) {
            llama.generateResponseStream(text)
        }
    }

    private fun setupNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("AIRI_SERVICE", "AIRI Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "AIRI_SERVICE")
            .setContentTitle("AIRI Active")
            .setSmallIcon(R.drawable.ic_airi_launcher)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        ttsManager.stop()
        ttsManager.shutdown()
        try { windowManager.removeView(bubbleView) } catch (e: Exception) {}
    }
}

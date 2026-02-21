package com.airi.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var avatarView: AvatarView
    private lateinit var emotionEngine: EmotionEngine
    private lateinit var memoryManager: MemoryManager
    private lateinit var sensoryBudget: SensoryBudgetManager
    private lateinit var llama: LlamaNative
    private lateinit var controlManager: SystemControlManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private val uiReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val message = intent?.getStringExtra("message") ?: ""
            if (message.isNotEmpty()) {
                val chatHistory = overlayView.findViewById<android.widget.TextView>(R.id.chat_history)
                chatHistory.append("\nAIRI: $message")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // 1. إعداد الناقل المركزي (Core Bus)
        AiriCore.init(applicationContext)
        registerReceiver(uiReceiver, android.content.IntentFilter("com.airi.assistant.UI_UPDATE"))

        // 2. إعداد إشعار الخدمة في المقدمة (Foreground Service)
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "AIRI_CHANNEL")
            .setContentTitle("AIRI نشطة")
            .setContentText("AIRI موجودة لمساعدتك")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)

        // 2. إعداد WindowManager والواجهة العائمة
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        
        // Note: chat_layout elements are part of the overlay or separate views. 
        // For simplicity, assuming they are accessible or handled via AiriCore.
        
        llama = LlamaNative()
        memoryManager = MemoryManager(this)
        emotionEngine = EmotionEngine()
        controlManager = SystemControlManager(this)
        sensoryBudget = SensoryBudgetManager()
        val airiAvatar = overlayView.findViewById<ImageView>(R.id.airi_avatar)
        avatarView = AvatarView(this, airiAvatar)
        avatarView.updateVisualState(EmotionEngine.State.NEUTRAL)
        
        val voiceManager = VoiceManager(this, object : VoiceManager.VoiceListener {
            override fun onWakeWordDetected() {
                emotionEngine.processInput("؟")
                emotionEngine.setEmotion(EmotionEngine.State.CURIOUS)
                airiAvatar.setImageResource(emotionEngine.getEmotionDrawable())
            }

            override fun onSpeechResult(text: String) {
                processUserRequest(text)
            }

            override fun onError(error: String) {
                Log.e("AIRI_VOICE", error)
            }
        })
        
        voiceManager.startWakeWordDetection()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        // 3. إضافة ميزة السحب والنقر
        val avatar = overlayView.findViewById<ImageView>(R.id.airi_avatar)
        
        avatar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Handle click if movement was minimal
                        if (Math.abs(event.rawX - initialTouchX) < 10 && Math.abs(event.rawY - initialTouchY) < 10) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        avatar.setOnClickListener {
            // Toggle chat visibility or open chat activity
            Log.d("AIRI_OVERLAY", "Avatar clicked")
        }

        windowManager.addView(overlayView, params)
    }

    private fun processUserRequest(text: String) {
        // Update visual state
        val newState = emotionEngine.processInput(text)
        val airiAvatar = overlayView.findViewById<ImageView>(R.id.airi_avatar)
        airiAvatar.setImageResource(emotionEngine.getEmotionDrawable())
        avatarView.updateVisualState(newState)

        // Send to core for processing
        MainScope().launch {
            AiriCore.send(AiriCore.AiriEvent.VoiceInput(text))
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Log.w("AIRI_OVERLAY", "Memory running low, trimming resources...")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "AIRI_CHANNEL",
                "AIRI Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(uiReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}

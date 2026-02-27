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
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var chatView: View
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
                val chatHistory = chatView.findViewById<TextView>(R.id.chat_history)
                chatHistory.append("\nAIRI: $message")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        AiriCore.init(applicationContext)
        registerReceiver(uiReceiver, android.content.IntentFilter("com.airi.assistant.UI_UPDATE"))

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "AIRI_CHANNEL")
            .setContentTitle("AIRI نشطة")
            .setContentText("AIRI موجودة لمساعدتك")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        chatView = LayoutInflater.from(this).inflate(R.layout.chat_layout, null)
        
        llama = LlamaNative(this)
        memoryManager = MemoryManager(this)
        emotionEngine = EmotionEngine()
        controlManager = SystemControlManager(this)
        sensoryBudget = SensoryBudgetManager()
        
        val airiAvatar = overlayView.findViewById<ImageView>(R.id.airi_avatar)
        avatarView = AvatarView(this, airiAvatar)
        avatarView.updateVisualState(EmotionEngine.State.NEUTRAL)
        
        val voiceManager = VoiceManager(this, object : VoiceManager.VoiceListener {
            override fun onWakeWordDetected() {
                // تصحيح: استخدام Enum الصحيح والدالة الجديدة
                val newState = EmotionEngine.State.CURIOUS
                emotionEngine.setEmotion(newState)
                airiAvatar.setImageResource(getEmotionResource(newState))
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

        airiAvatar.setOnTouchListener(object : View.OnTouchListener {
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
                        v.performClick()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayView, params)
                        return true
                    }
                }
                return false
            }
        })

        airiAvatar.setOnClickListener {
            toggleChat()
        }

        windowManager.addView(overlayView, params)
    }

    // دالة مساعدة لربط المشاعر بملفات الـ Drawable
    private fun getEmotionResource(state: EmotionEngine.State): Int {
        return when (state) {
            EmotionEngine.State.HAPPY -> android.R.drawable.ic_btn_speak_now
            EmotionEngine.State.CURIOUS -> android.R.drawable.ic_menu_search
            EmotionEngine.State.SAD -> android.R.drawable.ic_menu_close_clear_cancel
            else -> android.R.drawable.ic_menu_view
        }
    }

    private fun toggleChat() {
        if (chatView.visibility == View.VISIBLE) {
            chatView.visibility = View.GONE
            try { windowManager.removeView(chatView) } catch (e: Exception) {}
        } else {
            chatView.visibility = View.VISIBLE
            val chatParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            windowManager.addView(chatView, chatParams)
            
            chatView.findViewById<View>(R.id.btn_send).setOnClickListener {
                val input = chatView.findViewById<android.widget.EditText>(R.id.chat_input)
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    processUserRequest(text)
                    input.text.clear()
                }
            }
        }
    }

    private fun processUserRequest(text: String) {
        // تصحيح: استخدام setEmotion وتحديث الواجهة
        val newState = EmotionEngine.State.HAPPY 
        emotionEngine.setEmotion(newState)
        
        val airiAvatar = overlayView.findViewById<ImageView>(R.id.airi_avatar)
        airiAvatar.setImageResource(getEmotionResource(newState))
        avatarView.updateVisualState(newState)

        MainScope().launch {
            AiriCore.send(AiriCore.AiriEvent.VoiceInput(text))
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
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(uiReceiver) } catch (e: Exception) {}
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (e: Exception) {}
        }
    }
}

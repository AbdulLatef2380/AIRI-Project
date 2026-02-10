package com.airi.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat

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

    override fun onCreate() {
        super.onCreate()

        // 1. إعداد إشعار الخدمة
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "AIRI_CHANNEL")
            .setContentTitle("AIRI نشطة")
            .setContentText("AIRI موجودة لمساعدتك")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(1, notification)

        // 2. تهيئة المحركات والواجهة
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        llama = LlamaNative()
        memoryManager = MemoryManager(this)
        emotionEngine = EmotionEngine()
        controlManager = SystemControlManager(this)
        sensoryBudget = SensoryBudgetManager()

        val airiAvatar = overlayView.findViewById<ImageView>(R.id.airi_avatar)
        val chatContainer = overlayView.findViewById<View>(R.id.chat_container)
        val chatInput = overlayView.findViewById<EditText>(R.id.chat_input)
        val btnSend = overlayView.findViewById<Button>(R.id.btn_send)

        avatarView = AvatarView(this, airiAvatar)
        avatarView.updateVisualState(EmotionEngine.State.NEUTRAL)

        // 3. إعدادات النافذة العائمة
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

        // 4. السحب والنقر
        airiAvatar.setOnClickListener {
            chatContainer.visibility = if (chatContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayView, params)
                        return true
                    }
                    else -> return false
                }
            }
        })

        btnSend.setOnClickListener {
            val text = chatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                processUserRequest(text)
                chatInput.setText("")
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun processUserRequest(text: String) {
        val chatHistory = overlayView.findViewById<TextView>(R.id.chat_history)
        val airiAvatar = overlayView.findViewById<ImageView>(R.id.airi_avatar)

        val screenContext = AiriAccessibilityService.getInstance()?.getScreenContext() ?: "سياق الشاشة غير متوفر."
        val fullPrompt = "سياق الشاشة الحالي:\n$screenContext\n\nطلب المستخدم: $text"

        chatHistory.append("\nأنت: $text")
        memoryManager.recordInteraction("user", text)

        val newState = emotionEngine.processInput(text)
        airiAvatar.setImageResource(emotionEngine.getEmotionDrawable())
        avatarView.updateVisualState(newState)

        if (sensoryBudget.canVibrate()) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }

        val response = llama.generateResponse(fullPrompt)

        if (response.contains("COMMAND:")) {
            val jsonPart = response.substringAfter("COMMAND:").trim()
            Log.d("AIRI_COMMAND", jsonPart)
            // لاحقًا: تحليل JSON لتنفيذ الأوامر
        }

        chatHistory.append("\nAIRI: ${response.substringBefore("COMMAND:")}")
        memoryManager.recordInteraction("airi", response, newState.name)
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
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                // الحذف قد يفشل إذا كانت الواجهة غير مضافة
                Log.w("OverlayService", "Failed to remove overlay view", e)
            }
        }
    }
}

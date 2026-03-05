package com.airi.assistant.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.util.Log

class DebugOverlayService : Service() {

    companion object {

        private var textView: TextView? = null

        fun updateText(text: String) {

            try {

                textView?.post {

                    textView?.text = text

                }

            } catch (e: Exception) {

                Log.e("AIRI_OVERLAY", "Update error", e)

            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {

        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = TextView(this)

        overlayView.text = "AIRI Ready"

        overlayView.setBackgroundColor(0x88000000.toInt())

        overlayView.setTextColor(0xFFFFFFFF.toInt())

        overlayView.textSize = 12f

        overlayView.setPadding(20,20,20,20)

        val params = WindowManager.LayoutParams(

            WindowManager.LayoutParams.WRAP_CONTENT,

            WindowManager.LayoutParams.WRAP_CONTENT,

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,

            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,

            PixelFormat.TRANSLUCENT

        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        windowManager.addView(overlayView, params)

        textView = overlayView
    }

    override fun onDestroy() {

        super.onDestroy()

        try {

            windowManager.removeView(overlayView)

        } catch (e: Exception) {

            Log.e("AIRI_OVERLAY", "Remove error", e)

        }

        textView = null
    }
}

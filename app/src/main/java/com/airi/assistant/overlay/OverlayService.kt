package your.package.overlay

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    companion object {

        private var textView: TextView? = null

        fun updateText(text: String) {
            textView?.text = text
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

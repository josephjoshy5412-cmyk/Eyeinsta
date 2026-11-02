package com.eyescroll.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class ScrollAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ScrollAccessibilityService? = null
        private val INSTAGRAM = setOf("com.instagram.android", "com.instagram.lite")
    }

    @Volatile private var currentPkg = ""
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != "com.android.systemui") currentPkg = pkg
        }
    }

    override fun onInterrupt() {}

    /** next = true swipes up (next reel/post), false swipes down (previous). */
    fun scroll(next: Boolean): String {
        if (Prefs.onlyInstagram(this) && currentPkg !in INSTAGRAM) {
            return "Ignored (Instagram not open)"
        }
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val h = m.heightPixels.toFloat()
        val startY = if (next) h * 0.75f else h * 0.25f
        val endY = if (next) h * 0.25f else h * 0.75f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
            .build()
        main.post { dispatchGesture(gesture, null, null) }
        return if (next) "Next (swipe up)" else "Previous (swipe down)"
    }
}

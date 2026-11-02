package com.eyescroll.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(s: String, size: Float = 14f) = TextView(ctx).apply {
            text = s
            textSize = size
            setPadding(0, pad / 2, 0, pad / 4)
        }
        fun button(s: String, onClick: () -> Unit) = Button(ctx).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(label("EyeScroll", 28f))
        root.addView(label("Look down = next reel. Look up = previous reel."))

        root.addView(label("Step 1: Permissions"))
        root.addView(button("Allow camera & notifications") {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS), 1
            )
        })

        root.addView(label("Step 2: Accessibility (lets the app swipe for you)"))
        root.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(label(
            "Turn on EyeScroll there. If the switch is greyed out: Settings > Apps > EyeScroll > " +
                "three dots > Allow restricted settings."
        ))

        root.addView(label("Step 3: Start"))
        root.addView(button("Start tracking") {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(ctx, "Allow the camera first (Step 1)", Toast.LENGTH_LONG).show()
            } else {
                EyeState.error = null
                ContextCompat.startForegroundService(ctx, Intent(ctx, EyeTrackingService::class.java))
            }
        })
        root.addView(button("Stop tracking") {
            stopService(Intent(ctx, EyeTrackingService::class.java))
        })

        root.addView(label("Tuning"))
        root.addView(button("Calibrate (look at the middle of the screen)") {
            EyeState.calibrateUntil = SystemClock.uptimeMillis() + 1500
            Toast.makeText(ctx, "Look at the middle of the screen for 1.5 seconds", Toast.LENGTH_SHORT).show()
        })

        val thrLabel = label("")
        fun showThr(v: Float) { thrLabel.text = "Sensitivity threshold: %.2f (lower = more sensitive)".format(v) }
        showThr(Prefs.threshold(ctx))
        root.addView(thrLabel)
        root.addView(SeekBar(ctx).apply {
            max = 55
            progress = ((Prefs.threshold(ctx) * 100).toInt() - 5).coerceIn(0, 55)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (p + 5) / 100f
                    Prefs.sp(ctx).edit().putFloat("threshold", v).apply()
                    showThr(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(CheckBox(ctx).apply {
            text = "Only scroll inside Instagram"
            isChecked = Prefs.onlyInstagram(ctx)
            setOnCheckedChangeListener { _, c ->
                Prefs.sp(ctx).edit().putBoolean("only_ig", c).apply()
            }
        })

        root.addView(label("Live status"))
        status = TextView(ctx).apply { textSize = 15f }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val acc = ScrollAccessibilityService.instance != null
        status.text = buildString {
            appendLine("Accessibility: " + if (acc) "ON" else "OFF")
            appendLine("Tracking: " + if (EyeState.running) "RUNNING" else "stopped")
            EyeState.error?.let { appendLine("Error: $it") }
            appendLine("Face: " + if (EyeState.faceFound) "found" else "not found")
            appendLine("Look-down: %.2f".format(max(0f, EyeState.down - EyeState.baseDown)))
            appendLine("Look-up: %.2f".format(max(0f, EyeState.up - EyeState.baseUp)))
            appendLine("Last action: ${EyeState.lastAction}")
        }
    }
}

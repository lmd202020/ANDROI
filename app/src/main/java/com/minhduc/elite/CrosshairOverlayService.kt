package com.minhduc.elite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class CrosshairOverlayService : Service() {

    companion object {
        const val EXTRA_STYLE = "style"
        const val EXTRA_COLOR = "color"
        const val EXTRA_SIZE = "size"
        const val EXTRA_LOCK = "lock"
        const val EXTRA_SMOOTH = "smooth"
        const val EXTRA_COMPACT = "compact"
        const val CHANNEL_ID = "crosshair_overlay"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "STOP_CROSSHAIR"
    }

    private lateinit var wm: WindowManager
    private var crosshairView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var locked = false
    private var smooth = false
    private var compact = false

    private var lastRawX = 0f
    private var lastRawY = 0f
    private val smoothFactor = 0.55f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val style = intent?.getStringExtra(EXTRA_STYLE) ?: "⊕"
        val color = intent?.getIntExtra(EXTRA_COLOR, Color.CYAN) ?: Color.CYAN
        val size = intent?.getFloatExtra(EXTRA_SIZE, 36f) ?: 36f
        locked = intent?.getBooleanExtra(EXTRA_LOCK, false) ?: false
        smooth = intent?.getBooleanExtra(EXTRA_SMOOTH, false) ?: false
        compact = intent?.getBooleanExtra(EXTRA_COMPACT, false) ?: false

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        removeCrosshair()
        addCrosshair(style, color, size)
        return START_STICKY
    }

    private fun addCrosshair(style: String, color: Int, sizeSp: Float) {
        val tv = TextView(this).apply {
            text = style
            setTextColor(color)
            textSize = sizeSp
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
            setShadowLayer(if (compact) 5f else 3f, 0f, 0f, Color.argb(150, 0, 0, 0))
            if (compact) {
                background = null
                setPadding(2, 2, 2, 2)
            } else {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18f
                    setColor(Color.argb(48, 12, 22, 34))
                    setStroke(1, Color.argb(95, 255, 255, 255))
                }
                setPadding(12, 8, 12, 8)
                elevation = 8f
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 200
            y = 400
        }

        if (!locked) {
            tv.setOnTouchListener { v, e -> handleTouch(v, e, p) }
        }

        crosshairView = tv
        params = p
        runCatching { wm.addView(tv, p) }
    }

    private fun handleTouch(v: View, event: MotionEvent, p: WindowManager.LayoutParams): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                val factor = if (smooth) smoothFactor else 1f
                p.x += (dx * factor).toInt()
                p.y += (dy * factor).toInt()
                lastRawX = event.rawX
                lastRawY = event.rawY
                runCatching { wm.updateViewLayout(v, p) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun removeCrosshair() {
        crosshairView?.let { v -> runCatching { wm.removeView(v) } }
        crosshairView = null
        params = null
    }

    override fun onDestroy() {
        removeCrosshair()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Crosshair Overlay", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

        val stopIntent = Intent(this, CrosshairOverlayService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, flags)

        return builder
            .setContentTitle("Crosshair Overlay đang chạy")
            .setContentText(
                buildString {
                    append(if (locked) "🔒 Khóa " else "✋ Kéo ")
                    if (smooth) append("• Mượt ")
                    if (compact) append("• Gọn")
                    if (!locked && !smooth && !compact) append("Có thể kéo")
                }.trim()
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt", stopPi)
            .build()
    }
}

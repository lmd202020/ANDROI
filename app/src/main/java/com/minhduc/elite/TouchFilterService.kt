package com.minhduc.elite

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

class TouchFilterService : AccessibilityService() {

    companion object {
        const val TAG = "TouchFilter"
        const val ACTION_STATE = "com.minhduc.elite.TOUCH_STATE"
        const val EXTRA_SAMPLING = "samplingHz"
        const val EXTRA_EVENTS_PER_SEC = "eventsPerSec"
        
        @Volatile var isRunning = false
        @Volatile var lastSamplingHz = 0f
        @Volatile var lastEventsPerSec = 0f
    }

    private var eventCount = 0
    private var windowStart = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var filteredX = 0f
    private var filteredY = 0f
    private var smoothAlpha = 0.35f
    private var deadZonePx = 4f

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 50
        }
        serviceInfo = info
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }

    override fun onInterrupt() { isRunning = false }

    /**
     * Bắt MotionEvent toàn cục.
     */
    override fun onMotionEvent(event: MotionEvent) {
        val now = System.currentTimeMillis()
        if (windowStart == 0L) windowStart = now
        eventCount++

        val x = event.x
        val y = event.y
        if (lastX == 0f && lastY == 0f) {
            filteredX = x; filteredY = y
        } else {
            val dx = x - filteredX
            val dy = y - filteredY
            val dist = kotlin.math.hypot(dx, dy)
            if (dist > deadZonePx) {
                filteredX += smoothAlpha * dx
                filteredY += smoothAlpha * dy
            }
        }
        lastX = x; lastY = y

        if (now - windowStart >= 1000) {
            lastEventsPerSec = eventCount.toFloat()
            eventCount = 0
            windowStart = now
            sendBroadcast(Intent(ACTION_STATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_EVENTS_PER_SEC, lastEventsPerSec)
            })
        }
    }

    /** Cập nhật tham số filter từ UI */
    fun updateFilter(alpha: Float, deadZone: Float) {
        smoothAlpha = alpha.coerceIn(0.05f, 0.95f)
        deadZonePx = deadZone.coerceIn(0f, 20f)
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    /** Helper mở trang Accessibility Settings */
    fun openSettings(context: Context) {
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

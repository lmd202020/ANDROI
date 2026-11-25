package com.minhduc.elite

import android.content.Context
import android.os.Build
import android.view.WindowManager

object TouchBoost {
    /** Android exposes no reliable public API for the hardware touch sampling rate. */
    fun hardwareSamplingRateHz(): Float = 0f

    fun currentRefreshRate(context: Context): Float {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.refreshRate ?: 60f
        } else {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.refreshRate ?: 60f
        }
    }
}

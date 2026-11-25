package com.minhduc.elite

import android.content.Context

data class GameProfile(
    val packageName: String,
    val appName: String,
    val preset: String = "BALANCED",
    val sensitivity: Float = 0.85f,
    val smoothness: Float = 0.90f
)

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("minhduc_profiles", Context.MODE_PRIVATE)

    fun all(): List<GameProfile> = prefs.all.mapNotNull { (pkg, raw) ->
        val v = raw as? String ?: return@mapNotNull null
        val p = v.split("|")
        if (p.size < 4) return@mapNotNull null
        runCatching { GameProfile(pkg, p[0], p[1], p[2].toFloat(), p[3].toFloat()) }.getOrNull()
    }

    fun get(pkg: String) = all().firstOrNull { it.packageName == pkg }

    fun save(p: GameProfile) {
        prefs.edit().putString(
            p.packageName,
            listOf(p.appName, p.preset, p.sensitivity.toString(), p.smoothness.toString())
                .joinToString("|")
        ).apply()
    }

    fun delete(pkg: String) {
        prefs.edit().remove(pkg).apply()
    }
}

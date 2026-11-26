package com.minhduc.elite

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Cloudflare Worker-backed License manager.
 *
 * The Android app never contains ADMIN_SECRET. Admin operations must be performed
 * from the separate admin page. The public app only calls /activate and /validate.
 */
class LicenseManager(private val context: Context) {
    companion object {
        // Public API URL only. This is not an admin credential.
        private const val WORKER_BASE_URL =
            "https://muddy-snow-bf48.minhduc1234509876.workers.dev"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val prefs = context.getSharedPreferences("minhduc_license", Context.MODE_PRIVATE)

    private val deviceId: String
        get() = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )?.takeIf { it.isNotBlank() } ?: "android-unknown"

    data class Result(
        val ok: Boolean,
        val message: String,
        val expiresAt: String? = null
    )

    fun savedKey(): String? =
        prefs.getString("key", null)?.takeIf { it.isNotBlank() }

    fun lastMessage(): String =
        prefs.getString("message", "Chưa kích hoạt") ?: "Chưa kích hoạt"

    fun deviceIdentifier(): String = deviceId

    fun savedExpiration(): String? = prefs.getString("expires_at", null)

    fun lastVerifiedAt(): Long = prefs.getLong("last_check", 0L)

    fun maskedKey(): String = savedKey()?.let { key ->
        if (key.length <= 6) "••••••" else "${key.take(3)}••••••${key.takeLast(3)}"
    } ?: "Chưa có KEY"

    fun clear() {
        prefs.edit()
            .remove("key")
            .remove("last_check")
            .remove("message")
            .remove("expires_at")
            .apply()
    }

    suspend fun activate(input: String): Result = withContext(Dispatchers.IO) {
        val key = input.trim()
        if (key.isEmpty()) return@withContext Result(false, "Vui lòng nhập License KEY")

        try {
            val body = JSONObject()
                .put("key", key)
                .put("deviceId", deviceId)

            val json = post("/activate", body)
            if (!json.optBoolean("success", false)) {
                return@withContext Result(
                    false,
                    messageFor(json.optString("error", "ACTIVATION_FAILED"))
                )
            }

            val expiresAt = json.optString("expiresAt")
                .takeIf { it.isNotBlank() && it != "null" }

            prefs.edit()
                .putString("key", key)
                .putLong("last_check", System.currentTimeMillis())
                .putString("expires_at", expiresAt)
                .putString("message", "Kích hoạt thành công")
                .apply()

            Result(true, "Kích hoạt thành công", expiresAt)
        } catch (e: Exception) {
            Result(false, readableError(e))
        }
    }

    suspend fun verifySaved(): Result = withContext(Dispatchers.IO) {
        val key = savedKey() ?: return@withContext Result(false, "Chưa có License KEY")

        try {
            val body = JSONObject()
                .put("key", key)
                .put("deviceId", deviceId)

            val json = post("/validate", body)
            val valid = json.optBoolean("valid", false) &&
                json.optBoolean("success", false)

            if (!valid) {
                val errorCode = json.optString("error", "LICENSE_INVALID")
                val message = messageFor(errorCode)
                if (errorCode in setOf(
                        "LICENSE_NOT_FOUND", "LICENSE_REVOKED", "LICENSE_INVALID",
                        "LICENSE_EXPIRED", "DEVICE_MISMATCH"
                    )
                ) {
                    clear()
                }
                prefs.edit().putString("message", message).apply()
                return@withContext Result(false, message)
            }

            val expiresAt = json.optString("expiresAt")
                .takeIf { it.isNotBlank() && it != "null" }

            prefs.edit()
                .putLong("last_check", System.currentTimeMillis())
                .putString("expires_at", expiresAt)
                .putString("message", "Đã xác thực với Cloudflare")
                .apply()

            Result(true, "Đã xác thực với Cloudflare", expiresAt)
        } catch (e: Exception) {
            // Fail closed: do not grant access when the server cannot confirm validity.
            Result(false, readableError(e))
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val url = URL(WORKER_BASE_URL.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Cache-Control", "no-store")
        }

        try {
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw IllegalStateException("Máy chủ trả về dữ liệu không hợp lệ (HTTP $status)")
            }

            // Worker uses structured JSON errors for 4xx/5xx responses too.
            // Let activate/validate interpret the error code instead of hiding it.
            if (status !in 200..299 && !json.has("error")) {
                throw ApiException("Máy chủ trả về HTTP $status")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun messageFor(code: String): String = when (code) {
        "LICENSE_NOT_FOUND" -> "License KEY không tồn tại"
        "LICENSE_REVOKED", "LICENSE_INVALID" -> "License KEY đã bị khóa hoặc thu hồi"
        "LICENSE_EXPIRED" -> "License KEY đã hết hạn"
        "DEVICE_ALREADY_BOUND", "DEVICE_MISMATCH" ->
            "License KEY đã được gắn với thiết bị khác"
        "INVALID_KEY_OR_DEVICE", "INVALID_INPUT" -> "KEY hoặc Device ID không hợp lệ"
        "DATABASE_NOT_CONFIGURED" -> "Máy chủ chưa kết nối cơ sở dữ liệu"
        "UNAUTHORIZED" -> "Máy chủ từ chối quyền truy cập quản trị"
        "ACTIVATION_FAILED" -> "Không thể kích hoạt KEY, vui lòng thử lại"
        else -> "Lỗi máy chủ: $code"
    }

    private fun readableError(error: Exception): String = when (error) {
        is ApiException -> error.message ?: "Không thể xác thực License"
        else -> "Không thể kết nối Cloudflare: ${error.message ?: "Lỗi mạng"}"
    }

    /**
     * Accepts ISO-8601 timestamps from Worker and yyyy-MM-dd values for display
     * compatibility. Actual expiry enforcement is performed by the Worker too.
     */
    fun isExpirationPassed(value: String): Boolean {
        val now = Instant.now()
        return try {
            Instant.parse(value).isBefore(now)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant().isBefore(now)
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(value).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant().isBefore(now)
                } catch (_: DateTimeParseException) {
                    false
                }
            }
        }
    }

    private class ApiException(message: String) : Exception(message)
}

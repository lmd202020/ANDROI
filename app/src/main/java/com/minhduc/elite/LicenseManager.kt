package com.minhduc.elite

import android.content.Context
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GitHub-backed key activation.
 *
 * SECURITY: A token embedded in an APK can be extracted. Use a newly-created,
 * narrowly-scoped token and understand that APK distribution exposes it.
 */
class LicenseManager(private val context: Context) {
    companion object {
        // Replace this placeholder with a NEW GitHub token. Do not reuse a leaked token.
        private const val GITHUB_TOKEN = "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"
        private const val OWNER = "lmd140809"
        private const val REPO = "MinhDux"
        private const val BRANCH = "main"
        private const val PATH = "keys.json"
        private const val OFFLINE_GRACE_MS = 3L * 24 * 60 * 60 * 1000
        private const val API = "https://api.github.com/repos/$OWNER/$REPO/contents/$PATH"
    }

    private val prefs = context.getSharedPreferences("minhduc_license", Context.MODE_PRIVATE)
    private val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: "android-unknown"

    data class Result(val ok: Boolean, val message: String, val expiresAt: String? = null)

    fun savedKey(): String? = prefs.getString("key", null)?.takeIf { it.isNotBlank() }
    fun lastMessage(): String = prefs.getString("message", "Chưa kích hoạt") ?: "Chưa kích hoạt"
    fun deviceIdentifier(): String = deviceId
    fun savedExpiration(): String? = prefs.getString("expires_at", null)
    fun lastVerifiedAt(): Long = prefs.getLong("last_check", 0L)
    fun maskedKey(): String = savedKey()?.let { key ->
        if (key.length <= 6) "••••••" else "${key.take(3)}••••••${key.takeLast(3)}"
    } ?: "Chưa có KEY"
    fun clear() { prefs.edit().remove("key").remove("last_check").remove("message").remove("expires_at").apply() }

    suspend fun activate(input: String): Result = withContext(Dispatchers.IO) {
        val key = input.trim()
        if (key.isEmpty()) return@withContext Result(false, "Vui lòng nhập KEY")
        try {
            repeat(3) {
                val (root, sha) = getKeys()
                val keys = root.optJSONArray("keys") ?: return@withContext Result(false, "keys.json không có danh sách keys")
                var index = -1
                var match: JSONObject? = null
                for (i in 0 until keys.length()) {
                    val item = keys.optJSONObject(i) ?: continue
                    if (item.optString("key").equals(key, ignoreCase = true)) {
                        index = i; match = item; break
                    }
                }
                val item = match ?: return@withContext Result(false, "KEY không tồn tại trên server")
                val expires = item.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" }
                if (expires != null && isExpired(expires))
                    return@withContext Result(false, "KEY đã hết hạn", expires)

                val bound = item.optString("deviceID").takeIf { it.isNotBlank() && it != "null" }
                if (bound != null && bound != deviceId)
                    return@withContext Result(false, "KEY đã được kích hoạt trên thiết bị khác", expires)

                if (bound == null) {
                    item.put("deviceID", deviceId)
                    keys.put(index, item)
                    root.put("keys", keys)
                    root.put("updatedAt", isoNow())
                    try {
                        putKeys(root, sha)
                    } catch (e: HttpFailure) {
                        if (e.code == 409 || e.code == 422) return@repeat
                        throw e
                    }
                }
                prefs.edit().putString("key", key).putLong("last_check", System.currentTimeMillis())
                    .putString("expires_at", expires).putString("message", "Kích hoạt thành công").apply()
                return@withContext Result(true, "Kích hoạt thành công", expires)
            }
            Result(false, "Dữ liệu đang xung đột, vui lòng thử lại")
        } catch (e: Exception) {
            Result(false, readableError(e))
        }
    }

    suspend fun verifySaved(): Result = withContext(Dispatchers.IO) {
        val key = savedKey() ?: return@withContext Result(false, "Chưa có KEY")
        try {
            val (root, _) = getKeys()
            val keys = root.optJSONArray("keys") ?: return@withContext invalidate("Danh sách KEY không hợp lệ")
            var item: JSONObject? = null
            for (i in 0 until keys.length()) {
                val candidate = keys.optJSONObject(i) ?: continue
                if (candidate.optString("key").equals(key, ignoreCase = true)) { item = candidate; break }
            }
            val found = item ?: return@withContext invalidate("KEY đã bị thu hồi")
            val expires = found.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" }
            if (expires != null && isExpired(expires)) return@withContext invalidate("KEY đã hết hạn")
            if (found.optString("deviceID") != deviceId) return@withContext invalidate("KEY không còn gắn với thiết bị này")
            prefs.edit().putLong("last_check", System.currentTimeMillis())
                .putString("expires_at", expires).putString("message", "Đã xác thực").apply()
            Result(true, "Đã xác thực", expires)
        } catch (e: Exception) {
            val last = prefs.getLong("last_check", 0L)
            if (last > 0L && System.currentTimeMillis() - last <= OFFLINE_GRACE_MS) {
                Result(true, "Đang ngoại tuyến • đang dùng thời gian chờ xác thực")
            } else {
                invalidate("Không thể xác thực KEY. Vui lòng kết nối mạng.")
            }
        }
    }

    private fun invalidate(message: String): Result {
        clear()
        prefs.edit().putString("message", message).apply()
        return Result(false, message)
    }

    private data class FileData(val json: JSONObject, val sha: String)

    private fun getKeys(): Pair<JSONObject, String> {
        val response = request("GET")
        val file = JSONObject(response)
        val content = file.getString("content").replace("\n", "").replace("\r", "")
        val decoded = Base64.decode(content, Base64.DEFAULT).toString(Charsets.UTF_8)
        return JSONObject(decoded) to file.getString("sha")
    }

    private fun putKeys(root: JSONObject, sha: String) {
        val content = Base64.encodeToString(root.toString(2).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = JSONObject().put("message", "Bind Android device")
            .put("content", content).put("sha", sha).put("branch", BRANCH)
        request("PUT", body.toString())
    }

    private fun request(method: String, body: String? = null): String {
        if (GITHUB_TOKEN == "PASTE_NEW_GITHUB_TOKEN_HERE")
            throw IllegalStateException("Bạn chưa cấu hình GitHub Token trong LicenseManager.kt")
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $GITHUB_TOKEN")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MINHDUC-ANDROID-ELITE")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpFailure(code, "GitHub trả về HTTP $code: ${extractMessage(text)}")
            return text
        } finally { connection.disconnect() }
    }

    private fun extractMessage(raw: String): String =
        runCatching { JSONObject(raw).optString("message") }.getOrDefault("")

    private fun readableError(e: Exception): String = when (e) {
        is HttpFailure -> e.message ?: "Lỗi GitHub"
        else -> e.message ?: "Không thể kết nối GitHub"
    }

    private fun isExpired(value: String): Boolean {
        val date = parseDate(value) ?: return false
        val endOfDay = date + 24L * 60 * 60 * 1000
        return endOfDay <= System.currentTimeMillis()
    }

    private fun parseDate(value: String): Long? {
        val formats = listOf("yyyy-MM-dd", "yyyy-M-d")
        for (pattern in formats) {
            try {
                val f = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return f.parse(value)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    private fun isoNow(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private class HttpFailure(val code: Int, message: String) : Exception(message)
}

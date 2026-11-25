package com.minhduc.elite

import android.content.Context

/** Runs as the user-approved Shizuku shell identity. */
class PrivilegedPerformanceService() : IPerformanceService.Stub() {
    @Suppress("unused")
    constructor(context: Context) : this()

    override fun readAnimationValues(): Array<String> {
        val result = execute(
            "settings get global window_animation_scale; " +
                "settings get global transition_animation_scale; " +
                "settings get global animator_duration_scale"
        )
        check(result.first == 0) { "Không đọc được animation: ${result.second}" }
        val values = result.second.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.toList()
        check(values.size >= 3) { "Dữ liệu animation không hợp lệ: ${result.second}" }
        return values.take(3).map { if (it == "null") "1" else it }.toTypedArray()
    }

    override fun applyAnimation(
        enable: Boolean,
        windowScale: String?,
        transitionScale: String?,
        animatorScale: String?
    ): String {
        val values = if (enable) listOf("0", "0", "0") else listOf(
            safeScale(windowScale), safeScale(transitionScale), safeScale(animatorScale)
        )
        val script = "set -e; " +
            "settings put global window_animation_scale ${values[0]}; " +
            "settings put global transition_animation_scale ${values[1]}; " +
            "settings put global animator_duration_scale ${values[2]}; " +
            "echo __MINHDUC_OK__"
        val result = execute(script)
        check(result.first == 0 && result.second.contains("__MINHDUC_OK__")) {
            "Không thể thay đổi animation (exit=${result.first}): ${result.second}"
        }
        val verified = readAnimationValues()
        val expected = values.map { it.toFloat() }
        check(verified.indices.all {
            kotlin.math.abs(verified[it].toFloat() - expected[it]) < 0.001f
        }) { "Android không xác nhận đủ 3 mức animation: ${verified.joinToString()}" }
        return if (enable) "Đã tắt 3 mức animation Android qua Shizuku."
        else "Đã khôi phục 3 mức animation Android."
    }

    private fun safeScale(value: String?): String {
        val parsed = value?.toFloatOrNull() ?: 1f
        return parsed.coerceIn(0f, 10f).toString()
    }

    private fun execute(script: String): Pair<Int, String> {
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }

    // Kept to preserve the existing AIDL interface. Asset injection is intentionally disabled.
    override fun installGameAssets(sourcePath: String, packageName: String): String {
        require(GameDataPaths.isSupported(packageName)) {
            "Package game không được hỗ trợ. Dùng com.dts.freefireth hoặc com.garena.ffvn."
        }
        throw UnsupportedOperationException(
            "Chức năng tự chép/thay thế tài nguyên game không được bật trong bản an toàn này."
        )
    }

    override fun restoreGameAssets(packageName: String): String {
        require(GameDataPaths.isSupported(packageName)) {
            "Package game không được hỗ trợ. Dùng com.dts.freefireth hoặc com.garena.ffvn."
        }
        throw UnsupportedOperationException(
            "Chức năng thay đổi tài nguyên game đã bị vô hiệu hóa; không có thao tác khôi phục nào được chạy."
        )
    }

    override fun destroy() {
        System.exit(0)
    }
}

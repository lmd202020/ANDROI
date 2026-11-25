package com.minhduc.elite

import android.content.Context

/** Runs as the user-approved Shizuku shell/root identity. */
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
        val values = result.second.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
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
        check(verified.indices.all { kotlin.math.abs(verified[it].toFloat() - expected[it]) < 0.001f }) {
            "Android không xác nhận đủ 3 mức animation: ${verified.joinToString()}"
        }
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


    override fun installGameAssets(sourcePath: String, packageName: String): String {
        val pkg = packageName
        require(pkg == "com.dts.freefireth" || pkg == "com.garena.ffv") {
            "Package game không được hỗ trợ."
        }
        val source = sourcePath
        require(source.startsWith("/storage/emulated/0/Android/data/com.minhduc.elite/files/")) {
            "Đường dẫn nguồn không hợp lệ."
        }
        val destination = "/sdcard/Android/data/$pkg/files/contentcache/Compulsory/android/gameassetbundles"
        val backup = "/storage/emulated/0/Android/data/com.minhduc.elite/files/game_restore/$pkg/backup"
        fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val script = """
            set -e
            SRC=${q(source)}
            DST=${q(destination)}
            BAK=${q(backup)}
            test -d "${'$'}SRC/avatar"
            test -d "${'$'}SRC/config"
            ACOUNT=${'$'}(find "${'$'}SRC/avatar" -maxdepth 1 -type f | wc -l)
            CCOUNT=${'$'}(find "${'$'}SRC/config" -maxdepth 1 -type f | wc -l)
            test "${'$'}ACOUNT" -ge 1
            test "${'$'}CCOUNT" -ge 2
            mkdir -p "${'$'}DST/avatar" "${'$'}DST/config"
            if [ ! -f "${'$'}BAK/.backup_done" ]; then
              mkdir -p "${'$'}BAK"
              if [ -d "${'$'}DST/avatar" ] && [ "${'$'}(find "${'$'}DST/avatar" -mindepth 1 -maxdepth 1 | wc -l)" -gt 0 ]; then
                cp -a "${'$'}DST/avatar" "${'$'}BAK/avatar_before"
                touch "${'$'}BAK/avatar_existed"
              fi
              if [ -d "${'$'}DST/config" ] && [ "${'$'}(find "${'$'}DST/config" -mindepth 1 -maxdepth 1 | wc -l)" -gt 0 ]; then
                cp -a "${'$'}DST/config" "${'$'}BAK/config_before"
                touch "${'$'}BAK/config_existed"
              fi
              touch "${'$'}BAK/.backup_done"
            fi
            cp -a "${'$'}SRC/avatar/." "${'$'}DST/avatar/"
            cp -a "${'$'}SRC/config/." "${'$'}DST/config/"
            test "${'$'}(find "${'$'}DST/avatar" -maxdepth 1 -type f | wc -l)" -ge "${'$'}ACOUNT"
            test "${'$'}(find "${'$'}DST/config" -maxdepth 1 -type f | wc -l)" -ge "${'$'}CCOUNT"
            echo "__MINHDUC_ASSETS_OK__"
        """.trimIndent()
        val result = execute(script)
        check(result.first == 0 && result.second.contains("__MINHDUC_ASSETS_OK__")) {
            "Không thể áp dụng file (exit=${result.first}): ${result.second}"
        }
        return "Đã áp dụng bộ file cho $pkg."
    }

    override fun restoreGameAssets(packageName: String): String {
        val pkg = packageName
        require(pkg == "com.dts.freefireth" || pkg == "com.garena.ffv") {
            "Package game không được hỗ trợ."
        }
        val base = "/storage/emulated/0/Android/data/com.minhduc.elite/files/game_restore/$pkg"
        val destination = "/sdcard/Android/data/$pkg/files/contentcache/Compulsory/android/gameassetbundles"
        val backup = "$base/backup"
        fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val script = """
            set -e
            BAK=${q(backup)}
            DST=${q(destination)}
            test -f "${'$'}BAK/.backup_done"
            if [ -f "${'$'}BAK/avatar_existed" ]; then
              rm -rf "${'$'}DST/avatar"
              cp -a "${'$'}BAK/avatar_before" "${'$'}DST/avatar"
            else
              rm -rf "${'$'}DST/avatar"
            fi
            if [ -f "${'$'}BAK/config_existed" ]; then
              rm -rf "${'$'}DST/config"
              cp -a "${'$'}BAK/config_before" "${'$'}DST/config"
            else
              rm -rf "${'$'}DST/config"
            fi
            rm -rf "${'$'}BAK"
            echo "__MINHDUC_RESTORE_OK__"
        """.trimIndent()
        val result = execute(script)
        check(result.first == 0 && result.second.contains("__MINHDUC_RESTORE_OK__")) {
            "Không thể khôi phục dữ liệu game (exit=${result.first}): ${result.second}"
        }
        return "Đã khôi phục dữ liệu trước khi áp dụng cho $pkg."
    }

    override fun destroy() {
        System.exit(0)
    }
}

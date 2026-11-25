package com.minhduc.elite

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/** Shizuku UserService client for Android animation settings. */
object ShizukuPerformance {
    const val REQUEST_CODE = 7104
    private const val PREFS = "shizuku_performance_backup"
    private const val KEY_BACKUP = "backup_exists"

    @Volatile private var remote: IPerformanceService? = null
    @Volatile private var connection: ServiceConnection? = null

    fun isServiceRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isUserServiceConnected(): Boolean = remote != null

    fun requestPermission() {
        check(isServiceRunning()) { "Shizuku chưa chạy. Hãy khởi động Shizuku trước." }
        if (!hasPermission()) Shizuku.requestPermission(REQUEST_CODE)
    }

    fun connect(context: Context, onResult: (Boolean, String) -> Unit) {
        if (!isServiceRunning()) {
            onResult(false, "Shizuku chưa chạy. Hãy khởi động Shizuku trước.")
            return
        }
        if (!hasPermission()) {
            onResult(false, "Chưa cấp quyền Shizuku cho MINHDUC.")
            return
        }
        if (remote != null) {
            onResult(true, "Đã kết nối Shizuku UserService.")
            return
        }
        if (connection != null) {
            onResult(false, "Đang chờ Shizuku UserService; nếu quá 12 giây hãy thử kết nối lại.")
            return
        }

        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var conn: ServiceConnection
        val timeout = Runnable {
            if (connection === conn && remote == null) {
                runCatching { Shizuku.unbindUserService(
                    Shizuku.UserServiceArgs(ComponentName(context, PrivilegedPerformanceService::class.java))
                        .daemon(false).processNameSuffix("minhduc_performance")
                        .tag("minhduc-performance-service").version(2), conn, true
                ) }
                connection = null
                onResult(false, "Shizuku không khởi chạy được UserService sau 12 giây. Hãy kiểm tra app đã được cập nhật và thử KẾT NỐI lại.")
            }
        }

        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, PrivilegedPerformanceService::class.java)
            ).daemon(false)
                .processNameSuffix("minhduc_performance")
                .tag("minhduc-performance-service")
                .version(2)

            conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    mainHandler.removeCallbacks(timeout)
                    val binder = service
                    val candidate = if (binder != null && binder.isBinderAlive) {
                        IPerformanceService.Stub.asInterface(binder)
                    } else null
                    if (candidate == null) {
                        remote = null
                        connection = null
                        onResult(false, "Shizuku đã bind nhưng Binder dịch vụ không hợp lệ.")
                        return
                    }
                    remote = candidate
                    connection = this
                    onResult(true, "Đã kết nối Shizuku UserService.")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    mainHandler.removeCallbacks(timeout)
                    remote = null
                    connection = null
                    onResult(false, "Shizuku UserService đã ngắt kết nối. Hãy kết nối lại.")
                }

                override fun onBindingDied(name: ComponentName?) {
                    mainHandler.removeCallbacks(timeout)
                    remote = null
                    connection = null
                    onResult(false, "Liên kết UserService bị hệ thống hủy. Hãy nhấn KẾT NỐI lại.")
                }

                override fun onNullBinding(name: ComponentName?) {
                    mainHandler.removeCallbacks(timeout)
                    remote = null
                    connection = null
                    onResult(false, "Shizuku trả về binding rỗng. Kiểm tra cấu hình UserService trong APK.")
                }
            }
            connection = conn
            Shizuku.bindUserService(args, conn)
            mainHandler.postDelayed(timeout, 12_000L)
            onResult(false, "Đã gửi yêu cầu khởi chạy UserService; đang chờ Binder...")
        } catch (t: Throwable) {
            mainHandler.removeCallbacks(timeout)
            remote = null
            connection = null
            onResult(false, "Không thể bind Shizuku UserService: ${t.message ?: t.javaClass.simpleName}")
        }
    }


    fun installGameAssets(sourcePath: String, packageName: String): Result<String> = runCatching {
        check(isServiceRunning()) { "Shizuku chưa chạy." }
        check(hasPermission()) { "MINHDUC chưa được cấp quyền Shizuku." }
        val service = checkNotNull(remote) { "UserService chưa kết nối. Hãy nhấn KẾT NỐI SHIZUKU." }
        service.installGameAssets(sourcePath, packageName)
    }

    fun restoreGameAssets(packageName: String): Result<String> = runCatching {
        check(isServiceRunning()) { "Shizuku chưa chạy." }
        check(hasPermission()) { "MINHDUC chưa được cấp quyền Shizuku." }
        val service = checkNotNull(remote) { "UserService chưa kết nối. Hãy nhấn KẾT NỐI SHIZUKU." }
        service.restoreGameAssets(packageName)
    }

    fun apply(context: Context, enable: Boolean): Result<String> = runCatching {
        check(isServiceRunning()) { "Shizuku đã dừng. Hãy khởi động lại dịch vụ." }
        check(hasPermission()) { "Ứng dụng chưa được cấp quyền trong Shizuku." }
        val service = checkNotNull(remote) { "Chưa kết nối UserService. Nhấn KẾT NỐI SHIZUKU trước." }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (enable && !prefs.getBoolean(KEY_BACKUP, false)) {
            val values = service.readAnimationValues()
            require(values.size >= 3) { "Không đọc được các giá trị animation hiện tại." }
            prefs.edit()
                .putString("window", values[0])
                .putString("transition", values[1])
                .putString("animator", values[2])
                .putBoolean(KEY_BACKUP, true)
                .apply()
        }

        val window = if (enable) "0" else prefs.getString("window", "1") ?: "1"
        val transition = if (enable) "0" else prefs.getString("transition", "1") ?: "1"
        val animator = if (enable) "0" else prefs.getString("animator", "1") ?: "1"
        val message = service.applyAnimation(enable, window, transition, animator)

        if (!enable) {
            prefs.edit().remove(KEY_BACKUP).remove("window").remove("transition").remove("animator").apply()
        }
        message
    }
}

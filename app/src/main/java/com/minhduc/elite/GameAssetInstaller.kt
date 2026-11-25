package com.minhduc.elite

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val AssetPanel = Color(0xFF111B27)
private val AssetMuted = Color(0xFF9AAABD)

@Composable
fun GameAssetInstallerPanel(packageName: String, gameLabel: String, accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(ShizukuPerformance.isUserServiceConnected()) }
    var running by remember { mutableStateOf(ShizukuPerformance.isServiceRunning()) }
    var granted by remember { mutableStateOf(ShizukuPerformance.hasPermission()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Chuẩn bị bộ file trong mã nguồn rồi nhấn ÁP DỤNG FILE.") }

    Column(
        Modifier.fillMaxWidth().background(AssetPanel, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GAME ASSET INSTALLER", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(gameLabel, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(
            "Chép các file bạn đóng gói trong app/src/main/assets/game_payload/gameassetbundles/ " +
                "vào thư mục dữ liệu game. Tên file gốc được giữ nguyên.",
            color = AssetMuted, fontSize = 11.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusTile("SHIZUKU", if (running) "RUNNING" else "OFFLINE", accent, Modifier.weight(1f))
            StatusTile("QUYỀN", if (granted) "GRANTED" else "CHƯA CẤP", accent, Modifier.weight(1f))
            StatusTile("SERVICE", if (connected) "CONNECTED" else "OFFLINE", accent, Modifier.weight(1f))
        }
        OutlinedButton(
            onClick = {
                running = ShizukuPerformance.isServiceRunning()
                granted = ShizukuPerformance.hasPermission()
                if (!running) {
                    status = "Mở Shizuku và khởi động dịch vụ Wireless Debugging trước."
                    runCatching {
                        context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?.let { intent -> context.startActivity(intent) }
                    }
                } else if (!granted) {
                    runCatching { ShizukuPerformance.requestPermission() }
                        .onFailure { status = it.message ?: "Không gửi được yêu cầu quyền." }
                    status = "Hãy chấp nhận quyền MINHDUC trong Shizuku, sau đó nhấn KẾT NỐI."
                } else {
                    ShizukuPerformance.connect(context) { ok, message ->
                        connected = ok
                        status = message
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Link, null)
            Spacer(Modifier.width(8.dp))
            Text("KẾT NỐI / CẤP QUYỀN SHIZUKU")
        }
        Button(
            enabled = !busy,
            onClick = {
                running = ShizukuPerformance.isServiceRunning()
                granted = ShizukuPerformance.hasPermission()
                connected = ShizukuPerformance.isUserServiceConnected()
                if (!running || !granted || !connected) {
                    status = "Cần Shizuku RUNNING, quyền GRANTED và UserService CONNECTED trước."
                    return@Button
                }
                busy = true
                status = "Đang kiểm tra bộ file và sao lưu dữ liệu cũ..."
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val staged = stageBundledAssets(context, packageName)
                            ShizukuPerformance.installGameAssets(staged.absolutePath, packageName).getOrThrow()
                        }
                    }
                    busy = false
                    result.onSuccess {
                        status = "ĐÃ ÁP DỤNG FILE THÀNH CÔNG\n$it"
                    }.onFailure {
                        status = "KHÔNG THỂ ÁP DỤNG: ${it.message ?: it.javaClass.simpleName}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF06111A))
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.InstallMobile, null)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "ĐANG ÁP DỤNG..." else "ÁP DỤNG FILE", fontWeight = FontWeight.Bold)
        }
        Text(status, color = if (status.startsWith("ĐÃ ÁP DỤNG")) Color(0xFF43D99B) else AssetMuted, fontSize = 11.sp)
        Text(
            "Đích: /Android/data/$packageName/files/contentcache/Compulsory/android/gameassetbundles/",
            color = AssetMuted, fontSize = 9.sp
        )
    }
}

@Composable
private fun StatusTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF07111C), RoundedCornerShape(10.dp)).padding(9.dp)) {
        Text(label, color = AssetMuted, fontSize = 9.sp)
        Text(value, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun stageBundledAssets(context: Context, packageName: String): File {
    require(packageName == "com.dts.freefireth" || packageName == "com.garena.ffv") {
        "Chỉ hỗ trợ Free Fire TH và Free Fire VN."
    }
    val assetRoot = "game_payload/gameassetbundles"
    val stage = File(context.getExternalFilesDir(null), "game_restore/$packageName/gameassetbundles")
    stage.parentFile?.deleteRecursively()
    stage.mkdirs()
    copyAssetDirectory(context.assets, assetRoot, stage)
    val avatar = File(stage, "avatar")
    val config = File(stage, "config")
    require(avatar.isDirectory && config.isDirectory) {
        "Thiếu thư mục avatar hoặc config trong app/src/main/assets/game_payload/gameassetbundles."
    }
    val avatarFiles = avatar.listFiles()?.filter { it.isFile && it.name != ".gitkeep" }.orEmpty()
    val configFiles = config.listFiles()?.filter { it.isFile && it.name != ".gitkeep" }.orEmpty()
    require(avatarFiles.size >= 1) { "Thư mục avatar chưa có file. Hãy thêm assetindexer vào đó." }
    require(configFiles.size >= 2) { "Thư mục config cần ít nhất 2 file clothesslotoverlays." }
    return stage
}

private fun copyAssetDirectory(assets: AssetManager, assetPath: String, destination: File) {
    val children = assets.list(assetPath).orEmpty()
    if (children.isNotEmpty()) {
        destination.mkdirs()
        children.filterNot { it == ".gitkeep" || it == "README.txt" }.forEach { child ->
            copyAssetDirectory(assets, "$assetPath/$child", File(destination, child))
        }
    } else {
        destination.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
    }
}

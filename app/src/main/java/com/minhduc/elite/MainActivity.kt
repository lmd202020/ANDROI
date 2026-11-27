@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.minhduc.elite

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale

// ============================================================
// COLORS
// ============================================================
private val Bg = Color(0xFF080D14)
private val Panel = Color(0xFF111B27)
private val Panel2 = Color(0xFF172536)
private val Cyan = Color(0xFF46DDF4)
private val Muted = Color(0xFF9AAABD)
private val Green = Color(0xFF43D99B)
private val Orange = Color(0xFFFFB45E)

data class InstalledApp(val label: String, val pkg: String)

/** Đường dẫn dữ liệu của các game được hỗ trợ */
object GameDataPaths {
    const val FREE_FIRE_TH = "com.dts.freefireth"
    const val FREE_FIRE_VN = "com.garena.ffvn"

    fun isSupported(packageName: String): Boolean =
        packageName == FREE_FIRE_TH || packageName == FREE_FIRE_VN

    // Đã sửa: Thêm /storage/emulated/0 để trỏ đúng vào bộ nhớ trong, tránh lỗi Read-only
    fun gameFilesPath(packageName: String): String {
        require(isSupported(packageName)) { "Package game không được hỗ trợ: $packageName" }
        return "/storage/emulated/0/Android/data/$packageName/files/"
    }

    fun contentCachePath(packageName: String): String =
        gameFilesPath(packageName) + "contentcache/"

    fun targetBundlePath(packageName: String): String =
        contentCachePath(packageName) + "Optional/android/gameassetbundles/"
}

// ============================================================
// FILE INSTALLER LOGIC (DÙNG SHIZUKU + REFLECTION)
// ============================================================
object FileInstaller {
    const val ASSETS_BUNDLES_DIR = "game_payload/file/contentcache/Optional/android/gameassetbundles"

    fun getLocalAssets(context: Context): List<String> {
        return try {
            context.assets.list(ASSETS_BUNDLES_DIR)?.toList()?.sorted() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun applyFilesWithShizuku(context: Context, packageName: String): String = withContext(Dispatchers.IO) {
        // 1) Kiểm tra package hỗ trợ
        if (!GameDataPaths.isSupported(packageName)) {
            return@withContext "❌ Package game không được hỗ trợ."
        }
        val files = getLocalAssets(context)
        if (files.isEmpty()) {
            return@withContext "❌ Không tìm thấy file trong assets."
        }

        // 2) Kiểm tra Shizuku
        val shizukuOk = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        if (!shizukuOk) {
            return@withContext "❌ Shizuku chưa được cấp quyền."
        }

        // 3) Copy assets ra thư mục tạm app có thể ghi
        val tmpRoot = File(context.cacheDir, "elite_payload")
        if (tmpRoot.exists()) tmpRoot.deleteRecursively()
        tmpRoot.mkdirs()

        try {
            for (name in files) {
                val outFile = File(tmpRoot, name)
                context.assets.open("$ASSETS_BUNDLES_DIR/$name").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) {
            return@withContext "❌ Lỗi chuẩn bị file tạm: ${e.message}"
        }

        // 4) Dùng Shizuku copy sang thư mục đích
        val targetDir = GameDataPaths.targetBundlePath(packageName)
        val tmpPath = tmpRoot.absolutePath
        val script = buildString {
            append("set -e\n")
            append("am force-stop $packageName || true\n")
            append("mkdir -p '$targetDir'\n")
            append("cp -f '$tmpPath'/* '$targetDir' 2>/dev/null || true\n")
            append("chmod -R 771 '$targetDir' 2>/dev/null || true\n")
            append("chown -R $(stat -c '%u:%g' '/storage/emulated/0/Android/data/$packageName') '$targetDir' 2>/dev/null || true\n")
            append("ls '$targetDir'\n")
        }

        val resultText = try {
            execShell(script)
        } catch (e: Exception) {
            tmpRoot.deleteRecursively()
            return@withContext "❌ Lỗi Shizuku: ${e.message}"
        }

        // 5) Dọn thư mục tạm
        tmpRoot.deleteRecursively()

        return@withContext if (resultText.contains("ERROR")) {
            "❌ Áp dụng thất bại:\n$resultText"
        } else {
            "✅ Đã áp dụng ${files.size} file vào:\n$targetDir"
        }
    }

    /**
     * Chạy script shell qua Shizuku, trả về stdout/stderr.
     * Shizuku 13.x đã chuyển newProcess thành private → dùng reflection.
     */
    private fun execShell(script: String): String {
        val process = newShizukuProcess(arrayOf("sh", "-c", script))
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        return buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                append("\n[stderr]\n").append(stderr)
                append("\nERROR")
            }
            if (exit != 0) {
                append("\n[exit=$exit] ERROR")
            }
        }.trim()
    }

    /**
     * Gọi Shizuku.newProcess thông qua reflection để tương thích cả Shizuku 12.x và 13.x.
     */
    private fun newShizukuProcess(cmd: Array<String>): ShizukuRemoteProcess {
        return try {
            // Thử gọi trực tiếp trước (Shizuku 12.x)
            @Suppress("DEPRECATION")
            val direct = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            direct.isAccessible = true
            direct.invoke(null, cmd, null, null) as ShizukuRemoteProcess
        } catch (e: Exception) {
            // Fallback reflection qua Class.forName (Shizuku 13.x)
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as ShizukuRemoteProcess
        }
    }
}

// ============================================================
// ACTIVITY
// ============================================================
class MainActivity : ComponentActivity() {
    private lateinit var store: ProfileStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan, secondary = Green, background = Bg, surface = Panel,
                    onBackground = Color.White, onSurface = Color.White
                )
            ) {
                LicenseGate { licenseManager ->
                    EliteApp(
                        manufacturer = Build.MANUFACTURER,
                        model = Build.MODEL,
                        androidVersion = Build.VERSION.RELEASE,
                        api = Build.VERSION.SDK_INT,
                        refreshRate = (getSystemService(DISPLAY_SERVICE) as DisplayManager)
                            .getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f,
                        apps = remember { queryApps() },
                        store = store,
                        licenseManager = licenseManager,
                        openDisplay = { runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } },
                        openAccessibility = { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } },
                        launchApp = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) },
                        canDrawOverlay = { Settings.canDrawOverlays(this) },
                        requestOverlayPermission = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        },
                        startCrosshair = { style, color, size, lock, smooth, compact ->
                            val intent = Intent(this, CrosshairOverlayService::class.java).apply {
                                putExtra(CrosshairOverlayService.EXTRA_STYLE, style)
                                putExtra(CrosshairOverlayService.EXTRA_COLOR, color)
                                putExtra(CrosshairOverlayService.EXTRA_SIZE, size)
                                putExtra(CrosshairOverlayService.EXTRA_LOCK, lock)
                                putExtra(CrosshairOverlayService.EXTRA_SMOOTH, smooth)
                                putExtra(CrosshairOverlayService.EXTRA_COMPACT, compact)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                startForegroundService(intent)
                            else startService(intent)
                        },
                        stopCrosshair = { stopService(Intent(this, CrosshairOverlayService::class.java)) }
                    )
                }
            }
        }
    }

    private fun queryApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return list.mapNotNull { r ->
            val info = r.activityInfo ?: return@mapNotNull null
            InstalledApp(r.loadLabel(packageManager).toString(), info.packageName)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase(Locale.ROOT) }
    }
}

// ============================================================
// APP UI
// ============================================================
@Composable
private fun EliteApp(
    manufacturer: String, model: String, androidVersion: String, api: Int, refreshRate: Float,
    apps: List<InstalledApp>, store: ProfileStore, licenseManager: LicenseManager, openDisplay: () -> Unit,
    openAccessibility: () -> Unit, launchApp: (String) -> Unit,
    canDrawOverlay: () -> Boolean, requestOverlayPermission: () -> Unit,
    startCrosshair: (String, Int, Float, Boolean, Boolean, Boolean) -> Unit, stopCrosshair: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("READY • Chưa thay đổi thiết lập hệ thống.") }
    var profiles by remember { mutableStateOf(store.all()) }
    val context = LocalContext.current

    val xmlImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                val report = EliteXml.import(input)
                report.imported.forEach(store::save)
                profiles = store.all()
                status = "📥 ${report.summary}"
            }
        }.onFailure { e -> status = "❌ Import lỗi: ${e.message}" }
    }
    val xmlExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output)
                EliteXml.export(profiles, output)
            }
            status = "Đã xuất ${profiles.size} profile XML."
        }.onFailure { status = "Không thể xuất XML." }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.app_logo), null, Modifier.size(42.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("MINHDUC", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("ANDROID ELITE 4.4", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
            Surface(color = Color(0xFF103D36), shape = RoundedCornerShape(30.dp)) {
                Text("NO ROOT", color = Green, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        HorizontalDivider(color = Color(0xFF1E3041))
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> Dashboard(manufacturer, model, androidVersion, api, refreshRate, profiles.size,
                    status, openDisplay, licenseManager)
                1 -> GameManager(apps = apps, launchApp = launchApp)
                2 -> XmlManager(
                    profiles,
                    onImport = { xmlImport.launch(arrayOf("application/xml", "text/xml", "text/*")) },
                    onExport = { xmlExport.launch("MINHDUC_ELITE_4.2.xml") },
                    onDelete = { store.delete(it); profiles = store.all(); status = "Đã xóa profile." }
                )
                else -> SettingsScreen(manufacturer, model, androidVersion, api, openDisplay, openAccessibility)
            }
        }
        NavigationBar(containerColor = Color(0xFF0D1621), contentColor = Muted) {
            listOf("HOME", "GAMES", "XML", "SETTINGS").forEachIndexed { i, label ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = {
                        Icon(
                            listOf(
                                Icons.Default.Home,
                                Icons.Default.SportsEsports,
                                Icons.Default.Code,
                                Icons.Default.Settings
                            )[i], null
                        )
                    },
                    label = { Text(label, fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Cyan, selectedTextColor = Cyan,
                        indicatorColor = Color(0xFF173748),
                        unselectedIconColor = Muted, unselectedTextColor = Muted
                    )
                )
            }
        }
    }
}

// ============================================================
// SHARED COMPONENTS
// ============================================================
@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        content = content
    )
}

@Composable
private fun Heading(kicker: String, title: String, subtitle: String) {
    Text(kicker, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
    Text(subtitle, color = Muted, fontSize = 12.sp)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Section(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(Modifier.weight(1f), color = Color(0xFF263A4D))
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun InfoCard(title: String, value: String, icon: ImageVector) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Cyan, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Muted, fontSize = 11.sp)
                Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ============================================================
// DASHBOARD
// ============================================================
@Composable
private fun Dashboard(
    maker: String, model: String, android: String, api: Int, hz: Float, count: Int,
    status: String, openDisplay: () -> Unit, licenseManager: LicenseManager
) {
    ScreenColumn {
        Heading("MULTI-DEVICE CONTROL", "Command Center",
            "Profile manager • XML tools • device information")
        Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("SYSTEM STATUS", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(Green, RoundedCornerShape(50)))
                    Spacer(Modifier.width(8.dp))
                    Text("READY", color = Green, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Spacer(Modifier.weight(1f))
                    Text("ELITE 4.4", color = Cyan, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(status, color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Section("DEVICE INFORMATION")
        InfoCard("Device name", "$maker $model", Icons.Default.PhoneAndroid)
        InfoCard("Device ID", licenseManager.deviceIdentifier(), Icons.Default.Fingerprint)
        InfoCard("Android version", "$android • API $api", Icons.Default.Android)
        InfoCard("Display Refresh Rate", "${"%.1f".format(hz)} Hz", Icons.Default.Monitor)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = openDisplay,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Panel2)
        ) {
            Icon(Icons.Default.DisplaySettings, null)
            Spacer(Modifier.width(8.dp))
            Text("OPEN DISPLAY SETTINGS")
        }
    }
}

// ============================================================
// GAME MANAGER
// ============================================================
@Composable
private fun GameManager(apps: List<InstalledApp>, launchApp: (String) -> Unit) {
    var target by remember { mutableStateOf<String?>(null) }
    val thPackage = "com.dts.freefireth"
    val vnPackage = "com.garena.ffvn"
    val thInstalled = apps.any { it.pkg == thPackage }
    val vnInstalled = apps.any { it.pkg == vnPackage }

    ScreenColumn {
        if (target == null) {
            Heading("GAME ASSET INSTALLER", "Chọn phiên bản game",
                "Mỗi phiên bản chỉ có một chức năng: ÁP DỤNG FILE")
            GameTargetCard("FREE FIRE TH", thPackage, thInstalled, false, Cyan,
                onClick = { target = thPackage })
            Spacer(Modifier.height(12.dp))
            GameTargetCard("FREE FIRE VN", vnPackage, vnInstalled, false, Color(0xFFFF3F72),
                onClick = { target = vnPackage })
        } else {
            val pkg = target!!
            val label = if (pkg == thPackage) "FREE FIRE TH" else "FREE FIRE VN"
            val accent = if (pkg == thPackage) Cyan else Color(0xFFFF3F72)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { target = null }) {
                    Icon(Icons.Default.ArrowBack, null)
                    Text("GAMES")
                }
                Spacer(Modifier.weight(1f))
                Text(label, color = accent, fontWeight = FontWeight.Black)
            }
            Heading("ONE-TAP FILE INSTALLER", label, "Áp dụng bộ file đã đóng gói vào dữ liệu game")

            GameAssetInstallerPanel(
                packageName = pkg,
                gameLabel = label,
                accent = accent,
                isGameInstalled = apps.any { it.pkg == pkg },
                onLaunchGame = { launchApp(pkg) }
            )
        }
    }
}

@Composable
private fun GameTargetCard(
    title: String, packageName: String, installed: Boolean,
    selected: Boolean, accent: Color, onClick: () -> Unit
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            accent.copy(alpha = if (selected) 1f else .45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent.copy(alpha = .14f), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(54.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ff_avatar), contentDescription = title,
                            modifier = Modifier.size(46.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text(packageName, color = Cyan, fontSize = 11.sp)
                    Text(if (installed) "● ĐÃ CÀI ĐẶT  •  SẴN SÀNG" else "○ CHƯA CÀI ĐẶT",
                        color = if (installed) Green else Orange, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold)
                }
                Icon(Icons.Default.ChevronRight, null, tint = accent, modifier = Modifier.size(30.dp))
            }
        }
    }
}

// ============================================================
// GAME ASSET INSTALLER PANEL
// ============================================================
@Composable
fun GameAssetInstallerPanel(
    packageName: String,
    gameLabel: String,
    accent: Color,
    isGameInstalled: Boolean,
    onLaunchGame: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val localAssets = remember { FileInstaller.getLocalAssets(context) }
    var statusMsg by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }

    // Trạng thái Shizuku
    var shizukuReady by remember { mutableStateOf(false) }

    fun checkShizuku() {
        try {
            shizukuReady = Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            shizukuReady = false
        }
    }

    LaunchedEffect(Unit) { checkShizuku() }

    val requestShizukuPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            shizukuReady = true
            statusMsg = "✅ Đã kết nối Shizuku!"
        } else {
            statusMsg = "❌ Bạn đã từ chối cấp quyền Shizuku."
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .background(Panel, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("GAME DATA LOCATION", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(gameLabel, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)

        Text(
            "Thư mục đích:\n${GameDataPaths.targetBundlePath(packageName)}",
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp
        )

        HorizontalDivider(color = Color(0xFF263A4D), modifier = Modifier.padding(vertical = 4.dp))

        Text("Phát hiện ${localAssets.size} file trong App:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (localAssets.isEmpty()) {
            Text("Không tìm thấy file nào.", color = Color(0xFFFF7777), fontSize = 11.sp)
        } else {
            localAssets.forEach { fileName ->
                Text("• $fileName", color = accent, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        // NÚT VÀO GAME
        if (isGameInstalled) {
            Button(
                onClick = onLaunchGame,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF06111A))
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("VÀO GAME", fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text("CHƯA CÀI ĐẶT GAME")
            }
        }

        // NÚT KẾT NỐI SHIZUKU
        if (!shizukuReady) {
            Button(
                onClick = {
                    try {
                        if (!Shizuku.pingBinder()) {
                            statusMsg = "❌ Shizuku chưa chạy. Hãy mở app Shizuku trước."
                        } else {
                            requestShizukuPermission.launch("moe.shizuku.privileged.api")
                        }
                    } catch (e: Exception) {
                        statusMsg = "❌ Lỗi Shizuku: ${e.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Security, null)
                Spacer(Modifier.width(8.dp))
                Text("KẾT NỐI SHIZUKU", fontWeight = FontWeight.Bold)
            }
        } else {
            Surface(
                color = Color(0xFF103D36),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shizuku đã kết nối", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ===== NÚT ÁP DỤNG FILE =====
        Button(
            onClick = {
                isInstalling = true
                statusMsg = "⏳ Đang áp dụng file vào dữ liệu game..."
                scope.launch {
                    val result = FileInstaller.applyFilesWithShizuku(context, packageName)
                    statusMsg = result
                    isInstalling = false
                }
            },
            enabled = !isInstalling && localAssets.isNotEmpty() && shizukuReady,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF06111A))
        ) {
            Icon(Icons.Default.InstallMobile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isInstalling) "ĐANG ÁP DỤNG..." else "ÁP DỤNG FILE", fontWeight = FontWeight.Bold)
        }

        if (statusMsg.isNotEmpty()) {
            Text(
                text = statusMsg,
                color = if (statusMsg.startsWith("✅")) Green else Orange,
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Surface(
            color = Color(0xFF1C2A38),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(
                "💡 HƯỚNG DẪN:\n" +
                "1. Đảm bảo app Shizuku đang chạy.\n" +
                "2. Nhấn 'KẾT NỐI SHIZUKU' và cấp quyền.\n" +
                "3. Nhấn ÁP DỤNG FILE để copy bộ file vào dữ liệu game.\n" +
                "4. Mở game để áp dụng thay đổi.",
                color = Muted, fontSize = 10.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

// ============================================================
// XML MANAGER
// ============================================================
@Composable
private fun XmlManager(
    profiles: List<GameProfile>, onImport: () -> Unit, onExport: () -> Unit, onDelete: (String) -> Unit
) {
    ScreenColumn {
        Heading("CONFIGURATION FILES", "XML Manager", "Import and apply supported MINHDUC XML profiles")
        InfoCard("Profiles stored", profiles.size.toString(), Icons.Default.FolderOpen)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp))
            Text("CHỌN & NẠP FILE XML")
        }
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp))
            Text("EXPORT XML")
        }
        Spacer(Modifier.height(18.dp))
        Section("SAVED PROFILES")
        if (profiles.isEmpty()) Text("No profiles yet.", color = Muted, fontSize = 12.sp)
        profiles.forEach { p ->
            Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.appName, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(p.packageName, color = Muted, fontSize = 10.sp)
                    }
                    IconButton(onClick = { onDelete(p.packageName) }) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFFF647C))
                    }
                }
            }
        }
    }
}

// ============================================================
// SETTINGS
// ============================================================
@Composable
private fun SettingsScreen(
    maker: String, model: String, android: String, api: Int,
    openDisplay: () -> Unit, openAccessibility: () -> Unit
) {
    val context = LocalContext.current
    val zaloUrl = "https://zalo.me/0358648824"
    val facebookUrl = "https://www.facebook.com/share/1A8hF8BzF4/?mibextid=wwXIfr"

    fun openLink(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    ScreenColumn {
        Heading("APPLICATION PREFERENCES", "Settings", "Gói dịch vụ ROOT • liên hệ tư vấn • thông tin thiết bị")

        Section("LIÊN HỆ HỖ TRỢ")
        Button(
            onClick = { openLink(zaloUrl) }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FE8))
        ) {
            Icon(Icons.Default.Chat, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("LIÊN HỆ ZALO • 0358648824")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { openLink(facebookUrl) }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.6f))
        ) {
            Icon(Icons.Default.Public, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("LIÊN HỆ FACEBOOK")
        }

        Spacer(Modifier.height(16.dp))
        Section("THÔNG TIN THIẾT BỊ")
        InfoCard("Device", "$maker $model", Icons.Default.PhoneAndroid)
        InfoCard("Android", "$android • API $api", Icons.Default.Android)

        Spacer(Modifier.height(16.dp))
        Section("SYSTEM SHORTCUTS")
        Button(onClick = openDisplay, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2)) {
            Icon(Icons.Default.DisplaySettings, null); Spacer(Modifier.width(8.dp)); Text("OPEN DISPLAY SETTINGS")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = openAccessibility, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2)) {
            Icon(Icons.Default.Accessibility, null); Spacer(Modifier.width(8.dp)); Text("OPEN ACCESSIBILITY SETTINGS")
        }
    }
}

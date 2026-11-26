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
import androidx.compose.foundation.Canvas
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
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.InputStream
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

data class InstalledApp(val label: String, val pkg: String)

/** Đường dẫn dữ liệu của các game được hỗ trợ */
object GameDataPaths {
    const val FREE_FIRE_TH = "com.dts.freefireth"
    const val FREE_FIRE_VN = "com.garena.ffvn"

    fun isSupported(packageName: String): Boolean =
        packageName == FREE_FIRE_TH || packageName == FREE_FIRE_VN

    fun gameFilesPath(packageName: String): String {
        require(isSupported(packageName)) { "Package game không được hỗ trợ: $packageName" }
        return "/Android/data/$packageName/files/"
    }

    fun contentCachePath(packageName: String): String =
        gameFilesPath(packageName) + "contentcache/"
}

// ============================================================
// FILE INSTALLER LOGIC
// ============================================================
object FileInstaller {
    // Đường dẫn chuẩn xác dựa trên hình ảnh bạn cung cấp
    const val ASSETS_BUNDLES_DIR = "game_payload/file/contentcache/Optional/android/gameassetbundles"

    fun getLocalAssets(context: Context): List<String> {
        return try {
            context.assets.list(ASSETS_BUNDLES_DIR)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun applyFilesWithSAF(context: Context, targetFolderUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val targetDir = DocumentFile.fromTreeUri(context, targetFolderUri)
                ?: return@withContext "❌ Không thể mở thư mục đích."

            if (!targetDir.canWrite()) {
                return@withContext "❌ Không có quyền ghi vào thư mục này. Hãy cấp quyền lại."
            }

            val filesToCopy = getLocalAssets(context)
            if (filesToCopy.isEmpty()) {
                return@withContext "❌ Không tìm thấy file nào trong assets."
            }

            var successCount = 0
            var errorCount = 0

            for (fileName in filesToCopy) {
                try {
                    val assetPath = "$ASSETS_BUNDLES_DIR/$fileName"
                    val inputStream: InputStream = context.assets.open(assetPath)

                    // Xóa file cũ nếu tồn tại để ghi đè
                    targetDir.findFile(fileName)?.delete()

                    // Tạo file mới
                    val newFile = targetDir.createFile("application/octet-stream", fileName)
                    if (newFile != null) {
                        context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        successCount++
                    } else {
                        errorCount++
                    }
                    inputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorCount++
                }
            }

            if (errorCount == 0) "✅ Đã áp dụng thành công $successCount file vào game."
            else "⚠️ Áp dụng xong: $successCount thành công, $errorCount lỗi."

        } catch (e: Exception) {
            "❌ Lỗi hệ thống: ${e.message}"
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
    var selected by remember(apps) {
        mutableStateOf(apps.firstOrNull { it.pkg == "com.dts.freefireth" }
            ?: apps.firstOrNull { it.pkg == "com.garena.ffvn" })
    }
    var preset by remember { mutableStateOf("BALANCED") }
    var sensitivity by remember { mutableFloatStateOf(.85f) }
    var smoothness by remember { mutableFloatStateOf(.90f) }
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
                status = "📥 ${report.summary}" +
                    if (report.skipped.isNotEmpty())
                        "\n⚠️ ${report.skipped.take(2).joinToString("; ")}" +
                            (if (report.skipped.size > 2) "…" else "")
                    else ""
            }
        }.onFailure { e ->
            status = "❌ Import lỗi: ${e.message ?: "XML không hợp lệ"}"
        }
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
        Section("LICENSE INFORMATION")
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("KEY STATUS", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("ACTIVE", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                InfoCard("License KEY", licenseManager.maskedKey(), Icons.Default.VpnKey)
                InfoCard("Expiration",
                    licenseManager.savedExpiration() ?: "Không giới hạn / chưa có dữ liệu",
                    Icons.Default.Event)
                InfoCard("Verification", licenseManager.lastMessage(), Icons.Default.VerifiedUser)
            }
        }
        Spacer(Modifier.height(16.dp))
        Section("DEVICE INFORMATION")
        InfoCard("Device name", "$maker $model", Icons.Default.PhoneAndroid)
        InfoCard("Device ID", licenseManager.deviceIdentifier(), Icons.Default.Fingerprint)
        InfoCard("Android version", "$android • API $api", Icons.Default.Android)
        InfoCard("Manufacturer", maker, Icons.Default.Business)
        Spacer(Modifier.height(16.dp))
        Section("DEVICE ANALYZER")
        InfoCard("Manufacturer / Model", "$maker • $model", Icons.Default.PhoneAndroid)
        Spacer(Modifier.height(8.dp))
        InfoCard("Android Version", "$android • API $api", Icons.Default.Android)
        Spacer(Modifier.height(8.dp))
        InfoCard("Display Refresh Rate", "${"%.1f".format(hz)} Hz (reported by system)", Icons.Default.Monitor)
        Spacer(Modifier.height(16.dp))
        Section("LOCAL PROFILE LIBRARY")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("SAVED PROFILES", count.toString(), Modifier.weight(1f))
            MetricCard("ROOT REQUIRED", "NO", Modifier.weight(1f))
        }
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
        Text("Profile values are stored locally. They do not modify Free Fire or another app's internal behavior.",
            color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Muted, fontSize = 10.sp)
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
            Spacer(Modifier.height(12.dp))
            Text(
                "Hãy đặt file của bạn trong thư mục game_payload theo hướng dẫn trong mã nguồn, " +
                    "sau đó build APK và nhấn ÁP DỤNG FILE.",
                color = Muted, fontSize = 11.sp
            )
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
            if (apps.any { it.pkg == pkg }) {
                Button(
                    onClick = { launchApp(pkg) },
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
            Spacer(Modifier.height(10.dp))
            GameAssetInstallerPanel(packageName = pkg, gameLabel = label, accent = accent)
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
                    color = if (installed) Green else Color(0xFFFFB45E), fontSize = 9.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("MỞ BẢNG ĐIỀU KHIỂN  →", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
fun GameAssetInstallerPanel(packageName: String, gameLabel: String, accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Lấy danh sách file từ assets
    val localAssets = remember { FileInstaller.getLocalAssets(context) }
    
    var statusMsg by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }

    // Trình chọn thư mục (SAF)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            isInstalling = true
            statusMsg = "⏳ Đang áp dụng file..."
            scope.launch {
                val result = FileInstaller.applyFilesWithSAF(context, uri)
                statusMsg = result
                isInstalling = false
            }
        } else {
            statusMsg = "⚠️ Bạn chưa chọn thư mục."
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
            "Thư mục đích: /Android/data/$packageName/files/contentcache/Optional/android/gameassetbundles/",
            color = Muted,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )

        HorizontalDivider(color = Color(0xFF263A4D), modifier = Modifier.padding(vertical = 4.dp))

        Text(
            "Phát hiện ${localAssets.size} file trong App:",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
        if (localAssets.isEmpty()) {
            Text("Không tìm thấy file nào.", color = Color(0xFFFF7777), fontSize = 11.sp)
        } else {
            localAssets.forEach { fileName ->
                Text(
                    "• $fileName",
                    color = accent, fontSize = 10.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // NÚT ÁP DỤNG
        Button(
            onClick = {
                // Mở thẳng vào thư mục gốc của bộ nhớ trong để dễ tìm
                val initialUri = Uri.parse("content://com.android.externalstorage.documents/root/primary")
                folderPickerLauncher.launch(initialUri)
            },
            enabled = !isInstalling && localAssets.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF06111A))
        ) {
            Icon(Icons.Default.InstallMobile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isInstalling) "ĐANG XỬ LÝ..." else "ÁP DỤNG FILE VÀO GAME", fontWeight = FontWeight.Bold)
        }

        if (statusMsg.isNotEmpty()) {
            Text(
                text = statusMsg,
                color = if (statusMsg.startsWith("✅")) Green else Color(0xFFFFB45E),
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
                "1. Nhấn nút Áp Dụng.\n" +
                "2. Bấm dấu 3 chấm (⋮) chọn 'Hiển thị bộ nhớ trong' (nếu không thấy Android).\n" +
                "3. Tìm đến: Android/data/$packageName/files/contentcache/Optional/android/gameassetbundles/\n" +
                "4. Nhấn 'Sử dụng thư mục này' để hoàn tất.",
                color = Muted,
                fontSize = 10.sp,
                lineHeight = 15.sp,
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
        Heading("CONFIGURATION FILES", "XML Manager",
            "Import and apply supported MINHDUC XML profiles")
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
        Text(
            "Hỗ trợ XML profile MINHDUC: package, name, preset, sensitivity, smoothness. " +
                "Chấp nhận nhiều root tag (<MINHDUC_ENGINE>, <APP>, <PROFILES>…). " +
                "Entry lỗi sẽ bị bỏ qua thay vì fail cả file.",
            color = Muted, fontSize = 11.sp
        )
        Spacer(Modifier.height(18.dp))
        Section("SAVED PROFILES")
        if (profiles.isEmpty())
            Text("No profiles yet. Create one in Game Manager or import an XML file.",
                color = Muted, fontSize = 12.sp)
        profiles.forEach { p ->
            Surface(
                color = Panel, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.appName, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(p.packageName, color = Muted, fontSize = 10.sp)
                        Text(
                            "${p.preset} • Sens ${(p.sensitivity * 100).toInt()} • Smooth ${(p.smoothness * 100).toInt()}",
                            color = Cyan, fontSize = 10.sp
                        )
                    }
                    IconButton(onClick = { onDelete(p.packageName) }) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFFF647C))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "XML chỉ là dữ liệu — parser không thực thi bất kỳ lệnh nào, có chống XXE. " +
                "File XML lạ không thể chạy mã trong app hoặc thay đổi dữ liệu bên trong Free Fire.",
            color = Muted, fontSize = 11.sp
        )
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
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    ScreenColumn {
        Heading(
            "APPLICATION PREFERENCES", "Settings",
            "Gói dịch vụ ROOT • liên hệ tư vấn • thông tin thiết bị"
        )

        Section("DANH SÁCH GÓI DỊCH VỤ")
        Text(
            "Lựa chọn gói phù hợp với nhu cầu của bạn. Nhấn nút đăng ký để liên hệ và được tư vấn chi tiết trước khi sử dụng.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(12.dp))

        RootPackageCard(
            name = "ROOT CƠ BẢN", price = "150.000đ",
            description = "Gói ROOT cơ bản dành cho nhu cầu sử dụng thông thường.",
            accent = Cyan, onRegister = { openLink(zaloUrl) }
        )
        Spacer(Modifier.height(10.dp))
        RootPackageCard(
            name = "CHỨNG CHỈ ROOT", price = "250.000đ",
            description = "Gói chứng chỉ ROOT. Liên hệ để được tư vấn thiết bị và điều kiện tương thích.",
            accent = Color(0xFF9C8CFF), onRegister = { openLink(zaloUrl) }
        )
        Spacer(Modifier.height(10.dp))
        RootPackageCard(
            name = "ROOT VIP ANDROID", price = "450.000đ",
            description = "Gói VIP Android dành cho khách hàng có nhu cầu nâng cấp trải nghiệm.",
            accent = Color(0xFFFFC36A), onRegister = { openLink(zaloUrl) },
            featured = true
        )

        Spacer(Modifier.height(16.dp))
        Surface(
            color = Panel2, shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("MUỐN NÂNG CẤP VIP HƠN?", color = Cyan,
                    fontSize = 14.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(5.dp))
                Text(
                    "Nếu bạn cần gói cao hơn hoặc muốn hỏi về thiết bị, hãy nhắn trực tiếp để được tư vấn riêng.",
                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))
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
            }
        }

        Spacer(Modifier.height(16.dp))
        Section("THÔNG TIN THIẾT BỊ")
        InfoCard("Device", "$maker $model", Icons.Default.PhoneAndroid)
        Spacer(Modifier.height(8.dp))
        InfoCard("Android", "$android • API $api", Icons.Default.Android)

        Spacer(Modifier.height(16.dp))
        Section("SYSTEM SHORTCUTS")
        Button(
            onClick = openDisplay, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Panel2)
        ) {
            Icon(Icons.Default.DisplaySettings, null)
            Spacer(Modifier.width(8.dp))
            Text("OPEN DISPLAY SETTINGS")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = openAccessibility, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Panel2)
        ) {
            Icon(Icons.Default.Accessibility, null)
            Spacer(Modifier.width(8.dp))
            Text("OPEN ACCESSIBILITY SETTINGS")
        }

        Spacer(Modifier.height(16.dp))
        Section("ABOUT")
        Text(
            "MINHDUC ANDROID ELITE 4.4 — công cụ quản lý profile, XML và overlay tâm ảo. " +
                "Các gói dịch vụ được giới thiệu ở trên cần được tư vấn về khả năng tương thích " +
                "với từng thiết bị trước khi đăng ký.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        InfoCard("Version", "4.2 • Elite Build", Icons.Default.Info)
        Spacer(Modifier.height(8.dp))
        InfoCard("Terms", "Use at your own risk", Icons.Default.Warning)
    }
}

@Composable
private fun RootPackageCard(
    name: String,
    price: String,
    description: String,
    accent: Color,
    onRegister: () -> Unit,
    featured: Boolean = false
) {
    Surface(
        color = Panel, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            if (featured) 1.8.dp else 1.dp,
            accent.copy(alpha = if (featured) 0.95f else 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    Text(description, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(price, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRegister, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Icon(Icons.Default.Chat, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("ĐĂNG KÝ / TƯ VẤN", color = Color(0xFF07111A))
            }
        }
    }
}

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
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private val Amber = Color(0xFFFFC36A)
private val Violet = Color(0xFF9C8CFF)
private val Pink = Color(0xFFFF3F72)
private val Danger = Color(0xFFFF647C)

data class InstalledApp(val label: String, val pkg: String)

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
                    RobotApp(
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
// MODULE DEFINITIONS
// ============================================================
private data class ModuleInfo(
    val code: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color
)

private val MODULES = listOf(
    ModuleInfo("M01", "COMMAND", "Tổng quan", Icons.Default.Dashboard, Cyan),
    ModuleInfo("M02", "GAMES", "Chạy game", Icons.Default.SportsEsports, Green),
    ModuleInfo("M03", "XML", "Nạp profile", Icons.Default.Code, Violet),
    ModuleInfo("M04", "SERVICE", "Gói & hỗ trợ", Icons.Default.Build, Amber)
)

// ============================================================
// ROOT APP
// ============================================================
@Composable
private fun RobotApp(
    manufacturer: String, model: String, androidVersion: String, api: Int, refreshRate: Float,
    apps: List<InstalledApp>, store: ProfileStore, licenseManager: LicenseManager,
    openDisplay: () -> Unit, openAccessibility: () -> Unit, launchApp: (String) -> Unit,
    canDrawOverlay: () -> Boolean, requestOverlayPermission: () -> Unit,
    startCrosshair: (String, Int, Float, Boolean, Boolean, Boolean) -> Unit, stopCrosshair: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Robot đã khởi động • chưa thay đổi hệ thống") }
    var isBusy by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(store.all()) }
    val context = LocalContext.current

    val xmlImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isBusy = true
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                val report = EliteXml.import(input)
                report.imported.forEach(store::save)
                profiles = store.all()
                status = "✅ Đã nạp ${report.imported.size} profile" +
                    if (report.skipped.isNotEmpty()) " • bỏ qua ${report.skipped.size}" else ""
            }
        }.onFailure { status = "❌ Lỗi nạp XML: ${it.message}" }
        isBusy = false
    }

    val xmlExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isBusy = true
        runCatching {
            context.contentResolver.openOutputStream(uri).use {
                requireNotNull(it); EliteXml.export(profiles, it)
            }
            status = "✅ Đã xuất ${profiles.size} profile ra XML"
        }.onFailure { status = "❌ Không thể xuất XML" }
        isBusy = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {

        // ── Brand bar gọn ──────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.app_logo), null, Modifier.size(38.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "MINHDUC ROBOT", color = Cyan, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                Text(
                    "ELITE 4.4 • FUNCTIONAL", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Black
                )
            }
            Surface(color = Color(0xFF103D36), shape = RoundedCornerShape(30.dp)) {
                Text(
                    "NO ROOT", color = Green, fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Robot status bar ───────────────────────────
        RobotHeader(isBusy, status) { status = "Đã làm mới trạng thái" }

        // ── Module selector ────────────────────────────
        ModuleSelector(tab) { tab = it }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = Color(0xFF1E3041))

        // ── Nội dung module ────────────────────────────
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> CommandCenter(
                    manufacturer, model, androidVersion, api, refreshRate,
                    profiles.size, licenseManager, openDisplay
                )
                1 -> GameLauncher(apps, launchApp) { status = it }
                2 -> XmlInstaller(
                    profiles,
                    onImport = { xmlImport.launch(arrayOf("application/xml", "text/xml", "text/*")) },
                    onExport = { xmlExport.launch("MINHDUC_ELITE_4.4.xml") },
                    onDelete = { store.delete(it); profiles = store.all(); status = "Đã xóa profile $it" }
                )
                else -> ServiceDesk(
                    manufacturer, model, androidVersion, api,
                    openDisplay, openAccessibility,
                    canDrawOverlay, requestOverlayPermission,
                    startCrosshair, stopCrosshair
                ) { status = it }
            }
        }
    }
}

// ============================================================
// HEADER / NAVIGATION
// ============================================================
@Composable
private fun RobotHeader(isBusy: Boolean, statusText: String, onRefresh: () -> Unit) {
    Surface(
        color = Panel, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val ledColor by animateColorAsState(
                if (isBusy) Amber else Green, label = "led"
            )
            Box(Modifier.size(10.dp).background(ledColor, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isBusy) "ROBOT ĐANG XỬ LÝ…" else "ROBOT SẴN SÀNG",
                    color = ledColor, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(statusText, color = Muted, fontSize = 10.sp, maxLines = 2)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Làm mới", tint = Cyan)
            }
        }
    }
}

@Composable
private fun ModuleSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MODULES.forEachIndexed { i, m ->
            val active = selected == i
            Surface(
                color = if (active) m.accent.copy(alpha = 0.18f) else Panel,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (active) m.accent else Color(0xFF263A4D)),
                modifier = Modifier.weight(1f).clickable { onSelect(i) }
            ) {
                Column(
                    Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        m.icon, null,
                        tint = if (active) m.accent else Muted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        m.code, color = if (active) m.accent else Muted,
                        fontSize = 9.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        m.title, color = if (active) Color.White else Muted,
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                    )
                }
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
    Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
    Text(subtitle, color = Muted, fontSize = 12.sp)
    Spacer(Modifier.height(16.dp))
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
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Muted, fontSize = 11.sp)
                Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = Muted, fontSize = 10.sp, letterSpacing = 0.5.sp)
            Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        color = if (enabled) Panel else Panel.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.7f else 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.clickable(enabled = enabled, onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = accent.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = if (enabled) Color.White else Muted,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Text(description, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Icon(
                Icons.Default.ChevronRight, null,
                tint = if (enabled) accent else Muted.copy(alpha = 0.3f)
            )
        }
    }
}

// ============================================================
// MODULE M01 — COMMAND CENTER
// ============================================================
@Composable
private fun CommandCenter(
    maker: String, model: String, android: String, api: Int, hz: Float,
    profileCount: Int, licenseManager: LicenseManager, openDisplay: () -> Unit
) {
    ScreenColumn {
        Heading(
            "M01 • COMMAND CENTER", "Tổng quan Robot",
            "Trạng thái thiết bị, license và profile đã lưu"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("PROFILE", profileCount.toString(), Modifier.weight(1f))
            MetricCard("REFRESH", "${"%.1f".format(hz)}Hz", Modifier.weight(1f))
            MetricCard("ROOT", "NO", Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Section("TRẠNG THÁI THIẾT BỊ")
        InfoCard("Thiết bị", "$maker $model", Icons.Default.PhoneAndroid)
        Spacer(Modifier.height(8.dp))
        InfoCard("Android", "$android • API $api", Icons.Default.Android)
        Spacer(Modifier.height(8.dp))
        InfoCard("Tần số quét", "${"%.1f".format(hz)} Hz (hệ thống báo cáo)", Icons.Default.Monitor)

        Spacer(Modifier.height(16.dp))
        Section("BẢO MẬT & LICENSE")
        InfoCard("License", licenseManager.maskedKey(), Icons.Default.VpnKey)
        Spacer(Modifier.height(8.dp))
        InfoCard(
            "Hạn dùng",
            licenseManager.savedExpiration() ?: "Chưa có dữ liệu",
            Icons.Default.Event
        )
        Spacer(Modifier.height(8.dp))
        InfoCard("Xác thực", licenseManager.lastMessage(), Icons.Default.VerifiedUser)
        Spacer(Modifier.height(8.dp))
        InfoCard("Device ID", licenseManager.deviceIdentifier(), Icons.Default.Fingerprint)

        Spacer(Modifier.height(16.dp))
        Section("HÀNH ĐỘNG NHANH")
        ActionCard(
            Icons.Default.DisplaySettings, "Mở cài đặt hiển thị",
            "Điều chỉnh tần số quét & độ phân giải trong Settings hệ thống",
            Cyan, onClick = openDisplay
        )

        Spacer(Modifier.height(14.dp))
        Text(
            "Profile được lưu cục bộ. Không can thiệp dữ liệu bên trong Free Fire " +
            "hoặc bất kỳ ứng dụng khác.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

// ============================================================
// MODULE M02 — GAME LAUNCHER
// ============================================================
@Composable
private fun GameLauncher(
    apps: List<InstalledApp>,
    launchApp: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    val thPkg = "com.dts.freefireth"
    val vnPkg = "com.garena.ffvn"

    ScreenColumn {
        Heading(
            "M02 • GAME LAUNCHER", "Chạy game",
            "Chọn phiên bản game đã cài và mở nhanh"
        )

        GameTargetCard(
            "FREE FIRE TH", thPkg,
            apps.any { it.pkg == thPkg }, false, Cyan,
            onClick = { onStatus("Đã chọn Free Fire TH") },
            onLaunch = { launchApp(thPkg); onStatus("Đang mở Free Fire TH…") }
        )

        Spacer(Modifier.height(12.dp))

        GameTargetCard(
            "FREE FIRE VN", vnPkg,
            apps.any { it.pkg == vnPkg }, false, Pink,
            onClick = { onStatus("Đã chọn Free Fire VN") },
            onLaunch = { launchApp(vnPkg); onStatus("Đang mở Free Fire VN…") }
        )

        Spacer(Modifier.height(14.dp))
        Text(
            "Module này chỉ mở game đã cài. Không can thiệp file game, không root, " +
            "không thay đổi dữ liệu Free Fire.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

@Composable
private fun GameTargetCard(
    title: String, packageName: String, installed: Boolean,
    selected: Boolean, accent: Color, onClick: () -> Unit, onLaunch: () -> Unit
) {
    Surface(
        color = Panel, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            accent.copy(alpha = if (selected) 1f else .45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = accent.copy(alpha = .14f), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painterResource(R.drawable.ff_avatar), contentDescription = title,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(packageName, color = Cyan, fontSize = 10.sp)
                    Text(
                        if (installed) "● ĐÃ CÀI ĐẶT • SẴN SÀNG" else "○ CHƯA CÀI ĐẶT",
                        color = if (installed) Green else Amber, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Button(
                onClick = onLaunch, enabled = installed,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent, contentColor = Color(0xFF06111A)
                )
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (installed) "VÀO GAME" else "CHƯA CÀI ĐẶT GAME",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ============================================================
// MODULE M03 — XML INSTALLER
// ============================================================
@Composable
private fun XmlInstaller(
    profiles: List<GameProfile>,
    onImport: () -> Unit, onExport: () -> Unit, onDelete: (String) -> Unit
) {
    ScreenColumn {
        Heading(
            "M03 • XML INSTALLER", "Nạp / xuất Profile",
            "Quản lý file cấu hình XML MINHDUC"
        )

        ActionCard(
            Icons.Default.FileOpen, "NẠP FILE XML",
            "Chọn file .xml từ máy, robot sẽ đọc & lưu profile",
            Violet, onClick = onImport
        )
        Spacer(Modifier.height(10.dp))
        ActionCard(
            Icons.Default.FileDownload, "XUẤT PROFILE XML",
            "Ghi toàn bộ profile đã lưu ra file XML",
            Cyan, onClick = onExport
        )

        Spacer(Modifier.height(16.dp))
        Section("DANH SÁCH PROFILE (${profiles.size})")

        if (profiles.isEmpty()) {
            Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Chưa có profile nào. Hãy nạp file XML để bắt đầu.",
                    color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(20.dp).fillMaxWidth()
                )
            }
        }

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
                            "${p.preset} • Sens ${(p.sensitivity * 100).toInt()} • " +
                                "Smooth ${(p.smoothness * 100).toInt()}",
                            color = Cyan, fontSize = 10.sp
                        )
                    }
                    IconButton(onClick = { onDelete(p.packageName) }) {
                        Icon(Icons.Default.Delete, null, tint = Danger)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "XML chỉ là dữ liệu — parser có chống XXE, không thực thi lệnh. " +
            "File XML lạ không thể chạy mã trong app hoặc thay đổi dữ liệu bên trong Free Fire.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

// ============================================================
// MODULE M04 — SERVICE DESK
// ============================================================
@Composable
private fun ServiceDesk(
    maker: String, model: String, android: String, api: Int,
    openDisplay: () -> Unit, openAccessibility: () -> Unit,
    canDrawOverlay: () -> Boolean, requestOverlayPermission: () -> Unit,
    startCrosshair: (String, Int, Float, Boolean, Boolean, Boolean) -> Unit,
    stopCrosshair: () -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val zaloUrl = "https://zalo.me/0358648824"
    val facebookUrl = "https://www.facebook.com/share/1A8hF8BzF4/?mibextid=wwXIfr"

    fun openLink(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { onStatus("❌ Không mở được link") }
    }

    ScreenColumn {
        Heading(
            "M04 • SERVICE DESK", "Gói dịch vụ & hỗ trợ",
            "Tư vấn ROOT • liên hệ • shortcuts hệ thống"
        )

        Section("GÓI DỊCH VỤ")
        Text(
            "Chọn gói phù hợp. Nhấn để liên hệ và được tư vấn chi tiết trước khi đăng ký.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(12.dp))

        RootPackageCard(
            name = "ROOT CƠ BẢN", price = "150.000đ",
            description = "Gói ROOT cơ bản cho nhu cầu thông thường.",
            accent = Cyan, onRegister = { openLink(zaloUrl) }
        )
        Spacer(Modifier.height(10.dp))
        RootPackageCard(
            name = "CHỨNG CHỈ ROOT", price = "250.000đ",
            description = "Chứng chỉ ROOT. Liên hệ để tư vấn thiết bị tương thích.",
            accent = Violet, onRegister = { openLink(zaloUrl) }
        )
        Spacer(Modifier.height(10.dp))
        RootPackageCard(
            name = "ROOT VIP ANDROID", price = "450.000đ",
            description = "Gói VIP Android cho khách hàng cần trải nghiệm cao cấp.",
            accent = Amber, onRegister = { openLink(zaloUrl) }, featured = true
        )

        Spacer(Modifier.height(16.dp))
        Surface(
            color = Panel2, shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "CẦN GÓI CAO HƠN?", color = Cyan,
                    fontSize = 14.sp, fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Nhắn trực tiếp để được tư vấn riêng cho thiết bị của bạn.",
                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { openLink(zaloUrl) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087FE8))
                ) {
                    Icon(Icons.Default.Chat, null)
                    Spacer(Modifier.width(8.dp))
                    Text("ZALO • 0358648824")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openLink(facebookUrl) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.Public, null)
                    Spacer(Modifier.width(8.dp))
                    Text("FACEBOOK")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Section("TÂM ẢO (CROSSHAIR)")
        val hasOverlay = remember { canDrawOverlay() }
        ActionCard(
            Icons.Default.CenterFocusStrong,
            if (hasOverlay) "BẬT TÂM ẢO" else "CẤP QUYỀN OVERLAY",
            if (hasOverlay) "Khởi động dịch vụ vẽ tâm ảo trên màn hình"
            else "Cần quyền vẽ trên ứng dụng khác để hiển thị tâm ảo",
            Green,
            onClick = {
                if (hasOverlay) {
                    startCrosshair("cross", AndroidColor.CYAN, 24f, false, true, false)
                    onStatus("✅ Đã bật tâm ảo")
                } else {
                    requestOverlayPermission()
                    onStatus("Vui lòng cấp quyền overlay, sau đó quay lại")
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        ActionCard(
            Icons.Default.StopCircle, "TẮT TÂM ẢO",
            "Dừng dịch vụ overlay đang chạy",
            Danger,
            onClick = { stopCrosshair(); onStatus("Đã tắt tâm ảo") }
        )

        Spacer(Modifier.height(16.dp))
        Section("SHORTCUTS HỆ THỐNG")
        ActionCard(
            Icons.Default.DisplaySettings, "Mở Display Settings",
            "Điều chỉnh hiển thị, tần số quét",
            Cyan, onClick = openDisplay
        )
        Spacer(Modifier.height(8.dp))
        ActionCard(
            Icons.Default.Accessibility, "Mở Accessibility Settings",
            "Quản lý trợ năng và dịch vụ hệ thống",
            Violet, onClick = openAccessibility
        )

        Spacer(Modifier.height(16.dp))
        Section("THÔNG TIN THIẾT BỊ")
        InfoCard("Device", "$maker $model", Icons.Default.PhoneAndroid)
        Spacer(Modifier.height(8.dp))
        InfoCard("Android", "$android • API $api", Icons.Default.Android)

        Spacer(Modifier.height(16.dp))
        Section("VỀ ỨNG DỤNG")
        Text(
            "MINHDUC ANDROID ELITE 4.4 — robot quản lý profile, XML và overlay tâm ảo. " +
            "Các gói dịch vụ cần được tư vấn tương thích theo từng thiết bị trước khi đăng ký.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        InfoCard("Version", "4.4 • Elite Build", Icons.Default.Info)
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
                Icon(Icons.Default.Chat, null)
                Spacer(Modifier.width(7.dp))
                Text("ĐĂNG KÝ / TƯ VẤN", color = Color(0xFF07111A))
            }
        }
    }
}

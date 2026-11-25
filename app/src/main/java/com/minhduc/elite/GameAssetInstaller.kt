package com.minhduc.elite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AssetPanel = Color(0xFF111B27)
private val AssetMuted = Color(0xFF9AAABD)

/** Supported game package IDs and their Android/data locations. */
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

/** Read-only panel: displays the correctly resolved game package and data path. */
@Composable
fun GameAssetInstallerPanel(packageName: String, gameLabel: String, accent: Color) {
    val supported = GameDataPaths.isSupported(packageName)
    Column(
        Modifier.fillMaxWidth()
            .background(AssetPanel, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("GAME DATA LOCATION", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(gameLabel, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(
            if (supported) "Đã nhận diện Package ID: $packageName"
            else "Package ID không được hỗ trợ: $packageName",
            color = if (supported) accent else Color(0xFFFF7777),
            fontSize = 12.sp
        )
        Text(
            if (supported) "Thư mục dữ liệu: ${GameDataPaths.gameFilesPath(packageName)}"
            else "Hãy chọn Free Fire TH hoặc Free Fire VN.",
            color = AssetMuted,
            fontSize = 11.sp
        )
        if (supported) {
            Text(
                "Content cache: ${GameDataPaths.contentCachePath(packageName)}",
                color = AssetMuted,
                fontSize = 10.sp
            )
        }
        Text(
            "Mục này chỉ kiểm tra và hiển thị đường dẫn; không tự chép hoặc thay thế tài nguyên trong game.",
            color = AssetMuted,
            fontSize = 10.sp
        )
    }
}

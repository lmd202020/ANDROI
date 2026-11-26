import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
// (Các import khác giữ nguyên)

@Composable
fun GameAssetInstallerPanel(packageName: String, gameLabel: String, accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Lấy danh sách 2 file từ assets
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
            .background(AssetPanel, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("GAME DATA LOCATION", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(gameLabel, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        
        // Hiển thị đường dẫn đích chính xác theo yêu cầu
        Text(
            "Thư mục đích: /Android/data/$packageName/files/contentcache/Optional/android/gameassetbundles/",
            color = AssetMuted,
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

        // Hướng dẫn quan trọng cho người dùng
        Surface(
            color = Color(0xFF1C2A38),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(
                "💡 HƯỚNG DẪN:\n" +
                "1. Nhấn nút Áp Dụng.\n" +
                "2. Chọn 'Sử dụng thư mục này' khi được hỏi.\n" +
                "3. Tìm đến: Android/data/$packageName/files/contentcache/Optional/android/gameassetbundles/\n" +
                "4. Nhấn 'Cho phép' hoặc 'Use this folder' để hoàn tất.",
                color = AssetMuted,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

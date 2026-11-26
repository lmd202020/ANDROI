package com.minhduc.elite

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object FileInstaller {
    
    // Đường dẫn CHUẨN XÁC trong thư mục assets theo hình ảnh bạn cung cấp
    const val ASSETS_BUNDLES_DIR = "game_payload/file/contentcache/Optional/android/gameassetbundles"

    // Hàm quét lấy 2 file trong assets
    fun getLocalAssets(context: Context): List<String> {
        return try {
            context.assets.list(ASSETS_BUNDLES_DIR)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Hàm copy file bằng SAF
    suspend fun applyFilesWithSAF(
        context: Context, 
        targetFolderUri: Uri
    ): String = withContext(Dispatchers.IO) {
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

                    // Xóa file cũ nếu đã tồn tại để ghi đè (tránh lỗi trùng tên)
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

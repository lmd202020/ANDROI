package com.minhduc.elite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Gate màn hình chính bằng KEY; nội dung app chỉ được mở sau khi xác thực. */
@Composable
fun LicenseGate(content: @Composable (LicenseManager) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { LicenseManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(manager.savedKey() != null) }
    var busy by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(manager.lastMessage()) }

    LaunchedEffect(manager) {
        if (manager.savedKey() != null) {
            val result = manager.verifySaved()
            active = result.ok
            message = result.message
            checking = false
        } else {
            active = false
            checking = false
        }
        while (true) {
            delay(60_000L)
            if (manager.savedKey() != null) {
                val result = manager.verifySaved()
                active = result.ok
                message = result.message
            }
        }
    }

    if (active) {
        content(manager)
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF080D14)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "MINHDUC ANDROID ELITE",
                color = Color(0xFF46DDF4),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text("LICENSE ACTIVATION", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))

            if (checking) {
                CircularProgressIndicator(color = Color(0xFF46DDF4))
                Spacer(Modifier.height(12.dp))
                Text("Đang xác thực KEY...", color = Color.LightGray)
            } else {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nhập License KEY") },
                    placeholder = { Text("Dán KEY của bạn") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (!busy) {
                            busy = true
                            message = "Đang kiểm tra KEY..."
                            scope.launch {
                                val result = manager.activate(keyInput)
                                busy = false
                                message = result.message
                                active = result.ok
                                if (result.ok) keyInput = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !busy && keyInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF168DA5))
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                    else Text("KÍCH HOẠT", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    manager.clear()
                    keyInput = ""
                    message = "Đã xóa KEY đã lưu"
                    active = false
                }) { Text("Xóa KEY đã lưu", color = Color(0xFF9AAABD)) }
            }
            Spacer(Modifier.height(18.dp))
            Text(message, color = Color(0xFFB7C4D3), fontSize = 13.sp)
        }
    }
}

package com.minhduc.elite

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

/** In-app touch diagnostics and smoothing demo. It deliberately does not intercept or alter game input. */
@Composable
fun TouchLabPanel(
    accent: Color,
    initialMonitor: Boolean,
    initialFilter: Boolean,
    initialAlpha: Float,
    initialDeadZone: Float,
    onMonitorChange: (Boolean) -> Unit,
    onFilterChange: (Boolean) -> Unit,
    onAlphaChange: (Float) -> Unit,
    onDeadZoneChange: (Float) -> Unit
) {
    var monitorOn by remember(initialMonitor) { mutableStateOf(initialMonitor) }
    var filterOn by remember(initialFilter) { mutableStateOf(initialFilter) }
    var alpha by remember(initialAlpha) { mutableFloatStateOf(initialAlpha.coerceIn(.05f, .95f)) }
    var deadZone by remember(initialDeadZone) { mutableFloatStateOf(initialDeadZone.coerceIn(0f, 20f)) }
    var eventCount by remember { mutableIntStateOf(0) }
    var eventsPerSecond by remember { mutableIntStateOf(0) }
    var windowStart by remember { mutableLongStateOf(0L) }
    var raw by remember { mutableStateOf(Offset.Zero) }
    var filtered by remember { mutableStateOf(Offset.Zero) }
    var sampleCount by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF111B27), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("TOUCH LAB · ĐO TRỰC TIẾP", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text("Số liệu được đo khi bạn chạm vào vùng thử bên dưới; không phải thông số Hz do máy công bố.", color = Color(0xFF9AAABD), fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("BÁM ĐẦU · GIÁM SÁT CHẠM", color = Color.White, fontSize = 13.sp)
                Text(if (monitorOn) "Đang đo sự kiện chạm trong Touch Lab" else "Đang tắt đo", color = Color(0xFF9AAABD), fontSize = 10.sp)
            }
            Switch(checked = monitorOn, onCheckedChange = { monitorOn = it; onMonitorChange(it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("EVENT RATE", if (monitorOn) "$eventsPerSecond/s" else "OFF", accent, Modifier.weight(1f))
            MetricTile("SAMPLES", if (monitorOn) sampleCount.toString() else "—", accent, Modifier.weight(1f))
        }
        Divider(color = Color(0xFF263748))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FIX RUNG · EMA + DEADZONE", color = Color.White, fontSize = 13.sp)
                Text(if (filterOn) "Bộ lọc đang xử lý tọa độ trong vùng thử" else "Bộ lọc đang tắt", color = Color(0xFF9AAABD), fontSize = 10.sp)
            }
            Switch(checked = filterOn, onCheckedChange = { filterOn = it; onFilterChange(it) })
        }
        Text("Độ mượt α: ${"%.2f".format(alpha)}", color = Color.White, fontSize = 11.sp)
        Slider(value = alpha, onValueChange = { alpha = it; onAlphaChange(it) }, valueRange = 0.05f..0.95f, enabled = filterOn)
        Text("Deadzone: ${deadZone.toInt()} px", color = Color.White, fontSize = 11.sp)
        Slider(value = deadZone, onValueChange = { deadZone = it; onDeadZoneChange(it) }, valueRange = 0f..20f, enabled = filterOn)
        Text("VÙNG KIỂM TRA CẢM ỨNG", color = accent, fontSize = 10.sp)
        Box(
            Modifier.fillMaxWidth().height(150.dp)
                .background(Color(0xFF07111C), RoundedCornerShape(12.dp))
                .pointerInput(monitorOn, filterOn, alpha, deadZone) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var fx = down.position.x
                        var fy = down.position.y
                        raw = down.position
                        filtered = down.position
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val p = change.position
                                raw = p
                                val dx = p.x - fx
                                val dy = p.y - fy
                                if (!filterOn || hypot(dx, dy) > deadZone) {
                                    val a = if (filterOn) alpha else 1f
                                    fx += a * dx
                                    fy += a * dy
                                }
                                filtered = Offset(fx, fy)
                                if (monitorOn) {
                                    sampleCount++
                                    val now = SystemClock.elapsedRealtime()
                                    if (windowStart == 0L) windowStart = now
                                    eventCount++
                                    if (now - windowStart >= 1000L) {
                                        eventsPerSecond = eventCount
                                        eventCount = 0
                                        windowStart = now
                                    }
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(Color(0xFF263748), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1f)
                drawLine(Color(0xFF263748), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                if (raw != Offset.Zero) {
                    drawCircle(Color(0xFFFFB454), 9.dp.toPx(), raw)
                    drawCircle(accent, 7.dp.toPx(), filtered, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    drawLine(accent.copy(alpha = .55f), raw, filtered, 2.dp.toPx())
                }
            }
            if (raw == Offset.Zero) Text("CHẠM VÀ KÉO TẠI ĐÂY", color = Color(0xFF9AAABD), modifier = Modifier.align(Alignment.Center), fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("● RAW", color = Color(0xFFFFB454), fontSize = 10.sp)
            Text("◎ FILTERED", color = accent, fontSize = 10.sp)
        }
        Text("Lưu ý: phép đo và lọc chỉ áp dụng trong vùng thử của ứng dụng, không thay đổi cảm ứng hoặc tâm ngắm bên trong Free Fire.", color = Color(0xFF9AAABD), fontSize = 10.sp)
    }
}

@Composable
fun MetricTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF07111C), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(label, color = Color(0xFF9AAABD), fontSize = 9.sp)
        Text(value, color = accent, style = MaterialTheme.typography.titleLarge)
    }
}

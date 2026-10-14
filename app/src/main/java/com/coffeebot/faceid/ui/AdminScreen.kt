package com.coffeebot.faceid.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coffeebot.faceid.App
import com.coffeebot.faceid.core.CoffeeService
import com.coffeebot.faceid.data.ControllerMode
import com.coffeebot.faceid.data.DataChanges
import com.coffeebot.faceid.data.Side
import com.coffeebot.faceid.data.SlotOrder
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val SlotOn = Color(0xFF2E7D32)
private val SlotOff = Color(0xFF424242)
private val Danger = Color(0xFFC62828)

/** Защита от подбора пароля: после 5 ошибок вход блокируется на минуту. Живёт, пока жив процесс. */
private object LoginGuard {
    var failures = 0
    var lockedUntil = 0L
}

@Composable
fun AdminLogin(app: App, onSuccess: () -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val lockedSec = ((LoginGuard.lockedUntil - now) / 1000).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Вход в админку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = "" },
                    label = { Text("Пароль") }, singleLine = true, enabled = lockedSec == 0L,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (lockedSec > 0) Text("Слишком много попыток. Подождите $lockedSec с", color = Danger)
                else if (error.isNotEmpty()) Text(error, color = Danger)
            }
        },
        confirmButton = {
            Button(enabled = lockedSec == 0L, onClick = {
                if (password == app.settings.pin) {
                    LoginGuard.failures = 0
                    onSuccess()
                } else {
                    LoginGuard.failures++
                    password = ""
                    if (LoginGuard.failures >= 5) {
                        LoginGuard.failures = 0
                        LoginGuard.lockedUntil = System.currentTimeMillis() + 60_000
                        app.events.add("admin", "блокировка входа после 5 неверных паролей")
                    } else error = "Неверный пароль"
                }
            }) { Text("Войти") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminScreen(app: App, onExit: () -> Unit, onCloseApp: () -> Unit, isDeviceOwner: Boolean) {
    val tick by DataChanges.tick.collectAsState()
    val live by app.analyzer.live.collectAsState()
    val s = app.settings
    val slots = remember(tick) { app.slots.all() }
    val recentFaces = remember(tick) { app.faces.recent(24) }
    val events = remember(tick) { app.events.recent(40) }
    val startOfDay = remember(tick) { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val approvedToday = remember(tick) { app.events.countSince(CoffeeService.EV_APPROVED, startOfDay) }
    val deniedToday = remember(tick) { app.events.countSince(CoffeeService.EV_DENIED, startOfDay) }

    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val time = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale("ru")) }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF121212)).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Админка", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onExit) { Text("Выйти", fontSize = 18.sp) }
        }

        // ---------- Ячейки ----------
        Section("Ячейки с кофе — заполнено ${slots.count { it.filled }} из ${slots.size}") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (side in Side.entries) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(side.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        for (slot in slots.filter { it.side == side }) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (slot.filled) SlotOn else SlotOff)
                                    .clickable { app.slots.set(slot.side, slot.index, !slot.filled) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text("№${slot.index}", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                Text(if (slot.filled) "☕ есть" else "пусто", color = Color.White, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { app.slots.setAll(true); app.events.add("slots", "заполнить всё") },
                    colors = ButtonDefaults.buttonColors(containerColor = SlotOn),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Заполнить всё", fontSize = 18.sp) }
                OutlinedButton(
                    onClick = { confirm = "Отметить все ячейки пустыми?" to { app.slots.setAll(false); app.events.add("slots", "очистить всё") } },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Очистить всё", fontSize = 18.sp, color = Color.White) }
            }
        }

        // ---------- База лиц ----------
        Section("База лиц") {
            Stat("В базе", "${app.faces.count()}")
            Stat("Выдано сегодня", "$approvedToday")
            Stat("Отказов сегодня", "$deniedToday")
            Stat("Последний сброс", if (s.lastResetAt == 0L) "—" else time.format(Date(s.lastResetAt)))
            Button(
                onClick = { confirm = "Удалить все лица? Все снова смогут получить кофе." to { app.reset.resetManually("планшет") } },
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Сбросить базу лиц", fontSize = 18.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Автосброс каждый день", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = s.autoResetEnabled, onCheckedChange = { s.autoResetEnabled = it })
            }
            if (s.autoResetEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Время сброса", color = Color.White, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { s.autoResetHour = (s.autoResetHour + 23) % 24 }) { Text("−", color = Color.White) }
                    Text("%02d:00".format(s.autoResetHour), color = Color.White, fontSize = 20.sp)
                    OutlinedButton(onClick = { s.autoResetHour = (s.autoResetHour + 1) % 24 }) { Text("+", color = Color.White) }
                }
            }
            if (recentFaces.any { it.thumb != null }) {
                Text("Последние", color = Color.Gray)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentFaces.mapNotNull { it.thumb }.forEach { Thumb(it) }
                }
            }
        }

        // ---------- Распознавание ----------
        Section("Распознавание") {
            Text("Последний кадр: ${live.issue?.hint ?: "лицо годится"}", color = Color.White)
            Text(live.metrics, color = Color.Gray, fontSize = 13.sp)
            SettingSlider(
                "Порог «тот же человек» (выше — строже к отказу)", s.matchThreshold, 0.45f..0.95f, "%.2f",
            ) { s.matchThreshold = it }
            SettingSlider("Минимальный размер лица в кадре", s.minFaceSize, 0.15f..0.6f, "%.2f") { s.minFaceSize = it }
            SettingSlider("Минимальная резкость", s.minSharpness, 5f..200f, "%.0f") { s.minSharpness = it }
            SettingSlider("Максимальный поворот головы, °", s.maxAngle, 5f..30f, "%.0f") { s.maxAngle = it }
        }

        // ---------- Выдача ----------
        Section("Выдача кофе") {
            for (mode in ControllerMode.entries) {
                Row(Modifier.clickable { s.controllerMode = mode }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.controllerMode == mode, onClick = { s.controllerMode = mode })
                    Text(mode.title, color = Color.White)
                }
            }
            if (s.controllerMode == ControllerMode.STUB) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Заглушка отвечает ошибкой (тест сбоя)", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = s.stubFails, onCheckedChange = { s.stubFails = it })
                }
            } else {
                TextSetting("Адрес Raspberry Pi", s.rpiUrl) { s.rpiUrl = it }
                TextSetting("Токен (X-Token), необязательно", s.rpiToken) { s.rpiToken = it }
            }
            Text("Порядок выдачи", color = Color.Gray)
            for (order in SlotOrder.entries) {
                Row(Modifier.clickable { s.slotOrder = order }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.slotOrder == order, onClick = { s.slotOrder = order })
                    Text(order.title, color = Color.White)
                }
            }
        }

        // ---------- Безопасность и прочее ----------
        Section("Прочее") {
            SwitchRow("Сохранять превью лиц (для отладки)", s.saveThumbnails) { s.saveThumbnails = it }
            SwitchRow("Показывать отладочную информацию на экране", s.showDebug) { s.showDebug = it }
            SwitchRow("Веб-админка по сети (порт ${s.webPort})", s.webAdminEnabled) { s.webAdminEnabled = it; app.applyWebAdmin() }
            if (s.webAdminEnabled) {
                Text("Адрес: ${localIpAddresses().joinToString { "http://$it:${s.webPort}" }.ifEmpty { "нет сети" }}", color = Color.Gray)
            }
            PasswordChange(onChange = { s.pin = it; app.events.add("admin", "пароль изменён") })
        }

        // ---------- Приложение ----------
        Section("Приложение") {
            Text(
                if (isDeviceOwner) "Режим киоска: полная блокировка (Device Owner)"
                else "Режим киоска: закрепление экрана. Для полной блокировки см. README → «Режим киоска»",
                color = Color.Gray,
            )
            Button(
                onClick = { confirm = "Закрыть приложение и выйти из режима киоска?" to onCloseApp },
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Закрыть приложение", fontSize = 18.sp) }
        }

        // ---------- Журнал ----------
        Section("Журнал") {
            events.forEach { e ->
                Text("${time.format(Date(e.ts))} · ${e.type} ${e.detail}", color = Color.LightGray, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Подтвердите") },
            text = { Text(text) },
            confirmButton = { Button(onClick = { action(); confirm = null }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row { Text(label, color = Color.Gray, modifier = Modifier.weight(1f)); Text(value, color = Color.White) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row { Text(label, color = Color.White, modifier = Modifier.weight(1f)); Text(fmt.format(v), color = Color.White) }
        Slider(value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onDone(v) })
    }
}

@Composable
private fun TextSetting(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = { onSave(text) }, enabled = text != value) { Text("Сохранить") }
    }
}

@Composable
private fun PasswordChange(onChange: (String) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Text("Сменить пароль админки", color = Color.Gray)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(p1, { p1 = it; msg = "" }, label = { Text("Новый") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
        OutlinedTextField(p2, { p2 = it; msg = "" }, label = { Text("Ещё раз") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
        Button(onClick = {
            msg = when {
                p1.length < 4 -> "Минимум 4 символа"
                p1 != p2 -> "Пароли не совпадают"
                else -> { onChange(p1); p1 = ""; p2 = ""; "Пароль изменён" }
            }
        }) { Text("Сменить") }
    }
    if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Thumb(bmp: Bitmap) {
    Box(Modifier.size(64.dp).widthIn(max = 64.dp).clip(RoundedCornerShape(6.dp))) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

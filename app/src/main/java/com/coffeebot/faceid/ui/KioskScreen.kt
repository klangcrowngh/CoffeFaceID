package com.coffeebot.faceid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coffeebot.faceid.App
import com.coffeebot.faceid.core.Outcome
import com.coffeebot.faceid.data.DataChanges
import com.coffeebot.faceid.face.QualityIssue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFFF8F00)
private val Background = Color(0xFF2B1B17)

@Composable
fun KioskScreen(app: App, onAdminRequested: () -> Unit) {
    val scope = rememberCoroutineScope()
    val live by app.analyzer.live.collectAsState()
    val tick by DataChanges.tick.collectAsState()
    val coffeeLeft = remember(tick) { app.slots.filledCount() }
    val showDebug = remember(tick) { app.settings.showDebug }

    var scanning by remember { mutableStateOf(false) }
    var scanIssue by remember { mutableStateOf<QualityIssue?>(null) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }

    // Результат висит на экране несколько секунд, затем экран возвращается к ожиданию.
    LaunchedEffect(outcome) {
        if (outcome != null) { delay(if (outcome is Outcome.BadQuality) 2500 else 4000); outcome = null }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        // Камера включается только на время проверки после нажатия кнопки и ничего не выводит на экран.
        if (scanning) CameraAnalysis(app.analyzer)
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text("☕", fontSize = 120.sp)
            Text(
                "Бесплатный кофе",
                color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            // Во время проверки: зелёная подсказка — лицо годится, жёлтая — нужно что-то поправить.
            val (hint, hintColor) = when {
                !scanning -> "Нажмите кнопку и посмотрите в камеру" to Color(0x33FFFFFF)
                scanIssue == null -> "Отлично, не двигайтесь" to Green
                else -> scanIssue!!.hint to Amber.copy(alpha = 0.85f)
            }
            Text(
                hint,
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(hintColor)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    if (scanning || outcome != null) return@Button
                    scanIssue = QualityIssue.NO_FACE
                    scanning = true
                    scope.launch {
                        outcome = try {
                            app.service.handlePress { scanIssue = it }
                        } catch (e: Exception) {
                            Outcome.Error(e.message ?: "ошибка")
                        }
                        scanning = false
                    }
                },
                enabled = !scanning && outcome == null && coffeeLeft > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6D4C41), contentColor = Color.White,
                    disabledContainerColor = Color(0xFF4E342E), disabledContentColor = Color(0xCCFFFFFF),
                ),
                modifier = Modifier.height(96.dp).fillMaxWidth(0.7f),
                shape = RoundedCornerShape(48.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.size(16.dp))
                    Text("Проверяем лицо…", fontSize = 30.sp)
                } else {
                    Text(if (coffeeLeft > 0) "☕  Получить кофе" else "Кофе закончился", fontSize = 34.sp)
                }
            }
            Text(
                "Нажимая кнопку, вы соглашаетесь на обработку изображения лица для однократной выдачи. " +
                    "Данные удаляются ежедневно.",
                color = Color(0xCCFFFFFF), fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            if (showDebug) {
                Text(
                    "осталось $coffeeLeft · в базе ${app.faces.count()} · ${live.metrics}",
                    color = Color(0x99FFFFFF), fontSize = 12.sp,
                )
            }
        }

        AnimatedVisibility(outcome != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            outcome?.let { ResultOverlay(it, showDebug) }
        }

        // Скрытый вход в админку: 5 быстрых нажатий в левый верхний угол.
        AdminHotspot(onAdminRequested, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun ResultOverlay(o: Outcome, showDebug: Boolean) {
    val (color, mark, title, sub) = when (o) {
        is Outcome.Approved -> Quad(Green, "✓", "Ваш кофе!", "${o.side.title} ячейка №${o.slot}")
        is Outcome.Denied -> Quad(Red, "✗", "Вы уже получили кофе", "Одна порция на человека в день" + dbg(showDebug, o.similarity))
        is Outcome.BadQuality -> Quad(Amber, "!", "Не удалось распознать лицо", o.issue.hint)
        Outcome.NoCoffee -> Quad(Red, "✗", "Кофе закончился", "Скоро пополним")
        is Outcome.Error -> Quad(Red, "!", "Ошибка выдачи", if (showDebug) o.message else "Попробуйте ещё раз")
    }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(260.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text(mark, color = Color.White, fontSize = 170.sp, fontWeight = FontWeight.Bold)
            }
            Text(title, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(sub, color = Color(0xDDFFFFFF), fontSize = 28.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun dbg(show: Boolean, sim: Float) = if (show) "\nсходство %.2f".format(sim) else ""

private data class Quad(val color: Color, val mark: String, val title: String, val sub: String)

@Composable
private fun AdminHotspot(onTriggered: () -> Unit, modifier: Modifier) {
    var taps by remember { mutableIntStateOf(0) }
    var firstTap by remember { mutableLongStateOf(0L) }
    Box(
        modifier.size(120.dp).pointerInput(Unit) {
            detectTapGestures {
                val now = System.currentTimeMillis()
                if (now - firstTap > 3000) { firstTap = now; taps = 0 }
                taps++
                if (taps >= 5) { taps = 0; onTriggered() }
            }
        }
    )
}

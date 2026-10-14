package com.coffeebot.faceid.core

import com.coffeebot.faceid.data.EventLog
import com.coffeebot.faceid.data.FaceRepository
import com.coffeebot.faceid.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Ежедневный сброс базы лиц в заданный час. Проверка раз в минуту; если планшет был выключен
 * в момент сброса, сброс выполнится при следующем запуске.
 */
class DailyReset(
    private val settings: Settings,
    private val faces: FaceRepository,
    private val events: EventLog,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            runCatching { checkNow() }
            delay(60_000)
        }
    }

    fun checkNow() {
        if (!settings.autoResetEnabled) return
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val today = now.toLocalDate().atTime(settings.autoResetHour, 0)
        val due = if (now >= today) today else today.minusDays(1)
        val dueMs = due.atZone(zone).toInstant().toEpochMilli()
        if (settings.lastResetAt < dueMs) {
            faces.clear()
            settings.lastResetAt = System.currentTimeMillis()
            events.add("reset", "автосброс базы лиц")
        }
    }

    fun resetManually(source: String) {
        faces.clear()
        settings.lastResetAt = System.currentTimeMillis()
        events.add("reset", "ручной сброс ($source)")
    }
}

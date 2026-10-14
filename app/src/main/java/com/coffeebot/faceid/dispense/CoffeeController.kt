package com.coffeebot.faceid.dispense

import com.coffeebot.faceid.data.ControllerMode
import com.coffeebot.faceid.data.Settings
import com.coffeebot.faceid.data.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DispenseResult(val ok: Boolean, val message: String)

/** Команда «открыть ячейку» для платы выдачи. Протокол для Raspberry Pi описан в README.md. */
interface CoffeeController {
    suspend fun open(side: Side, slot: Int, requestId: String): DispenseResult
}

/** Выбирает реализацию по текущей настройке — переключение в админке работает сразу. */
class SwitchingController(private val settings: Settings) : CoffeeController {
    private val stub = StubController(settings)
    private val rpi = RaspberryPiController(settings)

    override suspend fun open(side: Side, slot: Int, requestId: String) =
        when (settings.controllerMode) {
            ControllerMode.STUB -> stub.open(side, slot, requestId)
            ControllerMode.RASPBERRY -> rpi.open(side, slot, requestId)
        }
}

/** Заглушка вместо Raspberry Pi: имитирует задержку открытия ячейки. */
class StubController(private val settings: Settings) : CoffeeController {
    override suspend fun open(side: Side, slot: Int, requestId: String): DispenseResult {
        delay(600)
        return if (settings.stubFails) DispenseResult(false, "Заглушка: имитация ошибки платы")
        else DispenseResult(true, "Заглушка: открыта ${side.title.lowercase()} ячейка №$slot")
    }
}

/**
 * POST {rpiUrl}/open  {"side":"left","slot":3,"request_id":"..."}
 * Ответ 200 {"status":"ok"} — ячейка открыта; всё остальное — ошибка.
 */
class RaspberryPiController(private val settings: Settings) : CoffeeController {
    override suspend fun open(side: Side, slot: Int, requestId: String): DispenseResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("${settings.rpiUrl}/open").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (settings.rpiToken.isNotEmpty()) setRequestProperty("X-Token", settings.rpiToken)
            }
            val body = JSONObject().put("side", side.id).put("slot", slot).put("request_id", requestId)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val status = runCatching { JSONObject(text).optString("status") }.getOrDefault("")
            if (code == 200 && status == "ok") DispenseResult(true, "Плата: ячейка открыта")
            else DispenseResult(false, "Плата ответила $code: ${text.take(200)}")
        } catch (e: Exception) {
            DispenseResult(false, "Нет связи с платой: ${e.message}")
        }
    }
}

package com.coffeebot.faceid.web

import android.content.Context
import com.coffeebot.faceid.core.CoffeeService
import com.coffeebot.faceid.core.DailyReset
import com.coffeebot.faceid.data.EventLog
import com.coffeebot.faceid.data.FaceRepository
import com.coffeebot.faceid.data.SLOTS_PER_SIDE
import com.coffeebot.faceid.data.Settings
import com.coffeebot.faceid.data.Side
import com.coffeebot.faceid.data.SlotRepository
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Веб-админка на планшете: http://<ip-планшета>:8080 с телефона в той же сети.
 * Все запросы к /api/… требуют заголовок X-Pin с PIN-кодом админки.
 */
class AdminServer(
    private val context: Context,
    private val settings: Settings,
    private val faces: FaceRepository,
    private val slots: SlotRepository,
    private val events: EventLog,
    private val reset: DailyReset,
) : NanoHTTPD(settings.webPort) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "error"))
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri
        if (uri == "/" || uri == "/index.html") {
            val html = context.assets.open("admin.html").bufferedReader().use { it.readText() }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        if (!uri.startsWith("/api/")) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        if (s.headers["x-pin"] != settings.pin) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Неверный PIN"))

        if (uri.startsWith("/api/thumb/") && s.method == Method.GET) {
            val bytes = uri.removePrefix("/api/thumb/").toLongOrNull()?.let { faces.thumbJpeg(it) }
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no thumb")
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
        }

        return when (s.method to uri) {
            Method.GET to "/api/state" -> json(Response.Status.OK, state())
            Method.POST to "/api/slot" -> {
                val body = body(s)
                val side = Side.of(body.getString("side")) ?: return bad("side")
                val idx = body.getInt("idx")
                if (idx !in 1..SLOTS_PER_SIDE) return bad("idx")
                slots.set(side, idx, body.getBoolean("filled"))
                ok()
            }
            Method.POST to "/api/slots/fill" -> { slots.setAll(true); events.add("slots", "заполнить всё (веб)"); ok() }
            Method.POST to "/api/slots/clear" -> { slots.setAll(false); events.add("slots", "очистить всё (веб)"); ok() }
            Method.POST to "/api/faces/reset" -> { reset.resetManually("веб"); ok() }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    }

    private fun state(): JSONObject {
        val all = slots.all()
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return JSONObject()
            .put("slots", JSONObject().apply {
                for (side in Side.entries) put(side.id, JSONArray(all.filter { it.side == side }.map { it.filled }))
            })
            .put("filled", all.count { it.filled })
            .put("total", all.size)
            .put("faces", faces.count())
            .put("approvedToday", events.countSince(CoffeeService.EV_APPROVED, startOfDay))
            .put("deniedToday", events.countSince(CoffeeService.EV_DENIED, startOfDay))
            .put("lastReset", if (settings.lastResetAt == 0L) "" else Instant.ofEpochMilli(settings.lastResetAt).toString())
            .put("autoReset", if (settings.autoResetEnabled) "%02d:00".format(settings.autoResetHour) else "выкл")
            .put("controller", settings.controllerMode.title)
            .put("recentFaces", JSONArray(faces.recent(24).map {
                JSONObject().put("id", it.id).put("ts", it.createdAt).put("hasThumb", it.thumb != null)
                    .put("side", it.side ?: "").put("slot", it.slot ?: 0)
            }))
            .put("events", JSONArray(events.recent(30).map {
                JSONObject().put("ts", it.ts).put("type", it.type).put("detail", it.detail)
            }))
    }

    private fun body(s: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        s.parseBody(files)
        return JSONObject(files["postData"] ?: "{}")
    }

    private fun ok() = json(Response.Status.OK, JSONObject().put("ok", true))
    private fun bad(field: String) = json(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad $field"))
    private fun json(status: Response.Status, o: JSONObject) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
}

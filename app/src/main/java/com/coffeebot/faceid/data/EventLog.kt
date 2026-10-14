package com.coffeebot.faceid.data

import android.content.ContentValues

data class Event(val ts: Long, val type: String, val detail: String)

/** Журнал решений (выдано / отказ / ошибка / сброс) — для отладки и статистики за день. */
class EventLog(private val db: AppDatabase) {

    fun add(type: String, detail: String = "") {
        db.writableDatabase.insert("events", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("type", type); put("detail", detail)
        })
        // Журнал не должен расти бесконечно.
        db.writableDatabase.execSQL("DELETE FROM events WHERE id <= (SELECT MAX(id) FROM events) - 2000")
        DataChanges.bump()
    }

    fun recent(limit: Int): List<Event> =
        db.readableDatabase.rawQuery(
            "SELECT ts, type, detail FROM events ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(Event(c.getLong(0), c.getString(1), c.getString(2) ?: "")) }
        }

    fun countSince(type: String, since: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE type = ? AND ts >= ?", arrayOf(type, since.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}

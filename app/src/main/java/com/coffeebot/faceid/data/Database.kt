package com.coffeebot.faceid.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val SLOTS_PER_SIDE = 10

enum class Side(val id: String, val title: String) {
    LEFT("left", "Левая"),
    RIGHT("right", "Правая");

    companion object {
        fun of(id: String): Side? = entries.firstOrNull { it.id == id }
    }
}

/** Счётчик изменений данных: UI и веб-админка перечитывают состояние, когда он меняется. */
object DataChanges {
    private val _tick = MutableStateFlow(0L)
    val tick = _tick.asStateFlow()
    fun bump() = _tick.update { it + 1 }
}

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "faceid.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE faces(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                embedding BLOB NOT NULL,
                thumb BLOB,
                side TEXT,
                slot INTEGER,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE slots(
                side TEXT NOT NULL,
                idx INTEGER NOT NULL,
                filled INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(side, idx))"""
        )
        db.execSQL(
            """CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                type TEXT NOT NULL,
                detail TEXT)"""
        )
        for (side in Side.entries) {
            for (i in 1..SLOTS_PER_SIDE) {
                db.insert("slots", null, ContentValues().apply {
                    put("side", side.id); put("idx", i); put("filled", 0)
                })
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

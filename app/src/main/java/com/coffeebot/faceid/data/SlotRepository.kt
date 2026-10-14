package com.coffeebot.faceid.data

import android.content.ContentValues

data class Slot(val side: Side, val index: Int, val filled: Boolean)

enum class SlotOrder(val id: String, val title: String) {
    LEFT_FIRST("left_first", "Сначала левая, потом правая"),
    ALTERNATE("alternate", "По очереди: левая / правая");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: LEFT_FIRST
    }
}

/** Ячейки с кофе: две колонки по [SLOTS_PER_SIDE]. Заполненность отмечается вручную в админке. */
class SlotRepository(private val db: AppDatabase) {

    @Synchronized
    fun all(): List<Slot> =
        db.readableDatabase.rawQuery("SELECT side, idx, filled FROM slots", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val side = Side.of(c.getString(0)) ?: continue
                    add(Slot(side, c.getInt(1), c.getInt(2) != 0))
                }
            }
        }.sortedWith(compareBy({ it.side.ordinal }, { it.index }))

    fun column(side: Side): List<Slot> = all().filter { it.side == side }

    fun filledCount(): Int = all().count { it.filled }

    @Synchronized
    fun set(side: Side, index: Int, filled: Boolean) {
        db.writableDatabase.update(
            "slots", ContentValues().apply { put("filled", if (filled) 1 else 0) },
            "side = ? AND idx = ?", arrayOf(side.id, index.toString())
        )
        DataChanges.bump()
    }

    @Synchronized
    fun setAll(filled: Boolean) {
        db.writableDatabase.update("slots", ContentValues().apply { put("filled", if (filled) 1 else 0) }, null, null)
        DataChanges.bump()
    }

    /** Следующая заполненная ячейка в выбранном порядке, или null, если кофе закончился. */
    fun next(order: SlotOrder): Slot? {
        val filled = all().filter { it.filled }
        return when (order) {
            SlotOrder.LEFT_FIRST -> filled.minWithOrNull(compareBy({ it.side.ordinal }, { it.index }))
            SlotOrder.ALTERNATE -> filled.minWithOrNull(compareBy({ it.index }, { it.side.ordinal }))
        }
    }
}

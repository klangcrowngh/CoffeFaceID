package com.coffeebot.faceid.data

import android.content.Context
import android.content.SharedPreferences

enum class ControllerMode(val id: String, val title: String) {
    STUB("stub", "Заглушка (без платы)"),
    RASPBERRY("rpi", "Raspberry Pi по HTTP");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: STUB
    }
}

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Сходство (косинус), начиная с которого человек считается «уже получившим кофе». */
    var matchThreshold: Float
        get() = p.getFloat("match_threshold", 0.70f)
        set(v) = edit { putFloat("match_threshold", v) }

    /** Минимальный размер лица относительно короткой стороны кадра. */
    var minFaceSize: Float
        get() = p.getFloat("min_face_size", 0.28f)
        set(v) = edit { putFloat("min_face_size", v) }

    /** Минимальная резкость лица (дисперсия лапласиана на кропе 112×112). */
    var minSharpness: Float
        get() = p.getFloat("min_sharpness", 40f)
        set(v) = edit { putFloat("min_sharpness", v) }

    /** Максимальный поворот головы (градусы) по горизонтали и вертикали. */
    var maxAngle: Float
        get() = p.getFloat("max_angle", 15f)
        set(v) = edit { putFloat("max_angle", v) }

    /** Пароль админки (любые символы). */
    var pin: String
        get() = p.getString("pin", "1234")!!
        set(v) = edit { putString("pin", v) }

    /** Веб-админка по сети. По умолчанию выключена: админка доступна только на самом планшете. */
    var webAdminEnabled: Boolean
        get() = p.getBoolean("web_admin", false)
        set(v) = edit { putBoolean("web_admin", v) }

    var controllerMode: ControllerMode
        get() = ControllerMode.of(p.getString("controller", ControllerMode.STUB.id)!!)
        set(v) = edit { putString("controller", v.id) }

    var rpiUrl: String
        get() = p.getString("rpi_url", "http://192.168.1.50:5000")!!
        set(v) = edit { putString("rpi_url", v.trim().trimEnd('/')) }

    var rpiToken: String
        get() = p.getString("rpi_token", "")!!
        set(v) = edit { putString("rpi_token", v.trim()) }

    /** Заглушка будет отвечать ошибкой — чтобы проверить поведение при сбое платы. */
    var stubFails: Boolean
        get() = p.getBoolean("stub_fails", false)
        set(v) = edit { putBoolean("stub_fails", v) }

    var slotOrder: SlotOrder
        get() = SlotOrder.of(p.getString("slot_order", SlotOrder.LEFT_FIRST.id)!!)
        set(v) = edit { putString("slot_order", v.id) }

    var autoResetEnabled: Boolean
        get() = p.getBoolean("auto_reset", true)
        set(v) = edit { putBoolean("auto_reset", v) }

    var autoResetHour: Int
        get() = p.getInt("auto_reset_hour", 3)
        set(v) = edit { putInt("auto_reset_hour", v.coerceIn(0, 23)) }

    var lastResetAt: Long
        get() = p.getLong("last_reset_at", 0L)
        set(v) = edit { putLong("last_reset_at", v) }

    var saveThumbnails: Boolean
        get() = p.getBoolean("save_thumbs", true)
        set(v) = edit { putBoolean("save_thumbs", v) }

    var showDebug: Boolean
        get() = p.getBoolean("show_debug", true)
        set(v) = edit { putBoolean("show_debug", v) }

    val webPort: Int get() = 8080

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(block).apply()
        DataChanges.bump()
    }
}

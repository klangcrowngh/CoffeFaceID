package com.coffeebot.faceid

import android.app.Application
import android.util.Log
import com.coffeebot.faceid.core.CoffeeService
import com.coffeebot.faceid.core.DailyReset
import com.coffeebot.faceid.data.AppDatabase
import com.coffeebot.faceid.data.EventLog
import com.coffeebot.faceid.data.FaceRepository
import com.coffeebot.faceid.data.Settings
import com.coffeebot.faceid.data.SlotRepository
import com.coffeebot.faceid.dispense.SwitchingController
import com.coffeebot.faceid.face.FaceChecker
import com.coffeebot.faceid.face.FaceEmbedder
import com.coffeebot.faceid.face.FrameAnalyzer
import com.coffeebot.faceid.web.AdminServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Ручная сборка зависимостей: приложение маленькое, DI-фреймворк не нужен. */
class App : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: Settings; private set
    lateinit var faces: FaceRepository; private set
    lateinit var slots: SlotRepository; private set
    lateinit var events: EventLog; private set
    lateinit var analyzer: FrameAnalyzer; private set
    lateinit var service: CoffeeService; private set
    lateinit var reset: DailyReset; private set

    val embedder by lazy { FaceEmbedder(this) }
    private var server: AdminServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val db = AppDatabase(this)
        settings = Settings(this)
        faces = FaceRepository(db)
        slots = SlotRepository(db)
        events = EventLog(db)
        analyzer = FrameAnalyzer(FaceChecker(settings))
        reset = DailyReset(settings, faces, events)
        service = CoffeeService(settings, faces, slots, events, analyzer, { embedder }, SwitchingController(settings))

        scope.launch { embedder } // прогрев модели, чтобы первое нажатие не ждало загрузки
        reset.start(scope)
        applyWebAdmin()
    }

    /** Запускает или останавливает веб-админку в соответствии с настройкой. */
    @Synchronized
    fun applyWebAdmin() {
        val running = server != null
        if (settings.webAdminEnabled == running) return
        if (running) {
            server?.stop()
            server = null
            return
        }
        try {
            server = AdminServer(this, settings, faces, slots, events, reset).also { it.start(5000, false) }
        } catch (e: Exception) {
            Log.e("App", "admin server failed to start", e)
        }
    }

    companion object {
        lateinit var instance: App; private set
    }
}

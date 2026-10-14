package com.coffeebot.faceid.core

import com.coffeebot.faceid.data.EventLog
import com.coffeebot.faceid.data.FaceRepository
import com.coffeebot.faceid.data.Settings
import com.coffeebot.faceid.data.Side
import com.coffeebot.faceid.data.SlotRepository
import com.coffeebot.faceid.dispense.CoffeeController
import com.coffeebot.faceid.face.FaceEmbedder
import com.coffeebot.faceid.face.FrameAnalyzer
import com.coffeebot.faceid.face.QualityIssue
import com.coffeebot.faceid.face.averageEmbedding
import com.coffeebot.faceid.data.cosine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Outcome {
    data class Approved(val side: Side, val slot: Int, val similarity: Float?) : Outcome
    data class Denied(val similarity: Float) : Outcome
    data class BadQuality(val issue: QualityIssue) : Outcome
    data object NoCoffee : Outcome
    data class Error(val message: String) : Outcome
}

/** Сценарий нажатия кнопки: проверка лица → поиск в базе → открытие ячейки → запись. */
class CoffeeService(
    private val settings: Settings,
    private val faces: FaceRepository,
    private val slots: SlotRepository,
    private val events: EventLog,
    private val analyzer: FrameAnalyzer,
    private val embedder: () -> FaceEmbedder,
    private val controller: CoffeeController,
) {
    private val mutex = Mutex()

    suspend fun handlePress(onIssue: (QualityIssue?) -> Unit): Outcome = mutex.withLock {
        if (slots.filledCount() == 0) return log(Outcome.NoCoffee)

        // Несколько подряд годных кадров — один случайно удачный кадр не пройдёт.
        val scan = analyzer.scan(needed = GOOD_FRAMES, timeoutMs = SCAN_TIMEOUT_MS, onIssue = onIssue)
        if (scan.crops.isEmpty()) return log(Outcome.BadQuality(scan.issue ?: QualityIssue.NO_FACE))

        val embeddings = withContext(Dispatchers.Default) { scan.crops.map { embedder().embed(it) } }
        // Все кадры должны быть одним и тем же лицом (человек не сменился, кадр не «поплыл»).
        val consistency = embeddings.indices.flatMap { i -> (i + 1 until embeddings.size).map { j -> cosine(embeddings[i], embeddings[j]) } }.min()
        if (consistency < CONSISTENCY_MIN) return log(Outcome.BadQuality(QualityIssue.BLURRY))
        val embedding = averageEmbedding(embeddings)

        val match = faces.bestMatch(embedding)
        if (match != null && match.similarity >= settings.matchThreshold) return log(Outcome.Denied(match.similarity))

        val slot = slots.next(settings.slotOrder) ?: return log(Outcome.NoCoffee)
        val result = controller.open(slot.side, slot.index, UUID.randomUUID().toString())
        if (!result.ok) return log(Outcome.Error(result.message))

        // В базу — только после подтверждённой выдачи.
        slots.set(slot.side, slot.index, false)
        faces.add(embedding, if (settings.saveThumbnails) scan.crops.first() else null, slot.side, slot.index)
        log(Outcome.Approved(slot.side, slot.index, match?.similarity))
    }

    private fun log(o: Outcome): Outcome {
        when (o) {
            is Outcome.Approved -> events.add(EV_APPROVED, "${o.side.title} №${o.slot}" + sim(o.similarity))
            is Outcome.Denied -> events.add(EV_DENIED, "уже получал" + sim(o.similarity))
            is Outcome.BadQuality -> events.add("bad_quality", o.issue.hint)
            Outcome.NoCoffee -> events.add("no_coffee")
            is Outcome.Error -> events.add("error", o.message)
        }
        return o
    }

    private fun sim(s: Float?) = if (s == null) "" else ", сходство %.2f".format(s)

    companion object {
        const val GOOD_FRAMES = 3
        const val SCAN_TIMEOUT_MS = 7000L // включает запуск камеры (~0.5–1 с)
        const val CONSISTENCY_MIN = 0.55f
        const val EV_APPROVED = "approved"
        const val EV_DENIED = "denied"
    }
}

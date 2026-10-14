package com.coffeebot.faceid.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class LiveStatus(val issue: QualityIssue?, val metrics: String, val ts: Long)

class ScanResult(val crops: List<Bitmap>, val issue: QualityIssue?)

/**
 * Анализатор кадров камеры. В простое проверяет ~3 кадра в секунду, чтобы показывать подсказку
 * («подойдите ближе» и т.п.) ещё до нажатия; во время сканирования — каждый кадр.
 */
class FrameAnalyzer(private val checker: FaceChecker) : ImageAnalysis.Analyzer {

    private val _live = MutableStateFlow(LiveStatus(QualityIssue.NO_FACE, "", 0))
    val live = _live.asStateFlow()

    @Volatile private var sink: Channel<FrameCheck>? = null
    private var lastIdleCheck = 0L
    @Volatile private var firstFrameAt = 0L

    override fun analyze(image: ImageProxy) {
        val active = sink
        val now = SystemClock.elapsedRealtime()
        if (active == null && now - lastIdleCheck < 330) { image.close(); return }
        lastIdleCheck = now
        // Камера включается по нажатию; первые кадры, пока подстраивается экспозиция, пропускаем.
        if (firstFrameAt == 0L) firstFrameAt = now
        if (now - firstFrameAt < WARMUP_MS) { image.close(); return }

        val frame = try {
            image.toBitmap().rotated(image.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            Log.w(TAG, "frame conversion failed", e)
            return
        } finally {
            image.close()
        }

        val check = try {
            checker.check(frame)
        } catch (e: Exception) {
            Log.w(TAG, "face check failed", e)
            FrameCheck(QualityIssue.NO_FACE)
        }
        _live.value = LiveStatus(check.issue, check.metrics, now)
        active?.trySend(check)
    }

    /**
     * Собирает [needed] годных кадров за [timeoutMs]. Если не набралось — возвращает
     * последнюю причину, чтобы показать человеку, что исправить.
     */
    suspend fun scan(needed: Int, timeoutMs: Long, onIssue: (QualityIssue?) -> Unit): ScanResult {
        val channel = Channel<FrameCheck>(Channel.CONFLATED)
        firstFrameAt = 0L
        sink = channel
        val good = mutableListOf<Bitmap>()
        var lastIssue: QualityIssue? = QualityIssue.NO_FACE
        try {
            withTimeoutOrNull(timeoutMs) {
                while (good.size < needed) {
                    val c = channel.receive()
                    if (c.ok) {
                        good += c.crop!!
                        lastIssue = null
                    } else {
                        lastIssue = c.issue
                    }
                    onIssue(c.issue)
                }
            }
        } finally {
            sink = null
            channel.close()
        }
        return if (good.size >= needed) ScanResult(good, null) else ScanResult(emptyList(), lastIssue ?: QualityIssue.BLURRY)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees == 0) this
        else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    companion object {
        private const val TAG = "FrameAnalyzer"
        private const val WARMUP_MS = 500L
    }
}

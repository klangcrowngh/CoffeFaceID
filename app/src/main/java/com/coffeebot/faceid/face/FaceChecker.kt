package com.coffeebot.faceid.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.coffeebot.faceid.data.Settings
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/** Причина, по которой кадр не годится. Порядок — от «самой грубой» проблемы к более тонкой. */
enum class QualityIssue(val hint: String) {
    CAMERA_BLOCKED("Камера закрыта — уберите руку или предмет"),
    TOO_DARK("Слишком темно"),
    TOO_BRIGHT("Слишком ярко — лицо засвечено"),
    NO_FACE("Встаньте перед камерой"),
    MULTIPLE_FACES("В кадре должен быть только один человек"),
    TOO_FAR("Подойдите ближе"),
    TOO_CLOSE("Отойдите чуть дальше"),
    OFF_CENTER("Встаньте по центру, лицо целиком в кадре"),
    NOT_FRONTAL("Посмотрите прямо в камеру"),
    OCCLUDED("Откройте лицо полностью"),
    EYES_CLOSED("Откройте глаза, снимите тёмные очки"),
    BLURRY("Не двигайтесь"),
}

class FrameCheck(
    val issue: QualityIssue?,
    /** Выровненное лицо 112×112, только если кадр прошёл все проверки. */
    val crop: Bitmap? = null,
    val metrics: String = "",
) {
    val ok get() = issue == null && crop != null
}

/**
 * Строгая проверка кадра: кофе выдаётся только по чёткому, открытому, фронтальному лицу.
 * Каждая проверка отсекает свой способ обмана или плохой снимок.
 */
class FaceChecker(private val settings: Settings) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build()
    )

    /** Блокирующий вызов — запускать только на фоновом потоке (анализатор камеры). */
    fun check(frame: Bitmap): FrameCheck {
        // 1. Весь кадр: закрытая камера даёт тёмную или однотонную картинку.
        val (mean, std) = lumaStats(frame, 0, 0, frame.width, frame.height)
        val frameInfo = "кадр %.0f±%.0f".format(mean, std)
        if (std < 10f || (mean < 25f && std < 18f)) return FrameCheck(QualityIssue.CAMERA_BLOCKED, metrics = frameInfo)
        if (mean < 35f) return FrameCheck(QualityIssue.TOO_DARK, metrics = frameInfo)

        // 2. Детекция лиц.
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
        if (faces.isEmpty()) return FrameCheck(QualityIssue.NO_FACE, metrics = frameInfo)
        val main = faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
        val mainArea = main.boundingBox.width() * main.boundingBox.height()
        // Люди далеко на фоне не мешают; второе крупное лицо — мешает.
        if (faces.any { it !== main && it.boundingBox.width() * it.boundingBox.height() > mainArea * 0.35f }) {
            return FrameCheck(QualityIssue.MULTIPLE_FACES, metrics = frameInfo)
        }

        // 3. Размер и положение.
        val box = main.boundingBox
        val rel = box.width().toFloat() / min(frame.width, frame.height)
        val info = StringBuilder("$frameInfo · лицо %.2f".format(rel))
        if (rel < settings.minFaceSize) return FrameCheck(QualityIssue.TOO_FAR, metrics = info.toString())
        if (rel > 0.85f) return FrameCheck(QualityIssue.TOO_CLOSE, metrics = info.toString())
        val margin = box.width() * 0.05f
        if (box.left < margin || box.top < margin || box.right > frame.width - margin || box.bottom > frame.height - margin) {
            return FrameCheck(QualityIssue.OFF_CENTER, metrics = info.toString())
        }

        // 4. Ракурс.
        info.append(" · угол %.0f/%.0f/%.0f".format(main.headEulerAngleY, main.headEulerAngleX, main.headEulerAngleZ))
        val maxAngle = settings.maxAngle
        if (abs(main.headEulerAngleY) > maxAngle || abs(main.headEulerAngleX) > maxAngle || abs(main.headEulerAngleZ) > 20f) {
            return FrameCheck(QualityIssue.NOT_FRONTAL, metrics = info.toString())
        }

        // 5. Открытое лицо: должны находиться глаза, нос и рот.
        val required = listOf(
            FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_BOTTOM,
        )
        if (required.any { main.getLandmark(it) == null }) return FrameCheck(QualityIssue.OCCLUDED, metrics = info.toString())
        if (!landmarksPlausible(main)) return FrameCheck(QualityIssue.OCCLUDED, metrics = info.toString())

        // 6. Глаза открыты (заодно отсекает тёмные очки).
        val le = main.leftEyeOpenProbability
        val re = main.rightEyeOpenProbability
        info.append(" · глаза %.2f/%.2f".format(le ?: -1f, re ?: -1f))
        if (le == null || re == null || le < 0.5f || re < 0.5f) {
            return FrameCheck(QualityIssue.EYES_CLOSED, metrics = info.toString())
        }

        // 7. Выровненное лицо: свет и резкость.
        val crop = align(frame, main) ?: return FrameCheck(QualityIssue.OCCLUDED, metrics = info.toString())
        val (fMean, fStd) = lumaStats(crop, 16, 16, 96, 96)
        val sharp = laplacianVariance(crop)
        info.append(" · свет %.0f±%.0f · резк %.0f".format(fMean, fStd, sharp))
        if (fMean < 45f) return FrameCheck(QualityIssue.TOO_DARK, metrics = info.toString())
        if (fMean > 215f) return FrameCheck(QualityIssue.TOO_BRIGHT, metrics = info.toString())
        // Ровное пятно на месте лица (ладонь, листок) — слабая текстура.
        if (fStd < 14f) return FrameCheck(QualityIssue.OCCLUDED, metrics = info.toString())
        if (sharp < settings.minSharpness) return FrameCheck(QualityIssue.BLURRY, metrics = info.toString())

        return FrameCheck(null, crop, info.toString())
    }

    /** Глаза выше носа, нос выше рта, расстояние между глазами разумное — иначе ML Kit «додумал» лицо. */
    private fun landmarksPlausible(face: Face): Boolean {
        val le = face.getLandmark(FaceLandmark.LEFT_EYE)!!.position
        val re = face.getLandmark(FaceLandmark.RIGHT_EYE)!!.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)!!.position
        val mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)!!.position
        val eyeY = (le.y + re.y) / 2
        val eyeDist = hypot(le.x - re.x, le.y - re.y)
        val w = face.boundingBox.width()
        return eyeY < nose.y && nose.y < mouth.y && eyeDist > w * 0.25f && eyeDist < w * 0.65f
    }

    /** Выравнивание по глазам в стандартную раскладку ArcFace 112×112, на которой обучен MobileFaceNet. */
    private fun align(frame: Bitmap, face: Face): Bitmap? {
        // LEFT_EYE в ML Kit — левый глаз самого человека, на снимке он справа.
        val subjLeft = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val subjRight = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val dx = subjLeft.x - subjRight.x
        val dy = subjLeft.y - subjRight.y
        val dist = hypot(dx, dy)
        if (dist < 8f) return null
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val scale = (73.53f - 38.29f) / dist

        val m = Matrix().apply {
            postTranslate(-subjRight.x, -subjRight.y)
            postRotate(-angle)
            postScale(scale, scale)
            postTranslate(38.29f, 51.69f)
        }
        val out = Bitmap.createBitmap(ALIGNED_SIZE, ALIGNED_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(frame, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    companion object {
        const val ALIGNED_SIZE = 112
    }
}

/** Средняя яркость и её разброс по сетке пикселей прямоугольника. */
fun lumaStats(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Pair<Float, Float> {
    val step = maxOf(1, min(right - left, bottom - top) / 40)
    var sum = 0.0; var sq = 0.0; var n = 0
    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val l = luma(bmp.getPixel(x, y))
            sum += l; sq += l * l; n++
            x += step
        }
        y += step
    }
    if (n == 0) return 0f to 0f
    val mean = sum / n
    return mean.toFloat() to sqrt(maxOf(0.0, sq / n - mean * mean)).toFloat()
}

/** Дисперсия лапласиана — стандартная мера резкости: смаз и расфокус дают маленькое значение. */
fun laplacianVariance(bmp: Bitmap): Float {
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val g = FloatArray(px.size) { luma(px[it]).toFloat() }
    var sum = 0.0; var sq = 0.0; var n = 0
    // Только центральная часть, чтобы края и фон не влияли.
    for (y in h / 6 until h - h / 6) for (x in w / 6 until w - w / 6) {
        val i = y * w + x
        val lap = g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]
        sum += lap; sq += lap * lap; n++
    }
    val mean = sum / n
    return (sq / n - mean * mean).toFloat()
}

private fun luma(p: Int): Int = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

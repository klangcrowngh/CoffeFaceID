package com.coffeebot.faceid.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * MobileFaceNet: выровненное лицо 112×112 → L2-нормированный вектор (192 числа).
 * Размеры входа/выхода читаются из модели, чтобы её можно было заменить без правки кода.
 */
class FaceEmbedder(context: Context, assetName: String = "mobile_face_net.tflite") {

    private val interpreter: Interpreter
    private val batch: Int
    val inputSize: Int
    val dim: Int

    init {
        val fd = context.assets.openFd(assetName)
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        val inShape = interpreter.getInputTensor(0).shape() // [N, 112, 112, 3]
        batch = inShape[0]
        inputSize = inShape[1]
        dim = interpreter.getOutputTensor(0).shape()[1]
    }

    @Synchronized
    fun embed(face: Bitmap): FloatArray {
        val img = if (face.width == inputSize && face.height == inputSize) face
        else Bitmap.createScaledBitmap(face, inputSize, inputSize, true)

        val pixels = IntArray(inputSize * inputSize)
        img.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val input = ByteBuffer.allocateDirect(4 * batch * pixels.size * 3).order(ByteOrder.nativeOrder())
        repeat(batch) {
            for (p in pixels) {
                input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        input.rewind()

        val output = Array(batch) { FloatArray(dim) }
        interpreter.run(input, output)
        return output[0].normalized()
    }
}

fun FloatArray.normalized(): FloatArray {
    var n = 0f
    for (v in this) n += v * v
    val inv = 1f / (sqrt(n) + 1e-9f)
    return FloatArray(size) { this[it] * inv }
}

fun averageEmbedding(list: List<FloatArray>): FloatArray {
    val out = FloatArray(list.first().size)
    for (e in list) for (i in out.indices) out[i] += e[i]
    return out.normalized()
}

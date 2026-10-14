package com.coffeebot.faceid.data

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FaceMatch(val id: Long, val similarity: Float)

data class StoredFace(val id: Long, val createdAt: Long, val thumb: Bitmap?, val side: String?, val slot: Int?)

/** Отпечатки лиц тех, кто уже получил кофе. Векторы держим в памяти — поиск по сотням записей занимает микросекунды. */
class FaceRepository(private val db: AppDatabase) {

    private val cache = mutableListOf<Pair<Long, FloatArray>>()

    init {
        db.readableDatabase.rawQuery("SELECT id, embedding FROM faces", null).use { c ->
            while (c.moveToNext()) cache += c.getLong(0) to c.getBlob(1).toFloats()
        }
    }

    @Synchronized
    fun count(): Int = cache.size

    @Synchronized
    fun bestMatch(embedding: FloatArray): FaceMatch? =
        cache.maxByOrNull { cosine(it.second, embedding) }
            ?.let { FaceMatch(it.first, cosine(it.second, embedding)) }

    @Synchronized
    fun add(embedding: FloatArray, thumb: Bitmap?, side: Side, slot: Int): Long {
        val id = db.writableDatabase.insert("faces", null, ContentValues().apply {
            put("embedding", embedding.toBytes())
            put("thumb", thumb?.toJpeg())
            put("side", side.id)
            put("slot", slot)
            put("created_at", System.currentTimeMillis())
        })
        cache += id to embedding
        DataChanges.bump()
        return id
    }

    @Synchronized
    fun clear() {
        db.writableDatabase.delete("faces", null, null)
        cache.clear()
        DataChanges.bump()
    }

    fun recent(limit: Int): List<StoredFace> =
        db.readableDatabase.rawQuery(
            "SELECT id, created_at, thumb, side, slot FROM faces ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val bytes = if (c.isNull(2)) null else c.getBlob(2)
                    add(
                        StoredFace(
                            id = c.getLong(0),
                            createdAt = c.getLong(1),
                            thumb = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                            side = c.getString(3),
                            slot = if (c.isNull(4)) null else c.getInt(4),
                        )
                    )
                }
            }
        }

    fun thumbJpeg(id: Long): ByteArray? =
        db.readableDatabase.rawQuery("SELECT thumb FROM faces WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null
        }
}

fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var na = 0f; var nb = 0f
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb) + 1e-9f)
}

private fun FloatArray.toBytes(): ByteArray =
    ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> forEach { b.putFloat(it) } }.array()

private fun ByteArray.toFloats(): FloatArray {
    val b = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { b.getFloat() }
}

private fun Bitmap.toJpeg(): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()

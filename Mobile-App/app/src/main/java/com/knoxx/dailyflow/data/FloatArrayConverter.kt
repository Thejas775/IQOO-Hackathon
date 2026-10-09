package com.knoxx.dailyflow.data

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room has no vector column type, so embeddings are stored as a packed float
 * BLOB.
 *
 * Byte order is pinned to **little-endian**, not `nativeOrder()`. Wick used
 * the native order while its own comment claimed little-endian; that
 * round-trips correctly on any single device, so the bug is invisible — but it
 * means the blob is not a portable format. Pinning it makes the stored bytes
 * mean the same thing everywhere, which matters the moment a database is
 * copied, backed up, or read by anything other than the process that wrote it.
 *
 * The methods live in the companion object so they are callable outside Room's
 * own column conversion. That is not stylistic: a raw `FloatArray` passed as an
 * `@Query` parameter gets expanded by Room into one bind argument *per float*,
 * as if for an `IN (:list)` clause, instead of being routed through this
 * converter — producing invalid SQL. Callers doing a column-scoped update must
 * pack to a ByteArray themselves first (see [MemoryDao.updateEmbedding]).
 */
class FloatArrayConverter {

    companion object {
        @TypeConverter
        @JvmStatic
        fun fromFloatArray(value: FloatArray?): ByteArray? {
            value ?: return null
            val buffer = ByteBuffer
                .allocate(value.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            value.forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        @TypeConverter
        @JvmStatic
        fun toFloatArray(bytes: ByteArray?): FloatArray? {
            bytes ?: return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
        }
    }
}

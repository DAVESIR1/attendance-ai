/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes one or more face embeddings without JSON or platform-specific
 * object serialization.
 *
 * Format: embedding count, followed by each embedding's float count and its
 * IEEE-754 float values, all as big-endian 32-bit values. Malformed input is
 * rejected before any partial result is returned.
 */
object EmbeddingCodec {
    fun encode(embeddings: List<FloatArray>): ByteArray {
        require(embeddings.size <= MAX_EMBEDDINGS) { "too many embeddings" }
        var bytes = INT_BYTES
        for (embedding in embeddings) {
            require(embedding.size <= MAX_DIMENSION) { "embedding is too large" }
            bytes = Math.addExact(bytes, INT_BYTES)
            bytes = Math.addExact(bytes, Math.multiplyExact(embedding.size, FLOAT_BYTES))
        }
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(embeddings.size)
        embeddings.forEach { embedding ->
            buffer.putInt(embedding.size)
            embedding.forEach(buffer::putFloat)
        }
        return buffer.array()
    }

    fun decode(encoded: ByteArray): List<FloatArray> {
        require(encoded.size >= INT_BYTES) { "embedding data is truncated" }
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.int
        require(count in 0..MAX_EMBEDDINGS) { "invalid embedding count" }
        val result = ArrayList<FloatArray>(count)
        repeat(count) {
            require(buffer.remaining() >= INT_BYTES) { "embedding dimension is truncated" }
            val dimension = buffer.int
            require(dimension in 1..MAX_DIMENSION) { "invalid embedding dimension" }
            val required = Math.multiplyExact(dimension, FLOAT_BYTES)
            require(buffer.remaining() >= required) { "embedding values are truncated" }
            result += FloatArray(dimension) { buffer.float }
        }
        require(!buffer.hasRemaining()) { "trailing bytes in embedding data" }
        return result
    }

    private const val INT_BYTES = 4
    private const val FLOAT_BYTES = 4
    private const val MAX_EMBEDDINGS = 64
    private const val MAX_DIMENSION = 4096
}

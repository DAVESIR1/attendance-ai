/*
 * Attendance AI — encrypted local attendance database tests.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingCodecTest {
    @Test
    fun `one embedding round trips`() {
        val input = listOf(floatArrayOf(0.1f, -2.5f, 3.75f))
        val output = EmbeddingCodec.decode(EmbeddingCodec.encode(input))

        assertEquals(1, output.size)
        assertArrayEquals(input[0], output[0], 0f)
    }

    @Test
    fun `multiple embeddings round trip`() {
        val input = (1..5).map { index ->
            FloatArray(8) { dimension -> (index * 100 + dimension).toFloat() }
        }
        val output = EmbeddingCodec.decode(EmbeddingCodec.encode(input))

        assertEquals(input.size, output.size)
        input.indices.forEach { index ->
            assertArrayEquals(input[index], output[index], 0f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated data is rejected`() {
        EmbeddingCodec.decode(byteArrayOf(0, 0, 0, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trailing data is rejected`() {
        val encoded = EmbeddingCodec.encode(listOf(floatArrayOf(1f)))
        EmbeddingCodec.decode(encoded + byteArrayOf(1))
    }
}

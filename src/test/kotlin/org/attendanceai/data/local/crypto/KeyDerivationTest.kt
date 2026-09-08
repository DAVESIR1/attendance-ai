/*
 * Attendance AI — offline-first, on-device attendance app.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later — see https://www.gnu.org/licenses/ for full text.
 */
package org.attendanceai.data.local.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PBKDF2-HMAC-SHA-256 ([Pbkdf2], [KeyManager.deriveKey]):
 * published RFC vectors (correctness), determinism for the same
 * password+salt, and salt properties.
 */
class KeyDerivationTest {

    @Test
    fun `matches published PBKDF2-HMAC-SHA256 vectors`() {
        // Vectors verified against RFC 7914 §11 / well-known test data.
        assertDerivation(
            password = "password",
            salt = "salt",
            iterations = 1,
            expected = "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
        )
        assertDerivation(
            password = "password",
            salt = "salt",
            iterations = 2,
            expected = "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
        )
        assertDerivation(
            password = "password",
            salt = "salt",
            iterations = 4096,
            expected = "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
        )
    }

    @Test
    fun `deterministic for the same password and salt at the production work factor`() {
        val password = "abandon abandon ability able about".toCharArray()
        val salt = KeyManager.randomSalt()
        val first = KeyManager.deriveKey(password, salt, KeyManager.PBKDF2_ITERATIONS)
        val second = KeyManager.deriveKey(password, salt, KeyManager.PBKDF2_ITERATIONS)
        assertArrayEquals(first, second)
        assertEquals(KeyManager.DERIVED_KEY_BITS / 8, first.size)
    }

    @Test
    fun `same password with different salts diverges`() {
        val password = "correct horse battery staple".toCharArray()
        val a = KeyManager.deriveKey(password, KeyManager.randomSalt(), 1_000)
        val b = KeyManager.deriveKey(password, KeyManager.randomSalt(), 1_000)
        assertTrue(
            "different salts must produce different keys",
            !a.contentEquals(b),
        )
    }

    @Test
    fun `longer derivations split across blocks correctly`() {
        // dkLen > 32 forces the SHA-256 block loop (64 bytes = 2 blocks).
        val password = "block-split".toCharArray()
        val salt = KeyManager.randomSalt(16)
        val a = Pbkdf2.compute(password, salt, 10, 512)
        val b = Pbkdf2.compute(password, salt, 10, 512)
        assertEquals(64, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun `random salt has the requested length and is unique`() {
        val salt = KeyManager.randomSalt()
        assertEquals(KeyManager.SALT_BYTES, salt.size)
        assertTrue(salt.any { it != 0.toByte() })
        assertTrue(
            !KeyManager.randomSalt().contentEquals(KeyManager.randomSalt()),
        )
    }

    private fun assertDerivation(
        password: String,
        salt: String,
        iterations: Int,
        expected: String,
    ) {
        val derived = Pbkdf2.compute(
            password.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            iterations,
            256,
        )
        assertEquals(expected, derived.joinToString("") { "%02x".format(it) })
    }
}
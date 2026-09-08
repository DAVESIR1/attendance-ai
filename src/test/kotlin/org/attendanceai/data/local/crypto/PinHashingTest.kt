/*
 * Attendance AI — offline-first, on-device attendance app.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later — see https://www.gnu.org/licenses/ for full text.
 */
package org.attendanceai.data.local.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for salted-PBKDF2 PIN hashing ([PinHasher]) and the
 * escalating lockout curve ([PinRepository.lockoutDelayForFailCount]).
 */
class PinHashingTest {

    @Test
    fun `correct PIN verifies`() {
        val pin = "123456".toCharArray()
        val salt = KeyManager.randomSalt()
        val stored = PinHasher.hash(pin, salt)
        assertTrue(PinHasher.verify("123456".toCharArray(), salt, stored))
    }

    @Test
    fun `incorrect PIN fails verification`() {
        val stored = PinHasher.hash("123456".toCharArray(), KeyManager.randomSalt())
        val salt = KeyManager.randomSalt()
        assertFalse(
            PinHasher.verify("654321".toCharArray(), salt, stored),
        )
    }

    @Test
    fun `verifier differs across salts for the same PIN`() {
        val pin = "987654".toCharArray()
        val first = PinHasher.hash(pin, KeyManager.randomSalt())
        val second = PinHasher.hash(pin, KeyManager.randomSalt())
        assertNotEquals(
            first.joinToString("") { "%02x".format(it) },
            second.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `verifier is 256 bits and never equals the PIN digits`() {
        val stored = PinHasher.hash("1111".toCharArray(), KeyManager.randomSalt())
        assertEquals(KeyManager.DERIVED_KEY_BITS / 8, stored.size)
        assertFalse(
            stored.toString(Charsets.UTF_8) == "1111",
        )
    }

    @Test
    fun `missing stored verifier fails closed`() {
        val salt = KeyManager.randomSalt()
        assertFalse(PinHasher.verify("1234".toCharArray(), salt, null))
    }

    @Test
    fun `iterations parameter is honored - same inputs same output`() {
        val pin = "246810".toCharArray()
        val salt = KeyManager.randomSalt(16)
        val a = PinHasher.hash(pin, salt, iterations = 1_000)
        val b = PinHasher.hash(pin, salt, iterations = 1_000)
        assertArrayEquals(a, b)
        assertNotEquals(
            a.joinToString("") { "%02x".format(it) },
            PinHasher.hash(pin, salt, iterations = 2_000)
                .joinToString("") { "%02x".format(it) },
        )
    }

    // ---- escalating lockout curve (after 5 wrong attempts) ------------------------

    @Test
    fun `first four failures do not lock`() {
        (1..4).forEach { count ->
            assertEquals("failure #$count", 0L, PinRepository.lockoutDelayForFailCount(count))
        }
    }

    @Test
    fun `fifth failure locks for 30 seconds`() {
        assertEquals(30_000L, PinRepository.lockoutDelayForFailCount(5))
    }

    @Test
    fun `sixth failure locks for 60 seconds`() {
        assertEquals(60_000L, PinRepository.lockoutDelayForFailCount(6))
    }

    @Test
    fun `seventh and later failures lock for 5 minutes`() {
        assertEquals(300_000L, PinRepository.lockoutDelayForFailCount(7))
        assertEquals(300_000L, PinRepository.lockoutDelayForFailCount(8))
        assertEquals(300_000L, PinRepository.lockoutDelayForFailCount(50))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
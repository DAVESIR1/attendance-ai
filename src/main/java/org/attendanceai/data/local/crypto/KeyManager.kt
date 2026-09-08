/*
 * Attendance AI — offline-first, on-device attendance app.
 * Copyright (C) 2026 The Attendance AI Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See https://www.gnu.org/licenses/ for the full license text.
 */
package org.attendanceai.data.local.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security role: PBKDF2-HMAC-SHA-256 (RFC 8018) implemented directly on
 * `javax.crypto.Mac` instead of `SecretKeyFactory("PBKDF2WithHmacSHA256")`,
 * which Android only exposes from API 26 — this app supports API 24.
 *
 * Used for two distinct purposes:
 *  1. Vault-key derivation: mnemonic sentence (as UTF-8 chars) + random
 *     salt, [KeyManager.PBKDF2_ITERATIONS] (210,000) iterations → 256-bit key.
 *  2. PIN hashing: PIN digits + random salt → stored verifier.
 *
 * Deliberately self-contained so the identical algorithm runs in JVM unit
 * tests and on device. Outputs are secrets: zeroise when practical, never
 * log them, in any build variant.
 */
object Pbkdf2 {

    /**
     * Computes PBKDF2-HMAC-SHA-256. Password is encoded UTF-8 from the
     * [CharArray] without an intermediate [String] (smaller secret
     * footprint). Deterministic for identical (password, salt, iterations,
     * keyBits); never equal across different salts.
     */
    fun compute(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        require(password.isNotEmpty()) { "empty password" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(keyBits > 0 && keyBits % 8 == 0) { "keyBits must be a positive multiple of 8" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val passwordBytes = ByteArray(encoded.remaining())
        encoded.get(passwordBytes)
        try {
            mac.init(SecretKeySpec(passwordBytes, HMAC_ALGORITHM))
            val dkLen = keyBits / 8
            val out = ByteArray(dkLen)
            var offset = 0
            var block = 1
            while (offset < dkLen) {
                mac.update(salt)
                mac.update(intToBytes(block))
                var u = mac.doFinal()
                val t = u.copyOf()
                for (i in 2..iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                }
                val copy = minOf(t.size, dkLen - offset)
                System.arraycopy(t, 0, out, offset, copy)
                offset += copy
                block++
            }
            return out
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private const val HMAC_ALGORITHM = "HmacSHA256"
}

/**
 * Security role: custodian of the vault master key. The raw 256-bit key
 * derived from the recovery phrase lives in RAM only long enough to be
 * **wrapped** (AES-GCM-encrypted) by a hardware-backed Android Keystore
 * key; only the wrapped blob is persisted (by [PinRepository]). At unlock
 * time the blob is unwrapped in memory, used, and discarded.
 *
 * The Keystore wrapping key never leaves secure hardware, so a stolen
 * wrapped blob alone is useless without the device. Wrap/unwrap cannot be
 * exercised in JVM unit tests (AndroidKeyStore is device-only) — the class
 * is deliberately thin so all surrounding logic stays testable.
 *
 * Properties: randomized GCM IV per wrap (Keystore-enforced), GCM tag
 * verifies blob integrity on unwrap (tamper → `AEADBadTagException`), and
 * [deleteWrapKey] destroys only the hardware key — nothing user-owned is
 * ever destructively wiped here.
 */
class KeyManager {

    /** AES-GCM wrapped key: 12-byte IV + ciphertext (incl. 128-bit tag). */
    data class WrappedKey(val iv: ByteArray, val ciphertext: ByteArray) {

        /** `Base64(iv) + ":" + Base64(ciphertext)` — stored in encrypted prefs. */
        fun encode(): String =
            Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)

        companion object {
            private const val SEPARATOR = ":"

            /** @throws IllegalArgumentException on malformed encoding. */
            fun decode(encoded: String): WrappedKey {
                val parts = encoded.split(SEPARATOR)
                require(parts.size == 2) { "malformed wrapped key" }
                return WrappedKey(
                    Base64.decode(parts[0], Base64.NO_WRAP),
                    Base64.decode(parts[1], Base64.NO_WRAP),
                )
            }
        }
    }

    /**
     * Encrypts [rawKey] under the AndroidKeyStore AES-GCM key. Every call
     * yields a fresh IV (randomized encryption required by the key spec).
     */
    fun wrap(rawKey: ByteArray): WrappedKey {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ciphertext = cipher.doFinal(rawKey)
        return WrappedKey(cipher.iv.clone(), ciphertext)
    }

    /**
     * Decrypts a [WrappedKey] produced by [wrap]. Throws
     * `AEADBadTagException` when the blob was tampered with — callers
     * must fail closed.
     */
    fun unwrap(wrapped: WrappedKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
        return cipher.doFinal(wrapped.ciphertext)
    }

    /** True when the Keystore wrapping key exists. */
    fun hasWrapKey(): Boolean {
        val keyStore = keyStore()
        keyStore.load(null)
        return keyStore.containsAlias(KEY_ALIAS)
    }

    /** Destroys the Keystore wrapping key (recovery requires re-setup). */
    fun deleteWrapKey() {
        val keyStore = keyStore()
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun obtainKey(): SecretKey {
        val keyStore = keyStore()
        keyStore.load(null)
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE)

    companion object {
        /** Work factor mandated by the plan for key derivation. */
        const val PBKDF2_ITERATIONS = 210_000

        /** Vault key length: 256 bits. */
        const val DERIVED_KEY_BITS = 256

        /** Random salt length: 32 bytes. */
        const val SALT_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "attendance_ai_vault_wrap_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Derives the 256-bit vault key with PBKDF2-HMAC-SHA-256 over
         * [secretChars] (mnemonic words or PIN) and [salt]. The result is
         * a raw key — callers wrap it immediately and zero it after use.
         * Never logged, never persisted unwrapped.
         */
        fun deriveKey(
            secretChars: CharArray,
            salt: ByteArray,
            iterations: Int = PBKDF2_ITERATIONS,
            keyBits: Int = DERIVED_KEY_BITS,
        ): ByteArray = Pbkdf2.compute(secretChars, salt, iterations, keyBits)

        /** Cryptographically random salt of [bytes] length. */
        fun randomSalt(bytes: Int = SALT_BYTES): ByteArray {
            require(bytes > 0)
            val salt = ByteArray(bytes)
            SecureRandom().nextBytes(salt)
            return salt
        }
    }
}
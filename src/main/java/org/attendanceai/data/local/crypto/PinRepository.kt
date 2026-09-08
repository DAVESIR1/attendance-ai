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

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Security role: persisted state of the Phase-1 vault, stored in
 * [EncryptedSharedPreferences] (at-rest encryption keyed by the Android
 * Keystore master key). Holds exactly five facts — the PIN *verifier*
 * (never the PIN), the PIN lockout counters, the setup stage, the
 * biometric opt-in flag, and the Keystore-wrapped vault key + its PBKDF2
 * salt. Plaintext PIN digits, mnemonic words and raw keys never enter
 * this store.
 */
enum class SetupStage {
    /** No vault yet — next step is mnemonic display. */
    NONE,

    /** A phrase was confirmed/restored and its key wrapped — next: PIN. */
    MNEMONIC_CONFIRMED,

    /** PIN verifier stored — next: biometric opt-in. */
    PIN_SET,

    /** Setup finished; app starts at the PIN/biometric unlock screen. */
    COMPLETE,
}

/** Persisted reference to the Keystore-wrapped vault key + its salt. */
data class VaultKeyBlob(val wrapped: String, val salt: String)

/**
 * Security role: pure PIN verifier logic (kept object-static so JVM unit
 * tests can exercise it without Android). A PIN is never stored: a fresh
 * 32-byte random salt is generated per PIN and PBKDF2-HMAC-SHA-256
 * (210,000 iterations) produces the stored verifier. Verification
 * recomputes the hash and compares with [MessageDigest.isEqual] — a
 * constant-time comparison that does not leak how many leading bytes
 * matched.
 */
object PinHasher {

    /** PBKDF2 verifier of [pin] under [salt] (256-bit output). */
    fun hash(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int = KeyManager.PBKDF2_ITERATIONS,
    ): ByteArray = Pbkdf2.compute(pin, salt, iterations, KeyManager.DERIVED_KEY_BITS)

    /**
     * Constant-time verification of [pin] against [expectedHash].
     * Constant time even when the stored verifier is absent/short.
     */
    fun verify(
        pin: CharArray,
        salt: ByteArray,
        expectedHash: ByteArray?,
        iterations: Int = KeyManager.PBKDF2_ITERATIONS,
    ): Boolean {
        if (expectedHash == null || salt.isEmpty()) return false
        val computed = hash(pin, salt, iterations)
        val equal = MessageDigest.isEqual(computed, expectedHash)
        computed.fill(0)
        return equal
    }
}

/**
 * Security role: the only component that talks to the vault's encrypted
 * persistence. Every accessor is a narrow, least-privilege operation;
 * there is deliberately **no** API to read a PIN back or to wipe roster/
 * attendance data — wrong PIN attempts only extend the lockout deadline,
 * never destroying anything ("never data-destructive" requirement).
 *
 * Lockout policy: after 5 consecutive wrong PINs a 30 s delay applies,
 * the 6th wrong attempt makes it 60 s, and any further attempt 5 minutes
 * (see [lockoutDelayForFailCount]). Counters persist across process
 * death, so restarting the app cannot bypass a running delay.
 *
 * @throws IllegalStateException if the encrypted store cannot be created
 * (corrupted keystore after a backup/restore) — callers must fail closed.
 */
class PinRepository(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ---- setup stage (survives process death) --------------------------------

    fun setupStage(): SetupStage =
        SetupStage.entries.firstOrNull { it.name == prefs.getString(KEY_STAGE, null) }
            ?: SetupStage.NONE

    fun setSetupStage(stage: SetupStage) {
        prefs.edit().putString(KEY_STAGE, stage.name).apply()
    }

    // ---- PIN verifier ----------------------------------------------------------

    fun isPinSet(): Boolean = prefs.getString(KEY_PIN_HASH, null) != null

    /**
     * Stores a fresh verifier for [pin] (salted PBKDF2, 210,000 iters).
     * The PIN char array is not retained; the caller clears its copy.
     */
    fun setPin(pin: CharArray) {
        val salt = KeyManager.randomSalt()
        val hash = PinHasher.hash(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_HASH, toBase64(hash))
            .putString(KEY_PIN_SALT, toBase64(salt))
            .putInt(KEY_PIN_ITERS, KeyManager.PBKDF2_ITERATIONS)
            .apply()
        hash.fill(0)
    }

    /** Constant-time PIN check against the stored verifier. */
    fun verifyPin(pin: CharArray): Boolean {
        val hashB64 = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val iterations = prefs.getInt(KEY_PIN_ITERS, KeyManager.PBKDF2_ITERATIONS)
        return PinHasher.verify(pin, fromBase64(saltB64), fromBase64(hashB64), iterations)
    }

    // ---- PIN lockout (persisted; escalating; never destructive) ---------------

    /** Consecutive wrong PINs recorded (cleared only by a correct PIN). */
    fun failedAttempts(): Int = prefs.getInt(KEY_FAIL_COUNT, 0)

    /**
     * Records one wrong attempt and returns the new lockout deadline in
     * epoch millis (0 = no delay). Escalation: 5th failure → 30 s,
     * 6th → 60 s, 7th and beyond → 5 min (capped).
     */
    fun registerFailedAttempt(nowMillis: Long = System.currentTimeMillis()): Long {
        val count = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        val delay = lockoutDelayForFailCount(count)
        val deadline = if (delay > 0) nowMillis + delay else 0L
        prefs.edit()
            .putInt(KEY_FAIL_COUNT, count)
            .putLong(KEY_LOCKOUT_UNTIL, deadline)
            .apply()
        return deadline
    }

    /** Clears the failure counter (called after a correct PIN). */
    fun resetFailedAttempts() {
        prefs.edit().putInt(KEY_FAIL_COUNT, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply()
    }

    /** Remaining lockout in ms (0 when unlocked). */
    fun remainingLockoutMs(nowMillis: Long = System.currentTimeMillis()): Long =
        (prefs.getLong(KEY_LOCKOUT_UNTIL, 0L) - nowMillis).coerceAtLeast(0L)

    fun isLockedOut(nowMillis: Long = System.currentTimeMillis()): Boolean =
        remainingLockoutMs(nowMillis) > 0

    // ---- biometric opt-in -------------------------------------------------------

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    // ---- Keystore-wrapped vault key ----------------------------------------------

    fun storeWrappedKey(blob: VaultKeyBlob) {
        prefs.edit()
            .putString(KEY_WRAPPED_KEY, blob.wrapped)
            .putString(KEY_KEY_SALT, blob.salt)
            .apply()
    }

    fun wrappedKey(): VaultKeyBlob? {
        val wrapped = prefs.getString(KEY_WRAPPED_KEY, null) ?: return null
        val salt = prefs.getString(KEY_KEY_SALT, null) ?: return null
        return VaultKeyBlob(wrapped, salt)
    }

    fun hasWrappedKey(): Boolean = prefs.getString(KEY_WRAPPED_KEY, null) != null

    // ---- helpers ---------------------------------------------------------------

    private fun toBase64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun fromBase64(text: String): ByteArray =
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)

    companion object {
        private const val PREFS_NAME = "attendance_vault_prefs"
        private const val KEY_STAGE = "vault.setup_stage"
        private const val KEY_PIN_HASH = "vault.pin_hash"
        private const val KEY_PIN_SALT = "vault.pin_salt"
        private const val KEY_PIN_ITERS = "vault.pin_iters"
        private const val KEY_FAIL_COUNT = "vault.pin_fail_count"
        private const val KEY_LOCKOUT_UNTIL = "vault.pin_lockout_until"
        private const val KEY_BIOMETRIC = "vault.biometric_enabled"
        private const val KEY_WRAPPED_KEY = "vault.wrapped_key"
        private const val KEY_KEY_SALT = "vault.key_salt"

        /** 5th failure → 30 s, 6th → 60 s, 7th+ → 5 min (capped). */
        fun lockoutDelayForFailCount(failCount: Int): Long = when {
            failCount < 5 -> 0L
            failCount == 5 -> 30_000L
            failCount == 6 -> 60_000L
            else -> 300_000L
        }
    }
}
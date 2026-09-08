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

/**
 * Outcome of [MnemonicValidator.validate].
 */
sealed interface MnemonicValidation {
    /** Phrase is structurally valid; [entropy] is the recovered entropy. */
    data class Valid(val entropy: ByteArray) : MnemonicValidation

    /** Phrase rejected; [reason] is a safe, human-readable message that
     * never echoes the submitted words. */
    data class Invalid(val reason: String) : MnemonicValidation
}

/**
 * Security role: gatekeeper for user-typed recovery phrases. Before any
 * phrase is accepted by the restore flow it must pass this validator,
 * which enforces the full BIP-39 structure — supported word count
 * (12/15/18/21/24), every word present in [Bip39WordList], and a
 * recomputed SHA-256 checksum match. A phrase failing any check can
 * therefore not correspond to real BIP-39 entropy and is rejected
 * before it can displace an existing key.
 *
 * On success the recovered entropy is returned; the caller derives the
 * vault key from it and immediately discards the words. Error reasons
 * are intentionally coarse (position/count only, never the words) so
 * failed attempts leak nothing about partially-typed phrases.
 */
object MnemonicValidator {

    /** Word counts permitted by BIP-39. */
    val SUPPORTED_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

    /**
     * Validates [words] against [wordList]. Returns [MnemonicValidation.Valid]
     * carrying the recovered entropy when the checksum matches; otherwise
     * [MnemonicValidation.Invalid] with a reason that does not repeat the
     * submitted words.
     */
    fun validate(words: List<String>, wordList: Bip39WordList): MnemonicValidation {
        val normalized = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalized.size !in SUPPORTED_WORD_COUNTS) {
            return MnemonicValidation.Invalid(
                "unsupported length: ${normalized.size} words (use 12, 15, 18, 21 or 24)"
            )
        }
        val indices = IntArray(normalized.size)
        for ((i, word) in normalized.withIndex()) {
            val index = wordList.indexOf(word)
            if (index < 0) {
                return MnemonicValidation.Invalid("unknown word at position ${i + 1}")
            }
            indices[i] = index
        }
        val totalBits = normalized.size * 11
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits
        val entropy = ByteArray(entropyBits / 8)
        for (i in 0 until entropyBits) {
            val bit = (indices[i / 11] shr (10 - i % 11)) and 1
            if (bit == 1) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
        }
        // Re-encode and compare — one shared code path with the generator,
        // so checksum verification cannot drift from checksum creation.
        val reencoded = MnemonicGenerator.fromEntropy(entropy, wordList)
        return if (reencoded == normalized) {
            MnemonicValidation.Valid(entropy)
        } else {
            MnemonicValidation.Invalid("checksum failed — re-check the phrase")
        }
    }

    /** Inverse operation used by tests to build fixed entropy vectors. */
    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4)
                    or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}

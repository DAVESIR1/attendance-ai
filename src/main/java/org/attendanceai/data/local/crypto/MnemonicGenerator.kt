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

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Security role: creates BIP-39 seed phrases from freshly generated
 * entropy. This is the *only* place where new wallet entropy is born.
 *
 * Entropy comes from [SecureRandom]; the BIP-39 checksum (first
 * `ENT/32` bits of SHA-256(entropy)) is appended before the bits are
 * split into 11-bit word indices. Generated phrases are shown exactly
 * once during setup ([org.attendanceai.presentation.lockscreen] step
 * `MnemonicDisplayStep`); this class never persists, caches, or logs
 * entropy or resulting words.
 *
 * The canonical BIP-39 passphrase is the NFKD-normalized mnemonic
 * sentence. The bundled list is English (ASCII), for which NFKD is the
 * identity, so joining words with single spaces is byte-exact.
 */
class MnemonicGenerator(
    private val wordList: Bip39WordList,
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * Generates a new mnemonic with [strengthBits] bits of entropy
     * (128/160/192/224/256 → 12/15/18/21/24 words). Default 128 bits
     * (12 words). Throws [IllegalArgumentException] on other sizes.
     */
    fun generate(strengthBits: Int = DEFAULT_STRENGTH_BITS): List<String> {
        require(strengthBits in SUPPORTED_STRENGTH_BITS) {
            "unsupported strength"
        }
        val entropy = ByteArray(strengthBits / 8)
        random.nextBytes(entropy)
        return fromEntropy(entropy, wordList)
    }

    companion object {
        const val DEFAULT_STRENGTH_BITS = 128

        /** Entropy sizes allowed by BIP-39, in bits. */
        val SUPPORTED_STRENGTH_BITS = setOf(128, 160, 192, 224, 256)

        /**
         * Deterministic BIP-39 encoding: [entropy] + SHA-256 checksum
         * bits → 11-bit indices → words. Pure function of its inputs;
         * also the re-encoding path used by [MnemonicValidator] to
         * verify checksums, so generator and validator can never drift.
         *
         * @throws IllegalArgumentException for sizes outside BIP-39.
         */
        fun fromEntropy(entropy: ByteArray, wordList: Bip39WordList): List<String> {
            require(entropy.size in 16..32 && entropy.size % 4 == 0) {
                "entropy must be 16..32 bytes in 4-byte steps"
            }
            val checksumBits = entropy.size * 8 / 32
            val digest = MessageDigest.getInstance("SHA-256").digest(entropy)
            val wordCount = (entropy.size * 8 + checksumBits) / 11
            val words = ArrayList<String>(wordCount)
            for (w in 0 until wordCount) {
                var index = 0
                repeat(11) { bit ->
                    index = index shl 1
                    index = index or bitAt(entropy, digest, entropy.size * 8, w * 11 + bit)
                }
                words.add(wordList.word(index))
            }
            return words
        }

        /** Bit [bitIndex] of entropy||checksum, MSB-first. */
        private fun bitAt(entropy: ByteArray, hash: ByteArray, entropyBits: Int, bitIndex: Int): Int {
            val source: ByteArray
            val local: Int
            if (bitIndex < entropyBits) {
                source = entropy
                local = bitIndex
            } else {
                source = hash
                local = bitIndex - entropyBits
            }
            // Sign extension only touches bits >= 8; `and 1` isolates the wanted bit.
            return (source[local / 8].toInt() shr (7 - local % 8)) and 1
        }
    }
}

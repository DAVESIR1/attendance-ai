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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Security role: immutable, read-only view of the bundled BIP-39 English
 * wordlist (2048 words) used by [MnemonicGenerator] and [MnemonicValidator].
 *
 * The list is loaded from `src/main/assets/bip39_english.txt` (upstream:
 * trezor/python-mnemonic `wordlist/english.txt`, MIT-licensed; SHA-256
 * `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`).
 * Loading is fail-closed: any deviation from exactly 2048 unique,
 * sorted, lowercase `a–z` words aborts construction — a truncated or
 * tampered wordlist must never silently corrupt seed-phrase encoding.
 *
 * The words themselves are *not* secret (they are a public dictionary),
 * but any mnemonic built from them is; this class holds only dictionary
 * data and never touches entropy or user secrets.
 *
 * @property size always [EXPECTED_SIZE] (2048) on success.
 * @throws IOException when the asset is missing or malformed.
 */
class Bip39WordList private constructor(private val words: List<String>) {

    val size: Int get() = words.size

    /** Word at a BIP-39 index (0..2047). */
    fun word(index: Int): String = words[index]

    /**
     * Index of [word] in the list, or `-1` when unknown. Binary search —
     * the constructor guarantees the list is sorted. Input is normalised
     * (trimmed, lower-cased) so user-typed words match.
     */
    fun indexOf(word: String): Int {
        val normalized = word.trim().lowercase()
        val index = words.binarySearch(normalized)
        return if (index >= 0) index else -1
    }

    companion object {
        /** Asset path of the bundled wordlist. */
        const val ASSET_PATH = "bip39_english.txt"

        /** BIP-39 mandates exactly 2048 words (11-bit indices). */
        const val EXPECTED_SIZE = 2048

        /** Loads and validates the wordlist bundled in `assets/`. */
        fun fromAssets(context: Context): Bip39WordList =
            context.assets.open(ASSET_PATH).use { fromStream(it) }

        /**
         * Parses and validates a wordlist stream (one word per line).
         * Fail-closed on wrong count, duplicates, unsorted entries, or
         * characters outside `a–z`.
         */
        fun fromStream(input: InputStream): Bip39WordList {
            val parsed = ArrayList<String>(EXPECTED_SIZE)
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).useLines { lines ->
                for (raw in lines) {
                    val word = raw.trim()
                    if (word.isEmpty()) continue
                    if (!WORD_PATTERN.matches(word)) {
                        throw IOException("bip39 wordlist contains invalid entry")
                    }
                    parsed.add(word)
                }
            }
            if (parsed.size != EXPECTED_SIZE) {
                throw IOException("bip39 wordlist has ${parsed.size} words, expected $EXPECTED_SIZE")
            }
            for (i in 1 until parsed.size) {
                if (parsed[i] == parsed[i - 1] || parsed[i] < parsed[i - 1]) {
                    throw IOException("bip39 wordlist is not strictly sorted/unique")
                }
            }
            return Bip39WordList(parsed)
        }

        private val WORD_PATTERN = Regex("^[a-z]+$")
    }
}

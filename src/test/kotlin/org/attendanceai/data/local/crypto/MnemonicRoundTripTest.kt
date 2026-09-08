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
import java.io.File
import kotlin.random.Random

/**
 * Unit tests for the BIP-39 encode/decode core: official test vectors,
 * a ≥20-seed random round-trip, wordlist integrity, and rejection of
 * malformed phrases.
 */
class MnemonicRoundTripTest {

    private val wordList: Bip39WordList = loadBundledWordList()

    // ---- official BIP-39 vectors (trezor/python-mnemonic vectors.json, English) --

    @Test
    fun `official vector 12 words zeros`() = assertVector(
        "00000000000000000000000000000000",
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon about",
    )

    @Test
    fun `official vector 12 words ff`() = assertVector(
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
        "legal winner thank year wave sausage worth useful legal winner thank yellow",
    )

    @Test
    fun `official vector 18 words zeros`() = assertVector(
        "000000000000000000000000000000000000000000000000",
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon agent",
    )

    @Test
    fun `official vector 24 words zeros`() = assertVector(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon art",
    )

    @Test
    fun `official vector 18 words mixed`() = assertVector(
        "c10ec20dc3cd9f652c7fac2f1230f7a3c828389a14392f05",
        "scissors invite lock maple supreme raw rapid void congress muscle " +
            "digital elegant little brisk hair mango congress clump",
    )

    @Test
    fun `official vector 24 words mixed`() = assertVector(
        "f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f",
        "void come effort suffer camp survey warrior heavy shoot primary " +
            "clutch crush open amazing screen patrol group space point ten " +
            "exist slush involve unfold",
    )

    private fun assertVector(hexEntropy: String, expectedPhrase: String) {
        val entropy = MnemonicValidator.fromHex(hexEntropy)
        val expected = expectedPhrase.split(" ")
        val encoded = MnemonicGenerator.fromEntropy(entropy, wordList)
        assertEquals(expectedPhrase, encoded.joinToString(" "))
        assertEquals(expected, encoded)
        val recovered = MnemonicValidator.validate(encoded, wordList)
        assertArrayEquals(entropy, (recovered as MnemonicValidation.Valid).entropy)
    }

    // ---- random round-trip: generate -> words -> validate -> same entropy --------

    @Test
    fun `round trip over 24 random seeds recovers the original entropy`() {
        repeat(24) { seed ->
            val strengthBits = listOf(128, 160, 192, 224, 256)[seed % 5]
            val entropy = ByteArray(strengthBits / 8)
            Random(seed * 7919 + 13).nextBytes(entropy)

            val mnemonic = MnemonicGenerator.fromEntropy(entropy, wordList)
            val expectedWords = strengthBits / 32 * 3
            assertEquals("word count for seed $seed", expectedWords, mnemonic.size)

            when (val result = MnemonicValidator.validate(mnemonic, wordList)) {
                is MnemonicValidation.Valid ->
                    assertArrayEquals("entropy for seed $seed", entropy, result.entropy)
                is MnemonicValidation.Invalid ->
                    throw AssertionError("round trip failed for seed $seed: ${result.reason}")
            }
        }
    }

    @Test
    fun `generate produces usable phrases via SecureRandom path`() {
        val generator = MnemonicGenerator(wordList)
        repeat(5) {
            val mnemonic = generator.generate()
            assertTrue(
                "generated phrase must validate",
                MnemonicValidator.validate(mnemonic, wordList)
                    is MnemonicValidation.Valid,
            )
        }
    }

    // ---- rejection paths ----------------------------------------------------------

    @Test
    fun `wrong checksum is rejected`() {
        val valid = MnemonicGenerator.fromEntropy(
            MnemonicValidator.fromHex("00000000000000000000000000000000"),
            wordList,
        )
        val corrupted = valid.toMutableList().also { it[0] = "zoo" }
        assertTrue(
            MnemonicValidator.validate(corrupted, wordList) is MnemonicValidation.Invalid,
        )
    }

    @Test
    fun `unknown word is rejected without echoing it`() {
        val words = listOf("notaword") + List(11) { "abandon" }
        val result = MnemonicValidator.validate(words, wordList)
        assertTrue(result is MnemonicValidation.Invalid)
        val reason = (result as MnemonicValidation.Invalid).reason
        assertTrue("reason must not echo the word", !reason.contains("notaword"))
    }

    @Test
    fun `unsupported word counts are rejected`() {
        listOf(11, 13, 25).forEach { count ->
            val words = List(count) { "abandon" }
            assertTrue(
                "count $count must be rejected",
                MnemonicValidator.validate(words, wordList) is MnemonicValidation.Invalid,
            )
        }
    }

    // ---- bundled wordlist integrity ------------------------------------------------

    @Test
    fun `bundled wordlist is the canonical 2048 word list`() {
        assertEquals(Bip39WordList.EXPECTED_SIZE, wordList.size)
        assertEquals("abandon", wordList.word(0))
        assertEquals("zoo", wordList.word(2047))
        assertEquals(0, wordList.indexOf("abandon"))
        assertEquals(-1, wordList.indexOf("notaword"))
    }

    private companion object {
        /**
         * Loads the same asset file the app packages. Unit tests run with
         * the module directory as the working directory; fall back to the
         * repo-root-relative path for other working directories.
         */
        fun loadBundledWordList(): Bip39WordList {
            val candidates = listOf(
                File("src/main/assets/${Bip39WordList.ASSET_PATH}"),
                File("attendance-ai/src/main/assets/${Bip39WordList.ASSET_PATH}"),
            )
            val file = candidates.firstOrNull(File::isFile)
                ?: throw AssertionError(
                    "bundled wordlist not found; tried ${candidates.map(File::getAbsolutePath)}"
                )
            return file.inputStream().use(Bip39WordList::fromStream)
        }
    }
}
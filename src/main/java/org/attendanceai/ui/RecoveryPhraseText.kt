/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.ui

/**
 * Host-JVM-testable text model for the "View recovery phrase" screen.
 *
 * Why an explanation instead of the words: Phase 1 generates the BIP-39
 * phrase exactly once at setup, derives the vault key from it, persists only a
 * Keystore-wrapped key blob plus salt, and then wipes the words
 * ([org.attendanceai.presentation.lockscreen.LockScreenViewModel]
 * `onMnemonicConfirmed` → `installPhrase`). PBKDF2 is one-way, so no existing
 * Phase-1 class can re-display the phrase — and the task explicitly forbids
 * adding new crypto (i.e. storing the phrase) to fake it. The honest,
 * privacy-preserving answer is therefore to re-authenticate (proving the
 * requester is the owner) and to explain the design plainly.
 */
object RecoveryPhraseText {

    /** Screen heading. */
    const val TITLE: String = "Recovery phrase"

    /** Shown first: the re-auth actually happened and why the screen exists. */
    const val REAUTH_NOTE: String =
        "You re-authenticated with your PIN or biometrics to open this screen."

    /** Fact 1: the phrase is shown once, at setup. */
    const val SHOWN_ONCE: String =
        "For your security, the recovery phrase was shown exactly once — during " +
            "first-time setup — and then erased from memory."

    /** Fact 2: it is never stored, so it cannot be re-displayed. */
    const val NEVER_STORED: String =
        "It is never written to the device. The app keeps only a key that is " +
            "wrapped by this device's secure hardware, so confirming your " +
            "identity here cannot reproduce words that were never stored."

    /** Fact 3: when it is actually needed. */
    const val WHEN_NEEDED: String =
        "You need the phrase only after reinstalling the app, a factory reset, " +
            "or moving to a new device."

    /** Fact 4: it is not needed for daily use. */
    const val NOT_NEEDED_DAILY: String =
        "For normal daily use you do not need it — your PIN or biometrics are enough."

    /** Fact 5: what happens if it was not saved. */
    const val IF_LOST: String =
        "If you never saved it, the app keeps working on this device, but the " +
            "stored data cannot be restored on a new device once this install is gone."

    /** Fact 6: reassurance that viewing the screen is side-effect free. */
    const val NOTHING_CHANGED: String =
        "Nothing was changed by opening this screen."

    /** Body paragraphs, in display order. */
    fun paragraphs(): List<String> = listOf(
        REAUTH_NOTE,
        SHOWN_ONCE,
        NEVER_STORED,
        WHEN_NEEDED,
        NOT_NEEDED_DAILY,
        IF_LOST,
        NOTHING_CHANGED,
    )
}

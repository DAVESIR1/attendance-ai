/*
 * Attendance AI — recovery-phrase screen text tests (host JVM).
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the honesty of the "View recovery phrase" screen: it must not imply
 * the phrase can be shown (Phase 1 never stores it), and it must still tell the
 * user when the phrase is needed and that nothing changed.
 */
class RecoveryPhraseTextTest {

    @Test
    fun titleIsTheRecoveryPhraseScreen() {
        assertEquals("Recovery phrase", RecoveryPhraseText.TITLE)
    }

    @Test
    fun paragraphsAreAllNonBlank() {
        assertTrue(RecoveryPhraseText.paragraphs().isNotEmpty())
        assertTrue(RecoveryPhraseText.paragraphs().all { it.isNotBlank() })
    }

    @Test
    fun firstParagraphConfirmsReAuthentication() {
        assertTrue(RecoveryPhraseText.paragraphs().first() == RecoveryPhraseText.REAUTH_NOTE)
        assertTrue(RecoveryPhraseText.REAUTH_NOTE.contains("re-authenticated"))
    }

    @Test
    fun explanationStatesThePhraseIsShownOnceAndNeverStored() {
        val body = RecoveryPhraseText.paragraphs().joinToString(" ")
        assertTrue(body.contains("shown exactly once"))
        assertTrue(body.contains("never written to the device"))
    }

    @Test
    fun explanationSaysHowToGetThePhraseBackIsNotPossible() {
        val body = RecoveryPhraseText.paragraphs().joinToString(" ")
        // The screen must not promise that the words can be reproduced.
        assertTrue(body.contains("cannot reproduce words that were never stored"))
    }

    @Test
    fun explanationSaysWhenThePhraseIsNeeded() {
        val body = RecoveryPhraseText.paragraphs().joinToString(" ")
        assertTrue(body.contains("reinstalling"))
        assertTrue(body.contains("factory reset"))
        assertTrue(body.contains("new device"))
    }

    @Test
    fun explanationReassuresDailyUseNeedsNoPhrase() {
        val body = RecoveryPhraseText.paragraphs().joinToString(" ")
        assertTrue(body.contains("PIN or biometrics"))
    }

    @Test
    fun viewingTheScreenChangesNothing() {
        assertTrue(RecoveryPhraseText.NOTHING_CHANGED.contains("Nothing was changed"))
    }
}

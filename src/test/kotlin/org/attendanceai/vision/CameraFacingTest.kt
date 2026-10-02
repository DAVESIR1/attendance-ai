/*
 * Attendance AI — camera-facing state tests (host JVM).
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure-JVM camera-switch state used by the main screen's
 * flip-camera button: it must start on the front camera (the app's
 * original default, so behaviour without taps is unchanged) and toggle
 * to the other selector on every tap. CameraX itself — binding the real
 * [androidx.camera.core.CameraSelector] — cannot run on a host JVM and is
 * verified manually on the phone (front preview ⇄ back preview, enrolment
 * still works on both).
 */
class CameraFacingTest {

    @Test
    fun stateStartsOnFrontCameraSoDefaultBehaviourIsUnchanged() {
        assertEquals(CameraFacing.FRONT, CameraFacingState().facing)
    }

    @Test
    fun explicitInitialFacingIsRespected() {
        assertEquals(CameraFacing.BACK, CameraFacingState(CameraFacing.BACK).facing)
    }

    @Test
    fun firstToggleSwitchesFromFrontToBack() {
        val state = CameraFacingState()

        assertEquals(CameraFacing.BACK, state.toggle())
        assertEquals(CameraFacing.BACK, state.facing)
    }

    @Test
    fun secondToggleSwitchesBackToFront() {
        val state = CameraFacingState()

        state.toggle()

        assertEquals(CameraFacing.FRONT, state.toggle())
        assertEquals(CameraFacing.FRONT, state.facing)
    }

    @Test
    fun repeatedTogglesAlternateBetweenTheTwoSelectors() {
        val state = CameraFacingState()
        val seen = mutableListOf<CameraFacing>()
        repeat(6) { seen.add(state.toggle()) }

        assertEquals(
            listOf(
                CameraFacing.BACK, CameraFacing.FRONT,
                CameraFacing.BACK, CameraFacing.FRONT,
                CameraFacing.BACK, CameraFacing.FRONT,
            ),
            seen,
        )
    }

    @Test
    fun statesAreIndependentOfEachOther() {
        val first = CameraFacingState()
        val second = CameraFacingState()

        first.toggle()

        assertEquals(CameraFacing.BACK, first.facing)
        assertEquals(CameraFacing.FRONT, second.facing)
    }

    @Test
    fun oppositeMapsEachFacingToTheOther() {
        assertEquals(CameraFacing.BACK, CameraFacing.FRONT.opposite())
        assertEquals(CameraFacing.FRONT, CameraFacing.BACK.opposite())
    }

    @Test
    fun labelsMatchTheLogWording() {
        assertEquals("front", CameraFacing.FRONT.label)
        assertEquals("back", CameraFacing.BACK.label)
    }
}

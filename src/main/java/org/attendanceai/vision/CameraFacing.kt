/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision

/**
 * Which physical camera a [CameraXSource] should prefer: the device's
 * front (selfie) or back camera. Kept free of any androidx.camera types so
 * the toggle logic is unit-testable on a host JVM; the mapping onto
 * [androidx.camera.core.CameraSelector] lives inside [CameraXSource].
 */
enum class CameraFacing {
    FRONT,
    BACK;

    /** The other physical camera. */
    fun opposite(): CameraFacing = if (this == FRONT) BACK else FRONT

    /** Human-readable name for on-screen log lines. */
    val label: String
        get() = if (this == FRONT) "front" else "back"
}

/**
 * View-model/state for the main screen's camera-switch button. Starts on
 * [CameraFacing.FRONT] — the app's original, unchanged default — and
 * [toggle] flips it on every tap. The activity owning this state persists
 * across camera restarts, so the chosen facing survives stop/start cycles.
 */
class CameraFacingState(initial: CameraFacing = CameraFacing.FRONT) {

    var facing: CameraFacing = initial
        private set

    /** Switches to the other camera and returns the new selection. */
    fun toggle(): CameraFacing {
        facing = facing.opposite()
        return facing
    }
}

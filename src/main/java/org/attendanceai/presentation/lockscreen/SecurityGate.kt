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
package org.attendanceai.presentation.lockscreen

/**
 * Security role: process-lifetime session gate between the vault and the
 * rest of the app. [AttendanceActivity][org.attendanceai.ui.AttendanceActivity]
 * refuses to initialise until [markUnlockedForSession] has been called by
 * the lock screen after a successful PIN/biometric verification or a
 * completed setup.
 *
 * Fail-closed by construction: the flag lives only in memory, so every
 * fresh process (launch or process-death restart) starts locked again,
 * and there is deliberately no API to unlock without going through
 * [LockScreenActivity]'s verification flow.
 */
object SecurityGate {

    @Volatile
    private var unlockedForSession = false

    /** True until the lock screen verifies the user in this session. */
    @JvmStatic
    fun lockRequired(): Boolean = !unlockedForSession

    /** Called exclusively by the lock screen after successful verification. */
    @JvmStatic
    fun markUnlockedForSession() {
        unlockedForSession = true
    }
}

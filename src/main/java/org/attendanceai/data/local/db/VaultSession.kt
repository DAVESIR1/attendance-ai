/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import android.content.Context

/**
 * Owns the process-lifetime encrypted database session. The unwrapped vault
 * key is accepted only long enough to open SQLCipher and is never returned to
 * callers; a fresh process starts with no database session until unlock.
 */
object VaultSession {
    @Volatile
    private var database: AttendanceDatabase? = null

    /** Opens the database once for this unlocked process session. */
    @JvmStatic
    @Synchronized
    fun open(context: Context, vaultKey: ByteArray) {
        require(vaultKey.isNotEmpty()) { "vault key must not be empty" }
        if (database != null) return
        val opened = AttendanceDatabase.create(context.applicationContext, vaultKey)
        try {
            // Room builds lazily. Force the first SQLCipher connection here so a
            // wrong/tampered key fails before the security gate is opened.
            opened.openHelper.writableDatabase
            database = opened
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
    }

    /** Returns the already-open database, or null while the app is locked. */
    @JvmStatic
    fun database(): AttendanceDatabase? = database

    /** Closes and forgets the current session database. */
    @JvmStatic
    @Synchronized
    fun close() {
        database?.close()
        database = null
    }
}

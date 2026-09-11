/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** String-keyed application setting persisted inside the encrypted database. */
@Entity(tableName = "app_settings")
data class AppSettingEntry(
    @PrimaryKey val key: String,
    val value: String,
)

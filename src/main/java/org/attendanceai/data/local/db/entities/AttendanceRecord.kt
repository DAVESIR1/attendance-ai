/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted attendance state for one person on one normalized epoch day. */
@Entity(
    tableName = "attendance_records",
    foreignKeys = [
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["personId", "date"], unique = true)],
)
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val groupId: Long? = null,
    val date: Long,
    val status: String,
    val note: String? = null,
    val source: String,
    val confidence: Float? = null,
    val editedBy: String? = null,
    val editedAt: Long? = null,
    val createdAt: Long,
) {
    companion object {
        const val PRESENT = "PRESENT"
        const val ABSENT = "ABSENT"
        const val AUTO = "AUTO"
        const val MANUAL = "MANUAL"
    }
}

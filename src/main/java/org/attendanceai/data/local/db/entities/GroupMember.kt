/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey

/** Many-to-many membership relation between groups and people. */
@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "personId"],
    indices = [androidx.room.Index(value = ["personId"])],
    foreignKeys = [
        ForeignKey(
            entity = Group::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GroupMember(
    val groupId: Long,
    val personId: Long,
)

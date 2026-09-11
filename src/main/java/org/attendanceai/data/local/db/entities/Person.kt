/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An enrolled person. Face embeddings are serialized by [EmbeddingCodec] and
 * must only be written through the SQLCipher-backed database.
 */
@Entity(tableName = "people")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val faceEmbeddings: ByteArray,
    val photoPath: String,
    val identityNumber: String? = null,
    val dob: Long? = null,
    val bloodGroup: String? = null,
    val mobile: String? = null,
    val consentTimestamp: Long,
    val createdAt: Long,
    val isDeleted: Boolean = false,
)

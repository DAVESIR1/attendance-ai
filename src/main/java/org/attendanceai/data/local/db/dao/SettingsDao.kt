/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.attendanceai.data.local.db.entities.AppSettingEntry

/** Key/value settings access backed by the encrypted database. */
@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: AppSettingEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entries: List<AppSettingEntry>)

    @Query("SELECT * FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): AppSettingEntry?

    @Query("SELECT * FROM app_settings ORDER BY `key`")
    suspend fun getAll(): List<AppSettingEntry>

    @Delete
    suspend fun delete(entry: AppSettingEntry)
}

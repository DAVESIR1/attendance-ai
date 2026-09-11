/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.attendanceai.data.local.db.entities.AttendanceRecord
import org.attendanceai.data.local.db.entities.Person

/** Attendance reads and upserts; the unique person/date index prevents duplicates. */
@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AttendanceRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<AttendanceRecord>)

    @Query("SELECT * FROM attendance_records WHERE date BETWEEN :startDate AND :endDate ORDER BY date, personId")
    suspend fun getForDateRange(startDate: Long, endDate: Long): List<AttendanceRecord>

    @Query("SELECT * FROM attendance_records WHERE personId IN (:personIds) AND date BETWEEN :startDate AND :endDate ORDER BY date, personId")
    suspend fun getForPeopleAndDateRange(personIds: List<Long>, startDate: Long, endDate: Long): List<AttendanceRecord>

    @Query("SELECT * FROM attendance_records WHERE date = :date ORDER BY personId")
    suspend fun getForDate(date: Long): List<AttendanceRecord>

    @Query("SELECT * FROM attendance_records ORDER BY date, personId")
    suspend fun getAll(): List<AttendanceRecord>

    @Query("SELECT * FROM attendance_records WHERE date BETWEEN :startDate AND :endDate ORDER BY date, personId")
    fun observeForDateRange(startDate: Long, endDate: Long): Flow<List<AttendanceRecord>>

    @Query("""
        SELECT p.* FROM people p
        INNER JOIN group_members gm ON gm.personId = p.id
        WHERE gm.groupId = :groupId
          AND p.isDeleted = 0
          AND NOT EXISTS (
              SELECT 1 FROM attendance_records ar
              WHERE ar.personId = p.id
                AND ar.date = :date
                AND ar.status = 'PRESENT'
          )
        ORDER BY p.name COLLATE NOCASE
    """)
    suspend fun getAbsentForGroupAndDate(groupId: Long, date: Long): List<Person>

    @Query("SELECT * FROM attendance_records WHERE personId = :personId AND date = :date LIMIT 1")
    suspend fun getForPersonAndDate(personId: Long, date: Long): AttendanceRecord?

    @Query("DELETE FROM attendance_records WHERE personId = :personId")
    suspend fun deleteForPerson(personId: Long)
}

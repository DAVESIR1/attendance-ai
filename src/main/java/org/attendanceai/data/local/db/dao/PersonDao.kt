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
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.attendanceai.data.local.db.entities.Person

/** CRUD boundary for enrolled people; all operations run on the encrypted DB. */
@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(person: Person): Long

    @Update
    suspend fun update(person: Person)

    @Delete
    suspend fun delete(person: Person)

    @Query("UPDATE people SET isDeleted = 1 WHERE id = :personId")
    suspend fun softDelete(personId: Long)

    @Query("DELETE FROM people WHERE id = :personId")
    suspend fun hardDelete(personId: Long)

    @Query("DELETE FROM people")
    suspend fun hardDeleteAll()

    @Query("SELECT * FROM people WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Person>

    @Query("SELECT * FROM people WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Person>>

    @Query("SELECT * FROM people WHERE id = :personId LIMIT 1")
    suspend fun getById(personId: Long): Person?

    @Query("SELECT * FROM people WHERE isDeleted = 0 AND name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE")
    suspend fun searchByName(query: String): List<Person>
}

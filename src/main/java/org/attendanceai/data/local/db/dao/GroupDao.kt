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
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.attendanceai.data.local.db.entities.Group
import org.attendanceai.data.local.db.entities.GroupMember
import org.attendanceai.data.local.db.entities.Person

/** Group and membership queries used by group management and attendance capture. */
@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: Group): Long

    @Delete
    suspend fun delete(group: Group)

    @Query("SELECT * FROM attendance_groups ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Group>

    @Query("SELECT * FROM attendance_groups ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Group>>

    @Query("SELECT * FROM attendance_groups WHERE id = :groupId LIMIT 1")
    suspend fun getById(groupId: Long): Group?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: GroupMember)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<GroupMember>)

    @Delete
    suspend fun deleteMember(member: GroupMember)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: Long)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: Long): List<GroupMember>

    @Query("SELECT personId FROM group_members WHERE groupId = :groupId")
    suspend fun getMemberIds(groupId: Long): List<Long>

    @Query("""
        SELECT p.* FROM people p
        INNER JOIN group_members gm ON gm.personId = p.id
        WHERE gm.groupId = :groupId AND p.isDeleted = 0
        ORDER BY p.name COLLATE NOCASE
    """)
    suspend fun getPeople(groupId: Long): List<Person>

    /** Creates a group and its membership rows in one transaction. */
    @Transaction
    suspend fun insertWithMembers(group: Group, members: List<GroupMember>): Long {
        val groupId = insert(group)
        if (members.isNotEmpty()) {
            insertMembers(members.map { it.copy(groupId = groupId) })
        }
        return groupId
    }
}

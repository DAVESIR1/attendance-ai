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
import org.attendanceai.data.local.db.entities.GroupMember

/** Direct access to the group membership join table. */
@Dao
interface GroupMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(member: GroupMember)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<GroupMember>)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun byGroup(groupId: Long): List<GroupMember>

    @Query("SELECT * FROM group_members WHERE personId = :personId")
    suspend fun byPerson(personId: Long): List<GroupMember>

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND personId = :personId")
    suspend fun delete(groupId: Long, personId: Long)
}

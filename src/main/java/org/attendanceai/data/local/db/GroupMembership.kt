/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import org.attendanceai.data.local.db.entities.GroupMember

/**
 * Pure assembly of `group_members` rows from roster (legacy) person ids.
 *
 * The Room DAOs need a real encrypted database, so this mapping lives outside
 * them: it is the one part of group creation that runs on a host JVM and is
 * therefore unit-tested directly ([GroupMembershipTest]). [RoomAttendanceStore]
 * uses it for the live create path, so the tested code is the shipped code.
 */
object GroupMembership {

    /** Legacy roster ids look like `person-<rowid>`. */
    const val LEGACY_PREFIX = "person-"

    /**
     * Parses a legacy roster id (`person-123`) into the Row ID used by the
     * `people` table, or null when the id is not a plain number.
     */
    fun databaseId(legacyId: String): Long? =
        legacyId.removePrefix(LEGACY_PREFIX)
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLongOrNull()

    /**
     * One [GroupMember] per known, distinct person id, in the order given.
     *
     * Rows are built with placeholder [groupId] 0 and stamped with the real
     * group id by
     * [org.attendanceai.data.local.db.dao.GroupDao.insertWithMembers] once the
     * group row exists — that is what keeps "create group + members" atomic.
     * Unknown/blank ids are dropped instead of producing a foreign-key failure.
     */
    fun rows(groupId: Long, memberLegacyIds: List<String>): List<GroupMember> =
        memberLegacyIds.asSequence()
            .mapNotNull(::databaseId)
            .distinct()
            .map { personId -> GroupMember(groupId = groupId, personId = personId) }
            .toList()
}

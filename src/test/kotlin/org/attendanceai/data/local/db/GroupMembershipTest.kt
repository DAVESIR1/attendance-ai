/*
 * Attendance AI — group membership assembly tests (host JVM).
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import org.attendanceai.data.local.db.entities.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers group *creation* membership rows on the host JVM: the exact rows
 * `RoomAttendanceStore.createGroup` hands to Room, including the group-id
 * stamping, the (groupId, personId) key safety, and the roster-id parsing the
 * rest of the store already uses. The database-level half of the story (rows
 * really landing in SQLCipher, and ON DELETE CASCADE) is instrumented in
 * `AttendanceDatabaseTest`, matching the existing Room entity patterns.
 */
class GroupMembershipTest {

    @Test
    fun rowsStampTheGivenGroupIdOnEveryMembershipRow() {
        val rows = GroupMembership.rows(42L, listOf("person-1", "person-2"))

        assertEquals(listOf(42L, 42L), rows.map { it.groupId })
        assertEquals(listOf(1L, 2L), rows.map { it.personId })
    }

    @Test
    fun rowsAreExactlyGroupMemberValues() {
        assertEquals(
            listOf(GroupMember(groupId = 7L, personId = 3L)),
            GroupMembership.rows(7L, listOf("person-3")),
        )
    }

    @Test
    fun unknownOrMalformedRosterIdsAreDroppedInsteadOfBreakingTheForeignKey() {
        val rows = GroupMembership.rows(
            1L,
            listOf("person-5", "ghost", "person-", "person-abc", ""),
        )

        assertEquals(listOf(5L), rows.map { it.personId })
    }

    @Test
    fun duplicatePeopleCollapseToASingleRow() {
        // group_members is keyed on (groupId, personId): a duplicate would
        // violate the primary key, so the assembler de-duplicates first.
        val rows = GroupMembership.rows(9L, listOf("person-2", "person-2", "person-2"))

        assertEquals(1, rows.size)
        assertEquals(2L, rows.single().personId)
    }

    @Test
    fun rowOrderFollowsTheCheckedOrderOfTheRoster() {
        val rows = GroupMembership.rows(4L, listOf("person-30", "person-10", "person-20"))

        assertEquals(listOf(30L, 10L, 20L), rows.map { it.personId })
    }

    @Test
    fun nothingCheckedMeansNoMembershipRows() {
        assertEquals(emptyList<GroupMember>(), GroupMembership.rows(5L, emptyList()))
    }

    @Test
    fun databaseIdParsesRosterRowIds() {
        assertEquals(123L, GroupMembership.databaseId("person-123"))
        assertEquals(0L, GroupMembership.databaseId("person-0"))
    }

    @Test
    fun databaseIdKeepsTheStoresPreExistingLenientParsing() {
        // Identical to the parsing RoomAttendanceStore used before this change:
        // any purely numeric suffix is accepted, a missing prefix included.
        assertEquals(123L, GroupMembership.databaseId("123"))
    }

    @Test
    fun databaseIdRejectsAnythingThatIsNotANumericRosterRowId() {
        assertNull(GroupMembership.databaseId(""))
        assertNull(GroupMembership.databaseId("person-"))
        assertNull(GroupMembership.databaseId("person-1x"))
        assertNull(GroupMembership.databaseId("person--5"))
    }

    @Test
    fun legacyPrefixMatchesTheStoreIdFormat() {
        assertEquals("person-", GroupMembership.LEGACY_PREFIX)
    }
}

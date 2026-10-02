/*
 * Attendance AI — GroupDao contract tests (host JVM).
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.attendanceai.data.local.db.dao.GroupDao
import org.attendanceai.data.local.db.entities.Group
import org.attendanceai.data.local.db.entities.GroupMember
import org.attendanceai.data.local.db.entities.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the parts of [GroupDao] that are real production code on
 * a host JVM: the `insertWithMembers` default method (which stamps the
 * generated group id onto every membership row inside one "transaction") and
 * the deletion calls the app makes.
 *
 * A fake in-memory DAO stands in for Room's generated SQL implementation — the
 * genuine database behaviour (SQLCipher persistence, the `(groupId, personId)`
 * primary key and the ON DELETE CASCADE foreign keys) is exercised on a device
 * by `AttendanceDatabaseTest`, exactly like the existing Room entity tests.
 */
class GroupDaoContractTest {

    /** Minimal in-memory [GroupDao]; delete() mirrors the declared cascade. */
    private class FakeGroupDao : GroupDao {
        val groups = mutableListOf<Group>()
        val members = mutableListOf<GroupMember>()
        val people = mutableListOf<Person>()
        private var nextId = 1L

        override suspend fun insert(group: Group): Long {
            val id = nextId++
            groups += group.copy(id = id)
            return id
        }

        override suspend fun delete(group: Group) {
            groups.removeAll { it.id == group.id }
            members.removeAll { it.groupId == group.id }
        }

        override suspend fun getAll(): List<Group> = groups.toList()

        override fun observeAll(): Flow<List<Group>> = MutableStateFlow(groups.toList())

        override suspend fun getById(groupId: Long): Group? =
            groups.firstOrNull { it.id == groupId }

        override suspend fun insertMember(member: GroupMember) {
            members += member
        }

        override suspend fun insertMembers(members: List<GroupMember>) {
            this.members += members
        }

        override suspend fun deleteMember(member: GroupMember) {
            members.removeAll {
                it.groupId == member.groupId && it.personId == member.personId
            }
        }

        override suspend fun deleteMembers(groupId: Long) {
            members.removeAll { it.groupId == groupId }
        }

        override suspend fun getMembers(groupId: Long): List<GroupMember> =
            members.filter { it.groupId == groupId }

        override suspend fun getMemberIds(groupId: Long): List<Long> =
            members.filter { it.groupId == groupId }.map { it.personId }

        override suspend fun getPeople(groupId: Long): List<Person> {
            val ids = getMemberIds(groupId).toSet()
            return people.filter { ids.contains(it.id) }.sortedBy { it.name.lowercase() }
        }
    }

    @Test
    fun insertWithMembersReturnsTheNewGroupIdAndWritesOneRowPerMember() = runBlocking {
        val dao = FakeGroupDao()

        val groupId = dao.insertWithMembers(
            Group(name = "Engineering", createdAt = 1L),
            GroupMembership.rows(0L, listOf("person-1", "person-2", "person-3")),
        )

        assertEquals("Engineering", dao.getById(groupId)?.name)
        assertEquals(
            listOf(
                GroupMember(groupId, 1L),
                GroupMember(groupId, 2L),
                GroupMember(groupId, 3L),
            ),
            dao.members,
        )
    }

    @Test
    fun insertWithMembersRestampsPlaceholderGroupIdsWithTheRealGroupId() = runBlocking {
        val dao = FakeGroupDao()

        val groupId = dao.insertWithMembers(
            Group(name = "Restamped", createdAt = 1L),
            listOf(GroupMember(groupId = 99L, personId = 7L)),
        )

        assertEquals(listOf(GroupMember(groupId, 7L)), dao.members)
        assertTrue(dao.members.none { it.groupId == 99L })
    }

    @Test
    fun insertWithMembersWithoutPeopleCreatesTheGroupRowOnly() = runBlocking {
        val dao = FakeGroupDao()

        val groupId = dao.insertWithMembers(Group(name = "Empty", createdAt = 1L), emptyList())

        assertNotNull(dao.getById(groupId))
        assertTrue(dao.members.isEmpty())
    }

    @Test
    fun membershipRowsBelongOnlyToTheirOwnGroup() = runBlocking {
        val dao = FakeGroupDao()
        val first = dao.insertWithMembers(
            Group(name = "First", createdAt = 1L),
            GroupMembership.rows(0L, listOf("person-1")),
        )
        val second = dao.insertWithMembers(
            Group(name = "Second", createdAt = 1L),
            GroupMembership.rows(0L, listOf("person-2")),
        )

        assertEquals(listOf(1L), dao.getMemberIds(first))
        assertEquals(listOf(2L), dao.getMemberIds(second))
    }

    @Test
    fun deletingAGroupRemovesItsMembershipRowsAndLeavesOtherGroupsAlone() = runBlocking {
        val dao = FakeGroupDao()
        val doomed = dao.insertWithMembers(
            Group(name = "Doomed", createdAt = 1L),
            GroupMembership.rows(0L, listOf("person-1")),
        )
        val kept = dao.insertWithMembers(
            Group(name = "Kept", createdAt = 1L),
            GroupMembership.rows(0L, listOf("person-2")),
        )

        dao.delete(dao.getById(doomed)!!)

        assertTrue(dao.groups.none { it.id == doomed })
        assertTrue(dao.getMembers(doomed).isEmpty())
        assertNotNull(dao.getById(kept))
        assertEquals(listOf(2L), dao.getMemberIds(kept))
    }

    @Test
    fun deleteMembersClearsAGroupWithoutRemovingTheGroupRow() = runBlocking {
        val dao = FakeGroupDao()
        val groupId = dao.insertWithMembers(
            Group(name = "Kept", createdAt = 1L),
            GroupMembership.rows(0L, listOf("person-1", "person-2")),
        )

        dao.deleteMembers(groupId)

        assertTrue(dao.getMembers(groupId).isEmpty())
        assertNotNull(dao.getById(groupId))
    }

    @Test
    fun deleteMemberRemovesASingleMembershipAndKeepsTheRest() = runBlocking {
        val dao = FakeGroupDao()
        val groupId = dao.insertWithMembers(
            Group(name = "Mixed", createdAt = 1L),
            GroupMembership.rows(0L, listOf("person-1", "person-2")),
        )

        dao.deleteMember(GroupMember(groupId, 1L))

        assertEquals(listOf(2L), dao.getMemberIds(groupId))
    }

    @Test
    fun getPeopleReturnsOnlyTheGroupsMembersSortedByName() = runBlocking {
        val dao = FakeGroupDao()
        dao.people += person(1L, "Zoe")
        dao.people += person(2L, "Ada")
        dao.people += person(3L, "NotInGroup")
        val groupId = dao.insertWithMembers(
            Group(name = "Some", createdAt = 1L),
            GroupMembership.rows(0L, listOf("person-1", "person-2")),
        )

        assertEquals(listOf("Ada", "Zoe"), dao.getPeople(groupId).map { it.name })
    }

    private fun person(id: Long, name: String): Person = Person(
        id = id,
        name = name,
        faceEmbeddings = byteArrayOf(1, 2, 3),
        photoPath = "",
        consentTimestamp = 1L,
        createdAt = 1L,
    )
}

/*
 * Attendance AI — encrypted local database instrumentation tests.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.attendanceai.data.local.db.entities.AppSettingEntry
import org.attendanceai.data.local.db.entities.AttendanceRecord
import org.attendanceai.data.local.db.entities.Group
import org.attendanceai.data.local.db.entities.GroupMember
import org.attendanceai.data.local.db.entities.Person
import org.attendanceai.store.AttendanceStore
import org.attendanceai.store.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises Room's schema and foreign-key behavior against SQLCipher's Android
 * driver. These tests deliberately use an in-memory encrypted database so no
 * biometric fixture is written to the test device's persistent storage.
 */
@RunWith(AndroidJUnit4::class)
class AttendanceDatabaseTest {

    private lateinit var database: AttendanceDatabase

    @Before
    fun setUp() {
        database = AttendanceDatabase.createInMemory(
            ApplicationProvider.getApplicationContext(),
            ByteArray(32) { (it + 1).toByte() },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun personAndEmbeddingRoundTrip() = runBlocking {
        val id = database.personDao().insert(person("Ada", 0L))
        val saved = database.personDao().getById(id)

        assertNotNull(saved)
        assertEquals("Ada", saved?.name)
        assertTrue(saved?.faceEmbeddings?.isNotEmpty() == true)
    }

    @Test
    fun groupDeleteCascadesMembershipButNotPerson() = runBlocking {
        val personId = database.personDao().insert(person("Grace", 0L))
        val groupId = database.groupDao().insert(Group(name = "Engineering", createdAt = 1L))
        database.groupMemberDao().insert(GroupMember(groupId, personId))

        database.groupDao().delete(database.groupDao().getById(groupId)!!)

        assertTrue(database.groupMemberDao().byGroup(groupId).isEmpty())
        assertNotNull(database.personDao().getById(personId))
    }

    @Test
    fun personDeleteCascadesAttendanceAndMembership() = runBlocking {
        val personId = database.personDao().insert(person("Lin", 0L))
        val groupId = database.groupDao().insert(Group(name = "Design", createdAt = 1L))
        database.groupMemberDao().insert(GroupMember(groupId, personId))
        database.attendanceDao().upsert(
            attendance(personId, date = 20L, status = AttendanceRecord.PRESENT),
        )

        database.personDao().hardDelete(personId)

        assertTrue(database.groupMemberDao().byPerson(personId).isEmpty())
        assertTrue(database.attendanceDao().getForPersonAndDate(personId, 20L) == null)
    }

    @Test
    fun samePersonAndDateUpsertsInsteadOfDuplicating() = runBlocking {
        val personId = database.personDao().insert(person("Mina", 0L))
        database.attendanceDao().upsert(
            attendance(personId, date = 21L, status = AttendanceRecord.ABSENT),
        )
        database.attendanceDao().upsert(
            attendance(personId, date = 21L, status = AttendanceRecord.PRESENT),
        )

        val records = database.attendanceDao().getForDate(21L)
        assertEquals(1, records.size)
        assertEquals(AttendanceRecord.PRESENT, records.single().status)
    }

    @Test
    fun absentForGroupExcludesPresentPeople() = runBlocking {
        val presentId = database.personDao().insert(person("Present", 0L))
        val absentId = database.personDao().insert(person("Absent", 0L))
        val groupId = database.groupDao().insert(Group(name = "Today", createdAt = 1L))
        database.groupMemberDao().insertAll(
            listOf(GroupMember(groupId, presentId), GroupMember(groupId, absentId)),
        )
        database.attendanceDao().upsert(
            attendance(presentId, date = 22L, status = AttendanceRecord.PRESENT),
        )

        val absent = database.attendanceDao().getAbsentForGroupAndDate(groupId, 22L)

        assertEquals(listOf(absentId), absent.map { it.id })
    }

    @Test
    fun legacyStoreFacadeUsesEncryptedRoomForRosterRecordsAndSettings() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AttendanceStore(context.filesDir, database)
        val person = AttendanceStore.Person.create(
            "person-from-legacy-api",
            "Compat User",
            floatArrayOf(0.3f, 0.4f),
        )
        val roster = LinkedHashMap<String, AttendanceStore.Person>()
        roster[person.id] = person

        store.saveRoster(roster)
        val loaded = store.loadRoster()
        val loadedPerson = loaded.values.single()
        store.appendRecord(
            AttendanceStore.Record.create(
                loadedPerson.id,
                loadedPerson.name,
                0.91f,
                TimeUnit.DAYS.toMillis(23L),
            )
        )
        val settings = Settings()
        settings.similarityThreshold = 0.77f
        store.saveSettings(settings)

        assertEquals("Compat User", loadedPerson.name)
        assertEquals(1, store.loadRecords().size)
        assertEquals(0.77f, store.loadSettings().similarityThreshold, 0.0001f)
    }

    @Test
    fun settingsCrudRoundTripsInsideDatabase() = runBlocking {
        database.settingsDao().put(AppSettingEntry("theme", "pastel-glass"))

        assertEquals("pastel-glass", database.settingsDao().get("theme")?.value)
        assertEquals(1, database.settingsDao().getAll().size)
    }

    @Test
    fun legacyJsonMigrationImportsIntoEncryptedDbAndDeletesFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacyDir = java.io.File(context.cacheDir, "legacy-migration-fixture").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val legacy = AttendanceStore(legacyDir)
            val roster = LinkedHashMap<String, AttendanceStore.Person>()
            val person = AttendanceStore.Person.create(
                "person-1700000000000", "Migrated Person", floatArrayOf(0.5f, -0.25f),
            )
            roster[person.id] = person
            legacy.saveRoster(roster)
            legacy.appendRecord(
                AttendanceStore.Record.create(
                    person.id, person.name, 0.9f, TimeUnit.DAYS.toMillis(30L),
                ),
            )
            val settings = Settings()
            settings.similarityThreshold = 0.7f
            legacy.saveSettings(settings)

            val outcome = LegacyJsonMigrator.migrate(legacyDir, database)

            assertTrue(outcome.failure == null)
            assertEquals(1, outcome.peopleImported)
            assertEquals(1, outcome.recordsImported)
            assertEquals(0, outcome.recordsSkipped)
            assertTrue(outcome.settingsImported)
            assertTrue(outcome.filesDeleted)
            assertTrue(!java.io.File(legacyDir, "roster.json").exists())
            assertTrue(!java.io.File(legacyDir, "records.json").exists())
            assertTrue(!java.io.File(legacyDir, "settings.json").exists())

            val saved = database.personDao().getById(1700000000000L)
            assertNotNull(saved)
            assertEquals("Migrated Person", saved?.name)
            assertEquals(person.enrolledAtMs, saved?.consentTimestamp)
            val dayRecords = database.attendanceDao().getForDate(30L)
            assertEquals(1, dayRecords.size)
            assertEquals(AttendanceRecord.PRESENT, dayRecords.single().status)

            // Settings round-trip through the same key the live store reads.
            val stored = AttendanceStore(legacyDir, database)
            assertEquals(0.7f, stored.loadSettings().similarityThreshold, 0.0001f)

            // A second run after file deletion must be a clean no-op.
            val second = LegacyJsonMigrator.migrate(legacyDir, database)
            assertTrue(second.failure == null)
            assertTrue(!second.migrated)
        } finally {
            legacyDir.deleteRecursively()
        }
    }

    @Test
    fun groupInsertWithMembersWritesCorrectMembershipRows() = runBlocking {
        val first = database.personDao().insert(person("Ada", 0L))
        val second = database.personDao().insert(person("Grace", 0L))

        // The exact call the Create Group screen makes (placeholder id 0).
        val groupId = database.groupDao().insertWithMembers(
            Group(name = "Engineering", createdAt = 5L),
            GroupMembership.rows(0L, listOf("person-$first", "person-$second")),
        )

        assertEquals("Engineering", database.groupDao().getById(groupId)?.name)
        assertEquals(setOf(first, second), database.groupDao().getMemberIds(groupId).toSet())
        assertEquals(2, database.groupDao().getMembers(groupId).size)
        assertEquals(listOf("Ada", "Grace"), database.groupDao().getPeople(groupId).map { it.name })
    }

    @Test
    fun clearingTheRosterCascadesGroupMembershipRows() = runBlocking {
        val personId = database.personDao().insert(person("Cascade", 0L))
        val groupId = database.groupDao().insert(Group(name = "Doomed", createdAt = 1L))
        database.groupMemberDao().insert(GroupMember(groupId, personId))

        // The same call the Settings "Clear roster" action makes.
        RoomAttendanceStore(database)
            .saveRoster(LinkedHashMap<String, AttendanceStore.Person>())

        assertTrue(database.personDao().getAll().isEmpty())
        assertTrue(database.groupMemberDao().byGroup(groupId).isEmpty())
        // Only the people (and their links) go — the group row itself survives.
        assertNotNull(database.groupDao().getById(groupId))
    }

    private fun person(name: String, createdAt: Long): Person = Person(
        name = name,
        faceEmbeddings = EmbeddingCodec.encode(listOf(floatArrayOf(0.1f, 0.2f))),
        photoPath = "",
        consentTimestamp = 1L,
        createdAt = createdAt,
    )

    private fun attendance(personId: Long, date: Long, status: String): AttendanceRecord =
        AttendanceRecord(
            personId = personId,
            date = date,
            status = status,
            source = AttendanceRecord.MANUAL,
            createdAt = TimeUnit.DAYS.toMillis(date),
        )
}

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

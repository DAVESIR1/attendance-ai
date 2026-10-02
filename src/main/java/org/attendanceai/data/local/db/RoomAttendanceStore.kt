/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.attendanceai.data.local.db.entities.AppSettingEntry
import org.attendanceai.data.local.db.entities.AttendanceRecord
import org.attendanceai.data.local.db.entities.Group
import org.attendanceai.data.local.db.entities.GroupMember
import org.attendanceai.data.local.db.entities.Person
import org.attendanceai.store.AttendanceStore
import org.attendanceai.store.Json
import org.attendanceai.store.Settings
import java.io.IOException


/**
 * One created group with its membership count — what the Home screen's group
 * cards display.
 */
data class GroupSummary(val id: Long, val name: String, val memberCount: Int)

/** One group with its members' display names — what the detail screen shows. */
data class GroupWithMembers(val id: Long, val name: String, val memberNames: List<String>)

/**
 * Synchronous compatibility facade for the existing camera pipeline.
 *
 * The legacy Java pipeline expects [AttendanceStore.Person] and
 * [AttendanceStore.Record] values, while the new source of truth is the
 * SQLCipher-backed Room database. This adapter keeps that API stable during the
 * migration and routes biometric templates, attendance records, and settings
 * through Room. The old JSON constructor remains available only for migration
 * tests and legacy data import.
 *
 * Database work is dispatched to IO because callers currently include the
 * classic Activity's main thread. Failures are propagated rather than silently
 * falling back to JSON, so encrypted-storage failures cannot downgrade privacy.
 */
class RoomAttendanceStore(private val database: AttendanceDatabase) {

    /**
     * Loads active people with ALL of their stored embeddings — the guided
     * multi-angle enrolment stores one per captured pose — plus the optional
     * identity fields (still null for people enrolled before they were asked
     * for).
     */
    fun loadRoster(): Map<String, AttendanceStore.Person> = runDb {
        val result = LinkedHashMap<String, AttendanceStore.Person>()
        database.personDao().getAll().forEach { person ->
            val embeddings = runCatching { EmbeddingCodec.decode(person.faceEmbeddings) }
                .getOrDefault(emptyList())
                .filter { it.isNotEmpty() }
            if (embeddings.isNotEmpty()) {
                val legacy = AttendanceStore.Person.createMulti(
                    legacyId(person.id),
                    person.name,
                    embeddings,
                )
                legacy.enrolledAtMs = person.createdAt
                legacy.identityNumber = person.identityNumber
                legacy.dob = person.dob
                legacy.bloodGroup = person.bloodGroup
                legacy.mobile = person.mobile
                result[legacy.id] = legacy
            }
        }
        result
    }

    /**
     * Upserts the roster into encrypted storage. An empty map preserves the
     * legacy "clear roster" action and hard-deletes people through Room so
     * foreign-key cascades remove their attendance and membership rows.
     */
    @Throws(IOException::class)
    fun saveRoster(people: Map<String, AttendanceStore.Person>) {
        try {
            runDb {
                if (people.isEmpty()) {
                    database.personDao().hardDeleteAll()
                    return@runDb
                }
                val existing = database.personDao().getAll().associateBy { it.id }
                val retainedIds = people.keys.mapNotNull(::databaseId).toSet()
                existing.keys.filterNot(retainedIds::contains).forEach { id ->
                    database.personDao().hardDelete(id)
                }
                people.values.forEach { legacy ->
                    val name = legacy.name.trim()
                    require(name.isNotEmpty()) { "person name must not be blank" }
                    // All captured embeddings (guided multi-angle enrolment),
                    // falling back to the single legacy template.
                    val embeddings = legacy.allTemplates()
                    require(embeddings.isNotEmpty()) {
                        "person embedding must not be empty"
                    }
                    val id = databaseId(legacy.id)
                    val old = id?.let(existing::get)
                    val entity = Person(
                        id = id ?: 0L,
                        name = name,
                        faceEmbeddings = EmbeddingCodec.encode(embeddings),
                        photoPath = old?.photoPath ?: "",
                        // A caller that does not carry the optional details
                        // (null) must not erase what is already stored.
                        identityNumber = legacy.identityNumber ?: old?.identityNumber,
                        dob = legacy.dob ?: old?.dob,
                        bloodGroup = legacy.bloodGroup ?: old?.bloodGroup,
                        mobile = legacy.mobile ?: old?.mobile,
                        consentTimestamp = old?.consentTimestamp ?: System.currentTimeMillis(),
                        createdAt = old?.createdAt ?: legacy.enrolledAtMs,
                        isDeleted = false,
                    )
                    if (id == null || old == null) {
                        if (id != null && old == null) {
                            // Id collision outside the normal path: replace first.
                            database.personDao().hardDelete(id)
                        }
                        database.personDao().insert(entity)
                    } else {
                        database.personDao().update(entity)
                    }
                }
            }
        } catch (failure: RuntimeException) {
            throw IOException("encrypted roster write failed", failure)
        }
    }

    /** Loads all encrypted attendance records in deterministic date/person order. */
    fun loadRecords(): List<AttendanceStore.Record> = runDb {
        val names = database.personDao().getAll().associate { it.id to it.name }
        database.attendanceDao().getAll().map { record ->
            AttendanceStore.Record.create(
                legacyId(record.personId),
                names[record.personId] ?: "",
                record.confidence ?: 0f,
                millisForEpochDay(record.date),
            )
        }
    }

    /** Upserts one legacy punch as a PRESENT/AUTO record for its epoch day. */
    @Throws(IOException::class)
    fun appendRecord(record: AttendanceStore.Record) {
        try {
            runDb {
                val personId = databaseId(record.personId)
                    ?: throw IllegalArgumentException("unknown person id")
                val person = database.personDao().getById(personId)
                    ?: throw IllegalArgumentException("unknown person")
                database.attendanceDao().upsert(
                    AttendanceRecord(
                        personId = person.id,
                        groupId = null,
                        date = Math.floorDiv(record.tsMs, MILLIS_PER_DAY),
                        status = AttendanceRecord.PRESENT,
                        source = AttendanceRecord.AUTO,
                        confidence = record.score,
                        createdAt = record.tsMs,
                    )
                )
            }
        } catch (failure: RuntimeException) {
            throw IOException("encrypted attendance write failed", failure)
        }
    }

    /** Reads the compatibility settings JSON from one encrypted Room value. */
    fun loadSettings(): Settings = runDb {
        val settings = Settings()
        val entry = database.settingsDao().get(SETTINGS_KEY)
        if (entry != null) {
            settings.fromMap(Json.asMap(Json.parse(entry.value)))
        }
        settings
    }

    /** Stores the compatibility settings object inside the encrypted DB. */
    @Throws(IOException::class)
    fun saveSettings(settings: Settings) {
        try {
            runDb {
                database.settingsDao().put(
                    AppSettingEntry(SETTINGS_KEY, Json.stringify(settings.toMap()))
                )
            }
        } catch (failure: RuntimeException) {
            throw IOException("encrypted settings write failed", failure)
        }
    }

    // ------------------------------------------------------------ groups

    /**
     * Loads every group with its membership count, ordered by name — the Home
     * screen's card list. Counts come from the join table, so a group whose
     * members were deleted (cascade) correctly reports zero.
     */
    fun loadGroups(): List<GroupSummary> = runDb {
        database.groupDao().getAll().map { group ->
            GroupSummary(
                id = group.id,
                name = group.name,
                memberCount = database.groupDao().getMemberIds(group.id).size,
            )
        }
    }

    /** Loads one group with its members' names, or null when it is gone. */
    fun loadGroup(groupId: Long): GroupWithMembers? = runDb {
        val group = database.groupDao().getById(groupId) ?: return@runDb null
        GroupWithMembers(
            id = group.id,
            name = group.name,
            memberNames = database.groupDao().getPeople(groupId).map { it.name },
        )
    }

    /**
     * Creates a group and its membership rows in one Room transaction.
     *
     * [memberLegacyIds] are the roster ids the Home screen already uses
     * (`person-<rowid>`); unknown ids are dropped by [GroupMembership.rows]
     * rather than failing the foreign key. A blank name and a duplicate name
     * are rejected as [IllegalArgumentException] so the UI can explain the
     * problem precisely; storage failures surface as [IOException], matching
     * the roster/settings writes above.
     */
    @Throws(IOException::class)
    fun createGroup(name: String, memberLegacyIds: List<String>): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "group name must not be blank" }
        try {
            return runDb {
                if (database.groupDao().getAll().any { it.name.equals(trimmed, ignoreCase = true) }) {
                    throw IllegalArgumentException("a group named \"$trimmed\" already exists")
                }
                database.groupDao().insertWithMembers(
                    Group(name = trimmed, createdAt = System.currentTimeMillis()),
                    // Placeholder group id 0: insertWithMembers stamps the real
                    // id on every row inside its transaction.
                    GroupMembership.rows(0L, memberLegacyIds),
                )
            }
        } catch (invalid: IllegalArgumentException) {
            throw invalid
        } catch (failure: RuntimeException) {
            throw IOException("encrypted group write failed", failure)
        }
    }

    private fun <T> runDb(block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { block() }

    private fun legacyId(id: Long): String = GroupMembership.LEGACY_PREFIX + id

    private fun databaseId(legacyId: String): Long? = GroupMembership.databaseId(legacyId)

    private fun millisForEpochDay(day: Long): Long = day * MILLIS_PER_DAY

    internal companion object {
        const val SETTINGS_KEY = "legacy_settings"
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

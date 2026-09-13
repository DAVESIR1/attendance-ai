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
import org.attendanceai.data.local.db.entities.Person
import org.attendanceai.store.AttendanceStore
import org.attendanceai.store.Json
import org.attendanceai.store.Settings
import java.io.IOException


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

    /** Loads active people and exposes the first stored embedding to the old pipeline. */
    fun loadRoster(): Map<String, AttendanceStore.Person> = runDb {
        val result = LinkedHashMap<String, AttendanceStore.Person>()
        database.personDao().getAll().forEach { person ->
            val embedding = runCatching { EmbeddingCodec.decode(person.faceEmbeddings).firstOrNull() }
                .getOrNull()
            if (embedding != null && embedding.isNotEmpty()) {
                val legacy = AttendanceStore.Person.create(
                    legacyId(person.id),
                    person.name,
                    embedding,
                )
                legacy.enrolledAtMs = person.createdAt
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
                    require(legacy.template != null && legacy.template.isNotEmpty()) {
                        "person embedding must not be empty"
                    }
                    val id = databaseId(legacy.id)
                    val old = id?.let(existing::get)
                    val entity = Person(
                        id = id ?: 0L,
                        name = name,
                        faceEmbeddings = EmbeddingCodec.encode(listOf(legacy.template)),
                        photoPath = old?.photoPath ?: "",
                        identityNumber = old?.identityNumber,
                        dob = old?.dob,
                        bloodGroup = old?.bloodGroup,
                        mobile = old?.mobile,
                        consentTimestamp = old?.consentTimestamp ?: System.currentTimeMillis(),
                        createdAt = old?.createdAt ?: legacy.enrolledAtMs,
                        isDeleted = false,
                    )
                    if (id == null || old == null) {
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

    private fun <T> runDb(block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { block() }

    private fun legacyId(id: Long): String = "person-$id"

    private fun databaseId(legacyId: String): Long? =
        legacyId.removePrefix("person-")
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLongOrNull()

    private fun millisForEpochDay(day: Long): Long = day * MILLIS_PER_DAY

    internal companion object {
        const val SETTINGS_KEY = "legacy_settings"
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

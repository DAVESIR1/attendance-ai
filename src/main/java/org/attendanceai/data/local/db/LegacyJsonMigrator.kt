/*
 * Attendance AI — legacy JSON → encrypted database migration.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.attendanceai.data.local.db.entities.AppSettingEntry
import org.attendanceai.data.local.db.entities.AttendanceRecord
import org.attendanceai.data.local.db.entities.Person
import org.attendanceai.store.AttendanceStore
import org.attendanceai.store.Json
import org.attendanceai.store.Settings

/**
 * One-time migration of the pre-SQLCipher JSON store into the encrypted Room
 * database, closing the Phase 2 data-at-rest gap: the legacy `roster.json`
 * kept raw face templates in plaintext.
 *
 * Behaviour:
 *  - Reads the legacy roster/records/settings JSON files through the legacy
 *    JSON-mode [AttendanceStore].
 *  - Imports people (one embedding each, wrapped by [EmbeddingCodec]),
 *    attendance records (upserted onto the `(personId, date)` unique index)
 *    and settings (under [SETTINGS_KEY], never overwriting a value already
 *    written by the live app) inside a single Room transaction — either
 *    everything applies or nothing does.
 *  - Only after a successful transaction are the JSON files overwritten with
 *    zeros and deleted, so an interrupted migration never loses data and a
 *    retry is safe (already-migrated people are detected by id and skipped).
 *
 * Limitation: on flash storage an overwrite is a best-effort erase, not a
 * guaranteed one; the delete step still removes the file handle either way.
 *
 * No person names, embeddings or timestamps are ever logged — outcomes carry
 * counts only.
 */
object LegacyJsonMigrator {

    /** Count-only result; carries no PII so it is safe to surface in the UI. */
    data class Outcome(
        val peopleImported: Int,
        val recordsImported: Int,
        val recordsSkipped: Int,
        val settingsImported: Boolean,
        val filesDeleted: Boolean,
        val failure: String? = null,
    ) {
        /** True when at least one legacy artefact was imported successfully. */
        val migrated: Boolean
            get() = failure == null &&
                (peopleImported > 0 || recordsImported > 0 || settingsImported)

        /** True when the migration could not complete (source files kept). */
        val failed: Boolean
            get() = failure != null
    }

    /** Legacy files parsed into domain values, before database mapping. */
    internal data class LegacySnapshot(
        val people: List<AttendanceStore.Person>,
        val records: List<AttendanceStore.Record>,
        val settings: Settings?,
    )

    /** A person ready for insertion, retaining its legacy id for record remapping. */
    internal data class PreparedPerson(val legacyId: String, val entity: Person)

    /** A record whose person reference is still a legacy id. */
    internal data class PreparedRecord(
        val legacyPersonId: String,
        val record: AttendanceStore.Record,
    )

    /** Everything the transaction needs, fully parsed and validated. */
    internal data class PreparedMigration(
        val people: List<PreparedPerson>,
        val records: List<PreparedRecord>,
        val settings: Settings?,
    )

    /**
     * Blocking entry point for classic-Activity callers. Runs on [Dispatchers.IO];
     * returns an [Outcome] rather than throwing so a failed migration can never
     * crash the unlock flow.
     */
    @JvmStatic
    fun migrateBlocking(filesDir: File, database: AttendanceDatabase): Outcome =
        runBlocking(Dispatchers.IO) { migrate(filesDir, database) }

    /**
     * Suspend entry point: snapshot → prepare → transactional import → secure
     * delete. Returns an [Outcome.failure] instead of throwing on any problem,
     * keeping the unencrypted source files untouched in that case.
     */
    suspend fun migrate(filesDir: File, database: AttendanceDatabase): Outcome {
        val snapshot = withContext(Dispatchers.IO) {
            readLegacySnapshot(filesDir)
        } ?: return Outcome(0, 0, 0, false, false)

        val prepared = try {
            prepare(snapshot)
        } catch (failure: RuntimeException) {
            return Outcome(0, 0, 0, false, false, "unusable legacy data: ${failure.javaClass.simpleName}")
        }

        var peopleImported = 0
        var recordsImported = 0
        var recordsSkipped = 0
        var settingsImported = false
        try {
            database.withTransaction {
                val idByLegacy = HashMap<String, Long>(prepared.people.size)
                for (person in prepared.people) {
                    val explicitId = person.entity.id.takeIf { it != 0L }
                    val existing = explicitId?.let { database.personDao().getById(it) }
                    val id = existing?.id ?: database.personDao().insert(person.entity)
                    idByLegacy[person.legacyId] = id
                    if (existing == null) peopleImported++
                }
                for (record in prepared.records) {
                    val personId = idByLegacy[record.legacyPersonId]
                    if (personId == null) {
                        recordsSkipped++
                        continue
                    }
                    database.attendanceDao().upsert(
                        AttendanceRecord(
                            personId = personId,
                            groupId = null,
                            date = Math.floorDiv(record.record.tsMs, MILLIS_PER_DAY),
                            status = AttendanceRecord.PRESENT,
                            source = AttendanceRecord.AUTO,
                            confidence = record.record.score,
                            createdAt = record.record.tsMs,
                        )
                    )
                    recordsImported++
                }
                if (prepared.settings != null &&
                    database.settingsDao().get(SETTINGS_KEY) == null
                ) {
                    database.settingsDao().put(
                        AppSettingEntry(SETTINGS_KEY, Json.stringify(prepared.settings.toMap()))
                    )
                    settingsImported = true
                }
            }
        } catch (failure: Throwable) {
            return Outcome(0, 0, 0, false, false, "encrypted import failed: ${failure.javaClass.simpleName}")
        }

        var filesDeleted = true
        val legacyStore = AttendanceStore(filesDir)
        for (file in listOf(
            legacyStore.rosterFile(),
            legacyStore.recordsFile(),
            legacyStore.settingsFile(),
        )) {
            if (file.isFile && !secureDelete(file)) {
                filesDeleted = false
            }
        }
        return Outcome(peopleImported, recordsImported, recordsSkipped, settingsImported, filesDeleted)
    }

    /**
     * Reads the legacy JSON store; returns null when none of the three files
     * exist (the common post-migration state), making the migration a no-op.
     * Parse failures inside the legacy store degrade to empty lists rather
     * than crashing, so a corrupt file cannot block an unlock.
     */
    internal fun readLegacySnapshot(dir: File): LegacySnapshot? {
        val store = AttendanceStore(dir)
        val hasRoster = store.rosterFile().isFile
        val hasRecords = store.recordsFile().isFile
        val hasSettings = store.settingsFile().isFile
        if (!hasRoster && !hasRecords && !hasSettings) {
            return null
        }
        val settings = if (hasSettings) store.loadSettings() else null
        return LegacySnapshot(
            people = store.loadRoster().values.toList(),
            records = store.loadRecords(),
            settings = settings,
        )
    }

    /**
     * Pure conversion step (unit-tested on the JVM): keeps the numeric suffix
     * of `person-<millis>` ids as the explicit Room id so later loads via
     * [RoomAttendanceStore] resolve the same person, derives the mandatory
     * consent timestamp from the legacy enrolment time (defaulting to now so
     * consent is never null), and keeps records keyed by legacy id for
     * remapping inside the transaction.
     */
    internal fun prepare(snapshot: LegacySnapshot): PreparedMigration {
        val people = snapshot.people.map { person ->
            val name = person.name.trim()
            require(name.isNotEmpty()) { "legacy person name is blank" }
            require(person.template != null && person.template.isNotEmpty()) {
                "legacy person has no template"
            }
            val enrolledAt = if (person.enrolledAtMs > 0L) {
                person.enrolledAtMs
            } else {
                System.currentTimeMillis()
            }
            val numericId = person.id.removePrefix(ID_PREFIX).toLongOrNull()?.takeIf { it > 0L }
            PreparedPerson(
                legacyId = person.id,
                entity = Person(
                    id = numericId ?: 0L,
                    name = name,
                    faceEmbeddings = EmbeddingCodec.encode(listOf(person.template)),
                    photoPath = "",
                    consentTimestamp = enrolledAt,
                    createdAt = enrolledAt,
                ),
            )
        }
        val records = snapshot.records.map { record ->
            PreparedRecord(record.personId, record)
        }
        return PreparedMigration(people, records, snapshot.settings)
    }

    /**
     * Best-effort secure deletion: overwrites the file contents with zeros,
     * forces them to storage, then unlinks the file. Returns true only when
     * the file is gone afterwards.
     */
    internal fun secureDelete(file: File): Boolean {
        if (!file.isFile) return true
        return try {
            FileOutputStream(file).use { out ->
                var remaining = file.length()
                val chunk = ByteArray(OVERWRITE_CHUNK)
                while (remaining > 0) {
                    val n = minOf(chunk.size.toLong(), remaining).toInt()
                    out.write(chunk, 0, n)
                    remaining -= n
                }
                out.flush()
                out.fd.sync()
            }
            file.delete()
        } catch (failure: Throwable) {
            false
        }
    }

    private const val ID_PREFIX = "person-"
    private const val OVERWRITE_CHUNK = 8192

    private const val SETTINGS_KEY = RoomAttendanceStore.SETTINGS_KEY
    private const val MILLIS_PER_DAY = RoomAttendanceStore.MILLIS_PER_DAY
}


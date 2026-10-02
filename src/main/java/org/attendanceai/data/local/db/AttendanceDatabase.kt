/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SupportFactory
import org.attendanceai.data.local.db.dao.AttendanceDao
import org.attendanceai.data.local.db.dao.GroupDao
import org.attendanceai.data.local.db.dao.GroupMemberDao
import org.attendanceai.data.local.db.dao.PersonDao
import org.attendanceai.data.local.db.dao.SettingsDao
import org.attendanceai.data.local.db.entities.AppSettingEntry
import org.attendanceai.data.local.db.entities.AttendanceRecord
import org.attendanceai.data.local.db.entities.Group
import org.attendanceai.data.local.db.entities.GroupMember
import org.attendanceai.data.local.db.entities.Person

/**
 * Room database backed by SQLCipher. Callers must provide the unwrapped
 * database key only for construction; the passphrase buffer is wiped after
 * the SQLCipher factory is created.
 */
@Database(
    entities = [
        Person::class,
        Group::class,
        GroupMember::class,
        AttendanceRecord::class,
        AppSettingEntry::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        /**
         * Adds the optional person columns collected by the enrolment details
         * form (identity number, date of birth, blood group, mobile).
         *
         * Idempotent on purpose: databases created by the current entity already
         * contain these columns, so the migration reads `PRAGMA table_info` and
         * issues only the ALTERs that are genuinely missing. Running it against
         * an already-current database is a no-op instead of a "duplicate column
         * name" failure, which keeps the upgrade safe for every v1 database on
         * the phone.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                PersonOptionalColumns.alterStatements(existingPeopleColumns(database))
                    .forEach(database::execSQL)
            }
        }

        /** Column names of the `people` table as the database currently has them. */
        internal fun existingPeopleColumns(database: SupportSQLiteDatabase): Set<String> {
            val columns = LinkedHashSet<String>()
            database.query("PRAGMA table_info(people)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex >= 0) {
                    while (cursor.moveToNext()) {
                        columns.add(cursor.getString(nameIndex))
                    }
                }
            }
            return columns
        }

        /** Opens the persistent encrypted database for the application. */
        fun create(context: Context, databaseKey: ByteArray): AttendanceDatabase {
            require(databaseKey.isNotEmpty()) { "database key must not be empty" }
            val passphrase = databaseKey.copyOf()
            val factory = SupportFactory(passphrase, null, true)
            passphrase.fill(0)
            return Room.databaseBuilder(
                context.applicationContext,
                AttendanceDatabase::class.java,
                DATABASE_NAME,
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }

        /** In-memory SQLCipher database for integration tests and previews. */
        fun createInMemory(context: Context, databaseKey: ByteArray): AttendanceDatabase {
            require(databaseKey.isNotEmpty()) { "database key must not be empty" }
            val passphrase = databaseKey.copyOf()
            val factory = SupportFactory(passphrase, null, true)
            passphrase.fill(0)
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AttendanceDatabase::class.java,
            )
                .openHelperFactory(factory)
                .build()
        }

        private const val DATABASE_NAME = "attendance.db"
    }
}

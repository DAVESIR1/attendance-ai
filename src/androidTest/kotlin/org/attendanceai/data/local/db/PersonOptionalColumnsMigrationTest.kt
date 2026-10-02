/*
 * Attendance AI — encrypted local database instrumentation tests.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Room migration for the optional enrolment details columns, run against a
 * real SQLite database.
 *
 * The `people` table is created by hand here so both upgrade paths can be
 * exercised on one device: an older shape without the four optional columns,
 * and the current shape that already has them (which must stay a no-op instead
 * of failing with "duplicate column name").
 */
@RunWith(AndroidJUnit4::class)
class PersonOptionalColumnsMigrationTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: SupportSQLiteDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "migration-test-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationAddsTheOptionalColumnsToAnOlderPeopleTable() {
        database = open(withOptionalColumns = false)
        val db = database!!
        assertTrue(columns(db).none { it in optionalColumnNames() })

        AttendanceDatabase.MIGRATION_1_2.migrate(db)

        val after = columns(db)
        optionalColumnNames().forEach { column ->
            assertTrue("expected column $column to be added", after.contains(column))
        }
    }

    @Test
    fun migrationIsIdempotentAndNeverFailsOnACurrentDatabase() {
        database = open(withOptionalColumns = true)
        val db = database!!

        AttendanceDatabase.MIGRATION_1_2.migrate(db)
        AttendanceDatabase.MIGRATION_1_2.migrate(db)

        optionalColumnNames().forEach { column ->
            assertEquals("column $column must exist exactly once",
                1, columns(db).count { it == column })
        }
    }

    @Test
    fun migrationKeepsExistingRows() {
        database = open(withOptionalColumns = false)
        val db = database!!
        db.execSQL(
            "INSERT INTO people (name, faceEmbeddings, photoPath) VALUES (?, ?, ?)",
            arrayOf<Any>("Kept Person", byteArrayOf(1, 2, 3), ""),
        )

        AttendanceDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT name, identityNumber FROM people").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Kept Person", cursor.getString(0))
            assertTrue("the new column must default to NULL", cursor.isNull(1))
        }
    }

    /** Column names of the `people` table, straight from SQLite. */
    private fun columns(db: SupportSQLiteDatabase): List<String> {
        val names = ArrayList<String>()
        db.query("PRAGMA table_info(people)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                names.add(cursor.getString(nameIndex))
            }
        }
        return names
    }

    private fun optionalColumnNames(): List<String> =
        PersonOptionalColumns.TYPES.map { it.first }

    /**
     * Opens a database whose `people` table mimics either the pre-details schema
     * or the current one.
     */
    private fun open(withOptionalColumns: Boolean): SupportSQLiteDatabase {
        val optional = if (withOptionalColumns) {
            ", identityNumber TEXT, dob INTEGER, bloodGroup TEXT, mobile TEXT"
        } else {
            ""
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE people (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "name TEXT NOT NULL, " +
                            "faceEmbeddings BLOB NOT NULL, " +
                            "photoPath TEXT NOT NULL" + optional + ")",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Nothing to do: the test drives migration manually.
                }
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }
}

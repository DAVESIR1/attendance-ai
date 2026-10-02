/*
 * Attendance AI — encrypted local database tests.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the decision logic behind `MIGRATION_1_2` — which optional
 * person columns still have to be added to a given database. Host-JVM only: the
 * migration itself is exercised against a real SQLite database in
 * `PersonOptionalColumnsMigrationTest` (instrumented), while this keeps the
 * "already current → no SQL at all" rule covered without a device.
 */
class PersonOptionalColumnsTest {

    @Test
    fun `an empty people table needs all four optional columns`() {
        val statements = PersonOptionalColumns.alterStatements(emptyList())

        assertEquals(4, statements.size)
        assertEquals(
            listOf(
                "ALTER TABLE people ADD COLUMN identityNumber TEXT",
                "ALTER TABLE people ADD COLUMN dob INTEGER",
                "ALTER TABLE people ADD COLUMN bloodGroup TEXT",
                "ALTER TABLE people ADD COLUMN mobile TEXT",
            ),
            statements,
        )
    }

    @Test
    fun `a current database needs no changes at all`() {
        val existing = PersonOptionalColumns.TYPES.map { it.first }

        assertTrue(PersonOptionalColumns.missing(existing).isEmpty())
        assertTrue(PersonOptionalColumns.alterStatements(existing).isEmpty())
    }

    @Test
    fun `only the genuinely missing columns are added`() {
        val existing = listOf("id", "name", "faceEmbeddings", "photoPath", "bloodGroup")

        assertEquals(
            listOf("identityNumber", "dob", "mobile"),
            PersonOptionalColumns.missing(existing).map { it.first },
        )
        assertEquals(
            listOf(
                "ALTER TABLE people ADD COLUMN identityNumber TEXT",
                "ALTER TABLE people ADD COLUMN dob INTEGER",
                "ALTER TABLE people ADD COLUMN mobile TEXT",
            ),
            PersonOptionalColumns.alterStatements(existing),
        )
    }

    @Test
    fun `the statements are plain additive alters and never destructive`() {
        PersonOptionalColumns.alterStatements(emptyList()).forEach { statement ->
            assertTrue(statement.startsWith("ALTER TABLE people ADD COLUMN "))
            assertTrue(!statement.contains("DROP"))
            assertTrue(!statement.contains("DELETE"))
        }
    }
}

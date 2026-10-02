/*
 * Attendance AI — encrypted local attendance database.
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.data.local.db

/**
 * The optional person columns collected by the post-capture details form
 * (identity number, date of birth, blood group, mobile number).
 *
 * The decision logic lives here — separate from the Room migration itself — so
 * it is pure JVM code with a host unit test: given the column names a database
 * already has, which ALTER statements are still needed? The migration answers
 * that with `PRAGMA table_info(people)`, which makes it idempotent: a database
 * created by the current entity already has every column, so nothing is
 * executed, while a database predating the columns is brought up to shape.
 */
object PersonOptionalColumns {

    /** Column name → SQLite type, in the order the columns are added. */
    val TYPES: List<Pair<String, String>> = listOf(
        "identityNumber" to "TEXT",
        "dob" to "INTEGER",
        "bloodGroup" to "TEXT",
        "mobile" to "TEXT",
    )

    /** The columns from [TYPES] that [existing] does not already contain. */
    fun missing(existing: Collection<String>): List<Pair<String, String>> =
        TYPES.filterNot { (name, _) -> existing.contains(name) }

    /**
     * `ALTER TABLE` statements that bring the `people` table up to the current
     * shape; empty when every optional column is already present.
     */
    fun alterStatements(existing: Collection<String>): List<String> =
        missing(existing).map { (name, type) -> "ALTER TABLE people ADD COLUMN $name $type" }
}

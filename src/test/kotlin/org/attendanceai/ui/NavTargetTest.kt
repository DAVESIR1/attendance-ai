/*
 * Attendance AI — nav target tests (host JVM).
 * Copyright (C) 2026 The Attendance AI Authors
 * GPL-3.0-or-later.
 */
package org.attendanceai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the navigation menu's target mapping: Home must be first and the
 * default, every menu item must have a distinct non-zero id (0 means
 * "no id" to Android's Menu API), and an unknown id must degrade to Home
 * instead of throwing.
 */
class NavTargetTest {

    @Test
    fun homeIsTheDefaultDestination() {
        assertEquals(NavTarget.HOME, NavTarget.values().first())
    }

    @Test
    fun menuContainsExactlyHomeReportsSettingsInOrder() {
        assertEquals(
            listOf(NavTarget.HOME, NavTarget.REPORTS, NavTarget.SETTINGS),
            NavTarget.values().toList(),
        )
    }

    @Test
    fun everyTargetHasADistinctNonZeroMenuId() {
        val ids = NavTarget.values().map { it.getMenuId() }
        assertTrue(ids.none { it == 0 })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun menuIdRoundTripsForEveryTarget() {
        NavTarget.values().forEach { target ->
            assertEquals(target, NavTarget.fromMenuId(target.getMenuId()))
        }
    }

    @Test
    fun unknownMenuIdFallsBackToHome() {
        assertEquals(NavTarget.HOME, NavTarget.fromMenuId(999))
        assertEquals(NavTarget.HOME, NavTarget.fromMenuId(0))
        assertEquals(NavTarget.HOME, NavTarget.fromMenuId(-1))
    }

    @Test
    fun labelsAreTheVisibleMenuText() {
        assertEquals("Home", NavTarget.HOME.getLabel())
        assertEquals("Reports", NavTarget.REPORTS.getLabel())
        assertEquals("Settings", NavTarget.SETTINGS.getLabel())
    }

    @Test
    fun neighbourIdsDifferFromEachOther() {
        assertNotEquals(NavTarget.HOME.getMenuId(), NavTarget.REPORTS.getMenuId())
        assertNotEquals(NavTarget.REPORTS.getMenuId(), NavTarget.SETTINGS.getMenuId())
    }
}
/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.enrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The optional details form: everything blank must be saveable. */
public class EnrolmentDetailsTest {

    @Test
    public void anUntouchedFormIsEmptyAndNullsEveryField() {
        EnrolmentDetails details = EnrolmentDetails.of("  ", null, "Unknown", "   ");

        assertTrue(details.isEmpty());
        assertNull(details.identityNumber);
        assertNull(details.dobMillis);
        assertNull(details.bloodGroup);
        assertNull(details.mobile);
    }

    @Test
    public void filledFieldsAreTrimmedAndKept() {
        EnrolmentDetails details =
                EnrolmentDetails.of(" ID-42 ", 1700000000000L, "O+", " +91 98765 43210 ");

        assertFalse(details.isEmpty());
        assertEquals("ID-42", details.identityNumber);
        assertEquals(Long.valueOf(1700000000000L), details.dobMillis);
        assertEquals("O+", details.bloodGroup);
        assertEquals("+91 98765 43210", details.mobile);
    }

    @Test
    public void bloodGroupUnknownMeansNothingChosen() {
        assertNull(EnrolmentDetails.bloodGroupOrNull("Unknown"));
        assertNull(EnrolmentDetails.bloodGroupOrNull("  unknown "));
        assertNull(EnrolmentDetails.bloodGroupOrNull(""));
        assertEquals("AB-", EnrolmentDetails.bloodGroupOrNull("AB-"));
        assertEquals(9, EnrolmentDetails.BLOOD_GROUPS.length);
    }

    @Test
    public void emptyMobileIsAllowedBecauseTheFieldIsOptional() {
        assertTrue(EnrolmentDetails.isValidMobile(""));
        assertTrue(EnrolmentDetails.isValidMobile(null));
        assertTrue(EnrolmentDetails.isValidMobile("   "));
    }

    @Test
    public void looseMobileRulesAcceptDigitsPlusSpacesAndHyphens() {
        assertTrue(EnrolmentDetails.isValidMobile("9876543"));            // 7 digits
        assertTrue(EnrolmentDetails.isValidMobile("+91 98765 43210"));    // 12 digits
        assertTrue(EnrolmentDetails.isValidMobile("98765-43210"));       // hyphen
        assertTrue(EnrolmentDetails.isValidMobile("123456789012345"));    // 15 digits
    }

    @Test
    public void looseMobileRulesRejectWrongLengthsAndCharacters() {
        assertFalse("6 digits is too short", EnrolmentDetails.isValidMobile("123456"));
        assertFalse("16 digits is too long",
                EnrolmentDetails.isValidMobile("1234567890123456"));
        assertFalse("letters are not allowed", EnrolmentDetails.isValidMobile("98765abc"));
        assertFalse("only '+', spaces and hyphens are allowed besides digits",
                EnrolmentDetails.isValidMobile("98765 (43)"));
    }
}

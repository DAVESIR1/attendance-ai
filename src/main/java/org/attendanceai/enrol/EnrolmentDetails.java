/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.enrol;

/**
 * The optional details collected after the guided capture, on the way to the
 * final save. Every field is optional: {@link #of} trims values and turns blank
 * input into null, so an untouched form stores SQL NULL in the nullable Room
 * columns instead of an empty string.
 *
 * Pure JVM code (no Android imports) so the loose mobile validation and the
 * blood-group vocabulary are unit-tested on the host.
 */
public final class EnrolmentDetails {

    /** Dropdown entries for the blood group field (spec's exact list). */
    public static final String[] BLOOD_GROUPS = {
        "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-", "Unknown",
    };

    /** Loose mobile bounds: a non-empty number carries 7-15 digits. */
    public static final int MOBILE_MIN_DIGITS = 7;
    public static final int MOBILE_MAX_DIGITS = 15;

    public final String identityNumber;
    public final Long dobMillis;
    public final String bloodGroup;
    public final String mobile;

    public EnrolmentDetails(String identityNumber, Long dobMillis, String bloodGroup, String mobile) {
        this.identityNumber = identityNumber;
        this.dobMillis = dobMillis;
        this.bloodGroup = bloodGroup;
        this.mobile = mobile;
    }

    /** A form where nothing was filled in. */
    public static EnrolmentDetails empty() {
        return new EnrolmentDetails(null, null, null, null);
    }

    /** Trims every text field and maps blanks — and the dropdown's "Unknown"
     *  blood group — to null, so an untouched form is fully empty. */
    public static EnrolmentDetails of(String identityNumber, Long dobMillis,
            String bloodGroup, String mobile) {
        return new EnrolmentDetails(clean(identityNumber), dobMillis,
                bloodGroupOrNull(bloodGroup), clean(mobile));
    }

    /** Trimmed value, or null when absent/blank. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Loose mobile validation: only digits, '+', spaces and hyphens are accepted,
     * and a non-empty value must carry 7-15 digits. No country format is
     * enforced beyond that, and an empty value is valid because the field is
     * optional.
     */
    public static boolean isValidMobile(String mobile) {
        String value = clean(mobile);
        if (value == null) {
            return true;
        }
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c != '+' && c != '-' && c != ' ') {
                return false;
            }
        }
        return digits >= MOBILE_MIN_DIGITS && digits <= MOBILE_MAX_DIGITS;
    }

    /** True when no optional field was filled in at all. */
    public boolean isEmpty() {
        return identityNumber == null && dobMillis == null
                && bloodGroup == null && mobile == null;
    }

    /**
     * Maps the dropdown's default "Unknown" entry to null, so an untouched
     * blood-group field stores SQL NULL like the other optional columns.
     */
    public static String bloodGroupOrNull(String selected) {
        String value = clean(selected);
        if (value == null || "Unknown".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }
}

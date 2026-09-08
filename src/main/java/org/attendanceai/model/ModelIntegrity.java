/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.model;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SHA-256 verification for the on-device model files.
 *
 * Checksums are stored in {@code models/checksums.sha256} in sha256sum
 * format ({@code <64 hex chars>  <filename>}). The pipeline refuses to run
 * when a pinned model is missing or its hash does not match — models are
 * downloaded and pinned as a deliberate, auditable action (see
 * scripts/fetch_models.sh), never silently.
 */
public final class ModelIntegrity {

    private ModelIntegrity() {
    }

    /** Hex-encoded SHA-256 digest of a file. */
    public static String sha256Of(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(digest.getDigestLength() * 2);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0x0F, 16));
                hex.append(Character.forDigit(b & 0x0F, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    /**
     * Loads a sha256sum-format file into an insertion-ordered map of
     * filename (lower-cased) to expected lowercase hex digest.
     *
     * @param checksumsFile the checksum file (may be absent → empty map)
     */
    public static Map<String, String> loadChecksums(File checksumsFile) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (checksumsFile == null || !checksumsFile.isFile()) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(checksumsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // Accept "<hex>  <name>" (sha256sum) and "<hex> *<name>"
                int space = line.indexOf(' ');
                if (space < 0) {
                    continue;
                }
                String hash = line.substring(0, space).trim().toLowerCase();
                String name = line.substring(space + 1).trim();
                if (name.startsWith("*")) {
                    name = name.substring(1);
                }
                if (hash.length() == 64 && !name.isEmpty()) {
                    result.put(name.toLowerCase(), hash);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Verifies one model file against the given expected hex digest.
     * A missing expected hash pins nothing → treated as unverified.
     */
    public static boolean verify(File modelFile, String expectedSha256) {
        if (modelFile == null || !modelFile.isFile() || expectedSha256 == null) {
            return false;
        }
        try {
            return expectedSha256.trim().toLowerCase().equals(sha256Of(modelFile));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Verifies every entry in the checksum file that names a file also
     * present in {@code modelDir}. Returns the list of failures
     * (human-readable); an empty list means everything checked out.
     */
    public static java.util.List<String> verifyAll(File modelDir, File checksumsFile) throws IOException {
        Map<String, String> checksums = loadChecksums(checksumsFile);
        java.util.List<String> failures = new java.util.ArrayList<String>();
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            File model = new File(modelDir, entry.getKey());
            if (!model.isFile()) {
                failures.add("missing: " + entry.getKey());
            } else if (!verify(model, entry.getValue())) {
                failures.add("hash mismatch: " + entry.getKey());
            }
        }
        return failures;
    }
}
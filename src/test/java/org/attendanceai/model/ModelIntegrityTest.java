/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ModelIntegrityTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // SHA-256("abc") — well-known digest for a tiny known input.
    private static final String SHA_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void hashesFileCorrectly() throws Exception {
        File file = folder.newFile("abc.txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(SHA_ABC, ModelIntegrity.sha256Of(file));
    }

    @Test
    public void verifyAcceptsMatchingHash() throws Exception {
        File file = folder.newFile("abc.txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(ModelIntegrity.verify(file, SHA_ABC));
        assertFalse(ModelIntegrity.verify(file, "0000"));
        assertFalse(ModelIntegrity.verify(file, null));
    }

    @Test
    public void parsesSha256sumFile() throws Exception {
        File checksums = folder.newFile("checksums.sha256");
        java.io.FileWriter w = new java.io.FileWriter(checksums);
        w.write(SHA_ABC + "  abc.txt\n");
        w.write("# a comment\n");
        w.write("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef *star.txt\n");
        w.close();

        Map<String, String> map = ModelIntegrity.loadChecksums(checksums);
        assertEquals(SHA_ABC, map.get("abc.txt"));
        assertEquals("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                map.get("star.txt"));
    }

    @Test
    public void verifyAllReportsFailures() throws Exception {
        File dir = folder.newFolder("models");
        File good = new File(dir, "abc.txt");
        try (FileOutputStream out = new FileOutputStream(good)) {
            out.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        File checksums = new File(dir, "checksums.sha256");
        java.io.FileWriter w = new java.io.FileWriter(checksums);
        w.write(SHA_ABC + "  abc.txt\n");
        w.write("0000000000000000000000000000000000000000000000000000000000000000  missing.bin\n");
        w.close();

        java.util.List<String> failures = ModelIntegrity.verifyAll(dir, checksums);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("missing.bin"));
    }
}
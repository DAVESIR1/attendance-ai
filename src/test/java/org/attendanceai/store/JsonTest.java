/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonTest {

    @Test
    public void roundTripsObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", "Alice");
        root.put("count", 3L);
        root.put("ratio", 0.5d);
        root.put("ok", Boolean.TRUE);
        root.put("nothing", null);
        List<Object> arr = new java.util.ArrayList<Object>();
        arr.add(1L);
        arr.add("two");
        root.put("items", arr);

        String text = Json.stringify(root);
        Map<String, Object> parsed = Json.asMap(Json.parse(text));
        assertEquals("Alice", parsed.get("name"));
        assertEquals(3L, parsed.get("count"));
        assertEquals(0.5d, (Double) parsed.get("ratio"), 1e-9);
        assertEquals(Boolean.TRUE, parsed.get("ok"));
        assertTrue(parsed.containsKey("nothing"));
        assertEquals(null, parsed.get("nothing"));
        assertEquals(1L, Json.asList(parsed.get("items")).get(0));
        assertEquals("two", Json.asList(parsed.get("items")).get(1));
    }

    @Test
    public void escapesStrings() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("quote", "say \"hi\"");
        root.put("slash", "a\\b");
        root.put("newline", "line1\nline2");
        String text = Json.stringify(root);
        Map<String, Object> parsed = Json.asMap(Json.parse(text));
        assertEquals("say \"hi\"", parsed.get("quote"));
        assertEquals("a\\b", parsed.get("slash"));
        assertEquals("line1\nline2", parsed.get("newline"));
    }

    @Test
    public void unicodeSurrogatesRoundTrip() {
        String emoji = "face \uD83D\uDE00"; // U+1F600
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("e", emoji);
        String text = Json.stringify(root);
        Map<String, Object> parsed = Json.asMap(Json.parse(text));
        assertEquals(emoji, parsed.get("e"));
    }

    @Test
    public void floatArrayHelpersRoundTrip() {
        float[] values = {1.5f, -2.0f, 3.25f};
        List<Object> list = Json.doubleList(values);
        float[] back = Json.floatArray(list);
        assertEquals(values.length, back.length);
        assertEquals(1.5f, back[0], 1e-6f);
        assertEquals(-2.0f, back[1], 1e-6f);
        assertEquals(3.25f, back[2], 1e-6f);
    }

    @Test
    public void prettyOutputIsValid() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("a", 1L);
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("b", "x");
        root.put("nested", nested);
        String pretty = Json.stringify(root, true);
        Map<String, Object> parsed = Json.asMap(Json.parse(pretty));
        assertEquals(1L, parsed.get("a"));
        assertEquals("x", Json.asMap(parsed.get("nested")).get("b"));
    }
}
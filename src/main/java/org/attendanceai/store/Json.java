/*
 * Attendance AI - offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.store;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer covering the shapes used by
 * {@link AttendanceStore}: objects, arrays, strings, numbers, booleans,
 * null. Round-trips the store's data faithfully and is unit-tested on a
 * plain JVM.
 *
 * A tiny parser keeps the binary small and the data format fully under our
 * control (no third-party JSON dependency for an offline-first app).
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- writer

    /** Serialises the object graph to JSON text. */
    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    public static String stringify(Object value, boolean pretty) {
        if (!pretty) {
            return stringify(value);
        }
        StringBuilder out = new StringBuilder();
        writePretty(out, value, 0);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Double || value instanceof Float) {
            out.append(formatNumber(((Number) value).doubleValue()));
        } else if (value instanceof Number) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value);
        } else if (value instanceof Iterable) {
            out.append('['); boolean first = true; for (Object v : (Iterable<?>) value) { if (!first) { out.append(','); } first = false; writeValue(out, v); } out.append(']');
        } else {
            writeString(out, value.toString());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writePretty(StringBuilder out, Object value, int depth) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{').append('\n');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(",\n");
                }
                first = false;
                indent(out, depth + 1);
                writeString(out, String.valueOf(entry.getKey()));
                out.append(": ");
                writePretty(out, entry.getValue(), depth + 1);
            }
            out.append('\n');
            indent(out, depth);
            out.append('}');
        } else if (value instanceof Iterable) {
            List<Object> items = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                items.add(item);
            }
            if (items.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[').append('\n');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.append(",\n");
                }
                indent(out, depth + 1);
                writePretty(out, items.get(i), depth + 1);
            }
            out.append('\n');
            indent(out, depth);
            out.append(']');
        } else {
            writeValue(out, value);
        }
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    // ---------------------------------------------------------------- reader

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw new IllegalArgumentException("trailing characters at offset " + pos);
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException(
                            "unexpected character '" + c + "' at offset " + pos);
            }
        }

        private Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    pos++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    pos++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw new IllegalArgumentException("expected string at offset " + pos);
            }
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'u':
                        appendUnicodeEscape(out);
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "invalid escape '\\" + esc + "' at offset " + (pos - 1));
                }
            }
        }

        /** Handles backslash-uXXXX escapes including surrogate-pair combinations. */
        private void appendUnicodeEscape(StringBuilder out) {
            if (pos + 4 > text.length()) {
                throw new IllegalArgumentException("truncated \\u escape");
            }
            int code = Integer.parseInt(text.substring(pos, pos + 4), 16);
            pos += 4;
            if (code >= 0xD800 && code <= 0xDBFF) {
                // High surrogate: look for a following \uDC00-\uDFFF low surrogate.
                if (pos + 6 <= text.length()
                        && text.charAt(pos) == '\\'
                        && text.charAt(pos + 1) == 'u') {
                    int low = Integer.parseInt(text.substring(pos + 2, pos + 6), 16);
                    if (low >= 0xDC00 && low <= 0xDFFF) {
                        pos += 6;
                        int combined = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                        out.append(Character.toChars(combined));
                        return;
                    }
                }
                out.append('\uFFFD');
                return;
            }
            if (code >= 0xDC00 && code <= 0xDFFF) {
                out.append('\uFFFD');
                return;
            }
            out.append((char) code);
        }

        private Object parseNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                while (pos < text.length() && isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < text.length() && isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String token = text.substring(start, pos);
            if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                try {
                    return Long.valueOf(token);
                } catch (NumberFormatException ignore) {
                    // fall through
                }
            }
            return Double.valueOf(token);
        }

        private boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        private void expect(char c) {
            if (pos >= text.length() || text.charAt(pos) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at offset " + pos);
            }
            pos++;
        }

        private void expect(String token) {
            if (!text.startsWith(token, pos)) {
                throw new IllegalArgumentException("expected '" + token + "' at offset " + pos);
            }
            pos += token.length();
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Parses JSON text; JSON null maps to Java null. */
    public static Object parse(String text) {
        return new Parser(text).parse();
    }

    /** Reads a UTF-8 JSON file. */
    public static Object readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
        }
        return parse(sb.toString());
    }

    /** Writes JSON to a file atomically (tmp + rename). */
    public static void writeFile(File file, Object value, boolean pretty) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create directory: " + dir);
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            writer.write(stringify(value, pretty));
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot rename temporary file to " + file);
        }
    }

    // --------------------------------------------------------- typed accessors

    public static float asFloat(Object value, float fallback) {
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    public static long asLong(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public static boolean asBoolean(Object value, boolean fallback) {
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    public static String asString(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    public static List<Object> asList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    /** Converts a float[] to a JSON list of doubles. */
    public static List<Object> doubleList(float[] values) {
        List<Object> list = new ArrayList<Object>(values.length);
        for (float value : values) {
            list.add((double) value);
        }
        return list;
    }

    /** Converts a JSON list of numbers to a float[]. */
    public static float[] floatArray(List<Object> values) {
        if (values == null) {
            return new float[0];
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            Object v = values.get(i);
            out[i] = v instanceof Number ? ((Number) v).floatValue() : 0f;
        }
        return out;
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = String.format("%04x", (int) c);
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}

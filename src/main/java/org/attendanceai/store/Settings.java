/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.store;

import java.util.HashMap;
import java.util.Map;

/**
 * User-facing configuration. Pure value object — no Android imports —
 * serialised by {@link AttendanceStore}.
 */
public final class Settings {

    /** MediaPipe Face Landmarker confidence floors. */
    public float minFaceDetectionConfidence = 0.5f;
    public float minTrackingConfidence = 0.5f;
    public float minFacePresenceConfidence = 0.5f;

    /** Embedding model input geometry (MobileFaceNet default: 112×112). */
    public int modelWidth = 112;
    public int modelHeight = 112;

    /** Matcher empathy: minimum cosine similarity to accept a match. */
    public float similarityThreshold = 0.60f;

    /** Consecutive frames that must agree before a match sticks. */
    public int stableFrames = 3;

    /** Minimum gap between two punches for the same person. */
    public long checkInCooldownMs = 30000L;

    /** Embedding engine: "tflite" (MobileFaceNet) or "signature" (fallback). */
    public String embedder = EMBEDDER_TFLITE;

    public static final String EMBEDDER_TFLITE = "tflite";
    public static final String EMBEDDER_SIGNATURE = "signature";

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("minFaceDetectionConfidence", (double) minFaceDetectionConfidence);
        map.put("minTrackingConfidence", (double) minTrackingConfidence);
        map.put("minFacePresenceConfidence", (double) minFacePresenceConfidence);
        map.put("modelWidth", (long) modelWidth);
        map.put("modelHeight", (long) modelHeight);
        map.put("similarityThreshold", (double) similarityThreshold);
        map.put("stableFrames", (long) stableFrames);
        map.put("checkInCooldownMs", checkInCooldownMs);
        map.put("embedder", embedder);
        return map;
    }

    public void fromMap(Map<String, Object> map) {
        if (map == null) {
            return;
        }
        minFaceDetectionConfidence = Json.asFloat(map.get("minFaceDetectionConfidence"), minFaceDetectionConfidence);
        minTrackingConfidence = Json.asFloat(map.get("minTrackingConfidence"), minTrackingConfidence);
        minFacePresenceConfidence = Json.asFloat(map.get("minFacePresenceConfidence"), minFacePresenceConfidence);
        modelWidth = (int) Json.asLong(map.get("modelWidth"), modelWidth);
        modelHeight = (int) Json.asLong(map.get("modelHeight"), modelHeight);
        similarityThreshold = Json.asFloat(map.get("similarityThreshold"), similarityThreshold);
        stableFrames = (int) Json.asLong(map.get("stableFrames"), stableFrames);
        checkInCooldownMs = Json.asLong(map.get("checkInCooldownMs"), checkInCooldownMs);
        Object embedderValue = map.get("embedder");
        if (embedderValue instanceof String) {
            String value = (String) embedderValue;
            if (EMBEDDER_TFLITE.equals(value) || EMBEDDER_SIGNATURE.equals(value)) {
                embedder = value;
            }
        }
    }
}
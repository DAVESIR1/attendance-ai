/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

/**
 * Turns an aligned face tensor (rgb channel values in [-1, 1], shape
 * h×w×3, row-major) into a fixed-size embedding vector.
 */
public interface EmbeddingEngine {

    /** Expected tensor length = modelWidth * modelHeight * 3. */
    int inputLength();

    /** Embedding dimension. */
    int outputDim();

    /**
     * One-line description of the loaded model's tensor contract (input → output
     * shapes), or an empty string when the engine has nothing to report. Shown
     * on screen so a bad model is visible without logcat.
     */
    default String contractNote() {
        return "";
    }

    /** Embedding of the aligned tensor. Never null; may be zeros on failure. */
    float[] embed(float[] alignedTensor);
}
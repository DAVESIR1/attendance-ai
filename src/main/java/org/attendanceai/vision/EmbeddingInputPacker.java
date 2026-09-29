/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

/**
 * Batch-slot arithmetic for embedding-model inputs. Pure JVM code — no
 * android.* imports — so it is unit-tested on the host.
 *
 * Some MobileFaceNet conversions pin the input batch size (for example
 * {@code [2, 112, 112, 3]}), while the pipeline always produces a single
 * aligned face. The engine duplicates the single face across every batch
 * slot; this class owns the arithmetic behind that decision so it can be
 * tested without a TensorFlow Lite runtime.
 */
public final class EmbeddingInputPacker {

    private EmbeddingInputPacker() {
    }

    /** Elements in one face slot of a width×height RGB model input. */
    public static int perSlotLength(int width, int height) {
        return width * height * 3;
    }

    /** Elements of the full model input ({@code batch} face slots). */
    public static int packedLength(int width, int height, int batch) {
        return perSlotLength(width, height) * Math.max(1, batch);
    }

    /**
     * True when {@code srcLength} holds at least one complete face slot. A
     * single-slot tensor is valid input for a multi-slot model: the engine
     * repeats it in every batch position.
     */
    public static boolean canPack(int srcLength, int width, int height, int batch) {
        if (batch < 1) {
            return false;
        }
        return srcLength >= perSlotLength(width, height);
    }

    /**
     * Copies ONE aligned face into every batch slot of a packed model input.
     *
     * The bundled mobilefacenet.tflite declares {@code input: [2, 112, 112, 3]}
     * and {@code embeddings: [2, 192]} (verified against the model file), while
     * the pipeline always produces a single aligned face. Leaving the second
     * slot empty used to make the interpreter fail on the whole batch, so every
     * embedding came back as a silent all-zero vector. Duplicating the face in
     * every slot keeps the graph fed with valid data, and the caller then reads
     * one per-face row out of the output with {@link #embeddingRow}.
     *
     * @param src   one face slot, length ≥ perSlotLength(width, height)
     * @param dst   destination, length ≥ packedLength(width, height, batch)
     * @return true when the copy happened; false when either array is too small
     */
    public static boolean packSingleFace(float[] src, int width, int height, int batch,
            float[] dst) {
        int perSlot = perSlotLength(width, height);
        if (src == null || dst == null || batch < 1
                || !canPack(src.length, width, height, batch)
                || dst.length < packedLength(width, height, batch)) {
            return false;
        }
        for (int slot = 0; slot < batch; slot++) {
            System.arraycopy(src, 0, dst, slot * perSlot, perSlot);
        }
        return true;
    }

    /**
     * True when a rank-2 model output ({@code [batch, dim]}) carries exactly one
     * row per batch slot the engine sent. Any other count means the model
     * contract is not what the engine was built for, and the engine must fail
     * loudly instead of returning zeros.
     */
    public static boolean outputMatchesBatch(int rows, int batch) {
        return batch > 0 && rows == batch;
    }

    /**
     * Copies the embedding of one batch slot out of a rank-2 output.
     *
     * @return a fresh array (never null), or an EMPTY array when the index is
     *         out of range or a row is missing — callers treat an empty vector
     *         as a visible failure, never as a valid all-zero embedding
     */
    public static float[] embeddingRow(float[][] rows, int slotIndex) {
        if (rows == null || slotIndex < 0 || slotIndex >= rows.length) {
            return new float[0];
        }
        float[] row = rows[slotIndex];
        if (row == null || row.length == 0) {
            return new float[0];
        }
        return row.clone();
    }
}

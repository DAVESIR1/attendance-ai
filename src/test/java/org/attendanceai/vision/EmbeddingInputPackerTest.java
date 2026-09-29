/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmbeddingInputPackerTest {

    @Test
    public void perSlotLengthIsWidthTimesHeightTimesThree() {
        assertEquals(112 * 112 * 3, EmbeddingInputPacker.perSlotLength(112, 112));
        assertEquals(160 * 160 * 3, EmbeddingInputPacker.perSlotLength(160, 160));
    }

    @Test
    public void packedLengthMultipliesByBatch() {
        int perSlot = EmbeddingInputPacker.perSlotLength(112, 112);
        assertEquals(perSlot, EmbeddingInputPacker.packedLength(112, 112, 1));
        assertEquals(perSlot * 2, EmbeddingInputPacker.packedLength(112, 112, 2));
        // A missing/zero batch must not produce a zero-length buffer.
        assertEquals(perSlot, EmbeddingInputPacker.packedLength(112, 112, 0));
    }

    @Test
    public void singleFaceIsPackableIntoBatchTwoModel() {
        // The shipped mobilefacenet.tflite pins the input to [2, 112, 112, 3];
        // the pipeline always supplies one aligned face. That must be packable.
        int singleFace = EmbeddingInputPacker.perSlotLength(112, 112);
        assertTrue(EmbeddingInputPacker.canPack(singleFace, 112, 112, 2));
        assertTrue(EmbeddingInputPacker.canPack(singleFace, 112, 112, 1));
    }

    @Test
    public void undersizedTensorIsRejected() {
        int singleFace = EmbeddingInputPacker.perSlotLength(112, 112);
        assertFalse(EmbeddingInputPacker.canPack(singleFace - 1, 112, 112, 2));
        assertFalse(EmbeddingInputPacker.canPack(0, 112, 112, 2));
    }

    @Test
    public void invalidBatchIsRejected() {
        int singleFace = EmbeddingInputPacker.perSlotLength(112, 112);
        assertFalse(EmbeddingInputPacker.canPack(singleFace, 112, 112, 0));
        assertFalse(EmbeddingInputPacker.canPack(singleFace, 112, 112, -1));
    }

    @Test
    public void singleFaceIsDuplicatedIntoEveryBatchSlot() {
        // The regression behind "every match scores 0.00": the model wanted a
        // batch of two 112x112x3 faces and got one, so run() threw and the
        // engine handed back zeros. The packed buffer must hold the aligned
        // face twice — not real data followed by an empty (all-zero) slot.
        int perSlot = EmbeddingInputPacker.perSlotLength(112, 112);
        float[] face = filled(perSlot, 0.5f);
        float[] packed = new float[EmbeddingInputPacker.packedLength(112, 112, 2)];

        assertTrue(EmbeddingInputPacker.packSingleFace(face, 112, 112, 2, packed));

        assertArrayEquals(face, java.util.Arrays.copyOfRange(packed, 0, perSlot), 0f);
        assertArrayEquals(face, java.util.Arrays.copyOfRange(packed, perSlot, perSlot * 2), 0f);
        // Both slots carry the face; the second slot is definitely not zeros.
        assertNotEquals(0f, packed[perSlot]);
    }

    @Test
    public void batchOneLeavesTheTensorUntouchedBeyondOneSlot() {
        int perSlot = EmbeddingInputPacker.perSlotLength(112, 112);
        float[] face = filled(perSlot, -1f);
        float[] packed = new float[perSlot];

        assertTrue(EmbeddingInputPacker.packSingleFace(face, 112, 112, 1, packed));
        assertArrayEquals(face, packed, 0f);
    }

    @Test
    public void undersizedFaceOrDestinationIsNotPacked() {
        int perSlot = EmbeddingInputPacker.perSlotLength(112, 112);
        float[] face = filled(perSlot, 0.25f);
        float[] tooSmall = new float[perSlot * 2 - 1];

        // Half a face, a destination without room for both slots, nulls and a
        // zero batch must all be refused instead of silently half-filling.
        assertFalse(EmbeddingInputPacker.packSingleFace(new float[perSlot - 1], 112, 112, 2,
                new float[perSlot * 2]));
        assertFalse(EmbeddingInputPacker.packSingleFace(face, 112, 112, 2, tooSmall));
        assertFalse(EmbeddingInputPacker.packSingleFace(null, 112, 112, 2, new float[perSlot * 2]));
        assertFalse(EmbeddingInputPacker.packSingleFace(face, 112, 112, 2, null));
        assertFalse(EmbeddingInputPacker.packSingleFace(face, 112, 112, 0, new float[perSlot]));
    }

    @Test
    public void perFaceRowIsExtractedFromBatchTwoOutput() {
        // [2, 192] output: slot 0 belongs to the face we fed (every slot got
        // the same face, so both rows are identical for this model).
        float[][] rows = new float[2][192];
        for (int i = 0; i < 192; i++) {
            rows[0][i] = i * 0.01f;
            rows[1][i] = i * 0.01f;
        }

        float[] embedding = EmbeddingInputPacker.embeddingRow(rows, 0);
        assertEquals(192, embedding.length);
        assertEquals(0f, embedding[0], 1e-6f);
        assertEquals(1.91f, embedding[191], 1e-5f);
        // A copy, not the model's live row buffer.
        embedding[0] = 42f;
        assertEquals(0f, rows[0][0], 1e-6f);
    }

    @Test
    public void missingRowsNeverProduceAZeroVector() {
        // An empty result is a visible failure; a zero vector would look like a
        // valid embedding and silently match nobody.
        assertEquals(0, EmbeddingInputPacker.embeddingRow(null, 0).length);
        assertEquals(0, EmbeddingInputPacker.embeddingRow(new float[2][192], -1).length);
        assertEquals(0, EmbeddingInputPacker.embeddingRow(new float[2][192], 2).length);
        assertEquals(0, EmbeddingInputPacker.embeddingRow(new float[2][], 0).length);
    }

    @Test
    public void outputRowCountMustMatchTheInputBatch() {
        assertTrue(EmbeddingInputPacker.outputMatchesBatch(2, 2));
        assertTrue(EmbeddingInputPacker.outputMatchesBatch(1, 1));
        assertFalse(EmbeddingInputPacker.outputMatchesBatch(1, 2));
        assertFalse(EmbeddingInputPacker.outputMatchesBatch(2, 1));
        assertFalse(EmbeddingInputPacker.outputMatchesBatch(0, 0));
    }

    private static float[] filled(int length, float value) {
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            out[i] = value;
        }
        return out;
    }
}

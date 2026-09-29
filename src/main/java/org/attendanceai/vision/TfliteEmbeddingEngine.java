/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

/**
 * Runs a MobileFaceNet-style embedding model with the TensorFlow Lite Java
 * (Android) interpreter. Constructed on the same single background executor
 * that performs inference (native resources are thread-affine).
 *
 * The model is loaded from a {@link File} (the pipeline copies
 * mobilefacenet.tflite out of assets into private storage before use so the
 * SHA-256 integrity check can run — see docs/PLAN.md).
 *
 * The bundled mobilefacenet.tflite is a batch-2 model — input [2,112,112,3],
 * output [2,192] (confirmed against the model file itself, and re-checked from
 * the interpreter's tensors at construction). One aligned face is therefore
 * duplicated across both batch slots and slot 0 of the output is returned as
 * that face's embedding; any contract mismatch is a loud failure, never a
 * silent all-zero vector.
 */
public final class TfliteEmbeddingEngine implements EmbeddingEngine {

    private static final String TAG = "TfliteEmbeddingEngine";

    private final Interpreter interpreter;
    private final ByteBuffer inputBuffer;
    /**
     * Output storage matching the model's output tensor rank: rank-2 models
     * (the bundled mobilefacenet.tflite has [2,192]) need a float[][] because
     * the TFLite Java API rejects a flat float[384] with "Cannot copy from a
     * TensorFlowLite tensor with shape [2, 192] to a Java object with shape
     * [384]" — the old flat buffer made every embedding attempt throw, which
     * the old catch swallowed into all-zero vectors (every match scored 0.00).
     */
    private final float[] outputFlat;
    private final float[][] outputRows;
    private final int outputBatchRows;
    private final int rowLength;
    private final int modelWidth;
    private final int modelHeight;
    private final int outputDim;
    private final int inputBatchSize;
    /** One aligned face repeated into every batch slot of the packed input. */
    private final float[] packedInput;
    /** Human-readable model contract, surfaced in the app banner. */
    private volatile String contractNote = "not verified";

    public TfliteEmbeddingEngine(File modelFile, int modelWidth, int modelHeight)
            throws IOException {
        if (!modelFile.isFile()) {
            throw new IOException("model file missing: " + modelFile);
        }
        this.modelWidth = modelWidth;
        this.modelHeight = modelHeight;

        this.interpreter = new Interpreter(modelFile);
        try {
            // Read the model's native input shape so we can resize only if the
            // graph allows it. Some MobileFaceNet conversions hardcode batch=2
            // (e.g. final RESHAPE → [2,192]); resizing such a model to batch 1
            // triggers reshape.cc:92 num_input_elements != num_output_elements
            // (192 != 384), Node 229 RESHAPE.
            //
            // Best effort: keep the model's native shape when the leading dim is
            // not 1. Fall back to the requested {1, H, W, 3} only when the native
            // batch dimension is 1 (fully dynamic) or -1.
            int[] nativeShape = interpreter.getInputTensor(0).shape();
            int nativeBatch = nativeShape.length > 0 ? nativeShape[0] : 1;
            boolean resize;
            if (nativeBatch == 1 || nativeBatch == -1) {
                // Dynamic batch or already batch-1: enforce our desired batch.
                resize = true;
            } else {
                // Fixed batch > 1 (e.g. 2): keep native shape; do not resize.
                resize = false;
            }
            if (resize) {
                interpreter.resizeInput(0, new int[]{1, modelHeight, modelWidth, 3});
            }
            interpreter.allocateTensors();

            Tensor outputTensor = interpreter.getOutputTensor(0);
            int dim = 1;
            for (int d : outputTensor.shape()) {
                dim *= d;
            }
            if (dim <= 0) {
                throw new IOException("model output shape is empty: "
                        + java.util.Arrays.toString(outputTensor.shape()));
            }
            this.outputDim = dim;
            int[] outShape = outputTensor.shape();
            if (outShape.length == 1) {
                this.outputFlat = new float[outShape[0]];
                this.outputRows = null;
                this.outputBatchRows = 1;
            } else if (outShape.length == 2) {
                this.outputFlat = null;
                this.outputRows = new float[outShape[0]][outShape[1]];
                this.outputBatchRows = outShape[0];
            } else {
                throw new IOException("unsupported model output rank "
                        + outShape.length + ": " + java.util.Arrays.toString(outShape));
            }
            this.rowLength = outputDim / outputBatchRows;

            // Re-read the input shape AFTER the optional resize: this is the
            // contract the interpreter will actually enforce at run() time.
            int[] inputShape = interpreter.getInputTensor(0).shape();
            int batch = inputShape.length > 0 && inputShape[0] > 0 ? inputShape[0] : 1;
            this.inputBatchSize = batch;

            // The bundled mobilefacenet.tflite declares input [2,112,112,3] and
            // output [2,192]. Everything below is checked against the real
            // tensor shapes (verified for this model with an offline FlatBuffer
            // dump), and any mismatch is a LOUD construction failure: the
            // pipeline then runs in signature mode with a visible banner note
            // instead of returning all-zero embeddings that match nobody.
            int perSlot = modelHeight * modelWidth * 3;
            int inputElements = perSlot * batch;
            int declaredInput = elementCount(inputShape);
            if (declaredInput > 0 && declaredInput != inputElements) {
                throw new IOException("model input " + java.util.Arrays.toString(inputShape)
                        + " holds " + declaredInput + " elements, but an aligned face tensor of "
                        + modelHeight + "x" + modelWidth + "x3 in " + batch + " batch slot(s) needs "
                        + inputElements);
            }
            if (inputShape.length == 4
                    && (inputShape[1] != modelHeight || inputShape[2] != modelWidth
                        || inputShape[3] != 3)) {
                throw new IOException("model input " + java.util.Arrays.toString(inputShape)
                        + " does not match the aligned face tensor "
                        + modelHeight + "x" + modelWidth + "x3");
            }
            if (outputBatchRows != batch) {
                throw new IOException("model output " + java.util.Arrays.toString(outShape)
                        + " has " + outputBatchRows + " row(s) but the input batch is " + batch);
            }
            this.packedInput = new float[inputElements];
            this.inputBuffer = ByteBuffer.allocateDirect(inputElements * 4)
                    .order(ByteOrder.nativeOrder());
            this.contractNote = java.util.Arrays.toString(inputShape) + " -> "
                    + java.util.Arrays.toString(outShape) + " (batch " + batch + ", dim "
                    + rowLength + ")";
            Log.i(TAG, "embedding model contract " + contractNote);
        } catch (RuntimeException e) {
            interpreter.close();
            throw new IOException("failed to prepare embedding model", e);
        }
    }

    /** Product of a tensor shape; -1 when any dimension is unknown (-1). */
    private static int elementCount(int[] shape) {
        int elements = 1;
        for (int dimension : shape) {
            if (dimension <= 0) {
                return -1;
            }
            elements *= dimension;
        }
        return elements;
    }

    @Override
    public int inputLength() {
        return modelHeight * modelWidth * 3 * inputBatchSize;
    }

    @Override
    public int outputDim() {
        // The embedding vector returned by embed() — 192 for the bundled
        // [2,192] model — not the flattened 2×192 tensor element count.
        return rowLength;
    }

    @Override
    public float[] embed(float[] alignedTensor) {
        int perSlot = EmbeddingInputPacker.perSlotLength(modelWidth, modelHeight);
        if (alignedTensor == null
                || !EmbeddingInputPacker.canPack(alignedTensor.length,
                        modelWidth, modelHeight, inputBatchSize)) {
            // Fail visibly: an empty vector makes the pipeline report
            // "embedding failed" instead of silently matching nothing.
            Log.e(TAG, "aligned tensor too small for the model input: "
                    + (alignedTensor == null ? "null" : alignedTensor.length)
                    + " < " + perSlot);
            return new float[0];
        }
        try {
            // The pipeline supplies ONE aligned face, while the bundled
            // mobilefacenet.tflite pins the batch to 2 ([2,112,112,3] in,
            // [2,192] out). Fill every batch slot with that face so the graph
            // always sees a complete batch — feeding one image and leaving the
            // second slot empty makes run() throw, which is what used to end in
            // a silent all-zero embedding for every face.
            if (!EmbeddingInputPacker.packSingleFace(alignedTensor, modelWidth, modelHeight,
                    inputBatchSize, packedInput)) {
                Log.e(TAG, "cannot pack one aligned face into batch " + inputBatchSize
                        + " (slot " + perSlot + " floats, buffer " + packedInput.length + ")");
                return new float[0];
            }
            FloatBuffer floats = inputBuffer.asFloatBuffer();
            floats.position(0);
            floats.put(packedInput);
            inputBuffer.rewind();
            // The Java destination must mirror the tensor shape: [2,192] needs
            // a float[2][192], a flat float[384] is rejected by TFLite.
            if (outputRows != null) {
                if (!EmbeddingInputPacker.outputMatchesBatch(outputRows.length, inputBatchSize)) {
                    Log.e(TAG, "model output has " + outputRows.length
                            + " row(s) but the input batch is " + inputBatchSize);
                    return new float[0];
                }
                interpreter.run(inputBuffer, outputRows);
                // One embedding per batch slot; slot 0 is the face we fed.
                float[] row = EmbeddingInputPacker.embeddingRow(outputRows, 0);
                if (row.length == 0) {
                    Log.e(TAG, "model returned an empty embedding row (batch " + inputBatchSize
                            + ", rowLength " + rowLength + ")");
                }
                return row;
            }
            interpreter.run(inputBuffer, outputFlat);
            float[] row = new float[rowLength];
            System.arraycopy(outputFlat, 0, row, 0, row.length);
            return row;
        } catch (RuntimeException e) {
            // Loud, never a zero vector: an empty result makes the pipeline
            // report a visible "embedding failed" instead of matching nobody.
            Log.e(TAG, "tflite embedding failed (contract " + contractNote + ")", e);
            return new float[0];
        }
    }

    /** Describes the loaded model's real input → output tensor shapes. */
    @Override
    public String contractNote() {
        return contractNote;
    }
}
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
 */
public final class TfliteEmbeddingEngine implements EmbeddingEngine {

    private final Interpreter interpreter;
    private final ByteBuffer inputBuffer;
    private final float[] output;
    private final int modelWidth;
    private final int modelHeight;
    private final int outputDim;
    private final int inputBatchSize;

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
            this.output = new float[dim];

            int inputBatchSize = nativeShape.length > 0 ? nativeShape[0] : 1;
            this.inputBatchSize = inputBatchSize;
            int elements = modelHeight * modelWidth * 3 * inputBatchSize;
            this.inputBuffer = ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder());
        } catch (RuntimeException e) {
            interpreter.close();
            throw new IOException("failed to prepare embedding model", e);
        }
    }

    @Override
    public int inputLength() {
        return modelHeight * modelWidth * 3 * inputBatchSize;
    }

    @Override
    public int outputDim() {
        return outputDim;
    }

    @Override
    public float[] embed(float[] alignedTensor) {
        int needed = inputLength();
        if (alignedTensor == null || alignedTensor.length < needed) {
            return new float[outputDim];
        }
        try {
            int batch = inputBatchSize;
            FloatBuffer floats = inputBuffer.asFloatBuffer();
            floats.position(0);
            // Write one aligned face per batch slot. If the model uses batch > 1
            // we duplicate the single aligned tensor across all batch slots so the
            // graph still produces valid outputs (each row is the same embedding).
            for (int b = 0; b < batch; b++) {
                floats.put(alignedTensor, 0, needed / batch);
            }
            inputBuffer.rewind();
            interpreter.run(inputBuffer, output);
            // Return the first row only — callers expect a single embedding.
            float[] row = new float[outputDim / batch];
            System.arraycopy(output, 0, row, 0, row.length);
            return row;
        } catch (RuntimeException e) {
            return new float[outputDim];
        }
    }
}
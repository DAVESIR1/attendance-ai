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

    public TfliteEmbeddingEngine(File modelFile, int modelWidth, int modelHeight)
            throws IOException {
        if (!modelFile.isFile()) {
            throw new IOException("model file missing: " + modelFile);
        }
        this.modelWidth = modelWidth;
        this.modelHeight = modelHeight;

        this.interpreter = new Interpreter(modelFile);
        try {
            interpreter.resizeInput(0, new int[]{1, modelHeight, modelWidth, 3});
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

            int elements = modelHeight * modelWidth * 3;
            this.inputBuffer = ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder());
        } catch (RuntimeException e) {
            interpreter.close();
            throw new IOException("failed to prepare embedding model", e);
        }
    }

    @Override
    public int inputLength() {
        return modelHeight * modelWidth * 3;
    }

    @Override
    public int outputDim() {
        return outputDim;
    }

    @Override
    public float[] embed(float[] alignedTensor) {
        if (alignedTensor == null || alignedTensor.length < inputLength()) {
            return new float[outputDim];
        }
        try {
            FloatBuffer floats = inputBuffer.asFloatBuffer();
            floats.position(0);
            floats.put(alignedTensor, 0, inputLength());
            inputBuffer.rewind();
            interpreter.run(inputBuffer, output);
            return output.clone();
        } catch (RuntimeException e) {
            return new float[outputDim];
        }
    }
}
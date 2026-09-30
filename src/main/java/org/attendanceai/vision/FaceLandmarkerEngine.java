/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import org.attendanceai.camera.CameraFrame;

/**
 * Thin wrapper around the MediaPipe Tasks Face Landmarker. Runs in
 * {@link RunningMode#IMAGE} from the single pipeline executor (MediaPipe
 * native tasks are thread-affine — never call from multiple threads).
 *
 * Uses createFromFile loads from app assets; the pipeline may pre-verify the
 * model hash via the files-based factory.
 */
public final class FaceLandmarkerEngine implements AutoCloseable {

    private static final String TAG = "FaceLandmarkerEngine";

    private final FaceLandmarker landmarker;
    /**
     * Buffer of the staged model when the landmarker had to be created from
     * memory ({@code createFromBuffer}). Kept referenced so the JVM cannot
     * collect it while MediaPipe's native side still points at it.
     */
    private final ByteBuffer modelBuffer;
    private volatile boolean closed;

    /** Asset-only form (kept for paths that have no staged model file). */
    public FaceLandmarkerEngine(Context context, String assetPath) {
        this(context, assetPath, null);
    }

    /**
     * Creates the landmarker, trying progressively more direct model sources:
     * asset path → direct {@link ByteBuffer} of the staged file → staged
     * {@link File}. MediaPipe can fail to create the task on a particular
     * device/ROM (asset-manager bridge, asset cache copy, native graph init)
     * in ways the app cannot influence from Java — but the same native code
     * accepts the model by different routes, so a fixed file in private
     * storage is tried before giving up.
     *
     * The FIRST failure is rethrown (later attempts attached as suppressed
     * exceptions) so the on-screen diagnosis still names the original cause.
     */
    public FaceLandmarkerEngine(Context context, String assetPath, File stagedModel) {
        Throwable firstFailure = null;
        FaceLandmarker created = null;
        ByteBuffer buffer = null;

        try {
            created = FaceLandmarker.createFromFile(context, assetPath);
        } catch (Throwable failure) {
            firstFailure = failure;
            Log.e(TAG, "landmarker creation from asset failed", failure);
        }

        if (created == null && stagedModel != null && stagedModel.isFile()) {
            try {
                buffer = readDirect(stagedModel);
                created = FaceLandmarker.createFromBuffer(context, buffer);
            } catch (Throwable failure) {
                Log.e(TAG, "landmarker creation from staged buffer failed", failure);
                attach(firstFailure, failure);
                buffer = null; // never keep memory for a failed attempt
            }
        }

        if (created == null && stagedModel != null && stagedModel.isFile()) {
            try {
                created = FaceLandmarker.createFromFile(context, stagedModel);
            } catch (Throwable failure) {
                Log.e(TAG, "landmarker creation from staged file failed", failure);
                attach(firstFailure, failure);
            }
        }

        if (created == null) {
            throw rethrow(firstFailure);
        }
        this.landmarker = created;
        this.modelBuffer = buffer;
    }

    /** Reads the model file into the direct buffer createFromBuffer expects. */
    private static ByteBuffer readDirect(File file) throws IOException {
        long length = file.length();
        if (length <= 0L || length > Integer.MAX_VALUE) {
            throw new IOException("unusable model file size: " + length);
        }
        byte[] bytes = new byte[(int) length];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    throw new IOException("short read at " + offset + "/" + bytes.length);
                }
                offset += read;
            }
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.rewind();
        return buffer;
    }

    /** Records a later attempt's failure on the first one (never drops it). */
    private static void attach(Throwable primary, Throwable later) {
        if (primary != null && primary != later) {
            primary.addSuppressed(later);
        }
    }

    /** Re-throws the first failure unchanged when possible (keeps its class). */
    private static RuntimeException rethrow(Throwable failure) {
        if (failure == null) {
            return new IllegalStateException("face landmarker could not be created");
        }
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new IllegalStateException("face landmarker could not be created", failure);
    }

    /** Detects faces in an ARGB frame. Empty list when none found. */
    public List<Face> detect(CameraFrame frame) {
        List<Face> out = new ArrayList<Face>();
        if (closed || frame == null || frame.argb().length < frame.width * frame.height) {
            return out;
        }
        Bitmap bitmap = null;
        MPImage mpImage = null;
        try {
            // Classic 4-argument overload: colours, width, height, config.
            // The previous 6-argument call (colors, width, height, 0, width,
            // config) actually maps to createBitmap(colors, offset, width,
            // height, rowStride?, config) — the literal 0 landed in `height`,
            // so Bitmap.createBitmap threw "width must be > 0" on every frame
            // and detection silently produced no faces.
            bitmap = Bitmap.createBitmap(frame.argb(), frame.width, frame.height,
                    Bitmap.Config.ARGB_8888);
            mpImage = new BitmapImageBuilder(bitmap).build();
            FaceLandmarkerResult result = landmarker.detect(mpImage);
            if (result == null) {
                return out;
            }
            for (List<NormalizedLandmark>
                    landmarkList : result.faceLandmarks()) {
                if (landmarkList == null || landmarkList.isEmpty()) {
                    continue;
                }
                out.add(buildFace(landmarkList));
            }
            return out;
        } catch (RuntimeException e) {
            // Surface failures instead of degrading them to a silent "no
            // face": FacePipeline turns this into a visible "inference error"
            // (or "face detection failed" during enrolment) plus a logcat entry.
            Log.e(TAG, "face detection failed", e);
            throw e;
        } finally {
            if (mpImage != null) {
                mpImage.close();
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static Face buildFace(List<NormalizedLandmark> list) {
        int count = Math.min(list.size(), Face.LANDMARKS_PER_FACE);
        float[] landmarks = new float[count * Face.LANDMARK_DIM];
        float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
        for (int i = 0; i < count; i++) {
            NormalizedLandmark lm = list.get(i);
            float x = lm.x();
            float y = lm.y();
            float z = lm.z();
            landmarks[i * Face.LANDMARK_DIM] = x;
            landmarks[i * Face.LANDMARK_DIM + 1] = y;
            landmarks[i * Face.LANDMARK_DIM + 2] = z;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        float diag = (float) Math.hypot(maxX - minX, maxY - minY);
        float quality = (count / (float) Face.LANDMARKS_PER_FACE)
                * Math.min(1f, diag / 0.6f);
        return new Face(landmarks, count,
                new float[]{minX, minY, maxX, maxY}, quality);
    }

    @Override
    public void close() {
        closed = true;
        landmarker.close();
    }
}
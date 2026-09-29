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
    private volatile boolean closed;

    public FaceLandmarkerEngine(Context context, String assetPath) {
        this.landmarker = FaceLandmarker.createFromFile(context, assetPath);
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
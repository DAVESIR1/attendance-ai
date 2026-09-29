/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

import android.view.Surface;

/**
 * Compression point for the capture layer. The UI and pipeline only talk to
 * this interface, so the deprecated Camera2 implementation can later be
 * swapped for an androidx.camera one without touching the rest of the app.
 */
public interface CameraBackend {

    /** Called on the capture thread with each frame (copy data fast). */
    interface FrameListener {
        void onFrame(CameraFrame frame);
    }

    /**
     * Starts the camera. Returns false when no usable camera exists
     * (permission denied, no camera hardware, init failure).
     */
    default boolean start(FrameListener listener, int width, int height) {
        return start(listener, width, height, null);
    }

    /**
     * Starts capture and optionally renders the live preview to [preview].
     * Implementations that do not support a preview may use the three-argument
     * method; the production Camera2 backend uses both preview and analysis.
     */
    default boolean start(FrameListener listener, int width, int height, Surface preview) {
        return start(listener, width, height);
    }

    /** Stops the camera and releases all resources. Safe to call twice. */
    void stop();

    /**
     * Human-readable reason for the most recent start failure; empty when the
     * backend has no error to report. Implementations should set this whenever
     * {@link #start} (or a later asynchronous step) fails so the UI can show
     * the user what actually went wrong.
     */
    default String lastError() {
        return "";
    }

    boolean isRunning();
}
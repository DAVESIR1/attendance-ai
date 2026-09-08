/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

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
    boolean start(FrameListener listener, int width, int height);

    /** Stops the camera and releases all resources. Safe to call twice. */
    void stop();

    boolean isRunning();
}
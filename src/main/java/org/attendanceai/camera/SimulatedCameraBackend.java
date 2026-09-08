/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic synthetic camera for demo mode and host-side tests: emits a
 * moving gradient so the UI and pipeline can run without camera hardware.
 */
public final class SimulatedCameraBackend implements CameraBackend {

    private volatile FrameListener listener;
    private volatile boolean running;
    private ScheduledExecutorService executor;
    private final Random random = new Random(42L);

    private volatile int width = 640;
    private volatile int height = 480;

    @Override
    public boolean start(FrameListener listener, int width, int height) {
        if (running) {
            return true;
        }
        this.listener = listener;
        this.width = width;
        this.height = height;
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(new Runnable() {
            final int[] buffer = new int[width * height];
            int tick;

            @Override
            public void run() {
                if (!running) {
                    return;
                }
                tick++;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int r = (x + tick) & 0xFF;
                        int g = (y + 2 * tick) & 0xFF;
                        int b = ((x + y + tick) * 3) & 0xFF;
                        buffer[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
                FrameListener l = listener;
                if (l != null) {
                    l.onFrame(new CameraFrame(width, height, buffer, System.currentTimeMillis()));
                }
            }
        }, 0L, 100L, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService e = executor;
        executor = null;
        listener = null;
        if (e != null) {
            e.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
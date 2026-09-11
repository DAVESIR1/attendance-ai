/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera2 implementation of {@link CameraBackend} on top of the modern
 * android.media.ImageReader surface (the successor of the removed Camera2
 * ImageCapture/Reader classes). RGBA frames are handed to the
 * {@link FrameListener} on the capture thread.
 *
 * Camera2 is deprecated in the current SDK; it is isolated behind
 * {@link CameraBackend} so a future androidx.camera back-end can replace it
 * without touching the pipeline (see docs/PLAN.md).
 */
public final class Camera2Backend implements CameraBackend {

    private static final String TAG = "Camera2Backend";

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile FrameListener listener;
    private volatile ImageReader reader;
    private HandlerThread handlerThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface previewSurface;

    public Camera2Backend(Context context) {
        this.context = context;
    }

    @Override
    public boolean start(FrameListener listener, int width, int height, Surface preview) {
        if (running.getAndSet(true)) {
            return true;
        }
        this.listener = listener;
        this.previewSurface = preview;
        if (preview == null) {
            Log.e(TAG, "camera preview surface unavailable");
            running.set(false);
            return false;
        }
        try {
            Object service = context.getSystemService(Context.CAMERA_SERVICE);
            if (!(service instanceof CameraManager)) {
                Log.e(TAG, "CameraManager service unavailable");
                running.set(false);
                return false;
            }
            cameraManager = (CameraManager) service;

            handlerThread = new HandlerThread("attendance-camera");
            handlerThread.start();
            cameraHandler = new Handler(handlerThread.getLooper());

            String cameraId = pickFrontCamera(cameraManager);
            if (cameraId == null) {
                Log.e(TAG, "no camera available");
                running.set(false);
                return false;
            }

            ImageReader newReader =
                    ImageReader.newInstance(width, height, ImageFormat.FLEX_RGBA_8888, 2);
            newReader.setOnImageAvailableListener(onImageAvailable -> {
                FrameListener l = listener;
                ImageReader activeReader = reader;
                if (l == null || activeReader == null || !running.get()) {
                    return;
                }
                android.media.Image image = null;
                try {
                    image = activeReader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    int w = image.getWidth();
                    int h = image.getHeight();
                    android.media.Image.Plane[] planes = image.getPlanes();
                    if (planes.length == 0) {
                        return;
                    }
                    byte[] bytes = new byte[w * h * 4];
                    copyPlaneRowMajor(planes[0], bytes, w, h);
                    l.onFrame(CameraFrame.fromRgba(bytes, w, h,
                            System.currentTimeMillis()));
                } catch (RuntimeException e) {
                    // A device-specific stride/format must drop one frame, not
                    // terminate the camera thread and close the whole app.
                    Log.e(TAG, "camera frame dropped", e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, cameraHandler);
            reader = newReader;

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice device) {
                            cameraDevice = device;
                            configureSession(device);
                        }

                        @Override
                        public void onDisconnected(CameraDevice device) {
                            Log.w(TAG, "camera disconnected");
                            running.set(false);
                            device.close();
                            close();
                        }

                        @Override
                        public void onError(CameraDevice device, int error) {
                            Log.e(TAG, "camera error " + error);
                            running.set(false);
                            close();
                        }
                    },
                    cameraHandler);
            return true;
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "failed to start camera", e);
            running.set(false);
            close();
            return false;
        }
    }

    private String pickFrontCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private void configureSession(CameraDevice device) {
        try {
            ImageReader activeReader = reader;
            Surface activePreview = previewSurface;
            if (activeReader == null || activePreview == null) {
                throw new IllegalStateException("camera surfaces unavailable");
            }
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(activePreview);
            builder.addTarget(activeReader.getSurface());
            CaptureRequest request = builder.build();

            device.createCaptureSession(
                    Arrays.asList(activePreview, activeReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(request, null, cameraHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "session configuration failed");
                            running.set(false);
                            close();
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "failed to configure camera", e);
            running.set(false);
            close();
        }
    }

    /** RGBA planes may carry row padding; pack tightly into 4 bytes/pixel. */
    private static void copyPlaneRowMajor(android.media.Image.Plane plane,
            byte[] out, int width, int height) {
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int base = buffer.position();
        int limit = buffer.limit();
        if (pixelStride < 4) {
            throw new IllegalArgumentException("unsupported camera pixel stride");
        }
        for (int y = 0; y < height; y++) {
            int rowOffset = y * rowStride;
            int outOffset = y * width * 4;
            for (int x = 0; x < width; x++) {
                int ox = outOffset + x * 4;
                int ix = base + rowOffset + x * pixelStride;
                if (ix < base || ix + 3 >= limit) {
                    throw new IllegalArgumentException("camera buffer stride exceeds image bounds");
                }
                out[ox] = buffer.get(ix);
                out[ox + 1] = buffer.get(ix + 1);
                out[ox + 2] = buffer.get(ix + 2);
                out[ox + 3] = buffer.get(ix + 3);
            }
        }
    }

    @Override
    public void stop() {
        running.set(false);
        close();
    }

    private void close() {
        CameraCaptureSession session = captureSession;
        captureSession = null;
        if (session != null) {
            session.close();
        }
        CameraDevice device = cameraDevice;
        cameraDevice = null;
        if (device != null) {
            device.close();
        }
        ImageReader oldReader = reader;
        reader = null;
        previewSurface = null;
        if (oldReader != null) {
            oldReader.close();
        }
        HandlerThread thread = handlerThread;
        handlerThread = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
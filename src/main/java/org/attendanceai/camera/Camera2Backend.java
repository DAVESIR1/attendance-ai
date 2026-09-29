/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
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

    /**
     * Image formats tried in order. FLEX_RGBA_8888 (API 33+) is only usable
     * where the platform accepts it — the Nothing Phone (1) rejects it with
     * "Invalid format specified 42" from ImageReader.newInstance — so the
     * classic RGBA_8888 comes first and the Camera2-guaranteed YUV_420_888
     * (converted to ARGB by {@link Yuv420ToArgbConverter}) is the fallback
     * every device supports.
     */
    private static final int[] CANDIDATE_FORMATS = {
            PixelFormat.RGBA_8888,
            ImageFormat.YUV_420_888,
    };

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile FrameListener listener;
    private volatile ImageReader reader;
    private volatile String lastError = "";
    private int candidateIndex;
    private int activeFormat = ImageFormat.YUV_420_888;
    private int requestedWidth;
    private int requestedHeight;
    private int frameRotationDegrees;
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
        lastError = "";
        if (preview == null) {
            lastError = "camera preview surface unavailable";
            Log.e(TAG, lastError);
            running.set(false);
            return false;
        }
        try {
            Object service = context.getSystemService(Context.CAMERA_SERVICE);
            if (!(service instanceof CameraManager)) {
                lastError = "camera service unavailable";
                Log.e(TAG, lastError);
                running.set(false);
                return false;
            }
            cameraManager = (CameraManager) service;

            handlerThread = new HandlerThread("attendance-camera");
            handlerThread.start();
            cameraHandler = new Handler(handlerThread.getLooper());

            String cameraId = pickFrontCamera(cameraManager);
            if (cameraId == null) {
                lastError = "no camera device found";
                Log.e(TAG, lastError);
                running.set(false);
                return false;
            }

            requestedWidth = width;
            requestedHeight = height;
            candidateIndex = 0;
            frameRotationDegrees = computeFrameRotation(cameraId);
            Log.i(TAG, "frame rotation set to " + frameRotationDegrees
                    + "° for camera " + cameraId);
            if (!createReader()) {
                return false;
            }

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice device) {
                            cameraDevice = device;
                            configureSession(device);
                        }

                        @Override
                        public void onDisconnected(CameraDevice device) {
                            lastError = "camera disconnected";
                            Log.w(TAG, lastError);
                            running.set(false);
                            device.close();
                            close();
                        }

                        @Override
                        public void onError(CameraDevice device, int error) {
                            lastError = "camera error " + error;
                            Log.e(TAG, lastError);
                            running.set(false);
                            close();
                        }
                    },
                    cameraHandler);
            return true;
        } catch (CameraAccessException | RuntimeException e) {
            lastError = "camera failed to start: " + e.getClass().getSimpleName();
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
                            // The HAL refused this image format: fall through to
                            // the next candidate instead of failing silently.
                            Log.e(TAG, "session configuration failed for format "
                                    + activeFormat);
                            retryNextFormat("camera rejected image format " + activeFormat);
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            lastError = "camera session failed: " + e.getClass().getSimpleName();
            Log.e(TAG, "failed to configure camera", e);
            running.set(false);
            close();
        }
    }

    /**
     * Degrees to rotate captured frames clockwise so they are upright:
     * {@code SENSOR_ORIENTATION} adjusted by the current display rotation
     * (front cameras add, back cameras subtract — CameraX convention). The
     * camera image "needs to be rotated clockwise by SENSOR_ORIENTATION to be
     * upright in the device's natural orientation" per the camera2 docs.
     */
    private int computeFrameRotation(String cameraId) {
        try {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            int displayDegrees = 0;
            android.view.WindowManager windowManager =
                    (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                switch (windowManager.getDefaultDisplay().getRotation()) {
                    case Surface.ROTATION_90:
                        displayDegrees = 90;
                        break;
                    case Surface.ROTATION_180:
                        displayDegrees = 180;
                        break;
                    case Surface.ROTATION_270:
                        displayDegrees = 270;
                        break;
                    default:
                        displayDegrees = 0;
                        break;
                }
            }
            int sensorDegrees = sensor != null ? sensor : 0;
            boolean front = facing != null
                    && facing == CameraCharacteristics.LENS_FACING_FRONT;
            int rotation = front
                    ? (sensorDegrees + displayDegrees) % 360
                    : (sensorDegrees - displayDegrees + 360) % 360;
            Log.i(TAG, "sensor orientation " + sensorDegrees + "°, display "
                    + displayDegrees + "°, frame rotation " + rotation + "°");
            return rotation;
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "could not determine frame rotation; assuming 0", e);
            return 0;
        }
    }

    /** Creates the analysis reader with the first image format the device accepts. */
    private boolean createReader() {
        for (int i = candidateIndex; i < CANDIDATE_FORMATS.length; i++) {
            int format = CANDIDATE_FORMATS[i];
            try {
                ImageReader newReader = ImageReader.newInstance(
                        requestedWidth, requestedHeight, format, 2);
                newReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
                reader = newReader;
                activeFormat = format;
                candidateIndex = i;
                return true;
            } catch (RuntimeException e) {
                // ImageReader.newInstance throws for formats the platform does
                // not know (e.g. "Invalid format specified 42" for
                // FLEX_RGBA_8888 on the Nothing Phone (1)): try the next one.
                Log.w(TAG, "image format " + format + " rejected, trying next", e);
            }
        }
        lastError = "no supported camera image format";
        Log.e(TAG, lastError);
        running.set(false);
        close();
        return false;
    }

    /** Copies one delivered image into an ARGB camera frame. */
    private void onImageAvailable(ImageReader activeReader) {
        FrameListener l = listener;
        if (l == null || !running.get() || activeReader != reader) {
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
            CameraFrame frame;
            if (activeFormat == ImageFormat.YUV_420_888 && planes.length >= 3) {
                int[] argb = new int[w * h];
                Yuv420ToArgbConverter.convert(planeBytes(planes[0]),
                        planeBytes(planes[1]), planeBytes(planes[2]), w, h,
                        planes[0].getRowStride(), planes[1].getRowStride(),
                        planes[1].getPixelStride(), argb);
                frame = new CameraFrame(w, h, argb, System.currentTimeMillis());
            } else {
                byte[] bytes = new byte[w * h * 4];
                copyPlaneRowMajor(planes[0], bytes, w, h);
                frame = CameraFrame.fromRgba(bytes, w, h, System.currentTimeMillis());
            }
            // Sensor frames arrive rotated (front camera: SENSOR_ORIENTATION
            // 270°); rotate to upright so face detection sees a normal face.
            if (frameRotationDegrees != 0) {
                int[] rotated = FrameRotator.rotateCw(
                        frame.argb(), frame.width, frame.height, frameRotationDegrees);
                if (rotated != frame.argb()) {
                    boolean swap = FrameRotator.swapsDimensions(frameRotationDegrees);
                    frame = new CameraFrame(
                            swap ? frame.height : frame.width,
                            swap ? frame.width : frame.height,
                            rotated, frame.frameTimeMs);
                }
            }
            l.onFrame(frame);
        } catch (RuntimeException e) {
            // A device-specific stride/format must drop one frame, not
            // terminate the camera thread and close the whole app.
            Log.e(TAG, "camera frame dropped", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    /** Copies a whole plane (its slice of the image buffer) into a byte array. */
    private static byte[] planeBytes(android.media.Image.Plane plane) {
        ByteBuffer buffer = plane.getBuffer().duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Rebuilds the reader and session with the next candidate format after an
     * asynchronous configuration failure, or gives up with a visible reason.
     */
    private void retryNextFormat(String reason) {
        ImageReader oldReader = reader;
        reader = null;
        if (oldReader != null) {
            oldReader.close();
        }
        CameraCaptureSession session = captureSession;
        captureSession = null;
        if (session != null) {
            session.close();
        }
        candidateIndex++;
        if (!running.get() || candidateIndex >= CANDIDATE_FORMATS.length) {
            lastError = reason;
            running.set(false);
            close();
            return;
        }
        if (!createReader()) {
            return;
        }
        CameraDevice device = cameraDevice;
        if (device != null) {
            configureSession(device);
        } else {
            lastError = reason;
            running.set(false);
            close();
        }
    }

    @Override
    public String lastError() {
        return lastError;
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
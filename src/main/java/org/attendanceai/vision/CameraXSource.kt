/*
 * Attendance AI — offline-first face-recognition attendance app.
 * Copyright (C) 2026 The Attendance AI Authors. GPL-3.0-or-later.
 */
package org.attendanceai.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.attendanceai.camera.CameraBackend
import org.attendanceai.camera.CameraFrame
import org.attendanceai.camera.FrameRotator
import org.attendanceai.camera.Yuv420ToArgbConverter
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX capture backend — the production replacement for the old
 * `Camera2Backend`.
 *
 * It follows Google's official MediaPipe `face_landmarker/android` sample
 * (`FaceLandmarkerHelper.detectLiveStream` + `CameraFragment.bindCameraUseCases`):
 *
 *  1. `ProcessCameraProvider` binds a `Preview` and an `ImageAnalysis` use case
 *     to the activity lifecycle — there is no manual open/close bookkeeping
 *     left to get wrong, and pause/resume is handled by CameraX itself.
 *  2. The analysis use case asks for CameraX's RGBA_8888 *output* format and
 *     falls back to CameraX's own default (YUV_420_888) when a device refuses
 *     that combination. CameraX negotiates and configures the real camera
 *     streams internally, so the app never pins a device-level ImageReader
 *     format (hard-coding FLEX_RGBA_8888 is what made the old backend fail with
 *     `onConfigureFailed` on this phone).
 *  3. Each frame is copied out of the `ImageProxy` exactly like the official
 *     helper — `Bitmap.copyPixelsFromBuffer(planes[0].buffer)` — and only then
 *     turned into the ARGB [CameraFrame] the rest of the app already consumes.
 *
 * Deliberate deviation from the official sample: the frame is rotated but *not*
 * mirrored for the front camera. The sample mirrors only so its on-screen
 * overlay matches the selfie preview; [FaceAligner] fits a non-reflective
 * similarity transform, so a mirrored frame would be aligned wrongly (the fit
 * degenerates to a ~0.26 scale crop) and every embedding would be garbage.
 * Enrolment and matching both consume unmirrored frames, so they stay
 * consistent with each other.
 *
 * [facing] chooses which [CameraSelector] is *preferred*: the requested
 * camera is tried first and the other one remains a last-resort fallback
 * (the original behaviour), so a device missing the requested camera still
 * binds instead of failing outright. The default is [CameraFacing.FRONT],
 * which is what this source has always used first.
 */
class CameraXSource(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val facing: CameraFacing = CameraFacing.FRONT
) : CameraBackend {

    /** Reports asynchronous outcomes that `start()` cannot return. */
    interface StateListener {
        /** Preview + analysis are bound and frames are expected to flow. */
        fun onCameraStarted()

        /** The camera could not be started (or stopped delivering frames). */
        fun onCameraFailed(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stateListener: StateListener? = null

    @Volatile
    private var running = false

    @Volatile
    private var starting = false

    @Volatile
    private var frameListener: CameraBackend.FrameListener? = null

    @Volatile
    private var lastError = ""

    @Volatile
    private var firstFrameSeen = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzerExecutor: ExecutorService? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var rgbaOutput = true

    /** No frames after a successful bind means the stream is broken — say so. */
    private val frameWatchdog = Runnable {
        if (running && !firstFrameSeen) {
            reportFailure("camera bound but no frames arrived within " +
                    (WATCHDOG_MS / 1000) + " s")
        }
    }

    fun setStateListener(listener: StateListener?) {
        stateListener = listener
    }


    override fun start(listener: CameraBackend.FrameListener, width: Int, height: Int): Boolean =
        start(listener, width, height, null)

    /**
     * Starts preview + analysis. The [preview] surface is ignored: the camera
     * preview is rendered by the [PreviewView] handed to this source, which is
     * what makes the preview lifecycle-safe (CameraX owns the surface).
     */
    override fun start(
        listener: CameraBackend.FrameListener,
        width: Int,
        height: Int,
        preview: Surface?
    ): Boolean {
        if (running || starting) {
            return true
        }
        lastError = ""
        if (preview != null) {
            Log.i(TAG, "caller-supplied Surface ignored; the PreviewView renders the preview")
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "camera permission not granted"
            Log.e(TAG, lastError)
            return false
        }
        frameListener = listener
        firstFrameSeen = false
        starting = true
        analyzerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "attendance-camerax-analyzer")
        }

        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                if (!bind(provider, width, height)) {
                    reportFailure(if (lastError.isEmpty()) "camera binding failed" else lastError)
                }
            } catch (failure: Throwable) {
                // ExecutionException / InterruptedException / anything thrown
                // while binding: never swallow it into a silent no-op.
                reportFailure("camera provider unavailable: " + failure.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(activity))
        return true
    }

    /** Tries every usable camera/format combination; true when one bound. */
    private fun bind(provider: ProcessCameraProvider, width: Int, height: Int): Boolean {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        // The caller's chosen camera first (front by default — the original
        // behaviour), the other as fallback so an unavailable camera degrades
        // instead of failing. `describe()` logs which one actually bound.
        val preferred = if (facing == CameraFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val fallback = if (facing == CameraFacing.FRONT) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val selectors = listOf(preferred, fallback)
        for (selector in selectors) {
            // RGBA_8888 first (Google's official MediaPipe path), then CameraX's
            // own default format so a device that rejects RGBA still works.
            for (rgba in booleanArrayOf(true, false)) {
                if (bindAttempt(provider, selector, rgba, rotation, width, height)) {
                    return true
                }
            }
        }
        if (lastError.isEmpty()) {
            lastError = "no camera could be bound"
        }
        return false
    }

    private fun bindAttempt(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        rgba: Boolean,
        rotation: Int,
        width: Int,
        height: Int
    ): Boolean {
        val executor = analyzerExecutor ?: return false
        val previewUseCase = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .setTargetRotation(rotation)
            .build()
        val analysisUseCase = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(width, height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply {
                if (rgba) {
                    setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                }
            }
            .build()
        analysisUseCase.setAnalyzer(executor) { image -> analyze(image) }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(activity, selector, previewUseCase, analysisUseCase)
        } catch (failure: RuntimeException) {
            lastError = "binding " + describe(selector, rgba) + " failed: " +
                    failure.javaClass.simpleName
            Log.w(TAG, lastError, failure)
            return false
        }
        // The preview surface provider is attached after binding, in the same
        // order Google's sample does it.
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        this.analysisUseCase = analysisUseCase
        rgbaOutput = rgba
        running = true
        starting = false
        lastError = ""
        Log.i(TAG, "CameraX bound " + describe(selector, rgba) + " (rotation " + rotation +
                "°, analysis " + width + "x" + height + ")")
        stateListener?.onCameraStarted()
        mainHandler.removeCallbacks(frameWatchdog)
        mainHandler.postDelayed(frameWatchdog, WATCHDOG_MS)
        return true
    }

    /** Analyzer entry point: one ImageProxy in, one ARGB frame out. */
    private fun analyze(image: ImageProxy) {
        if (!running) {
            image.close()
            return
        }
        var frame: CameraFrame? = null
        try {
            frame = if (rgbaOutput && image.planes.size == 1) {
                rgbaFrame(image)
            } else {
                yuvFrame(image)
            }
        } catch (failure: RuntimeException) {
            Log.e(TAG, "frame conversion failed; dropping this frame", failure)
        } finally {
            // CameraX recycles the buffer as soon as this returns, so the frame
            // handed downstream must be a copy (it is: an ARGB int[]).
            image.close()
        }
        firstFrameSeen = true
        mainHandler.removeCallbacks(frameWatchdog)
        val listener = frameListener ?: return
        if (frame != null) {
            listener.onFrame(frame)
        }
    }

    /**
     * Official MediaPipe conversion: the tightly packed RGBA plane maps 1:1 onto
     * an ARGB_8888 bitmap, then a [Matrix] rotates it upright. Padded buffers
     * (rowStride > width*4) take the row-by-row path instead of shearing.
     */
    private fun rgbaFrame(image: ImageProxy): CameraFrame {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (plane.rowStride == width * 4 && plane.buffer.capacity() >= width * height * 4) {
                bitmapBuffer.copyPixelsFromBuffer(plane.buffer)
            } else {
                val packed = ByteArray(width * height * 4)
                copyPaddedPlane(plane.buffer, plane.rowStride, width, height, packed)
                bitmapBuffer.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
            }
            return upright(image, bitmapBuffer)
        } finally {
            bitmapBuffer.recycle()
        }
    }

    /** YUV_420_888 fallback built on this project's host-tested converter. */
    private fun yuvFrame(image: ImageProxy): CameraFrame? {
        if (image.planes.size < 3) {
            Log.e(TAG, "unexpected analysis frame with " + image.planes.size + " planes")
            return null
        }
        val width = image.width
        val height = image.height
        val argb = IntArray(width * height)
        Yuv420ToArgbConverter.convert(
            planeBytes(image.planes[0]),
            planeBytes(image.planes[1]),
            planeBytes(image.planes[2]),
            width,
            height,
            image.planes[0].rowStride,
            image.planes[1].rowStride,
            image.planes[1].pixelStride,
            argb
        )
        val degrees = image.imageInfo.rotationDegrees
        val rotated = FrameRotator.rotateCw(argb, width, height, degrees)
        if (rotated === argb) {
            return CameraFrame(width, height, argb, System.currentTimeMillis())
        }
        val swap = FrameRotator.swapsDimensions(degrees)
        return CameraFrame(
            if (swap) height else width,
            if (swap) width else height,
            rotated,
            System.currentTimeMillis()
        )
    }

    /** Rotates the bitmap upright and copies it into an ARGB frame. */
    private fun upright(image: ImageProxy, source: Bitmap): CameraFrame {
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        try {
            val width = upright.width
            val height = upright.height
            val argb = IntArray(width * height)
            upright.getPixels(argb, 0, width, 0, 0, width, height)
            return CameraFrame(width, height, argb, System.currentTimeMillis())
        } finally {
            // createBitmap returns the source itself for an identity matrix, so
            // only recycle a distinct bitmap.
            if (upright !== source) {
                upright.recycle()
            }
        }
    }

    override fun stop() {
        running = false
        starting = false
        firstFrameSeen = false
        mainHandler.removeCallbacks(frameWatchdog)
        val provider = cameraProvider
        cameraProvider = null
        if (provider != null) {
            try {
                provider.unbindAll()
            } catch (failure: RuntimeException) {
                Log.w(TAG, "unbind failed", failure)
            }
        }
        analysisUseCase = null
        val executor = analyzerExecutor
        analyzerExecutor = null
        if (executor != null) {
            executor.shutdown()
        }
        frameListener = null
        Log.i(TAG, "CameraX stopped")
    }

    override fun lastError(): String = lastError

    override fun isRunning(): Boolean = running

    /** Logs + surfaces an asynchronous failure instead of failing silently. */
    private fun reportFailure(reason: String) {
        lastError = reason
        Log.e(TAG, reason)
        running = false
        starting = false
        mainHandler.removeCallbacks(frameWatchdog)
        stateListener?.onCameraFailed(reason)
    }

    /** Copies a plane whose rows are padded (rowStride > width*4) tightly. */
    private fun copyPaddedPlane(buffer: ByteBuffer, rowStride: Int, width: Int, height: Int,
                                out: ByteArray) {
        val source = buffer.duplicate()
        val rowBytes = width * 4
        if (rowStride < rowBytes) {
            throw IllegalStateException("analysis row stride " + rowStride + " < " + rowBytes)
        }
        for (y in 0 until height) {
            val offset = y * rowStride
            if (offset + rowBytes > source.limit()) {
                throw IllegalStateException("analysis buffer too small for padded rows")
            }
            source.position(offset)
            source.get(out, y * rowBytes, rowBytes)
        }
    }

    private fun planeBytes(plane: ImageProxy.PlaneProxy): ByteArray {
        val buffer = plane.buffer.duplicate()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun describe(selector: CameraSelector, rgba: Boolean): String {
        val facing = if (selector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "back"
        val format = if (rgba) "rgba-8888" else "camerax-default(yuv-420-888)"
        return facing + " camera + " + format
    }

    private companion object {
        const val TAG = "CameraXSource"
        const val WATCHDOG_MS = 8000L
    }
}

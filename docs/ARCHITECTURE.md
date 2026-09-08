# Attendance AI — Architecture

```
┌─────────────────────────── Android app (org.attendanceai.app) ──────────────┐
│                                                                              │
│  ui/AttendanceActivity ─────────────────────────────────────────────────┐   │
│  │  • Attaches the android.media.ImageReader surface to the camera       │   │
│  │  • Swaps between Camera / Roster / Records / Settings views           │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
│                              │ RGBA frames (capture thread)                  │
│                              ▼                                                │
│  pipeline/FacePipeline (single background executor — native-thread-affine)    │
│   ┌────────────────────────────────────────────────────────────────────┐    │
│   │ ImageProxyFrame → LandmarkerEngine (tasks-vision FaceLandmarker)    │    │
│   │                 → FaceAligner (pure) → EmbeddingEngine             │    │
│   │                 → FaceMatcher (pure) → AttendanceService           │    │
│   └────────────────────────────────────────────────────────────────────┘    │
│      ▲                    ▲                    ▲                    ▲        │
│   camera/             vision/              vision/             store/        │
│   CameraBackend       (aligned crop,       FaceMatcher         Attendance-    │
│   (camera2+            sampling math)      (cosine, mean       Store,        │
│    android.media)                           templates)          Settings,     │
│   └ SimulatedCameraBackend (demo/tests)                        Json          │
│                                                                              │
│  models/ (gitignored binaries)  ──verified by──▶  model/ModelIntegrity (SHA-  │
│  face_landmarker.task, mobilefacenet.tflite                   256 vs checksum │
└──────────────────────────────────────────────────────────────────────────────┘
```

## Modules

### camera
* `CameraBackend` — interface (`start`, `stop`, availability; delivers RGBA
  frames through a listener).
* `Camera2Backend` — implementation on `android.hardware.camera2` +
  `android.media.ImageReader` (the 2026-era camera surface). Built directly on
  the SDK jar (verified against platform android-35). Deprecation warnings are
  suppressed locally and tracked in docs/PLAN.md.
* `SimulatedCameraBackend` — deterministic synthetic frames; lets the pipeline,
  UI and tests run without hardware.

### vision
* `Face` — one detected face: normalized landmarks + bounding box + a
  `FaceQuality` estimate (face size relative to frame, landmark presence).
* `FaceLandmarkerEngine` — wraps `FaceLandmarker.createFromOptions(...)`.
* `FaceAligner` — computes a similarity transform from
  leftEye / rightEye / mouth centers (MediaPipe FaceMesh indices derived from
  `FaceLandmarksConnections` sets) and re-samples the RGBA frame into a
  `modelHeight×modelWidth×3` float tensor (values in `[-1, 1]`).
* `EmbeddingEngine` — interface; `TfliteEmbeddingEngine` (TensorFlow Lite
  Java/Android) + `LandmarkSignatureEngine` (geometric fallback, experimental).
* `FaceMatcher` — cosine similarity over mean templates; returns best match +
  best cosine + threshold verdict.

### store
* `Json` — minimal dependency-free JSON encoder/decoder (objects, arrays,
  strings, numbers, booleans, null) with strict UTF-8 handling; used by
  `AttendanceStore`.
* `AttendanceStore` — atomic JSON persistence: roster (people + templates),
  attendance records, settings; exposes a `ModelIntegrity`-checked `ModelBundle`.
* `Settings` — value object: thresholds, delegate, sample counts, model paths.

### model
* `ModelIntegrity` — SHA-256 of a file; parses `models/checksums.sha256`.

### pipeline
* `FacePipeline` — orchestrates the above with a stability window.
* `AttendanceService` — check-in/out semantics, prevents double punches within
  a cooldown window.

## Threading model

* The camera capture thread (android.media callback) only copies RGBA bytes.
* A **single background executor** runs ALL native inference (face landmarker +
  tflite + aligner sampling) — MediaPipe native tasks are thread-affine.
* UI updates are marshalled with `runOnUiThread`.
* The pure-Java parts (matcher, store, json, aligner math) have **no** Android
  imports and are covered by JVM unit tests.

## Dependency audit

| Component | Coordinate | License |
|-----------|------------|---------|
| MediaPipe Tasks vision | `com.google.mediapipe:tasks-vision:1.0.0` | Apache-2.0 |
| TensorFlow Lite (Android) | `org.tensorflow:tensorflow-lite:2.16.1` + `-api` | Apache-2.0 |
| androidx annotations | `androidx.annotation:annotation:1.2.0` | Apache-2.0 |
| JUnit (test only) | `junit:junit:4.13.2` | Apache-2.0 (CPL/GPL dual for it) |
| Model: face_landmarker | `face_landmarker.task` from mediapipe-models | Apache-2.0 |
| Model: embeddings | `mobilefacenet.tflite` (third-party conversion) | **verify yourself** |
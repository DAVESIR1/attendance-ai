# Attendance AI

Offline-first, on-device face-recognition attendance app for Android.
FOSS: no Google Play Services, no Firebase, no cloud dependency.
License: GPL-3.0-or-later.

## What is here

* `src/` — the Android application (Java): camera capture (Camera2/`android.media`),
  MediaPipe **Face Landmarker** (`tasks-vision`), MobileFaceNet embeddings
  (TensorFlow Lite for Android), face matching, JSON attendance store.
* `models/` — model files + `checksums.sha256` (binaries gitignored).
* `scripts/` — environment, build, model fetch/checksum helpers.
* `docs/` — `PLAN.md`, `ARCHITECTURE.md`, `BUILDING.md`.

## Quick start

```bash
source scripts/env.sh          # JDK + Android SDK env
scripts/fetch_models.sh        # face_landmarker.task (verified); then add mobilefacenet.tflite
./gradlew assembleDebug        # build the APK
./gradlew test                 # pure-Java tests (matcher, aligner, store, json, sha256)
```

See `docs/BUILDING.md` for the full toolchain table and device install notes.

## Design highlights

* Single background executor for all native inference (thread-affine).
* Pure-Java core (`FaceMatcher`, `FaceAligner` math, `AttendanceStore`, `Json`,
  `ModelIntegrity`) — no Android imports, covered by JVM unit tests.
* Startup model integrity check (`sha256sum(checksums.sha256)`) and fail-closed
  behaviour when a pinned model is missing or corrupt.
* Camera layer isolated behind `CameraBackend` so androidx.camera can replace
  the deprecated Camera2 later without touching the pipeline.

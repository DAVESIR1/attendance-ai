# Attendance AI — Development Plan

> Status: **Phase 2 in progress** (Android application skeleton + ML pipeline).
> The repository only contained the Phase 1 scaffold (license/readme/gitignore/
> local.properties) and placeholders referencing "Phase 3" model files. This
> document replaces the implicit plan with an explicit one so any session can
> continue coherently.

## Guiding constraints

* **Offline-first, on-device** — no network at runtime, no Google Play Services,
  no Firebase, no cloud. All inference and storage happen on the device.
* **FOSS** — GPL-3.0-or-later for this project; every third-party dependency is
  Apache-2.0/Apache-2.0 compatible (MediaPipe Tasks, TensorFlow Lite, androidx).
* **Minimal attack surface** — models are verified by SHA-256 on startup before
  use; data is stored in the app's private directory as versioned JSON.

## Phases

| Phase | Deliverable | Status |
|-------|-------------|--------|
| 1 | Repo scaffold: license, readme, gitignore, local.properties | ✅ done |
| 2 | Buildable Android app skeleton: camera (Camera2/android.media), UI, storage, integrity check | 🔨 **this phase** |
| 3 | Face pipeline: MediaPipe Face Landmarker + MobileFaceNet embeddings + matching | 🔨 together with 2 |
| 4 | Attendance flows: check-in/out, roster, records, CSV export, settings | 📋 next |
| 5 | Packaging: release signing (local.properties RELEASE_*), side-loadable APK | 📋 later |

## Phase 2/3 decisions (verified against the installed SDK 34/35)

1. **UI framework**: classic `android.app.Activity` + standard widgets.
   The modern androidx "Android Views" stack (XML manifests, Kotlin view models,
   androidx.navigation / androidx.lifecycle / androidx.views) that the official
   MediaPipe samples use is NOT used, because it is Kotlin-only and couples the
   app to a fast-moving, barely-documented framework. Everything wooden —
   everything verifiable.
2. **Camera**: `android.hardware.camera2.CameraManager` + `android.media.ImageReader`
   (the successor of the removed `android.hardware.camera2.ImageCapture/ImageReader`);
   frames are delivered as RGBA `ByteBuffer`s via an `OnImageAvailableListener`.
   androidx.camera (CameraX) was evaluated — its classic `ImageAnalysisSession`
   API is gone in 1.4.2 and the replacement (lifecycle binding) requires the
   Android-Views stack, so it was deliberately **not** adopted.
3. **Face detection/landmarks**: MediaPipe Tasks `tasks-vision:1.0.0`,
   `FaceLandmarker` in `RunningMode.IMAGE` called from a single background
   executor (thread-affine native resources). 478 landmarks per face.
4. **Embeddings**: TensorFlow Lite Java (Android) `org.tensorflow:tensorflow-lite:2.16.1`
   + `tensorflow-lite-api:2.16.1`, running a user-supplied `mobilefacenet.tflite`
   (112×112 RGB, float32 input). Embeddings are 192-d for the conventional
   conversion; the engine reads the model's output shape at runtime and is
   agnostic to the exact dimension.
5. **Alignment**: pure-Java similarity transform computed from the eye/mouth
   landmarks (left/right eye + mouth centers derived from the FaceMesh
   connections), sampling the RGBA frame directly into the model's float tensor.
   No android.graphics involved → unit-testable on a host JVM.
6. **Matching**: cosine similarity against per-person mean templates with a
   configurable threshold + a short "stable match" window to avoid accidental
   check-ins.
7. **Storage**: versioned JSON files (`roster.json`, `records.json`, `settings.json`)
   written atomically (tmp+rename) in the app's private files directory.
   CSV export of attendance records.
8. **Model integrity**: `models/*.{task,tflite}` are verified against
   `models/checksums.sha256` (recorded by `scripts/fetch_models.sh`) before the
   pipeline will initialise.

## Build & signing (release)

Release builds are signed with the fixed key whose credentials live only in the
gitignored `local.properties` (`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`; keystore kept outside the repo) and
contain the single `arm64-v8a` slice (`ndk.abiFilters`), targeting the Nothing
Phone (1) test device.

**Minification is OFF for release** (`minifyEnabled false`, `shrinkResources false`):
"Release builds do not use R8 minification, because this is an open-source project
with no code-secrecy requirement, and because MediaPipe's internal Flogger-based
logging breaks under R8 renaming (google-ai-edge/mediapipe#4806, unresolved
upstream). Do not re-enable minifyEnabled for release without first confirming
MediaPipe has published an official fix."

Background: the on-device failure
`ExceptionInInitializerError ← IllegalStateException: "no caller found on the stack for: F2.d"`
(shown to the user as "face model unavailable") was caused by R8 renaming
MediaPipe's internal classes — Flogger's stack-based caller lookup then cannot
resolve the caller class while `FaceLandmarker`'s static initializer runs.
`proguard-rules.pro` is kept in the repo for reference but is intentionally not
applied; the decision is also documented in `build.gradle`.

## Known limitations / follow-ups

* `mobilefacenet.tflite` is a third-party conversion (license = "verify your
  own"). `fetch_models.sh` refuses to fabricate a checksum for it: you must
  supply the file (or set `MOBILEFACENET_URL` and accept the printed hash).
* Fallback matcher: without MobileFaceNet the app still runs using a
  landmark-geometry "signature" engine (clearly labelled experimental).
* Camera2 is deprecated; when androidx.camera provides a classic-UI-compatible
  path again (non-Kotlin-views), the camera layer is isolated behind
  `CameraBackend` for a drop-in swap.
* On-device perf tuning (delegate choice, frame throttle, input resolution)
  lives in Settings.

## Definition of done (Phase 2/3)

- [x] `./gradlew assembleDebug` produces an APK
- [x] `./gradlew test` passes for pure-Java core (matcher, aligner math, JSON, store, sha256)
- [ ] Real device: front camera preview + enroll N people + check-in/out with
      name+confidence; roster/records screens; CSV export
# Building Attendance AI

## Toolchain (as used in the reference environment)

| Tool | Version |
|------|---------|
| JDK | 17 or 21 (verified: `/home/davesir/.jdks/jdk-17.0.20.1+1`) |
| Android SDK | cmdline-tools 12.0 at `sdk.dir` (see `local.properties`), platforms `android-26`/`android-35`, build-tools 34/35 |
| Gradle | 8.14.2 (pinned by the wrapper) |
| AGP | 8.11.0 (declared in `build.gradle`) |

## First build

```bash
# 1. Set up the toolchain env (idempotent; run once per shell)
source scripts/env.sh

# 2. Fetch the model files (face_landmarker.task verified by SHA-256;
#    mobilefacenet.tflite needs your input — see models/README.md)
scripts/fetch_models.sh

# 3. Build the APK
./gradlew clean assembleDebug

# 4. Run the pure-Java unit tests
./gradlew test
```

`scripts/build.sh` runs steps 3 (+ optionally 4). `scripts/checksums.sh`
prints the SHA-256 of everything in `models/`.

## Without the second model

The app bundles `face_landmarker.task` for detection but the embedding model is
required for *meaningful* recognition. If `mobilefacenet.tflite` is missing the
pipeline fails closed at startup **unless** Settings → `signatureFallback` is on
(landmark-geometry matcher, experimental, lower accuracy).

## Deploying to a device

1. Enable developer mode / sideloading on the target Android device (SDK ≥ 24).
2. `./gradlew installDebug` with the device connected, or install the APK from
   `build/outputs/apk/debug/`.
3. Grant the CAMERA permission when prompted.

## Signing (release)

Not wired into Gradle yet (Phase 5). `local.properties` already carries
`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` /
`RELEASE_KEY_PASSWORD` for a future `assembleRelease` signing task.
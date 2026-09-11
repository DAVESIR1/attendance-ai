# Attendance AI — Project State
(Single source of truth for "where are we." Read AGENT_RULES.md first, always.)

Last updated: 2026-09-11T16:50:00Z
Last updated by: GPT-5.6 Luna / Zed on Linux

## A. Stage Progress (fill in real status for each, from actual git tags/files/tests)
| Stage | Description | Status | Evidence (tag/commit/files) |
|---|---|---|---|
| 1 | Project scaffold | ✅ complete for the original repository scope; ⚠️ does not match the newer Kotlin-only master plan | Commit `cc9e002`; `README.md`; `LICENSE`; `build.gradle`; `settings.gradle`; `src/` |
| 2 | App skeleton (camera, storage) | ⚠️ Room/SQLCipher schema, DAOs, session opening, adapter, and AndroidTest compilation are present; actual device execution and legacy-data migration remain | `data/local/db/`; `RoomAttendanceStore.kt`; `AttendanceStore.java`; `src/androidTest/.../AttendanceDatabaseTest.kt`; `assembleDebugAndroidTest` passed |
| 3 | Face recognition pipeline | ⚠️ basic pipeline implemented; guided multi-angle enrollment, liveness, and consent flow are not implemented | `pipeline/FacePipeline.java`; `vision/`; MediaPipe/TFLite dependencies in `build.gradle` |
| 4 | Attendance flows (roster, records, CSV, settings) | ⚠️ basic roster/record persistence and auto-punch exist; groups, full screens, CSV/PDF/Excel reports, and settings UI are missing | `AttendanceStore.java`; `AttendanceService.java`; only `AttendanceActivity.java` UI |
| 5 | Packaging/signing | ⚠️ debug build/CI exists; release signing and release hardening are not wired | `.github/workflows/android.yml`; `build/outputs/apk/debug/attendance-ai-debug.apk`; `build.gradle` release has `minifyEnabled false` |
| Security Core | ⚠️ lock/security core mostly implemented; unlock now force-opens SQLCipher before marking the session unlocked, but full on-device verification remains pending | Tag `phase-1-complete`; `data/local/crypto/`; `presentation/lockscreen/`; `VaultSession.kt`; security unit tests |
| Infra: consistent debug signing + auto-release workflow | ⚠️ debug CI workflow exists; release signing/auto-release does not | `.github/workflows/android.yml`; no release signing block in `build.gradle` |
| Infra: this state-tracking system | ✅ files created locally; not yet committed or pushed | `AGENT_RULES.md`; `PROJECT_STATE.md` |

## B. Repo Facts (query these live, don't assume)
- Current branch: `main`
- Latest code commit (hash + message): `443b02f chore(build): add arm64 debug test APK option`; a state-only follow-up commit will record this status
- All tags: `phase-1-complete`, `phase-2-complete`
- Uncommitted local changes (list files or "none"): `PROJECT_STATE.md` (state-only follow-up)
- Known model files present in models/ (with checksums if available): `face_landmarker.task` — SHA-256 `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`; `mobilefacenet.tflite` — SHA-256 `d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b`; `models/checksums.sha256` contains both verified entries
- CI status of last push (if checkable): workflow configuration is present in `.github/workflows/android.yml`; live GitHub Actions status was not checked in this environment

## C. Next Action (THE MOST IMPORTANT LINE — always keep this accurate and specific)
Wait for the user's phone test results from `build/test-artifacts/attendance-ai-arm64-debug.apk`; then fix any reproducible issue before resuming Phase 2 device verification.

## D. Open Decisions / Blockers
- Gradle wrapper/dependency validation may be blocked in this environment because the wrapper distribution host previously failed DNS resolution; retry before claiming Phase 2 green.
- MobileFaceNet model redistribution license is not yet confirmed; checksum is now recorded but licensing still needs human verification.
- Model checksum entries are now recorded and match the local files; MobileFaceNet redistribution license remains unconfirmed.
- The legacy JSON store contains raw templates and must be migrated to encrypted database storage before face-data-at-rest requirements are satisfied.
- Cloud backup is explicitly deferred by the locked master plan and requires a future self-hosted design choice.

## E. Needs Human Review
- `src/main/java/org/attendanceai/store/AttendanceStore.java` and its JSON files are legacy storage that should be migrated, not deleted, after Room/SQLCipher migration is verified.
- `src/main/java/org/attendanceai/camera/Camera2Backend.java` is an intentional Camera2 implementation despite the newer plan preferring CameraX; keep until the camera layer migration is planned.

## F. Log (append-only, newest entry on top, keep last ~20 entries, trim older ones)
- 2026-09-11T16:50:00Z — Committed arm64 build support as `443b02f`. APK remains available at `build/test-artifacts/attendance-ai-arm64-debug.apk` (38 MB, SHA-256 `9d926c13a220cabd5908d0d393cded5c6da5b9ce10d10bd4513edc0a8d32db9f`). Verified only `lib/arm64-v8a` native libraries; `assembleDebug`, `testDebugUnitTest`, and `assembleDebugAndroidTest` passed. No push performed.
- 2026-09-11T14:20:07Z — Created persistent agent rules and project state from live Git/files/model facts; corrected completion assessment to approximately 22% against the supplied full master plan — build/test not run yet for these documentation changes.

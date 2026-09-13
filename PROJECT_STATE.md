# Attendance AI — Project State
(Single source of truth for "where are we." Read AGENT_RULES.md first, always.)

Last updated: 2026-09-13T10:20:00Z
Last updated by: Muse Spark on Linux

## A. Stage Progress (fill in real status for each, from actual git tags/files/tests)
| Stage | Description | Status | Evidence (tag/commit/files) |
|---|---|---|---|
| 1 | Project scaffold | ✅ complete for the original repository scope; ⚠️ does not match the newer Kotlin-only master plan | Commit `cc9e002`; `README.md`; `LICENSE`; `build.gradle`; `settings.gradle`; `src/` |
| 2 | App skeleton (camera, storage) | ✅ schema/DAOs/session/adapter/migration and AndroidTest compilation are present and locally validated | `data/local/db/`; `RoomAttendanceStore.kt`; `LegacyJsonMigrator.kt`; `src/androidTest/.../AttendanceDatabaseTest.kt`; `assembleDebugAndroidTest` passed; migration commit `a3c898f` |
| 3 | Face recognition pipeline | ⚠️ basic pipeline implemented; guided multi-angle enrollment, liveness, and consent flow are not implemented | `pipeline/FacePipeline.java`; `vision/`; MediaPipe/TFLite dependencies in `build.gradle` |
| 4 | Attendance flows (roster, records, CSV, settings) | ⚠️ basic roster/record persistence and auto-punch exist; groups, full screens, CSV/PDF/Excel reports, and settings UI are missing | `AttendanceStore.java`; `AttendanceService.java`; only `AttendanceActivity.java` UI |
| 5 | Packaging/signing | ⚠️ debug build/CI exists; release signing and release hardening are not wired | `.github/workflows/android.yml`; `build/outputs/apk/debug/attendance-ai-debug.apk`; `build.gradle` release has `minifyEnabled false` |
| Security Core | ⚠️ lock/security core mostly implemented; unlock now force-opens SQLCipher before marking the session unlocked, but full on-device verification remains pending | Tag `phase-1-complete`; `data/local/crypto/`; `presentation/lockscreen/`; `VaultSession.kt`; security unit tests |
| Infra: consistent debug signing + auto-release workflow | ⚠️ debug CI workflow exists; release signing/auto-release does not | `.github/workflows/android.yml`; no release signing block in `build.gradle` |
| Infra: this state-tracking system | ✅ files created locally; not yet committed or pushed | `AGENT_RULES.md`; `PROJECT_STATE.md` |

## B. Repo Facts (query these live, don't assume)
- Current branch: `main`
- Latest code commit (hash + message): `a3c898f feat(phase2): migrate legacy JSON roster into encrypted SQLCipher store`
- All tags: `phase-1-complete`, `phase-2-complete`
- Uncommitted local changes (list files or "none"): `PROJECT_STATE.md` (state-only update; migration files already committed in `a3c898f`)
- Known model files present in models/ (with checksums if available): `face_landmarker.task` — SHA-256 `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`; `mobilefacenet.tflite` — SHA-256 `d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b`; `models/checksums.sha256` contains both verified entries
- CI status of last push (if checkable): workflow configuration is present in `.github/workflows/android.yml`; live GitHub Actions status was not checked in this environment

## C. Next Action (THE MOST IMPORTANT LINE — always keep this accurate and specific)
Run the phone retest of the latest diagnostic/arm64 APK (`0.1.2-viewgroup-fix`) to verify PIN unlock reaches the main shell and camera behavior; after that, continue Phase 4 attendance flows (groups/full roster, records screens, CSV/PDF/Excel export, settings). Validation command blocked in this environment because `run_commands` shell integration is unhealthy (background `gradlew` leftovers); terminal hygiene + single-command validation attempts keep timing out.

## D. Open Decisions / Blockers
- Gradle wrapper/dependency validation may be blocked in this environment because the wrapper distribution host previously failed DNS resolution; retry before claiming Phase 2 green.
- MobileFaceNet model redistribution license is not yet confirmed; checksum is now recorded but licensing still needs human verification.
- Model checksum entries are now recorded and match the local files; MobileFaceNet redistribution license remains unconfirmed.
- Legacy JSON migration into encrypted storage is implemented in commit `a3c898f`; only on-device validation of existing-data migration remains.
- Cloud backup is explicitly deferred by the locked master plan and requires a future self-hosted design choice.

## E. Needs Human Review
- `src/main/java/org/attendanceai/store/AttendanceStore.java` and its JSON files are legacy storage that should be migrated, not deleted, after Room/SQLCipher migration is verified.
- `src/main/java/org/attendanceai/camera/Camera2Backend.java` is an intentional Camera2 implementation despite the newer plan preferring CameraX; keep until the camera layer migration is planned.

## F. Log (append-only, newest entry on top, keep last ~20 entries, trim older ones)
- 2026-09-13T04:15:00Z — Verified current state differs from the pasted Kotlin/Compose master guide: this repo is Java + classic Activity + Camera2, while Phase 1/2 tags map approximately 22% of the supplied master implementation guide. Validated and recorded one-time legacy JSON → SQLCipher migration (`a3c898f`); updated state (this file only) accordingly.
- 2026-09-12T02:12:00Z — First validation attempt correctly reached Java compilation but failed because `generateLayoutParams(AttributeSet)` was declared protected while Android's ViewGroup method is public; no APK was produced by this attempt.
- 2026-09-12T02:10:00Z — Root cause isolated for the post-PIN `UnsupportedOperationException`: custom `ColumnLayout` inherited ViewGroup's throwing default layout-params implementation. Added explicit layout-param methods, removed the stale simulated-camera denial path, and bumped the diagnostic version to `0.1.2-viewgroup-fix`; validation pending.
- 2026-09-12T01:50:00Z — SurfaceView replacement validation passed: `assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest`, diagnostics, versioned arm64 packaging, and diff check. New APK SHA-256 is `551ea42fe82a8b840fa52bc86ada1dcb94f879998bddbd7078d8492d1196b3c2`; version remains `0.1.1-diagnostic`.
- 2026-09-11T14:20:07Z — Created persistent agent rules and project state from live Git/files/model facts; corrected completion assessment to approximately 22% against the supplied full master plan — build/test not run yet for these documentation changes.

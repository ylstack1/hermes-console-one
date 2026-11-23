# HERMES CONSOLE — LOCAL ENGINE INTEGRATION HANDOFF

**Session:** Local Hermes Agent engine integration (PRoot + Ubuntu rootfs, in-app, no Termux/VPS)  
**Date:** 2026-11-23  
**Repo:** `ylstack1/hermes-console-one` (GitHub)  
**Branch:** `main`  
**Workspace:** `/data/data/com.androidharness.app/files/github-projects/hermes-console-one-9add1315`  
**PC Path:** `E:\ylstack-space\Developments\ai\agents_app\hermes-console-one\`

---

## 1. SESSION SUMMARY

Console is a Flutter Android app that connects remotely to a self-hosted Hermes Agent (Nous Research). The goal of this session was to add an **optional in-app local Hermes runtime** so the phone can run Hermes entirely on-device — no Termux, no proot-distro, no VPS.

**What was built:**

| Layer | Files | Status |
|-------|-------|--------|
| **CI/CD** | `.github/workflows/local-engine-build.yml` | ✅ Builds debug APK, uploads artifact (~185 MB) |
| **Android Kotlin** | `android/app/src/main/kotlin/com/hermesagent/hermes_android/localengine/` | ✅ Complete |
| **Dart bridge** | `lib/core/services/platform/local_engine.dart` | ✅ Complete |
| **ConnectionManager** | `lib/core/services/connection_manager.dart` | ✅ Local connection registration |
| **Onboarding UI** | `lib/core/screens/onboarding_screen.dart`, `connect_method_cards.dart`, `connect_method_draft.dart`, `app_es.arb` | ✅ Local engine card + install flow |

**Kotlin files (new):**
- `LocalEngineBootstrap.kt` — downloads + extracts Ubuntu 24.04 arm64 rootfs (~150 MB), fake `/proc`/`/sys`, libtalloc copy
- `LocalEngineProcess.kt` — builds PRoot install/gateway commands, clean environment, process lifecycle
- `LocalHermesManager.kt` — install pipeline (rootfs → Python → Hermes Agent via pip), starts `hermes gateway --port 9119`
- `LocalEngineForegroundService.kt` — foreground service + wake lock to keep engine alive in background
- `LocalEngineController.kt` — MethodChannel `hermes/local_engine` handler, all responses marshaled to UI thread

**Dart files (new/modified):**
- `lib/core/services/platform/local_engine.dart` — `LocalEngine` bridge, state machine (`notInstalled → running`), mock impl
- `lib/core/services/connection_manager.dart` — `ensureLocalConnection()`, `getLocalConnection()`, `isLocalConnectionId()`
- `lib/core/screens/onboarding_screen.dart` — `_Step.localEngine`, `_startLocalEngine()`, `_localEngineStep()` widget
- `lib/core/screens/onboarding/connect_method_cards.dart` — `ConnectWay.localEngine` card
- `lib/core/screens/onboarding/connect_method_draft.dart` — `ConnectWay.localEngine` enum value
- `lib/l10n/app_es.arb` + `app_en.arb` — `le*` and `nacWayLocal*` strings

**Build config changes:**
- `android/app/build.gradle.kts` — arm64-v8a abiFilter, `jniLibs.useLegacyPackaging=true` (W^X bypass), `noCompress` for `.tar.gz`, `commons-compress` + `xz` deps
- `android/scripts/fetch-proot-binaries.sh` — fetches `libproot.so`, `libtalloc.so`, `libandroid-shmem.so` from Termux packages
- `android/app/src/main/AndroidManifest.xml` — FOREGROUND_SERVICE, WAKE_LOCK, `LocalEngineForegroundService`

**User flow:**
1. Welcome screen → tap **"Local Hermes engine"** card (Private · offline)
2. Progress screen: Checking device → Downloading rootfs → Extracting → Installing Python → Installing Hermes Agent → Starting gateway → Connecting → **Local Hermes ready**
3. Auto-connects to `ws://127.0.0.1:9119/api/ws` — existing Console chat UI works unchanged
4. Foreground service keeps runtime alive when backgrounded

---

## 2. KNOWN ISSUES

| Issue | Severity | Workaround / Fix Needed |
|-------|----------|------------------------|
| **Ubuntu rootfs HTTP 403** | High | Some mirrors (Tsinghua) return 403. Added ports.ubuntu.com, rackspace, netcologne fallbacks. Test on real device. |
| **APK signed with different key each CI run** | High | Must `adb uninstall dev.xpetalab.hermesconsole` before installing new debug APK. Build locally on PC with a persistent debug keystore to allow updates without uninstall. |
| **arm64-v8a only** | Medium | Rootfs contains glibc binaries for aarch64 only. APK restricts to arm64-v8a. x86_64 devices will fail at PRoot. |
| **~150 MB rootfs download** | Medium | First-time install downloads ~150 MB compressed rootfs. Warn user about data usage. |
| **RAM usage** | Medium | Python + Hermes + 1B model needs ~1–2 GB RAM. Tight on 4 GB phones. |
| **No Settings toggle yet** | Low | Local engine only accessible via onboarding welcome card. No Settings screen toggle to enable/disable later. |
| **Model not bundled** | Low | Hermes Agent runs but needs a model provider configured (OpenRouter default in skeleton config). User must add API key via Hermes `hermes model` or config. |

---

## 3. QUICKSTART — BUILD LOCALLY ON PC

Your PC has the full toolchain at `F:\toolchain`:

```
E:\ylstack-space\Developments\ai\agents_app\hermes-console-one\
  android/
    app/
      src/main/
        kotlin/com/hermesagent/hermes_android/localengine/   ← new Kotlin files
        jniLibs/
          arm64-v8a/
            libproot.so           ← run fetch-proot-binaries.sh
            libtalloc.so
            libandroid-shmem.so
        AndroidManifest.xml       ← updated
      build.gradle.kts            ← updated
    build.gradle.kts              ← unchanged
    settings.gradle.kts           ← unchanged
    scripts/
      fetch-proot-binaries.sh     ← new, run once at build time
  lib/
    core/services/platform/local_engine.dart   ← new
    core/services/connection_manager.dart      ← modified
    core/screens/onboarding_screen.dart        ← modified
    core/screens/onboarding/connect_method_cards.dart   ← modified
    core/screens/onboarding/connect_method_draft.dart   ← modified
  l10n/
    app_en.arb       ← modified (le* + nacWayLocal* strings)
    app_es.arb       ← modified (Spanish template)
  pubspec.yaml       ← version bumped to 1.4.3+14036
```

### Step 1: Copy project to PC

Already cloned at `E:\ylstack-space\Developments\ai\agents_app\hermes-console-one\`. Verify:

```cmd
cd E:\ylstack-space\Developments\ai\agents_app\hermes-console-one
git status
git log --oneline -5
```

### Step 2: Fetch PRoot binaries (run once)

```cmd
cd android
scripts\fetch-proot-binaries.sh
```

This downloads `libproot.so`, `libtalloc.so`, `libandroid-shmem.so` from Termux packages into `app/src/main/jniLibs/arm64-v8a/`. **Required** — without these, PRoot won't start.

### Step 3: Create local.properties

```ini
flutter.sdk=F:\toolchain\flutter
```

Save as `android/local.properties`.

### Step 4: Build

```cmd
flutter pub get
cd android
..\F:\toolchain\flutter\bin\flutter.bat build apk --debug
```

Or with Gradle directly:
```cmd
.\gradlew assembleDebug
```

APK output: `android/app/build/outputs/flutter-apk/app-debug.apk` (or `build/app/outputs/apk/debug/app-debug.apk`)

### Step 5: Install on device

```cmd
adb uninstall dev.xpetalab.hermesconsole   # REQUIRED — different signing key each CI build
adb install android/app/build/outputs/flutter-apk/app-debug.apk
```

### Step 6: Test

1. Open app → welcome screen
2. Tap **"Local Hermes engine"** card
3. Watch progress: rootfs download → extract → Python → Hermes Agent → gateway on localhost:9119
4. If error appears, note exact message — Dart now shows last log entry instead of "unknown"
5. Check logcat: `adb logcat -s LocalEngineBootstrap LocalHermesManager LocalEngineController`

---

## 4. NEXT STEPS (in priority order)

### Immediate (this session)

1. **Test on real Redmi Note 13 Pro 5G** — install latest APK, tap local engine card, report exact error message if it fails.
2. **Verify rootfs download** — HTTP 403 fix added more mirrors; confirm download succeeds on device.
3. **Verify Hermes gateway starts** — check `localhost:9119` responds, Console chat connects.

### Short-term

4. **Add Settings toggle** — currently local engine only accessible via onboarding welcome card. Add a Settings page switch to enable/disable local engine and show status.
5. **Model configuration** — Hermes Agent skeleton config defaults to OpenRouter. User must configure API key. Add UI to configure model/provider within local engine flow.
6. **Background persistence** — foreground service keeps engine alive; test app survives background/foreground cycles.
7. **Update without uninstall** — build locally on PC with a persistent debug keystore so `adb install -r` works without uninstalling.

### Medium-term

8. **Local model (Gemma 3 1B / SmolLM2)** — currently Hermes Agent connects to remote providers. Add on-device model inference via llama.cpp FFI or LiteRT-LM for fully offline agent.
9. **Cron/scheduler** — port Hermes cron scheduler to Android WorkManager.
10. **MCP client** — port Model Context Protocol client for local tool servers.
11. **Skills system** — port Hermes skill slash commands to Dart/Kotlin.

### Long-term

12. **Full Dart rewrite** — currently runs Hermes Agent Python via PRoot. For "light and faster" long-term, rewrite agent core natively in Dart (see earlier analysis: 3–6 months for full parity).
13. **Multi-arch support** — x86_64 emulator support if needed.

---

## 5. IMPORTANT STORIES / CONTEXT

### Why PRoot instead of Dart rewrite?

- **Time:** PRoot integration = days; Dart rewrite = 3–6 months for full Hermes parity (70+ tools, memory, cron, skills, MCP).
- **Compatibility:** Runs the actual Nous Research Hermes Agent code 1:1 — no behavior drift.
- **Trade-off:** Larger APK (~185 MB debug), ~150 MB rootfs download, ~1–2 GB RAM, arm64 only.

### Why not Chaquopy / python-for-android?

- Chaquopy is proprietary (some features paid), adds interpreter bulk, not all PyPI packages build cleanly for Android arm64.
- PRoot + Ubuntu rootfs uses standard `apt-get`/`pip` — same as Hermes docs.

### License compatibility (Console is GPL-3.0-only)

| Component | License | Compatible? |
|-----------|---------|-------------|
| proot | GPL-2.0 | ✅ Yes (GPL family) |
| Hermes Agent | MIT | ✅ Yes |
| hermes-mobile (reference) | MIT | ✅ Yes (reference only) |
| Ubuntu rootfs | Ubuntu EULA | ⚠️ Download at runtime, don't ship in APK |
| commons-compress / xz | Apache / public domain | ✅ Yes |

### HermesAgentMobile reference (Binair-Dev)

Studied `github.com/Binair-Dev/HermesAgentMobile` — same proot + Ubuntu rootfs approach. Key patterns borrowed:
- `BootstrapManager` — rootfs extraction with fake `/proc`/`/sys`
- `ProcessManager` — proot install vs gateway modes, clean environment
- `SetupService` — foreground service for background install
- `NativeBridge` — MethodChannel to Kotlin, all responses on UI thread (`runOnUiThread` equivalent)

### @UiThread error lesson

MethodChannel callbacks from Kotlin to Dart run on a **background platform thread**. Calling `setState` directly from `onProgress` crashes with `@UiThread` violation. Fix:
- **Kotlin:** Wrap all `result.success()`/`result.error()`/`channel.invokeMethod()` in `Handler(Looper.getMainLooper()).post {}`
- **Dart:** Wrap `setState` in `WidgetsBinding.instance.addPostFrameCallback(() { setState(...) })`

### APK update problem

CI debug APKs are signed with a temporary key each run. Android won't allow update without uninstall. **Build locally on PC** with a persistent debug keystore to allow `adb install -r` updates without uninstalling.

---

## 6. FILES CHANGED IN THIS SESSION

```
android/app/build.gradle.kts                    (modified - NDK, jniLibs, deps)
android/app/src/main/AndroidManifest.xml        (modified - permissions + service)
android/app/src/main/kotlin/com/hermesagent/hermes_android/localengine/LocalEngineBootstrap.kt   (new)
android/app/src/main/kotlin/com/hermesagent/hermes_android/localengine/LocalEngineProcess.kt     (new)
android/app/src/main/kotlin/com/hermesagent/hermes_android/localengine/LocalHermesManager.kt     (new)
android/app/src/main/kotlin/com/hermesagent/hermes_android/localengine/LocalEngineController.kt   (new)
android/app/src/main/kotlin/com/hermesagent/hermes_android/localengine/LocalEngineForegroundService.kt (new)
android/scripts/fetch-proot-binaries.sh         (new)
android/app/src/main/res/values/strings.xml     (modified - notification strings)
lib/core/services/platform/local_engine.dart    (new)
lib/core/services/connection_manager.dart       (modified - local connection methods)
lib/core/screens/onboarding_screen.dart         (modified - local engine step)
lib/core/screens/onboarding/connect_method_cards.dart   (modified - local engine card)
lib/core/screens/onboarding/connect_method_draft.dart   (modified - local engine enum)
lib/l10n/app_en.arb                             (modified - English strings)
lib/l10n/app_es.arb                             (modified - Spanish template)
pubspec.yaml                                    (modified - version 1.4.3+14036)
.github/workflows/local-engine-build.yml        (new - CI workflow)
```

---

## 7. GIT STATE

```
Branch: main
Remote: origin (ylstack1/hermes-console-one)
Last commit: d92ccaa fix: add more Ubuntu rootfs mirrors for HTTP 403
Working tree: clean
```

---

## 8. CONTINUE ON PC — CHECKLIST

- [ ] Clone/pull latest: `git pull origin main`
- [ ] Verify localengine/ Kotlin files exist
- [ ] Run `android/scripts/fetch-proot-binaries.sh` to populate jniLibs
- [ ] Create `android/local.properties` with `flutter.sdk=F:\toolchain\flutter`
- [ ] Run `flutter pub get`
- [ ] Run `flutter build apk --debug` (or `.\gradlew assembleDebug`)
- [ ] Install on device: `adb uninstall dev.xpetalab.hermesconsole && adb install app-debug.apk`
- [ ] Test local engine card → report exact error message if fails
7. **Build locally on PC with a persistent debug keystore** so `adb install -r` works without uninstalling.

---

## 9. SUPPORTING REFERENCES

- Hermes Agent docs: https://hermes-agent.nousresearch.com/docs/
- HermesAgentMobile (reference impl): https://github.com/Binair-Dev/HermesAgentMobile
- Hermes Console docs: `docs/` folder in repo (ARCHITECTURE.md, FEATURES.md, SECURITY_POLICY.md, UPSTREAM_CONTRACT.md)
- PRoot: https://github.com/proot-me/proot (GPL-2.0)
- Ubuntu rootfs: https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/

---

**End of handoff.** Any agent can pick up from here by reading this file, the repo, and running the quickstart on PC.

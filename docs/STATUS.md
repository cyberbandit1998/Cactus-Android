# Features, tests and limitations

Last updated with the final push of this work. Evidence for every "verified" claim is in
[VERIFIED_CONTRACT.md](VERIFIED_CONTRACT.md); the phone-only items are in [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md).

## 1. Implemented features

Legend: **built** = code exists and compiles in CI; **JVM-tested** = also covered by unit tests that ran;
**phone-only** = needs your Pixel 7 to know it works.

### Native AI (Needle 3 + Whistle)

| Feature | Status |
| --- | --- |
| Official engine linked statically via a thin C++ JNI shim (no Rust, no rewritten inference) | built; link verified on Linux and Windows runners |
| Whistle speech-to-text: 16 kHz mono, English, keyword hints from installed app names | built; **phone-only** |
| Needle 3 command interpretation: tool list given to `needle_init`, one conversation per command | built; **phone-only** |
| Whistle text reaches Needle automatically; typed text goes straight to Needle | built |
| Structured output validated: strict JSON, allowlisted tools, typed arguments, ranges, closed sets, hidden-character filter | built; JVM-tested (20 + 10 tests) |
| Confidence gate (default 70%, adjustable), "ungrounded" values, negation, suppressed calls | built; JVM-tested |
| Ambiguous / unsupported / invalid output handled with a clear message | built; JVM-tested |
| Per-stage timings measured on the device (load, record, speech, command, action, total) + engine-reported tokens/s | built; **phone-only** |
| Raw JSON viewer, copy recognised text, processing time and errors | built |
| Fallbacks if the tool list is too big for `needle_init` | built; whether needed is **phone-only** |

### Phone control

| Feature | Status |
| --- | --- |
| Open apps by name with fuzzy matching, roles (camera, messages, maps, clock...), disambiguation, website fallback | built; matcher JVM-tested; launching **phone-only** |
| Show installed apps (tap to open) | built; **phone-only** |
| Web search (Google, YouTube, Reddit, Wikipedia, Maps) through fixed hosts; offline message | built; URL building JVM-tested |
| Settings screens: Wi-Fi, Bluetooth, display, battery, sound, location, apps, storage, airplane, NFC, date/time, accessibility, main | built; **phone-only** |
| Alarms and timers via the Clock app | built; **phone-only** |
| Dial number, call contact (prefilled dialer, never places a call) | built; filter JVM-tested |
| Text a contact or number (prefilled message, never sends) | built |
| Battery level, media volume up/down/mute/unmute/set | built; **phone-only** |
| Contact search (permission-gated), file-name search in folders you pick (Storage Access Framework) | built; **phone-only** |
| Confirmation for calls/texts (default on), for low confidence, and for risky taps | built; JVM-tested |
| Optional Accessibility service: scroll, back/home/recents/notifications, tap by text, tap Nth result, type, find text; protections for password/payment fields and permission/installer screens | built; **phone-only** |
| Multi-step commands with timeouts, stop-on-first-problem, cancellation, redundant "open then search" collapsed | built; JVM-tested |
| Digital assistant integration (VoiceInteractionService + Compose overlay session + ASSIST intent) | built; **phone-only** (Pixel 7 behaviour is unknown) |

### Voice

| Feature | Status |
| --- | --- |
| Large push-to-talk button: hold-to-talk or tap-to-start/tap-to-stop, screen-reader action | built; **phone-only** |
| Visible "Microphone is on" indicator with level and timer; mic released immediately; stops when app leaves the screen | built; **phone-only** |
| Permission request at first use; denial explained | built |
| Cancel recording; 30 s limit | built |
| Voice activation master switch | built |
| Wake word | **not implemented, on purpose** (needs an extra model; not approved) |

### Models, storage, privacy

| Feature | Status |
| --- | --- |
| Download from Hugging Face with progress, speed, resume after interruption, host allow-list, https only | built; JVM-tested against a real local HTTP server |
| Integrity: SHA-256 when published, size, `.cact` structure check (catches truncation and wrong files), verify button | built; JVM-tested |
| Update check, delete, delete-and-reinstall, storage use/free, load status | built |
| Private storage, excluded from backup | built |
| History: local file, capped at 500, clear button | built; JVM-tested |
| No analytics, ads, accounts, crash reporters; telemetry env opt-out set before engine use | built; closed library inspected (no network imports, no URLs) |

### Build

| Feature | Status |
| --- | --- |
| Android Studio project, Gradle wrapper, version catalog | built |
| Windows scripts: environment check, engine fetch, debug build to `dist\CactusAI-debug.apk`, signed release build | CI ran the fetch script and the Gradle build on Windows; the `.ps1` wrapper scripts for build/check were **not** run |
| GitHub Actions: tests, APK on Linux + Windows, unsigned release, device-test compile, lint, engine/APK inspection | green on both runners (see VERIFIED_CONTRACT.md 2.4) |

## 2. Test inventory

| Suite | Count | Where it ran | Result |
| --- | --- | --- | --- |
| `:core` JVM tests (JSON 13, registry 20, Needle/Whistle parsing 10, planner 16, matching 11, audio 5, models/downloader 21, step runner + history 11, JNI glue vs. test double 14) | 121 | sandbox and GitHub Actions | all pass |
| `:app` JVM tests | 2 | GitHub Actions | pass |
| Instrumented tests (real models 7, Android actions 10, assistant/lifecycle 9, offline 1) | 27 | **compiled only** | **not run: need an arm64 phone** |
| Lint | - | GitHub Actions | 0 errors |

What the 121 tests deliberately do **not** prove: that Needle or Whistle produce the right output (the JNI tests use a
test double that only echoes), that Android behaves as the intents expect, or anything about speed or memory.

## 3. Differences from the brief, and why

* **Rust: none.** (Final instruction of the brief.)
* **"Official Cactus Android bindings"**: the `cactus` repository's Kotlin/Android bindings wrap a different engine
  (`libcactus_engine`) whose open-source code has no support for the `.cact` format, Needle 3 or Whistle (searched at
  v2.2.2). The supported route for these two models is the standalone engine and C API shipped on Hugging Face, which
  is what the app uses, through a JNI shim.
* **Hardware acceleration:** not used. The vendor documents a CPU engine for Android; nothing documents a GPU or
  TPU path for Needle/Whistle, and nothing could be measured, so none was added.
* **Unloading models:** the engine has no unload call, so models stay in memory after first use until the process ends.
* **Cancelling inference:** a model call already running cannot be interrupted; its result is discarded.
* **Release APK size / minimising:** debug APK is about 25 MB (mostly debug Compose dex). The release build uses R8;
  its size was not recorded here.
* **APK in `dist/`:** the Windows-built CI APK (25,370,561 bytes, built from commit `3fd9431`, run 39471386059) is committed there by the
  workflow's manual `commit_apk` option; `dist/BUILD-INFO.txt` is the authority for its SHA-256 and source commit. It is debug-signed with a throwaway CI key and has never been run
  on a phone. It contains the vendor engine statically linked, which the engine's repository declares Apache-2.0 (NOTICE.md).
  To build your own: `scripts\build-debug.ps1`; the CI artifact `CactusAI-debug-apk-Windows` is the same build.
* **Targets:** `targetSdk 35`, `minSdk 29`, arm64-v8a only. Lint notes newer SDKs/libraries exist; the pinned
  versions are the ones CI built.
* **Wi-Fi/mobile data toggling in the offline test** uses `svc` shell commands through UiAutomator; it is opt-in.

## 4. What to do next

1. Build and install (README quick start).
2. Download the models in the app.
3. Walk `DEVICE_TEST_CHECKLIST.md` and fill in the blanks; run `connectedDebugAndroidTest`.
4. If Needle's accuracy with 17 tools is weak, trim `AssistantTools.kt` toward the vendor's guidance of about five
   tools per context, or enable fewer groups.
5. Decide on a licence for the project's own code (the engine and models are Apache-2.0 as declared by their repositories; see NOTICE.md).

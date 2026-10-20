# Cactus AI — an offline Android phone assistant

A local-first assistant for the Google Pixel 7 (any arm64 Android 10+ phone). You press a button and speak;
**Whistle** (Cactus Compute's speech-to-text model) turns your voice into text on the phone, **Needle 3**
(their tool-calling model) turns that text into one of a fixed list of Android actions, and the app performs it
through ordinary Android APIs. No cloud AI, no account, no analytics.

> **Needle is a command interpreter, not a chat model.** It picks an action from a list ("open an app", "set a
> timer") and fills in the details. Ask it a general question and it answers "I could not match that".

* Kotlin + Jetpack Compose (Material 3) for the app, C++ only for a thin JNI shim over the **official Needle
  engine** (`libneedle.a` from Cactus-Compute). **No Rust anywhere.**
* Models are downloaded once into the app's private storage. After that nothing needs the internet.

## Read this first: what is and is not verified

This project was written in a cloud sandbox with **no Android phone, no emulator and no access to the model
files**. So:

| Verified (with evidence) | How |
| --- | --- |
| The C API Cactus Compute ships for Android matches what the app calls (`needle_load`, `needle_init`, `needle_complete`, `needle_transcribe`, `needle_reset`, `needle_models`) | CI printed the vendor's real `needle.h` and the symbols of `libneedle.a`; see [docs/VERIFIED_CONTRACT.md](docs/VERIFIED_CONTRACT.md) |
| The whole project **builds** on GitHub's Linux **and Windows** runners, links the real engine, and produces an arm64 debug APK (`lib/arm64-v8a/libcactusai_native.so` inside) | GitHub Actions, workflow `build` |
| An unsigned release build (R8 shrinking) and the on-device test sources **compile** | Same workflow |
| 120+ unit tests of the safety rules, parsers, planner, matcher, `.cact` checks, downloader (against a real local HTTP server: resume, corruption, redirects), step runner, history | `./gradlew :core:test`, run in CI and locally |
| The JNI glue handles UTF-8/emoji, float arrays, errors, concurrency, and sets the telemetry opt-out | 14 JVM tests against a **test double** of the engine's C ABI (not the real engine) |

| **Not verified — needs your Pixel 7** | Where to check |
| --- | --- |
| That Whistle and Needle actually load and answer on the phone; their accuracy, speed and memory | [docs/DEVICE_TEST_CHECKLIST.md](docs/DEVICE_TEST_CHECKLIST.md) §2, §3, §9 |
| Whether the 17-tool list fits Needle's context window (the app has fallbacks if it does not) | Models tab shows which wording was accepted |
| Microphone capture, the on-screen indicator, push-to-talk gestures | checklist §3 |
| Every Android action on a real phone (timers, dialer, settings, volume...) | checklist §4 and `AndroidActionsTest` |
| Assistant gesture / default-assistant behaviour on the Pixel 7 | checklist §6 |
| Accessibility actions (scroll, tap, type) in real apps | checklist §7 |
| That the engine sends nothing over the network | Data-usage check in [docs/PRIVACY.md](docs/PRIVACY.md) |

**No benchmark numbers are included anywhere**, because none were measured. The app measures every stage on your
phone and shows it, and the checklist has blanks for you to record it.

## Quick start (Windows)

You need: **Android Studio** (it brings the JDK, SDK and an emulator) and **Git**. No Rust, no Cargo, no separate Gradle.

1. **Check your PC.** In PowerShell, from the project folder:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1
   ```
   It lists anything missing and how to install it. In Android Studio's **SDK Manager** make sure these are installed:
   *SDK Platform "Android 15 (API 35)"*, and under *SDK Tools*: *NDK (Side by side)*, *CMake 3.22.1*,
   *Android SDK Platform-Tools*. (If you skip NDK/CMake, Gradle downloads them on the first build after you
   accept the licences: `sdkmanager --licenses`.)
2. **Build the APK** (this also downloads the Needle engine, about 4 MB, from Hugging Face):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\build-debug.ps1
   ```
   Result: `dist\CactusAI-debug.apk`. The first build takes several minutes.
3. **Install on the phone** (USB debugging on, phone connected, `adb devices` shows it):
   ```powershell
   adb install -r dist\CactusAI-debug.apk
   ```
4. **Open Cactus AI → Models tab → download Whistle and Needle 3** (about 52 MB together, Wi-Fi recommended).
5. Try the **Command** tab ("Open Bluetooth settings"), then the big microphone on **Home**.

Prefer Android Studio? *File → Open* the project folder, run `scripts\fetch-engine.ps1` once, wait for Gradle sync,
choose the `app` configuration and press Run with the phone selected.

No Windows machine handy? Every push builds a debug APK in GitHub Actions: open the repository's **Actions** tab,
the latest `build` run, and download the artifact `CactusAI-debug-apk-Windows` (or `-Linux`).

### If the build fails

| Message | Fix |
| --- | --- |
| `The Needle engine library was not found in ...native-engine\android-arm64` | Run `scripts\fetch-engine.ps1` (needs internet to huggingface.co). Or: `pip install cactus-needle` then `needle download android-arm64 --out native-engine` |
| `NDK not configured` / `CMake not found` | SDK Manager → SDK Tools → install *NDK (Side by side)* and *CMake*, or accept licences so Gradle can fetch them |
| `Could not determine java version` / `requires JDK 17` | Use Android Studio's bundled JDK: Settings → Build Tools → Gradle → Gradle JDK = *jbr-17* (or newer). `build-debug.ps1` does this for you |
| `SDK location not found` | Create `local.properties` with `sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk` (Android Studio does this automatically) |
| Gradle cannot download anything | Corporate proxy/VPN: set it in `%USERPROFILE%\.gradle\gradle.properties` |
| `undefined symbol: needle_...` at link time | The downloaded engine does not match `native/shim/needle_abi.h`. Re-run the fetch script; check `native-engine\android-arm64\ENGINE_SOURCE.txt`; see docs/VERIFIED_CONTRACT.md |
| App says "AI engine missing" | The APK was built with `-Pcactusai.allowMissingEngine=true` (CI compile checks only). Rebuild normally |

More: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## What it can do

Say or type; Needle picks one of these 17 tools (`core/.../tools/AssistantTools.kt`).

| You say | Tool | What the app does |
| --- | --- | --- |
| "Open Spotify", "Launch YouTube", "Open my camera/messages/Google Maps/the clock" | `open_app` | PackageManager launch; roles ("camera", "messages", "maps", "clock", ...) open your *default* app; several matches → you choose |
| "Search Google for ...", "Look up ...", "Search YouTube for ...", "Open Reddit" | `search_web` / `open_app` | Browser or app via a URL built from a fixed host list; says so when offline |
| "Open Wi-Fi / Bluetooth / display / battery / sound / location ... settings" | `open_settings` | Android settings intents |
| "Set a timer for 15 minutes", "Set an alarm for 7 am" | `set_timer`, `set_alarm` | Asks the Clock app; its own screen confirms |
| "Dial 555-123-4567", "Open the dialer" | `dial_number` | **Prefilled dialer; never places the call** |
| "Call Mom" | `call_contact` | Finds the contact (with permission), **prefilled dialer** |
| "Text John that I'm on my way" | `send_text` | **Prefilled message screen; never sends** |
| "What's my battery?" | `check_battery` | Reads the level |
| "Turn the volume up / mute / set volume to 40" | `change_volume` | Media volume |
| "Show my installed apps", "Find John in my contacts", "Find my resume file" | `search_phone` | Apps; contacts (with permission); files only in folders you chose |
| "Scroll down", "Go back", "Tap search", "Select the first result", "Type hello", "Is Settings on screen" | `scroll`, `navigate`, `tap_text`, `tap_result`, `type_text`, `find_on_screen` | Optional Accessibility service |

Anything unsupported, ambiguous, negated ("don't open ...") or below the confidence threshold is shown or refused,
never silently guessed. Multi-part commands ("open YouTube and search for woodworking") run step by step with timeouts
and stop at the first problem.

## Screens

Home (big push-to-talk button, microphone indicator, "You said", assistant answer, current action, cancel) ·
Command (typed commands, interpretation, raw JSON viewer) · History (local, clearable) ·
Models (download/progress/verify/update-check/delete/reinstall, load status, storage, measured timings, temperature) ·
Settings (microphone, assistant, accessibility, permissions, confirmations, files, privacy, debugging).

## Digital assistant and accessibility

Both optional, both documented honestly in [docs/ASSISTANT_AND_ACCESSIBILITY.md](docs/ASSISTANT_AND_ACCESSIBILITY.md),
including the Android 13+ **"Allow restricted settings"** step needed for apps installed from a file, and what Android
may reserve for Google's own assistant.

## Privacy

Everything runs on the phone. See [docs/PRIVACY.md](docs/PRIVACY.md). Short version: the only network traffic is the
model download and web searches you ask for; recordings are never saved; history is local and deletable; the vendor
engine's telemetry is switched off (and you can verify the data counter stays at zero).

## Tests

```powershell
.\gradlew.bat :core:test                          # 120+ JVM tests: safety rules, parsers, downloader, JNI glue (test double)
.\gradlew.bat :app:testDebugUnitTest              # small Android-module unit tests
.\gradlew.bat :app:connectedDebugAndroidTest      # ON A REAL arm64 PHONE: real models, real actions, assistant, UI
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.offline=true"   # Wi-Fi/data off scenario
```

The device tests need both models downloaded in the app first and skip themselves if not. They print a command
accuracy sheet and measured timings to logcat (`adb logcat -s CactusTest`). The speech fixtures are **synthetic
text-to-speech** (see `app/src/androidTest/assets/synthetic_speech/README.md`), not human recordings.

## Release build

See [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) (`scripts\build-release.ps1`).

## Project layout

```
core/            pure Kotlin: allowlist, validation, parsing, planner, matcher, downloader, step runner, history, JNI binding class
app/             Android: engine host, microphone, actions, Accessibility, assistant session, Compose UI, device tests
native/shim/     C++ JNI shim + the C ABI declarations (compared against the vendor header)
native/android/  CMake used by Gradle        native/host-test/  TEST DOUBLE of the C ABI, tests only, never packaged
native-engine/   (git-ignored) the official engine files fetched by scripts/fetch-engine.*
scripts/         check-env, fetch-engine, build-debug, build-release
docs/            ARCHITECTURE, VERIFIED_CONTRACT, DEVICE_TEST_CHECKLIST, ASSISTANT_AND_ACCESSIBILITY, PRIVACY, RELEASE_SIGNING, TROUBLESHOOTING, STATUS
```

## Licences and credits

* Cactus Compute's **Needle** and **Whistle** (engine and weights) are third-party. The Needle repository is
  Apache-2.0; the licence of the prebuilt engine binary and the model weights was **not verifiable from the build
  environment** (the model pages were unreachable). Read the Hugging Face model cards before sharing an APK.
  The separate `cactus` engine repository uses a custom licence (free for personal/non-commercial use) and is
  **not used** here: its open-source engine does not support Needle 3 or Whistle.
* Android Jetpack/Compose, Kotlin and kotlinx.coroutines: Apache-2.0.
* Test audio was synthesised with eSpeak NG (GPL-3.0); only the generated audio is included, nothing is linked.
* This project's own code has no licence file yet: add the one you want. See [NOTICE.md](NOTICE.md).

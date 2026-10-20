# Architecture

Cactus AI is a Kotlin + Jetpack Compose app with a small C++ shim that calls the official Needle engine.
There is no Rust anywhere in the project.

```
 microphone ─► MicRecorder ─► [Whistle: needle_transcribe] ─► text ─┐
 typed text ───────────────────────────────────────────────────────┤
                                                                    ▼
                                         [Needle 3: needle_init/needle_complete]
                                                      │  JSON envelope (tool calls, confidence)
                                                      ▼
                       NeedleResponses.parse ─► Planner (allowlist + policy) ─► Run / Confirm / Refuse
                                                                                   │
                                  ┌────────────────────────────────────────────────┘
                                  ▼
                        StepRunner (timeouts, cancel, stop on first problem)
                                  ▼
                        AndroidActionExecutor ─► Android intents / PackageManager / AudioManager
                                              └─► Accessibility service (optional, user-enabled)
```

## Modules

| Module | What it holds | Runs where |
| --- | --- | --- |
| `:core` | Everything that does not need Android: strict JSON, the **tool allowlist and argument validation**, Needle/Whistle response parsing, the planner, app/contact matching, `.cact` structure checks, the resumable model downloader, the step runner, history storage, and the JNI binding class `NativeLib`. | Plain JVM. Tested on any computer. |
| `:app` | The Android parts: engine host, microphone, model manager, action executor, Accessibility service, assistant session, Compose UI. | Android 10+ (minSdk 29), arm64-v8a |
| `native/` | `shim/` (the JNI shim and the C ABI declarations), `android/` (CMake used by Gradle), `host-test/` (a **test double** of the C ABI used only by JVM tests, never packaged). | NDK / host |

Why split it this way: the safety-critical rules (what the model may ask for, how arguments are checked, when to
ask the user) are pure Kotlin with 120+ unit tests that run in seconds, with no phone needed.

## The native layer

`libneedle.a` is the official engine for Android (Cactus-Compute/needle3, `android-arm64/`). Our shim
(`native/shim/cactusai_jni.cpp`) links it statically into `libcactusai_native.so` and exposes eight functions
to Kotlin. It contains no AI logic. It:

* converts Java strings to UTF-8 and back correctly, including emoji (JNI's own "modified UTF-8" does not);
* memory-maps model files and keeps them mapped (the C ABI does not say whether the engine copies the bytes);
* serialises calls with a mutex (the engine is not thread-safe) and returns failures as values;
* sets `NEEDLE_TELEMETRY=0` and `DO_NOT_TRACK=1` before the engine is first used;
* compiles in an explicit "engine not linked" mode when the library is missing, which the UI reports.

Both models live in one engine: `needle_load` "reads whichever kind the .cact holds and keeps the other"
(vendor header). The app checks this with `needle_models()` after each load.

The engine has **no unload call**. A loaded model stays until the process ends, so "release unused resources"
means: the microphone and `AudioRecord` are released the instant recording ends, nothing is loaded at app start
(models load on first use), and no background service exists.

## Threading

* One dedicated thread, `cactus-engine`, makes every native call (`EngineHost`).
* The UI thread never waits on the engine; the controller runs on `Dispatchers.Default`.
* Recording runs on `Dispatchers.IO` and checks for cancellation every 100 ms.
* A model call that is already running inside the engine cannot be interrupted. Cancelling discards its result.

## One command, step by step (`AssistantController`)

1. **Record** (voice only): 16 kHz mono PCM16, at most 30 s, level meter on screen. Stops on tap/release, on the
   limit, on cancel, or when the app leaves the screen (Android silences the mic for background apps).
2. **Trim silence** (optional setting) and reject clips with no speech without calling any model.
3. **Whistle**: `needle_transcribe`, language `en`, with the names of installed apps as keyword hints (setting).
4. **Needle**: `needle_reset`, then `needle_complete` on the text. The tool list was given to `needle_init`
   once (again only if the date or tool set changes).
5. **Parse** the JSON envelope strictly (duplicate keys, bad types, huge nesting are rejected).
6. **Plan** (`Planner`): validate every call against the allowlist, then decide *run*, *ask first* or *refuse*.
7. **Confirm** if needed, in the app, showing the exact action.
8. **Execute** step by step with a timeout per step; the first failure stops the sequence.
9. **Remember** the result in the local history, with the measured time of each stage.

## Safety model

* The model chooses a tool name and typed arguments. Nothing else it writes is used: not as code, a shell
  command, a URL or an Intent. Web addresses are built from a fixed list of hosts; settings screens and app roles
  come from fixed tables; phone numbers must pass a strict filter (no `*` or `#`, so no service codes).
* Unknown tools, unknown or extra arguments, wrong types, out-of-range numbers, values outside a closed set,
  control/zero-width/bidi characters and over-long text are all rejected before anything runs.
* Calls and texts only ever open a **prefilled dialer or message screen**; the user presses the button. The
  app does not hold `CALL_PHONE` or `SEND_SMS`. By default the app also asks first.
* Low confidence, details not found in what the user said (`validation.ungrounded`), or a missing confidence
  score means "ask first"; a request the engine flags as negated ("don't open ...") is refused.
* Screen control (Accessibility) is separate, off by default and revocable; see `ASSISTANT_AND_ACCESSIBILITY.md`.

## Handling `needle_init` limits

The vendor header warns `needle_init` fails when the system prompt plus tools exceed the context window. The
tool list is about 5.5 KB, much larger than the vendor's 5-tool examples. `EngineHost` therefore tries the full
wording, then shorter wording, and finally leaves the optional screen tools out, and the planner validates
against the tools Needle was really given. Which level was accepted is shown on the Models tab. Whether the
full list fits on a real phone is **not verified** (see `VERIFIED_CONTRACT.md`).

## Wake word (future)

Not implemented, deliberately. The microphone code is behind `MicRecorder` and the pipeline starts from
`AssistantController.startListening()`, so a later wake-word trigger would call the same entry point. It would
need its own model, which the brief says needs explicit approval first.

## Where to look

| Question | File |
| --- | --- |
| What can the assistant do? | `core/.../tools/AssistantTools.kt` |
| How are arguments checked? | `core/.../tools/ToolRegistry.kt` |
| When does it ask first? | `core/.../plan/Planner.kt` |
| What does each tool do on Android? | `app/.../actions/AndroidActionExecutor.kt` |
| How are models downloaded and verified? | `core/.../models/` |
| The C ABI and JNI | `native/shim/` |

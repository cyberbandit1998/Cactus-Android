# What was checked, against what, and what was not

This file exists so that nothing in the project rests on a guess dressed as a fact. Each claim says where it came
from. Dates are 2026-10-20 unless noted.

## 1. Sources reviewed

| Source | How it was read | Version |
| --- | --- | --- |
| `github.com/cactus-compute/needle` (README, `llms.txt`, Python package `needle/`, tests) | Cloned (shallow) | commit `b4fc11b`, "Pin engine 3.3.3", 2026-10-19 |
| `github.com/cactus-compute/cactus` (README, `android/`, `bindings/kotlin/`, `cactus-engine/`, LICENSE) | Cloned (shallow) | commit `2cfcdb8`, v2.2.2, 2026-09-26 |
| `Cactus-Compute/needle3` on Hugging Face, folder `android-arm64/` | Downloaded by the fetch script on GitHub's runners; header printed and library inspected by CI | repo commit `d6c7d6a2e9655505e0f8e23339356dd1705900eb` |
| `cactuscompute.com`, `docs.cactuscompute.com`, `huggingface.co` web pages | **Not reachable** from the sandbox this project was written in (blocked by its network policy). Their content is not used as evidence anywhere. | - |
| Hugging Face repository listings, `LICENSE` and `README.md` (model-card) headers of `Cactus-Compute/needle3` and `Cactus-Compute/whistle` | Read by a CI step on GitHub's runners (metadata and the first lines only) | snapshot of 2026-10-20 |
| `developer.android.com` | Reachable, but Android API use relies on the compile and lint results below | - |

## 2. Findings

### 2.1 Which engine runs Needle 3 and Whistle

* Needle 3 and Whistle are `.cact` archives (format tag `0x05E12A84`) run by **one closed prebuilt engine**,
  shipped per platform from Hugging Face. The upstream Python package fetches it (`needle/agent/fetch.py`; platforms
  include `android-arm64`) and says: "The Android builds ship a static library and the needle CLI ... not a shared
  library".
* The open-source `cactus` repository (v2.2.2) has **no `.cact`, Whistle, "engram" or Needle-3 code**; its engine's
  "needle" support is an older model type converted from Hugging Face weights. Its Kotlin bindings wrap
  `libcactus_engine`. Conclusion: the "official Cactus Android bindings" cannot run these two models; the standalone
  engine's C API can. The app uses that C API through a small JNI shim.

### 2.2 The C API (matches in three independent places)

Declared in the vendor's `needle.h` (printed by CI), used by the upstream Python wrapper (`ctypes` bindings in
`needle/_worker.py`, `needle/__init__.py`, `needle/agent/whistle.py`), and exported by `libneedle.a` (CI listed
`needle_complete, needle_embed, needle_init, needle_last_error, needle_load, needle_models, needle_reset,
needle_set_audio, needle_stream_transcribe_process, needle_stream_transcribe_stop, needle_transcribe`).

| Function | Signature (vendor header) |
| --- | --- |
| `needle_load` | `int (const unsigned char* cact, unsigned long long n)`; "reads whichever kind the .cact holds and keeps the other" |
| `needle_models` | `int (void)`: bitmask `NEEDLE_TEXT=1`, `NEEDLE_SPEECH=2` |
| `needle_last_error` | `const char* (void)`, valid until the next API call |
| `needle_init` | `int (const char* system_prompt, const char* tools_json, const char* tool_index_path)`; returns the static-prefix token count; "can fail when the system prompt plus statically-declared tools exceed the context window" |
| `needle_complete` | `int (const char* input, const float* pcm, int samples, int max_new_tokens, char* out, int out_capacity)`; exactly one of input/pcm non-null |
| `needle_reset` | `void (void)` |
| `needle_transcribe` | `int (const float* pcm, int samples, const char* language, const char* keywords, int word_timestamps, char* out, int out_capacity)`; returns the transcript's token count; output `{"text","language","ttft_ms","decode_tps"}` |

"One process-global, non-thread-safe model per kind." There is no unload function.
The app's own declarations (`native/shim/needle_abi.h`) were rewritten after reading this header, and the shim
**links against the real `libneedle.a`** (NDK links shared libraries with `--no-undefined`), on Linux and on Windows.

### 2.3 What the closed library imports (CI, `llvm-nm -u` and `strings`)

Everything libneedle.a needs from the system, C level: `_Unwind_Resume`, `__cxa_*`, math (`sin cos exp log pow ...`),
memory/string (`memcpy memmove memset memcmp strlen strstr strtod atoi atof ...`), file I/O
(`fopen fread fwrite fscanf fclose remove`), `mmap`/`munmap`, `pthread_create`/`pthread_setspecific`,
`sched_getaffinity`/`sched_setaffinity`/`sched_getcpu`, `getrusage`, `getauxval`, `stderr`/`fprintf`.

* **No `socket`, `connect`, `getaddrinfo`, `dlopen`, `exec*`, `fork`, `system`, `popen`, curl or SSL imports.** The
  library has no way to open a network connection by itself.
* **No URL strings** (`strings -a libneedle.a | grep http` found nothing).
* The upstream README's telemetry warning ("telemetry is turned on in the binary") therefore most likely concerns
  the separate `needle` command-line program, which the APK does not contain. The shim sets
  `NEEDLE_TELEMETRY=0` and `DO_NOT_TRACK=1` regardless.
* The library does read and write files (`fopen`, `fwrite`, `remove`). What for is **unknown**; the app gives it no
  file paths other than the model buffers it maps.
* This is evidence from imports and strings, not an audit of the binary. The Data-usage check in `PRIVACY.md` is the
  practical test.

### 2.4 Build, packaging, tests (GitHub Actions, workflow `build`)

| Claim | Evidence |
| --- | --- |
| The engine files are fetched correctly and verified | Fetch step on Linux and on Windows (PowerShell) succeeded; `libneedle.a` 2,177,676 bytes, SHA-256 checked against the API's LFS digest |
| The project builds on **Windows** and Linux; NDK 27.0.12077973, CMake 3.22.1, AGP 8.7.3, Gradle 8.10.2, JDK 17 | Both `debug APK` jobs green |
| The APK contains `lib/arm64-v8a/libcactusai_native.so` (about 2 MB, engine linked in) | `unzip -l` in the log |
| An unsigned release build with R8, and the instrumented-test sources, compile | Same job (`assembleRelease`, `assembleDebugAndroidTest`) |
| Lint: 0 errors | Lint summary step (39 warnings, all version-update or housekeeping hints) |
| The Apache-2.0 text is inside the APK (`assets/Apache-2.0.txt`) and the arm64 library is inside it; the build and the APK-commit job fail if either is missing | `unzip -l` checks in the workflow, passing on the Linux job and in the Windows-APK commit job (run of commit `3fd9431`) |
| The debug APK: package `app.cactusai.assistant` 0.1.0, `targetSdkVersion` 35, `native-code: arm64-v8a`, signed with the v2 scheme (`apksigner verify` says "Verifies"), requests only `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `READ_CONTACTS`, `com.android.alarm.permission.SET_ALARM` and AndroidX's `...DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (a signature permission it adds itself); native libraries inside: `libcactusai_native.so` (about 2.0 MB) and `libandroidx.graphics.path.so` | `aapt2 dump badging`, `apksigner verify`, `unzip -l` in the CI log (Linux job, run of commit `5900c97`) |
| 121 `:core` JVM tests pass (including the JNI glue against a test double) | `core tests (Linux)` job and local runs |

### 2.5 Licences

| Item | Finding |
| --- | --- |
| `cactus-compute/needle` repository | Apache-2.0 (`LICENSE`, `pyproject.toml`) |
| `cactus-compute/cactus` repository | Custom licence: free for individuals / education / non-profits / organisations under USD 2M funding and revenue; commercial licence otherwise. **Not used.** |
| Prebuilt engine (`libneedle.a`, `needle.h`) and the `.cact` weights | **Apache-2.0 as declared by their repositories.** CI printed it: both `Cactus-Compute/needle3` (which contains the engine in `android-arm64/`) and `Cactus-Compute/whistle` have an 11,358-byte `LICENSE` that begins "Apache License, Version 2.0, January 2004" (the same size as the upstream `needle` repository's Apache-2.0 `LICENSE`), and model-card headers with `license: apache-2.0`. Only the first lines of each file were read. Not checked: extra terms inside the closed binary, a separate `NOTICE`, the training-data licences. Details and consequences: `NOTICE.md`. |

## 3. Facts about the models taken from upstream documentation (not tested here)

* Whistle: 16.9 MB, 16 kHz mono audio, up to 30 s in one pass, English/German/French/Spanish/Italian/Dutch/Polish;
  silence gives an empty text. Needle 3: "35 MB `needle3.cact`", a tool-caller, empty list for off-topic requests,
  calibrated `confidence`, `validation.ungrounded`. (README, `llms.txt`.) The app's approximate sizes (17 and 35 MB) come
  from these statements; the real sizes come from the server at download time.
* Upstream guidance: keep toolsets small (five or fewer in its examples); plain names; closed sets as enums;
  "Takes no arguments" in descriptions; relative dates need a `date:` fact; call `reset()` between independent queries.
  The app follows all of these except the tool count (17), which is why it has the fallbacks described in ARCHITECTURE.md ("Handling `needle_init` limits").

## 4. Model hosting (metadata check in CI, only metadata was fetched)

The download design was checked against the real hosts by a CI step that requests listings and headers (never the
model files themselves):

| Item | Finding |
| --- | --- |
| Repositories and file names | `Cactus-Compute/needle3` contains `needle3.cact`; `Cactus-Compute/whistle` contains `whistle.cact` (as in the upstream `fetch.py`) |
| Sizes | `needle3.cact` **35,335,380 bytes**; `whistle.cact` **16,919,407 bytes** |
| Checksums | The repository listing returns `lfs.oid` (SHA-256) and `lfs.size`; the first answer to the download URL also carries `x-linked-etag` (full SHA-256; for needle3 `c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38`) and `x-linked-size`. The app reads the listing first and these headers as a fallback. |
| Redirect | The download URL answers `302` to a signed address on `us.aws.cdn.hf.co` (a `*.hf.co` host, which the app's allow-list permits). The signed address expires, so the app asks the file host again when it resumes. |
| Resume | The file server sends `accept-ranges: bytes` and answers a range request with `206` and `content-range` |
| Hash on the redirect | The first answer is a `302` that is not followed when reading the headers, as the upstream client does |

The hosts and sizes above are a snapshot from 2026-10-20; the app reads the real values from the server at download time.

## 5. Not verified at all

* Anything about Needle or Whistle output quality, speed, memory or battery on a Pixel 7.
* That the 17-tool list fits Needle's context window (unknown window size; fallbacks exist).
* Microphone capture on the phone, the assistant gesture, accessibility behaviour, every Android intent on a real
  phone, the `.ps1` convenience scripts other than `fetch-engine.ps1`.
* Anything beyond what the repositories' `LICENSE` files and model cards declare about the engine and weights (see 2.5).
* That the engine's file I/O is harmless (see 2.3).

## 6. Added with automatic execution and spoken answers

### 6.1 Needle's confidence value (from upstream documentation and code; the Android build has not been run)

* The engine's JSON envelope for `needle_complete` carries a calibrated `confidence` between 0 and 1 and a `suppressed_calls`
  list; both are documented in the Needle repository, together with the note that weights without the calibration head
  return no confidence. The existing JNI shim passes the envelope through unchanged and `NeedleResponses.parse` reads the
  value (null when absent or outside 0..1).
* **Unknown until the Pixel 7 run:** whether the Needle 3 weights this app downloads return a confidence value on Android.
  The app never estimates one: with no number, every command is shown to you first, and the screen says so.

### 6.2 Decisions that replaced earlier ideas

* **No Ollama, no web-search API, no API key.** An earlier request asked for an Ollama web search; a later one replaced it
  with reading the user's own browser through the Accessibility service and said not to add Ollama, Rust or cloud AI
  services. The later request was followed. The code and tests contain none of: an HTTP client for search, a stored key, an
  Ollama or other AI endpoint (a test scans the shipped sources for such names).
* **No extra voice-activity model.** End-of-speech detection is a loudness-and-variation detector in plain Kotlin, tuned on
  synthetic audio only (docs/AUTO_STOP.md).
* **Text-to-speech** is Android's `TextToSpeech` with the phone's engine; no speech synthesis library was added.

### 6.3 Not verified at all (needs the Pixel 7)

Real-voice behaviour of the end-of-speech detector; Chrome's accessibility tree for a Google results page (headings, link
addresses, ads, consent screens) and whether the parser's rules hold on it; whether Android lets the app return to the front
after a search; the system voice; how well Needle chooses between 24 tools in the context window it has; the speed of the whole
voice-to-answer path.

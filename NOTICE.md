# Third-party components

This project's own source code has **no licence file yet**. Until you add one, nobody but you has the right to reuse
it. (Choose the licence you want and add a `LICENSE` file.)

| Component | Used for | Licence | Notes |
| --- | --- | --- | --- |
| Needle engine (`libneedle.a`) from Hugging Face `Cactus-Compute/needle3`, folder `android-arm64/` | Runs Needle 3 and Whistle | **Apache-2.0**, as declared by that repository (see below) | Fetched by `scripts/fetch-engine.*`, never committed. Statically linked into the APK (the engine is a closed binary; its source is not available to this project). The licence text is in `LICENSES/Apache-2.0.txt`. |
| Needle 3 and Whistle weights (`needle3.cact`, `whistle.cact`) | The models | **Apache-2.0**, as declared by the `Cactus-Compute/needle3` and `Cactus-Compute/whistle` repositories | Downloaded by the app on the phone from Hugging Face; not part of this repository or the APK. |
| `cactus-compute/needle` (Python package, reference only) | Source of the verified C API usage | Apache-2.0 (its `LICENSE`) | Read, not copied or linked. |
| `cactus-compute/cactus` engine repository | Checked for Android support | Custom licence: free for individuals and non-commercial use; commercial licence needed above stated funding/revenue limits | **Not used.** At v2.2.2 its open-source engine does not support Needle 3 / Whistle (`.cact`). |
| AndroidX (Core, Activity, Lifecycle, Compose, Material 3) | App UI | Apache-2.0 | via Google Maven |
| Kotlin, kotlinx.coroutines | Language and concurrency | Apache-2.0 | via Maven Central |
| JUnit 4 | Tests | EPL-1.0 | test only |
| eSpeak NG | Generated the synthetic speech test clips (`app/src/androidTest/assets/synthetic_speech/`) | GPL-3.0 | Not linked or distributed as software; only the audio it produced is in the repository, and only in the test APK. |

## What the Needle / Whistle licence finding rests on

Read by the CI workflow on 2026-10-20 (metadata and the first lines of files only; the `build` run prints it):

* `Cactus-Compute/needle3` and `Cactus-Compute/whistle` each contain a `LICENSE` file of 11,358 bytes whose first
  lines are "Apache License, Version 2.0, January 2004", and their model-card headers say `license: apache-2.0`.
  The engine library is in the `needle3` repository, so that declaration is the only licence statement found for it.
* The same size as the standard Apache-2.0 text (and as the upstream `needle` repository's `LICENSE`) is consistent
  with the unmodified standard text, but only its first lines were read, not the whole file.
* **Not checked:** whether the closed engine binary carries any additional terms inside the file itself, whether a
  separate `NOTICE` file exists, and the licences of the data the models were trained on. This is a reading of
  public repository metadata, not legal advice.
* Apache-2.0 allows redistribution with its conditions (a copy of the licence, notices kept). The APK produced here
  statically links the engine, so a copy of the licence text is kept in this repository (`LICENSES/Apache-2.0.txt`),
  packaged inside the APK as an asset, and shown in the app under Settings → Licences.

Nothing in this project uses Rust, Home Assistant, any smart-home system, any external AI provider or any cloud
transcription.

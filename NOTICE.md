# Third-party components

This project's own source code has **no licence file yet**. Until you add one, nobody but you has the right to reuse
it. (Choose the licence you want and add a `LICENSE` file.)

| Component | Used for | Licence | Notes |
| --- | --- | --- | --- |
| Needle engine (`libneedle.a`) from Hugging Face `Cactus-Compute/needle3`, folder `android-arm64/` | Runs Needle 3 and Whistle | **Not verified.** | Fetched by `scripts/fetch-engine.*`, never committed. Statically linked into the APK. The Hugging Face model page could not be read from the build environment, so its licence terms are unknown to this project. **Read the model card before giving an APK to anyone.** |
| Needle 3 and Whistle weights (`needle3.cact`, `whistle.cact`) | The models | **Not verified.** | Downloaded by the app on the phone from Hugging Face; not part of this repository or the APK. |
| `cactus-compute/needle` (Python package, reference only) | Source of the verified C API usage | Apache-2.0 (its `LICENSE`) | Read, not copied or linked. |
| `cactus-compute/cactus` engine repository | Checked for Android support | Custom licence: free for individuals and non-commercial use; commercial licence needed above stated funding/revenue limits | **Not used.** At v2.2.2 its open-source engine does not support Needle 3 / Whistle (`.cact`). |
| AndroidX (Core, Activity, Lifecycle, Compose, Material 3) | App UI | Apache-2.0 | via Google Maven |
| Kotlin, kotlinx.coroutines | Language and concurrency | Apache-2.0 | via Maven Central |
| JUnit 4 | Tests | EPL-1.0 | test only |
| eSpeak NG | Generated the synthetic speech test clips (`app/src/androidTest/assets/synthetic_speech/`) | GPL-3.0 | Not linked or distributed as software; only the audio it produced is in the repository, and only in the test APK. |

Nothing in this project uses Rust, Home Assistant, any smart-home system, any external AI provider or any cloud
transcription.

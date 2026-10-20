# Troubleshooting

## Building

**`scripts\check-env.ps1` first.** It reports what is missing.

| Symptom | Cause / fix |
| --- | --- |
| `The Needle engine library was not found` | Run `scripts\fetch-engine.ps1`. If Hugging Face is blocked on your network, use a VPN, or on another machine run `pip install cactus-needle` and `needle download android-arm64 --out native-engine`, then copy the folder `native-engine\android-arm64` over. |
| Fetch script: `SHA-256 mismatch` | A download was cut off or altered. Run it again. |
| Fetch script: `No static library (*.a) was found` | Cactus Compute changed the folder layout. Look at `https://huggingface.co/Cactus-Compute/needle3/tree/main/android-arm64`, then adjust the script or use the `needle download` route. |
| Link errors such as `undefined symbol: needle_...` | The engine and `native/shim/needle_abi.h` disagree. Compare `native-engine\android-arm64\needle.h` with the header in `native\shim`; fix the declarations (the C names must match). |
| `Unsupported class file major version` / `Unsupported Java` | Use JDK 17+. In Android Studio: Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → `jbr-17` or newer. |
| `Failed to find Build Tools` / `NDK not found` / `CMake ... not found` | Install them in SDK Manager, or accept licences (`sdkmanager --licenses`) and let Gradle download them. |
| `Invalid or corrupt jarfile ... gradle-wrapper.jar` | The file was damaged by Git line-ending conversion. Re-clone, or restore it from the repository. |
| Out of memory during the build | Close other programs; `org.gradle.jvmargs=-Xmx3g` is set in `gradle.properties`, raise it if you have the RAM. |
| Windows: path too long / antivirus slowness | Build from a short path such as `C:\src\cactusai`; exclude the project and `%USERPROFILE%\.gradle` from live antivirus scanning. |
| `Execution failed for task ':core:buildHostShim'` | Not an error: that task is allowed to fail. It only builds the test-only JNI check, which needs `cmake` and a C++ compiler and is skipped on Windows. |

## Installing

| Symptom | Fix |
| --- | --- |
| `adb: device unauthorized` | Unlock the phone and accept the "Allow USB debugging" prompt. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed with a different key (debug vs release). Uninstall it first: `adb uninstall app.cactusai.assistant`. This deletes its models and history. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | The phone is not arm64 (or this is an x86 emulator). The Needle engine for Android is arm64-only. |
| Play Protect warning | Expected for a sideloaded APK; choose "Install anyway". |

## Using it

| Symptom | Fix |
| --- | --- |
| Home says **AI engine missing** | The APK was built without the engine. Rebuild with `scripts\build-debug.ps1`. |
| Models tab: download keeps failing | Check the connection; tap Resume (it continues). The app only talks to `huggingface.co` / `*.hf.co`; a network that blocks them will block the download. |
| "damaged ... Delete it and download it again" | The file failed the structure check (truncated or the wrong file). Delete and reinstall. |
| "could not be loaded" | The engine rejected the file. Note the message on the Models tab; if the engine in the APK is much older or newer than the model, fetch the engine again and rebuild. |
| "The command model could not be set up with any version of the tool list" | `needle_init` refused every version. Report the message; try fewer tools in `AssistantTools.kt`. |
| Everything is "Not understood" | See `docs/DEVICE_TEST_CHECKLIST.md` §2 and `ModelPipelineTest.commandSheetReport`. Needle only understands commands; try short imperative phrases. |
| Microphone does nothing | Settings → Voice activation on? Android's microphone privacy switch (Quick Settings) off? Another app (a call, a recorder) using the mic? |
| "Accessibility" switch is greyed out | Android 13+ restricted setting: App info → ⋮ → Allow restricted settings. |
| Assistant gesture opens Google instead | Set Cactus AI under Default apps → Digital assistant app; some gestures stay with Google. |
| Alarm/timer does not appear | Some Clock apps ignore the request; check the Clock app. The app asked for it with the standard Android alarm intents. |

## Getting diagnostics

`adb logcat -s CactusAI CactusTest` and the Settings tab's "Debugging" lines (version, shim version, engine linked).

## Independent check with the vendor's own command-line tool (optional, untested here)

`scripts\fetch-engine.ps1` also downloads Cactus Compute's own `needle` program for Android
(`native-engine\android-arm64\needle`). If you suspect the problem is the model or the phone rather than this app, you
can run the vendor's tool directly. The usage comes from the upstream README (`needle --model needle3.cact --tools
tools.json ...`); **it has not been tried by the author of this project**, so treat it as a starting point:

```powershell
# on the PC: get the weights once (about 35 MB)
curl.exe -L -o needle3.cact https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact
adb push native-engine\android-arm64\needle /data/local/tmp/needle
adb push needle3.cact /data/local/tmp/needle3.cact
adb shell chmod +x /data/local/tmp/needle
adb shell "/data/local/tmp/needle --help"
```

Note that this tool reports usage statistics unless `NEEDLE_TELEMETRY=0` and `DO_NOT_TRACK=1` are set in its environment
(`adb shell "NEEDLE_TELEMETRY=0 DO_NOT_TRACK=1 /data/local/tmp/needle ..."`). It is not part of the APK.

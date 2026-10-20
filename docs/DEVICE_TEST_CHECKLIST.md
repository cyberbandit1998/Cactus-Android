# Pixel 7 test checklist

Everything below needs a real phone. **None of it has been run by the person who wrote the code**: the build
environment had no device, no emulator image for arm64 and no access to the models. Treat every line as unknown
until you tick it. Write what you see in the "Result" column; those numbers are the only honest benchmarks.

Set up first: Pixel 7, Android updated, USB debugging on, `adb devices` shows the phone.

```
adb install -r dist\CactusAI-debug.apk
adb logcat -s CactusTest CactusAI        # leave this running in a second window
```

## 1. Install and models

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| 1.1 | Open the app | Home screen; no crash; if the engine banner "AI engine missing" shows, the build lacks `libneedle.a` | |
| 1.2 | Models tab → Download Whistle | Progress bar, speed, then "Installed · ~17 MB" | |
| 1.3 | Models tab → Download Needle 3 | Same, "Installed · ~35 MB" (sizes are the published approximations) | |
| 1.4 | Turn Wi-Fi off during a download, then back on, tap Resume | Continues where it stopped (bytes do not restart at 0) | |
| 1.5 | Force-stop the app during a download, reopen, tap Resume | Continues from the partial file | |
| 1.6 | "Verify file" on both | "matches its recorded checksum" | |
| 1.7 | "Check for update" on both | "up to date", or a clear message if offline / no checksum | |
| 1.8 | "Delete and reinstall" | File removed, downloaded again | |
| 1.9 | Free-space line | Matches Settings → Storage roughly | |

## 2. Command understanding (typed, Command tab)

Run each phrase and note the tool and arguments shown under "How I understood it".

| Phrase | Expected tool | Got | Right? |
| --- | --- | --- | --- |
| Open Spotify | `open_app` (Spotify) | | |
| Launch YouTube | `open_app` | | |
| Open my camera | `open_app` (camera) | | |
| Open Google Maps | `open_app` | | |
| Open my messages | `open_app` | | |
| Show my installed apps | `search_phone` (apps) | | |
| Search Google for the latest technology news | `search_web` | | |
| Look up 3D printing tutorials | `search_web` | | |
| Search YouTube for Bambu Lab P1S tips | `search_web` (site youtube) | | |
| Open Reddit | `open_app` (website if the app is missing) | | |
| Search for pizza recipes | `search_web` | | |
| Open Wi-Fi / Bluetooth / display / battery settings | `open_settings` | | |
| Set a timer for 15 minutes | `set_timer` minutes 15 | | |
| Set an alarm for 7:30 am | `set_alarm` hour 7 minute 30 | | |
| Dial 555-123-4567 | `dial_number` | | |
| Call Mom | `call_contact` | | |
| Text John that I'm on my way | `send_text` | | |
| What is my battery level | `check_battery` | | |
| What is the capital of France | *(nothing: "could not match")* | | |
| Don't open Spotify | *(nothing)* | | |

The same sheet runs automatically with `./gradlew :app:connectedDebugAndroidTest` (class `ModelPipelineTest`,
output under the logcat tag `CactusTest`). If Needle's accuracy with 17 tools is poor, the tool list is the
first thing to trim (`core/.../tools/AssistantTools.kt`).

## 3. Voice (Home tab)

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| 3.1 | Tap the big microphone for the first time | Android asks for microphone permission; Deny shows an explanation, nothing records | |
| 3.2 | Allow, hold the button, speak, release | "Microphone is on" with a level bar while held; text appears under "You said"; the command runs | |
| 3.3 | Tap once, speak, tap again | Same | |
| 3.4 | Start listening, press "Cancel and discard" | Stops; nothing transcribed | |
| 3.5 | Start listening, switch to another app | Recording stops with an explanation | |
| 3.6 | The Android microphone indicator (green dot) | Appears only while listening; gone a moment after | |
| 3.7 | Silence (tap, say nothing, tap) | "I did not hear anything", no model call | |
| 3.8 | Copy button next to the recognised text | Text on the clipboard | |
| 3.9 | Settings → Voice activation off | Mic button disabled; typed commands still work | |
| 3.10 | "Open Spotify.", "Search Google for 3D printing ideas.", "Open my camera.", "Set a timer for 15 minutes.", "Open Bluetooth settings.", "Launch YouTube." by voice | Each does the right thing | |

## 4. Actions and safety

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| 4.1 | "Call Mom" (contacts allowed) | Confirmation card, then the **dialer opens with the number filled in; no call is placed** | |
| 4.2 | "Text John that I'm on my way" | Confirmation card showing the text; Messages opens with the text filled in; **nothing is sent** | |
| 4.3 | Two contacts named alike | "Which one?" list | |
| 4.4 | Two apps with the same name | "Which one?" list | |
| 4.5 | "Call Mom" with contacts permission denied | Explanation; no crash | |
| 4.6 | "Open Zzyzx" | "I could not find an app called…" | |
| 4.7 | Airplane mode → "Search Google for cats" | "You are offline…" and nothing opens | |
| 4.8 | Set a timer / alarm | Clock app shows it | |
| 4.9 | "Turn the volume up", "mute", "set volume to 40 percent" | Media volume changes, Android volume panel shows | |
| 4.10 | History tab | Each command listed with outcome and measured times; "Clear history" empties it | |

## 5. Offline

1. Download both models. 2. Turn on airplane mode (Wi-Fi and data off). 3. Force-stop and reopen the app.
4. Run section 3.10 commands that do not need the internet (settings, timer, camera, battery).
Expected: speech, understanding and those actions all work. Web searches say you are offline.
Automated version: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.offline=true`.

**No phoning home:** Settings → Network & internet → Data usage → App data usage → Cactus AI. After the models
are installed, using voice and offline commands must add **0 bytes**. (The vendor says its engine reports usage
unless told not to; this app switches that off, but you can only confirm it by watching the data counter.)

## 6. Digital assistant

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| 6.1 | Settings → Apps → Default apps → Digital assistant app | Cactus AI is listed | |
| 6.2 | Choose it | Cactus AI's Settings tab says "Cactus AI is your digital assistant" | |
| 6.3 | Use your phone's assistant gesture (press-and-hold power, if set to Assistant) | Cactus AI's panel appears over the current app and starts listening | |
| 6.4 | Say "Open Bluetooth settings" | Runs; panel closes by itself after a plain success | |
| 6.5 | Close with the Close button / tap outside | Microphone stops | |
| 6.6 | Corner-swipe gesture, if enabled on this phone | Note what happens | |
| 6.7 | Lock screen | Note what happens (the assistant is declared **not** available from the lock screen) | |

Android may reserve some gestures and features for Google's assistant. Write down exactly what your Pixel 7 does.

## 7. Accessibility (optional)

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| 7.1 | Fresh install: Settings tab → Accessibility | "Off. This is the default." | |
| 7.2 | "Scroll down" with it off | Explains it is off and offers the Accessibility settings button | |
| 7.3 | Open Accessibility settings → Cactus AI screen control | If greyed out: App info → ⋮ → **Allow restricted settings**, then retry | |
| 7.4 | Turn it on, read Android's warning, accept | Settings tab shows "On and connected" | |
| 7.5 | In Chrome/Settings: "Scroll down", "Scroll up", "Go back", "Go home", "Open recent apps" | Each works | |
| 7.6 | "Tap search" in YouTube/Settings | Taps the search control; if several match, asks which | |
| 7.7 | Focus a text box, "Type hello" | Types it | |
| 7.8 | Focus a password field, "Type hello" | **Refused** | |
| 7.9 | "Tap pay" / "Tap delete" on a screen with such a button | Asks first | |
| 7.10 | On an Android permission dialog: "Tap allow" | **Refused** | |
| 7.11 | "Select the first result" in a list | Best effort; note whether it hits the right row | |
| 7.12 | "Open YouTube and search for woodworking" | Opens YouTube search results | |
| 7.13 | Turn the service off in Android settings | "Scroll down" explains it is off again | |

## 8. Lifecycle

| # | Check | Result |
| --- | --- | --- |
| 8.1 | Rotate the phone while a result is on screen: state is kept | |
| 8.2 | Press Home during "Working out what to do…": the command still finishes and shows in History | |
| 8.3 | Swipe the app away and reopen: models still installed, history intact | |
| 8.4 | Revoke the microphone permission in Android settings, reopen the app, tap the mic: asks again | |
| 8.5 | Revoke contacts, say "Call Mom": asks for permission | |

## 9. Performance (write down what you measure)

Run each three times; the times appear under every result ("Measured on this phone") and on the Models tab.

| Stage | Run 1 | Run 2 | Run 3 |
| --- | --- | --- | --- |
| Whistle model load (first voice command after app start) | | | |
| Needle model load + tool setup (first command after app start) | | | |
| Speech → text, ~3 s of speech | | | |
| Text → tool call | | | |
| Total, end of speech → action done | | | |

Memory: `adb shell dumpsys meminfo app.cactusai.assistant` after one voice command (note "TOTAL PSS").
Battery: Settings → Battery → Battery usage for Cactus AI after a day of normal use.
Heat: run 20 commands in a row and note the Models tab's phone temperature line; the app pauses at "very hot".

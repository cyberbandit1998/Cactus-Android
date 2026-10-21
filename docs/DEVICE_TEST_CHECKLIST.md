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

---

# Added with automatic stopping, automatic execution, questions and spoken answers

Same rule as above: **none of this has been run on a phone by the person who wrote it.** Sections 10 to 15 are the
tests this build needs from you. Background: [AUTO_STOP.md](AUTO_STOP.md), [AUTOMATIC_EXECUTION.md](AUTOMATIC_EXECUTION.md),
[ASSISTANT_INFO.md](ASSISTANT_INFO.md). For the browser-reading steps (12 and 13) turn on Accessibility first (7.3 and 7.4);
the app must have been installed with `adb install -r dist\CactusAI-debug.apk` (or the CI artifact) and both models downloaded.

## 10. Automatic end of speech

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| 10.1 | Quiet room: tap once, say "open Bluetooth settings", stay quiet | Screen goes Listening, then **Speech detected** when you talk; recording stops about 1.5 s after your last word and goes to **Processing** with no second tap | |
| 10.2 | Same, but tap again right after you finish | Stops at once (manual stop still works) | |
| 10.3 | Tap, say nothing | About 8 s later: "I did not hear anything, so I stopped listening." No model call | |
| 10.4 | Tap, then say "set a timer for ... [2 s pause] ... fifteen minutes" | Not cut off at the pause (default 1.5 s timeout). Note where the pause is long enough to cut it | |
| 10.5 | Raise the silence time to 3 s, repeat 10.1 | Stops about 3 s after your last word | |
| 10.6 | Lower it to 0.5 s, say a normal command | Note whether it clips the end | |
| 10.7 | Noisy room (fan, kitchen, TV low): tap, speak a command | Record: did it stop? If never, try sensitivity Low. Expect a TV with speech to keep it open until the time limit or your tap | |
| 10.8 | Quiet voice at arm's length | Record: heard at Normal? at High? | |
| 10.9 | A door slam / clap, then a command | The clap alone does not start the countdown; the command still works | |
| 10.10 | Set the longest recording to 5 s, talk continuously | Stops at 5 s and processes what it has | |
| 10.11 | Turn "Stop listening by itself" off | Back to tap-to-start and tap-to-stop; hold-to-talk still works | |
| 10.12 | Cancel and discard while listening | Stops; nothing transcribed | |
| 10.13 | Details panel after a recording | Says how it ended, when speech started, the background dB, short noises ignored | |

## 11. Automatic execution

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| 11.1 | "Open Spotify" (voice) | "Starting: Opening Spotify. Tap Cancel to stop." for about 1.2 s, then it opens; the card says **Ran automatically** and, if shown, the confidence | |
| 11.2 | Same, tap Cancel in that window | Nothing opens; "Cancelled." | |
| 11.3 | Is a confidence percentage shown at all? | **Key unknown**: write down yes/no. If "The model gave no confidence", every command will ask first; that is the designed safe behaviour | |
| 11.4 | Threshold 95%, say an ordinary command | Asks first if the confidence is under 95% (or always if none is reported) | |
| 11.5 | Threshold 60%, 70%, 80% on the same phrase | Note the confidence the model reports and where it flips between automatic and asking | |
| 11.6 | "Automatically execute safe actions" off | Every command asks first | |
| 11.7 | "Call Mom", "Text John that I'm late" | **Always** asks, at any threshold; opens the dialer/message prefilled, sends and dials nothing | |
| 11.8 | "Open accessibility settings" | Asks first | |
| 11.9 | "Set the volume to 90 percent" | Asks first; "turn the volume up" does not | |
| 11.10 | "Don't open Spotify" | Refused / nothing runs | |
| 11.11 | "Open YouTube and search for woodworking" | Both steps shown or run together; if either needs asking, both wait | |
| 11.12 | With Accessibility on, on a screen with a Pay or Delete button: "Tap pay" / "Tap delete" | Asks first, even with automatic execution on | |
| 11.13 | A dialog "Delete this? [Cancel] [OK]": "Tap OK" | Asks first (the question beside OK mentions delete) | |
| 11.14 | A password field focused: "Type hunter2" | Refused | |
| 11.15 | A permission dialog: "Tap allow" | Refused | |
| 11.16 | History tab and Settings → Recent commands | Each command shows Ran automatically / Ran after you confirmed, the confidence, and online if applicable | |

## 12. Questions that need no internet (airplane mode on)

| # | Say | Expected | Result |
| --- | --- | --- | --- |
| 12.1 | "What time is it?" | Local time, spoken, **no** cancel window, "Answered on this phone" | |
| 12.2 | "What time is it in California?" / "...in Tokyo?" | Correct zone | |
| 12.3 | "What day is tomorrow?" / "How many days until Halloween?" | Correct, computed locally | |
| 12.4 | "How much battery do I have?" "Is my phone charging?" "What Android version is this?" "How much storage is left?" | Matches Settings | |
| 12.5 | "What is 15 percent of 240?" | 36 | |
| 12.6 | "Who won the Tigers game?" (airplane mode) | "You're offline..." and **no browser opens** | |

## 13. The complete workflow: Voice → Whistle → Needle → Browser Search → Accessibility → Answer Extraction → Android TTS

Needs Wi-Fi/data, Accessibility on, Chrome (or your browser) as default, "Look things up online", "Read search results aloud"
and "Speak answers automatically" on. Do each of these with a **voice** command, one tap, no other taps.

| # | Say | Expected | Result |
| --- | --- | --- | --- |
| 13.1 | "Search Google for the latest technology news" | Chrome opens on results; after a moment Cactus AI is back in front and reads the first 2 or 3 result titles and descriptions aloud | |
| 13.2 | "Who invented the telephone?" (Short Answer) | One spoken sentence if two sites agree, otherwise the first results; the card says Online · checked <time> and lists sources | |
| 13.3 | Switch to "Read results", ask again | Reads the first 2 or 3 result titles and descriptions | |
| 13.4 | "When do the Tigers play next?" with Detroit Tigers set as a favourite team | Opponent, date and a start time **in your time zone**, or "I couldn't verify" | |
| 13.5 | "Did the Lions win last night?" / "What was the score of the Michigan football game?" | Score with opponent and date only if two sites agree; else it says it could not confirm | |
| 13.6 | A game in progress: "What's the score of the Tigers game?" | Does **not** present a live score as current; says it cannot verify live scores and where to look | |
| 13.7 | "What's the weather in Detroit?" | Temperature in your unit, attributed to the site | |
| 13.8 | Compare 13.1 with what Chrome shows | Nothing spoken that is not on the page; no ads, menu items, cookie text, phone numbers or emails; no URL spelled out | |
| 13.9 | Press **Stop speaking** during an answer | Speech stops at once | |
| 13.10 | Tap the microphone while it is speaking | Speech cuts off, then the microphone opens; its own words are **not** transcribed as the command | |
| 13.11 | Turn Accessibility off, repeat 13.1 | Results stay open in the browser; the answer says it cannot read them aloud without the Accessibility service | |
| 13.12 | Chrome's page with a consent dialog on first use | Note whether it can be read; if not it says reading is unavailable | |
| 13.13 | After the answer, is Cactus AI in front? | **Unknown**: record yes/no. If Android blocks the return, the browser stays and the answer is still spoken when it can be | |
| 13.14 | Where did the browser end up (still open, history)? | Record it; the app does not close Chrome | |
| 13.15 | Say a search whose page contains "open the camera" | Nothing from the page is ever run as a command | |
| 13.16 | Timing: end of speech → spoken answer | Write down the seconds | |
| 13.17 | Other browser (Firefox/Samsung) as default | Note whether results are readable at all | |
| 13.18 | The overlay (assistant gesture) variant of 13.1 | The overlay may hide when the browser opens; the lookup must still finish and be spoken | |

## 14. Text to speech

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| 14.1 | Settings → Spoken answers → Test voice | Plays the sample sentence | |
| 14.2 | Speed 0.5 and 2.0; pitch 0.5 and 2.0 | Audible difference | |
| 14.3 | Choose voice | Offline voices listed first; the choice is used | |
| 14.4 | Turn off "Speak answers automatically" | Answers appear but are not spoken | |
| 14.5 | Turn off "Allow the assistant to speak" | Nothing is ever spoken; Test voice is silent | |
| 14.6 | Play music, ask the time | Music ducks while speaking and returns after | |
| 14.7 | A phone call or another app takes audio focus mid-answer | Speech stops, no crash | |
| 14.8 | Phone without a speech engine / engine disabled | A clear message, no crash | |

## 15. Things to write down for the next iteration

* The confidence value Needle reports (or that it reports none) for 10 ordinary commands.
* The silence time and sensitivity that feel right in your usual places.
* Any phrase for which Needle picks the wrong one of the 24 tools, and the Models tab's tool-list line (full, shorter, or groups dropped).
* Chrome's actual behaviour for 13.12 to 13.14, and whether Google's result layout is parsed well.

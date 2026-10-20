# Default assistant and Accessibility

Both are optional. Without them Cactus AI is a normal app you open and talk to.

## Making Cactus AI your digital assistant

What is implemented (all in `app/src/main/kotlin/.../assistant/`):

* `CactusVoiceInteractionService`: the service Android lists under **Settings → Apps → Default apps → Digital
  assistant app**. It does nothing in the background: no hotword, no microphone.
* `CactusVoiceInteractionSessionService` / `CactusVoiceInteractionSession`: when the system's assistant gesture
  fires, Android opens a session. Ours draws the Compose assistant over the current app, starts listening
  (if voice activation is on and the microphone permission is already granted) and runs the same pipeline as the main
  screen. After a plain success it closes itself after two seconds; "Close" or tapping outside ends it.
* `CactusRecognitionService`: Android requires a recognition service next to a voice-interaction service. This
  one is a placeholder that answers "not supported". Cactus AI does not offer speech recognition to other apps.
* `MainActivity` also accepts `android.intent.action.ASSIST`, the older way Android starts an assistant, and starts
  listening on a fresh launch from it.
* The voice-interaction XML declares `supportsAssist=true` and `supportsLaunchVoiceAssistFromKeyguard=false`.

### Setting it up

1. Install and open Cactus AI once; download both models.
2. Settings tab → **Open assistant settings** (or Android Settings → Apps → Default apps → Digital assistant app)
   and choose Cactus AI. Android shows its own warning that the assistant can read the screen contents it is shown;
   read it. (Cactus AI does not use screenshots or "screen context"; it only listens to your voice.)
3. Invoke the assistant the way your phone does it. On many Pixels that is press-and-hold on the power button
   when Settings → System → Gestures → "Press and hold power button" is set to the digital assistant.

### What is *not* verified or not provided

* **Nothing here has been run on a Pixel 7.** `docs/DEVICE_TEST_CHECKLIST.md` section 6 lists what to check.
* Android and Google may reserve some assistant gestures, the "Hey Google" hotword, lock-screen access and the
  "Circle to Search"/screen-context features for Google's own assistant. Cactus AI claims none of them. If a gesture
  does not open Cactus AI on your phone, that is an Android limitation, and the app keeps working as a normal app.
* Microphone access from an assistant session relies on Android treating the visible session as a foreground
  voice interaction. That is how it is documented to work, but it is one of the things to confirm on the device.
* No wake word. Always-listening would need a separate always-on model and a foreground service; the brief says
  not to add one without approval.

## Accessibility service (screen control)

Class: `a11y/CactusAccessibilityService`. It is what makes "scroll down", "go back", "tap search",
"select the first result", "type hello" and "find <text> on the screen" work.

### Rules the code follows

* **Off by default; only you turn it on.** The app cannot enable it. The Settings tab explains it and opens
  Android's Accessibility screen. Turning it off there revokes it immediately.
* **Only when you ask.** The service has no background behaviour. It receives window-change events only to know
  that "the screen changed after my tap"; it does not read the event contents.
* **Looks only for what you named, keeps nothing.** It reads the on-screen element labels at the moment of a command
  to find the one you meant. Nothing is stored, logged or sent.
* **Nodes first, coordinates last.** It uses `AccessibilityNodeInfo` actions (click, scroll, set text, focus). It
  only falls back to a swipe or a tap at the element's own position when an element exposes no action.
* **Never password or payment fields.** It will not type into them, and the fields are skipped when searching.
  Fields are recognised by Android's password flag, by input types, and by hints such as "card number", "CVV".
* **Never Android's permission or installer screens.** It refuses to act inside them, so it cannot grant permissions
  or install apps on your behalf.
* **Risky taps ask first.** Labels such as pay, buy, order, send, delete, confirm, transfer, submit, install... show
  "Tap X? This may be hard to undo." with Do it / Don't.
* **Ambiguity is a question, not a guess.** If two things match "search", you pick.
* **It cannot bypass protection.** If an app marks a window secure or Android hides it from accessibility services,
  the service simply sees nothing and says so.

### Multi-step commands

"Open YouTube and search for woodworking" arrives from Needle as two calls. The planner notices that searching YouTube
already opens YouTube and runs only the search. In general, steps run one at a time (`StepRunner`) with an 8-second
limit each, a short pause between them, and the sequence stops at the first step that fails, times out, needs your
choice or is unavailable. Cancel stops the step in flight. Each step reports what it did ("Tapped “Search”, the
screen changed").

### Installing from an APK: "restricted settings"

On Android 13 and newer, an app installed from a file (not from a store) has its Accessibility switch greyed out until
you allow restricted settings: **Settings → Apps → Cactus AI → ⋮ (top right) → Allow restricted settings**, then go
back to Accessibility and try again. The Settings tab and the hint card both say this.

### Honest limitations

* "Select the first result" is a heuristic: the n-th clickable row below the toolbar. It will be wrong in some apps.
* Apps built with custom drawing (games, some web views) expose few or no elements, so tapping by name fails there.
* Behaviour of Android's accessibility on Pixel 7 for each app is untested here.

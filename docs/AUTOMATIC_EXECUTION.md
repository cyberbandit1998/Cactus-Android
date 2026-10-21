# Automatic execution of safe actions

After Needle has chosen what to do, Cactus AI decides one of three things: **run it now**, **show it and ask**, or **refuse**.
The rules live in `core/.../exec/ExecutionPolicy.kt` and `TargetSafety.kt` (plain Kotlin, unit-tested); the screens only show
the verdict.

## 1. The decision

```
Needle's answer
  -> validated against the allowlist and argument rules  (Planner; unchanged)
  -> any call the engine itself held back?               -> REFUSE, nothing runs
  -> negated request ("don't open Spotify")?             -> REFUSE
  -> nothing valid?                                      -> REFUSE
  -> any value that is not in what you said?             -> ASK
  -> no confidence number from the model?                -> ASK   (a number is never invented)
  -> confidence below your threshold?                    -> ASK
  -> "Automatically execute safe actions" off?           -> ASK
  -> EVERY step on the automatic list and passing its own check? -> RUN (after a short cancel window)
  -> otherwise                                           -> ASK
```

* **Confidence** is Needle's own calibrated number (`confidence` in the engine's JSON, 0 to 1). It is shown as a percentage
  when "Show the model's confidence" is on. If the model gives none, the screen says so and the command is always shown first.
  The threshold defaults to **70%**, is adjustable from **50% to 95%** (with 60, 70, 80, 90 buttons), is stored as a whole
  percent so 60 means exactly 0.60, and the comparison includes the threshold itself (70.0% runs at a 70% threshold).
* **One confidence per answer.** Needle reports a single number for a whole answer, not one per call. For a sequence
  ("open YouTube and search for cats") every step must pass its own checks; a confident first call is not permission for the
  rest. If one step needs asking, the whole sequence is shown first.
* **The cancel window.** A command that will run by itself first shows what it is about to do with a Cancel button for
  1.2 s (0 to 5 s in Settings, 0 runs at once). Instant, local answers (time, date, battery, phone facts, arithmetic) skip it.
* **Recorded.** The History tab and the Settings "Recent commands" list show, for each command, whether it ran automatically
  or after you confirmed, the model's confidence, and whether the answer came from the web.

## 2. What may run automatically

Only tools named on an explicit list; a tool added in future is shown first until someone adds it on purpose.

| Runs by itself (when confident and valid) | Notes |
| --- | --- |
| Open an app | |
| Web search | |
| Open a settings page | **except** the Accessibility page (security) |
| Timer, alarm | opens the Clock app's own confirmation |
| Battery, time, date, phone facts, calculator | answered on the phone |
| Sports, weather, general question | opens a Google search in your browser; shown as **Online** |
| Search the phone (apps, contacts, files) | |
| Change volume | up, down, mute, unmute, and "set" up to 70%; a higher level is shown first |
| Scroll, back, home, recents, notifications | needs the Accessibility service |
| Find text on screen, tap a numbered result, type text | needs the Accessibility service; see section 4 |
| Tap by name | only if the name you said is not a risky word; checked again on the real control |

## 3. What is always confirmed

No setting changes this. The Settings screen says "Confirm sensitive actions: always on" and has no switch for it.

* Calling a contact or a number, and writing a text (the phone's dialer or message screen opens, filled in; nothing is dialled or
  sent by Cactus AI even after you confirm).
* Anything that deletes, buys, pays, orders, subscribes, sends, submits, transfers, installs, uninstalls, resets, erases,
  grants or revokes permissions, or changes security settings. The Accessibility page of Android settings is in this group.
* A tap on a control whose label (or id, for icon-only buttons) contains those words, or on an "OK / Yes / Continue" button
  sitting next to a question that does.
* Any tap inside a payment or banking app, and any tap in Android's permission or installer screens (refused outright).
* A value in the command that is not in what you said, a model with no confidence, a confidence under your threshold.

## 4. Taps on the screen are checked on the control, not just on the words

"Tap the blue button" is not safe because the words are. When the Accessibility service finds the control it checks the label,
the view id, the text around it (the dialog's message, its neighbours), the app it is in and whether it is a password or payment
field, using the same rules as above. A risky control is shown to you first **even if the command was already running by itself**
(the screen then says "ran after you confirmed"). Password and payment fields are never tapped, typed into or read. If the
screen hides itself from accessibility (secure windows), the service simply cannot see it.

Limits: it works from words, so an icon-only button with no id or label, or a label in another language, can be missed. Your
confirmation prompt is the real protection; the word list only decides when to show it.

## 5. Tests

`ExecutionPolicyTest` (21), `TargetSafetyTest` (10) and `ActionTextAndHistoryTest` (6) run on the CI runners and cover:
confidence at, above and below the threshold; the threshold limits and 60%; missing confidence always asking and never
invented; suppressed (held-back) and invalid calls never running; sensitive actions asking at any confidence; automatic
execution off; every allowlisted tool running by itself; volume limits; the security settings page; risky tap requests;
a confident first call not authorising the rest of a sequence; values not in the request; negation; every tool being
classified on purpose; payment/delete/submit controls, generic "OK" buttons, password fields, permission screens and
payment apps.

**Not tested** without the phone: whether Needle's engine returns a `confidence` value on the Pixel 7 (the upstream
documentation says the base model carries the calibrated head and that fine-tuned weights may not; this build's behaviour is
unknown until you run it), and Chrome's/other apps' accessibility trees for the target checks.

# Automatic end-of-speech detection

Tap the microphone once, speak, and Cactus AI notices that you have finished. Whistle then transcribes the recording and the
text goes to Needle with no second tap. This page says how it works, what it cannot do, and exactly what has and has not been
tested.

**Status:** the detector and the capture loop are unit-tested on **synthetic audio** (generated tones, noise and a
voice-like signal). **It has not been tried on a real voice, a real room or a Pixel 7.** Every threshold below is a starting
point chosen on synthetic signals. Section 5 is how to tune it on the phone.

## 1. What happens, in order

1. Tap the microphone (or hold it, as before). The screen says **Microphone: Listening**.
2. The first 200 ms of audio sets the background level (the room's hum). Nothing is decided yet.
3. When a voice starts, the screen says **Speech detected**. From this moment, and not before, the silence countdown can run.
4. When you go quiet for the **silence timeout** (default 1.5 s, 0.5 to 4 s in Settings), recording ends.
5. **Processing**: the microphone is released first, then Whistle transcribes, then Needle interprets.
6. The action runs, or asks you first (see [AUTOMATIC_EXECUTION.md](AUTOMATIC_EXECUTION.md)).

Other ways it ends:

| Reason | When |
| --- | --- |
| You tap the microphone again | Always works, with automatic stopping on or off. |
| **Cancel and discard** | Throws the audio away. |
| No speech | 8 s after the start with nothing that sounds like a voice: "I did not hear anything." Nothing is sent to Whistle. |
| Time limit | 30 s by default (3 to 30 s in Settings; Whistle takes 30 s in one pass). Always enforced, even with automatic stopping off. |
| The app leaves the screen | Android silences background microphones, so recording stops and says why. |

With "Stop listening by itself" **off**, only your tap, cancel and the time limit end a recording, as in earlier versions.

## 2. The detector

It works on 20 ms frames of the 16 kHz audio, on the phone, with no model and no network. Audio is never sent anywhere.

A plain "louder than X" rule fails: it fires on a door slam, never fires in a loud room and cuts off a quiet speaker. So the
decision combines several cheap cues:

1. **Adaptive background level.** The quietest level in the first 200 ms (capped at -48 dB so a command that begins at once is
   not mistaken for background) is then tracked: it falls fast, rises slowly, and rises faster when the sound is perfectly
   steady for 1.5 s (a fan, a hum).
2. **Speech must stand out**: above the background by a margin and above an absolute minimum. Both depend on the sensitivity
   setting (Low: -38 dB and 12 dB; Normal: -45 dB and 9 dB; High: -52 dB and 7 dB).
3. **Speech must last and must vary.** At least 5 of the last 10 frames must be loud *and* the level must change from frame to
   frame by at least 1 dB (the median), the way syllables do. A click, a knock, or steady noise at any volume does not start the
   countdown.
4. **Two thresholds.** Once speaking, the level must fall clearly below the speech level (and below the start threshold) to count
   as quiet. A trailing syllable, the room's echo or a natural dip between words does not end the command.
5. **Short noises are forgotten.** A sound that ends before about 240 ms of voice is counted as a "short noise ignored" and the
   countdown never started.

Pauses: a pause **shorter than the silence timeout** never ends the recording. If you pause longer than that while thinking
(for example "set a timer for... ... fifteen minutes"), raise the timeout in Settings.

The recording screen shows the elapsed time, a level bar, "Speech detected" in words (not colour alone), and what ends the
recording. The details panel shows how the last recording ended, when speech started, the background level, and how many short
noises were ignored.

## 3. Limits, stated plainly

* **A television, radio or people talking nearby sound like you.** The detector cannot tell voices apart, so in that case the
  recording ends at the time limit or when you tap. Tap the microphone to send.
* **A very quiet voice** below the sensitivity's minimum is not noticed. Raise the sensitivity ("High").
* **A very noisy room** can keep the level above the stop threshold. Lower the sensitivity ("Low"), speak closer to the phone, or
  tap to stop.
* **A silence timeout under about a second can cut off a slow talker.**
* A recording that ends because of silence includes the quiet that waited out the timeout; Whistle's own handling and the
  "trim silence" setting remove most of it.
* Bluetooth headsets and the phone's noise suppression change levels in ways not tested here.

If a simple energy detector turns out to be unreliable for you, turn "Stop listening by itself" off; everything else works as
before. A neural voice-activity model would be better in noise but would be a second model to ship and verify; it was not added
because the task said not to add dependencies, and it is not needed for the basic flow.

## 4. What is tested

Run by `./gradlew :core:test` (and on the CI runners), on generated audio (33 tests in `SpeechEndpointerTest` and
`VoiceCaptureTest`):

* the recording ends after the default silence once speech has stopped, and at other configured times; the timeout is kept
  between 0.5 and 4 s;
* the silence countdown does not start before speech, and a brief natural pause does not end the command;
* a knock and a short noise do not start speech (and a short noise is forgotten while real speech afterwards still works);
  a steady hum is not mistaken for speech, speech is found and ended inside continuous hum, a noisy room is learned, and a
  command spoken immediately after the tap still works;
* a very quiet voice needs the High sensitivity level (the test checks that Normal misses it and High catches it);
* no-speech timeout (about 8 s, and it does not fire once speech has started), maximum duration (also with automatic stopping
  off), manual stop (with it on and off), cancel (audio discarded and never transcribed), a stop tap over silence transcribes
  nothing;
* the transcription is triggered automatically, exactly once, with the microphone already released, and never for a cancelled
  recording or one with no speech; a gate before transcription (for example a hot phone) can refuse; a broken microphone is
  reported rather than swallowed;
* the result does not depend on the size of the audio chunks.

**Not tested anywhere yet:** a real voice, real room noise, a Pixel 7 microphone. See section 5 and
[DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md) sections 10 and 11.

## 5. Tuning on the phone

1. In a quiet room: tap, say "open Bluetooth settings", stop talking. It should stop about the silence timeout after your last
   word. Details show "Stopped after you went quiet".
2. In a moderately noisy room (a fan, kitchen noise): repeat. If it never stops, set sensitivity to Low or raise it in small steps.
3. With a quiet voice at arm's length: if it says "I did not hear anything", set sensitivity to High.
4. Say a command with a pause in the middle. If it is cut off, raise the silence timeout by half a second.
5. Write the results in the checklist. They are the only real numbers this feature has.

# Privacy and security

## What leaves the phone

| What | When | Where to |
| --- | --- | --- |
| Model files (download only; nothing is uploaded) | When you tap Download, Resume or Delete and reinstall | `huggingface.co` and its file servers (`*.hf.co`); the app refuses any other host, and plain http |
| Model file listing and checksums (a read-only request) | When you download or tap "Check for update" | `huggingface.co/api/models/Cactus-Compute/...` |
| A web search | Only when you ask for one ("search Google for ...") | Your browser or the YouTube/Reddit/Maps app, with the search words in the address |
| Nothing else | | |

* No account. No analytics SDK. No advertising SDK. No crash reporter. No cloud AI. No remote logging.
* Your voice is never saved, not even temporarily on disk: audio exists only in memory while it is recorded and
  transcribed.
* Recognised text, typed commands and results go into the local history (Settings → Delete history, or the History
  tab → Clear history). The history file is in the app's private storage and is excluded from backup.
* Contacts are read only when a command needs a person (and only after you allow it), only on the phone, and only
  the matching names/numbers are used. App names may be handed to the speech model as spelling hints (switchable).
* Backups: `allowBackup=false` and data-extraction rules exclude everything, so models, history and settings are
  not copied to Google backup or to a new phone.

## The vendor engine's telemetry

The Needle project's README says: "By default, telemetry is turned on in the binary. To turn it off, set environment
variables `NEEDLE_TELEMETRY=0` and `DO_NOT_TRACK=1`." Its Python package sends "anonymous usage counts" to a
hosted endpoint unless those are set.

* This app sets both variables inside the native shim's `JNI_OnLoad`, before the engine can be called.
  A JVM test proves the variables are set in the shim's process.
* CI inspects the closed `libneedle.a` for networking functions and embedded URLs (see `VERIFIED_CONTRACT.md` for what
  it found). That is evidence, not a guarantee: the engine is a closed binary and was not audited line by line.
* **You can check it yourself:** after the models are installed, Android's Settings → Network & internet → Data usage
  → App data usage → Cactus AI should stay at zero while you use voice and offline commands.
  For a hard guarantee: stock Android lets you switch off an app's network access (App info → Mobile data & Wi-Fi,
  or Airplane mode, or a firewall app), which blocks every connection from the app, including any the
  vendor engine might try. Do that after the models are installed; downloads and web searches then stop working
  until you switch it back on.

## Permissions

| Permission | Why | When it is asked |
| --- | --- | --- |
| `RECORD_AUDIO` | Voice commands | The first time you tap the microphone |
| `READ_CONTACTS` | Call/text/find a person by name | The first time a command needs a contact |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Model download; web searches; knowing you are offline | Install time (normal permissions) |
| `SET_ALARM` | Ask the Clock app to create alarms/timers | Install time (normal permission) |
| Accessibility service | Optional screen control | Never by the app; you switch it on in Android settings |

Deliberately **not** requested: `CALL_PHONE`, `SEND_SMS`, `READ_SMS`, `QUERY_ALL_PACKAGES`, storage permissions,
location, camera, foreground-service permissions, `RECEIVE_BOOT_COMPLETED`, notifications. A device test checks the
installed APK requests nothing else.

## What the model cannot do

The model's output is parsed strictly and only its choice of a registered tool and typed arguments is used
(`docs/ARCHITECTURE.md`). It cannot run code or shell commands, open arbitrary URLs or intents, install apps,
read other apps' data, place a call, send a message, or turn on screen control. Those paths do not exist in the app.

## Reporting a problem

This is a personal project; open an issue on the repository.

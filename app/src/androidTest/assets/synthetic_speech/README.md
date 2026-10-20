# Synthetic speech fixtures

These WAV files are **synthetic**: they were produced with the eSpeak NG text-to-speech engine
(`espeak-ng -v en-us -s 145`) and converted to 16 kHz mono 16-bit PCM with `sox`. They are **not recordings of
a person**, so how well Whistle transcribes them says little about how it handles real voices; they only let the
instrumented test run actual audio through the actual model without needing a microphone.

| File | Text that was synthesised |
| --- | --- |
| `open_bluetooth_settings.wav` | open bluetooth settings |
| `set_timer_15_minutes.wav` | set a timer for fifteen minutes |
| `search_google_printing.wav` | search google for three d printing ideas |

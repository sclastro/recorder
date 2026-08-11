# Recorder — project conventions

An Android voice recorder built for a Samsung Galaxy S23. See `PLAN.md` for the
full design and roadmap.

## UI language — English only

**Every user-facing string in the app is English, and stays English.** This is a
standing decision by the owner, not a default to revisit. It covers:

- Composable text, labels, placeholders, `contentDescription`
- Notification titles, texts and action labels
- Error and status messages surfaced from `RecorderEngine`, `TrimEngine`, etc.
- Enum display labels (`Preset`, `MicSource`, `AudioContainer`, `Channels`,
  `SortOrder`, `ThemeMode`)
- Generated names and prefixes (`Unsorted`, `Recording`, `Recovered_`)
- Date and time formats — use `Locale.ENGLISH`, never `Locale.getDefault()`

Strings currently live inline in Kotlin rather than `strings.xml`. If the app
ever gains a second language, move them to `strings.xml` first; until then keep
new strings inline and in English for consistency.

Conversation with the owner is in Cantonese; that has no bearing on the UI.

## Visual language

Light theme is the primary look — a warm off-white ground, low-chroma accents.
Both themes are defined in `ui/theme/`; add colours to `Palette` and expose them
through `RecorderAccents` rather than hard-coding values in screens.

- Ground `#FAF9F5`, cards `#FFFFFF`, sunk `#F2F0E9`, hairline `#E9E6DC`
- Record accent — soft red `#CB5A52` (gradient to `#E58C84`, deep `#8E3A34`)
- Playback accent — sage `#6D8278`; bookmarks `#9E8154`
- Clipping/destructive — crimson `#8C1D18`, deliberately much deeper than the
  record accent so a peak warning reads as an escalation, not more of the same
- The owner asked for red after an earlier low-chroma terracotta ("Warm Stone",
  `#A9705B`) read as brown. Keep the accent recognisably red; keep everything
  else low-chroma so nothing out-shouts the waveform.

The record button is deliberately shallow: a half-strength vertical gradient, a
hairline lit top edge, a 5dp shadow. No radial "sphere" body, no specular oval,
no heavy drop shadow — that treatment was tried and rejected. The
circle-to-rounded-square morph is meaning, not decoration; keep it.

## Architecture

Single `:app` module, Compose + Material 3, manual DI via `AppContainer` (no
Hilt). Capture is `AudioRecord` → `AudioSink` on a dedicated audio-priority
thread inside a microphone foreground service — never `MediaRecorder`, which
cannot select bit depth. Room indexes a real directory tree and is reconciled
against disk on launch.

## Checks before pushing

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Lint is configured to fail on errors, and CI runs the same three tasks plus
`assembleRelease`. Audio maths (WAV headers, trim boundaries, peak bucketing)
has JVM unit tests — extend them rather than relying on manual device testing,
since no emulator is available in this environment.

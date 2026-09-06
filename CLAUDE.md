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
new strings inline and in English for consistency. (This was reconsidered and
kept: extracting several hundred strings is a large, regression-prone refactor
that buys nothing while the app is English-only.)

Conversation with the owner is in Cantonese; that has no bearing on the UI.

## Visual language

Light theme is the primary look — a warm off-white ground, low-chroma accents.
Both themes are defined in `ui/theme/`; add colours to `Palette` and expose them
through `RecorderAccents` rather than hard-coding values in screens.

- Ground `#FAF9F5`, cards `#FFFFFF`, sunk `#F2F0E9`, hairline `#E9E6DC`
- Record accent — red `#D8453E` (gradient to `#F08079`, deep `#9C2B26`)
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

### Standing decisions about audio

- **Lossless by default.** Trimming and splitting copy bytes (WAV) or packets
  (compressed). The only operation that re-encodes is "export smaller", and it
  always writes a separate file.
- **Fades and normalising are WAV-only** (`EditEngine`). On a compressed file
  they would mean decode → process → re-encode, losing quality every time. The
  editor says so rather than degrading an M4A quietly. `GainPass` works at the
  file's own bit depth so a 24-bit recording is never narrowed just to be faded.
- **Recordings live in app storage and stay there.** Capture needs a real file
  descriptor on the audio thread, trimming seeks inside the file, and peaks are
  sidecars beside it. `FolderMirror` copies finished recordings into a
  user-chosen SAF tree instead — a mirror, not a move, and off by default
  because it doubles disk use.
- **Playback settings ExoPlayer owns but `Player` does not expose**
  (skip-silence, gain above unity) travel to `PlaybackService` as custom
  session commands; see `PlaybackCommands`. They belong to the session, not to
  a screen, so the service publishes them back through `setSessionExtras` and
  every controller reads them on connect.
- **WAV always splits near 4 GB**, whatever auto-split is set to. RIFF sizes
  are unsigned 32-bit, so a longer file describes itself wrongly — this app's
  reader falls back to the on-disk length and would never notice, but nothing
  else would open it. Reachable in about 2 hours at 96 kHz/24-bit/stereo. See
  `WavSink.MAX_DATA_BYTES`.
- **The capture loop must not touch the filesystem, or grow anything large.**
  It runs fifty times a second at `THREAD_PRIORITY_URGENT_AUDIO`.
  `Policy.shouldSplit` takes the file size as a lambda for exactly this reason,
  and `Peaks.Recorder` keeps its buckets in a hand-grown `ByteArray` rather
  than a collection — a millisecond spent reallocating there is a dropped
  buffer. Keep both that way.
- **`WavFile` is the only RIFF parser.** `TrimEngine`, `EditEngine`,
  `ExportEngine` and `MediaProbe` all go through it; do not write a second one.
- **Backups carry the index and the settings, never the audio.** Recordings run
  to gigabytes and cannot travel. Settings live in DataStore, which is the
  `file` domain — if a new preference store is ever added, list it in both
  `backup_rules.xml` and `data_extraction_rules.xml` or it silently will not
  survive a device transfer.

## Checks before pushing

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Lint is configured to fail on errors, and CI runs the same three tasks plus
`assembleRelease`. Audio maths (WAV headers, trim boundaries, peak bucketing)
has JVM unit tests — extend them rather than relying on manual device testing,
since no emulator is available in this environment.

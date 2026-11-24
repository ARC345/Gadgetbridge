# Pebble Qore Vox support: phase 2 (voice notes)

Date: 2026-11-17. Branch: `pebble-qore-vox`. Builds on the phase 1 spec
(`2026-11-16-pebble-qore-vox-design.md`).

## Goal

Copy the voice notes that the band records (side button, double-press) from the band into
Gadgetbridge, as audio files that a normal player can play. No transcription.

**Success criteria:**

1. A data fetch also downloads each new voice note from the band.
2. Each note becomes one `.ogg` file (Ogg Opus) in a folder that the user can open.
3. A file that is already downloaded is not downloaded again.
4. A broken transfer (CRC error, lost link) leaves no partial file.
5. Nothing is deleted from the band in this phase.

## Facts from the Pebble Vox app

Source: decompiled `com.pebblevox.ring` 1.0.1. Classes: `q0/j0.java` (commands),
`com/crrepa/ring/j/p0.java` method `d3` (replies), `com/crrepa/ring/u/g.java` (download),
`com/crrepa/ring/u/b.java` (`fdd6` owner), `f1/a.java` (CRC), `t0/c.java` (parsers),
`com/moyoung/ring/health/record/util/AudioFileTransformer.java` and the JieLi
`OpusManager` (decode).

### Commands (group 10)

| Cmd | Request payload | Reply payload |
|---|---|---|
| 10,0 | none: query recording state | state info |
| 10,1 | `{1}` start, `{2}` pause, `{0}` stop | — |
| 10,3 | none: list files | `[count u16, remaining u16, name × count]` (more frames while remaining > 0) |
| 10,4 | `{name}` delete; 15 × `0xFF` deletes all | result |
| 10,5 | `{1, name}` query size | `[1, size u32, name]` |
| 10,5 | `{2, offset u32, length u32, name}` request data | `[2, dataSize u32, name]`, then the data on `fdd6` |
| 10,5 | `{3, crc u16 BE, name}` send CRC | `[3, ok, name]` (ok is a signed byte: > 0 is OK, 0xFF is a failure) |
| 10,5 | `{4, ok (1 or 0xFF), name}` end transfer | `[3, …]` or `[0, …]` state |
| 10,11 | none: storage check | `[1]` the band has storage, `[0]` stream-only |
| 10,5 | `{5, name}` file mapping (stream-only) | `[5, count u16, remaining u16, (startFrame u32, endFrame u32, name) × count]`; `FF FF FF FF` = not found |

- A name is always 15 bytes, UTF-8, padded with zeros. All numbers are little-endian, except
  the CRC in `10,5 {3}`, which is big-endian.
- The app requests chunks of at most 4096 bytes. It waits until `dataSize` bytes arrived on
  `fdd6` before it requests the next chunk.
- `fdd6` carries raw file bytes with no header. Only one transfer can use it at a time.
- CRC: the Nordic CRC-16 (`crc16_compute`) algorithm, but with the start value `0xFDDA`
  (64986, set in `g.java`), over all file bytes:
  ```
  crc = (crc ushr 8 or (crc shl 8)) and 0xFFFF; crc = crc xor byte
  crc = crc xor ((crc and 0xFF) ushr 4); crc = crc xor (crc shl 12) and 0xFFFF
  crc = crc xor ((crc and 0xFF) shl 5) and 0xFFFF
  ```
- The app stops a transfer after 10 s with no data.
- If `10,11` says "no storage", the band is "stream-only". The Qore Vox (firmware
  MOY-WWB2-1.0.1) reports this. It still stores the recording: a listed name is virtual
  (`<start timestamp>.<ext>`), and the mapping query (`10,5 {5, name}`) returns the stored
  segments. The vendor app downloads each segment with the normal transfer and joins them in
  frame order (`w0/c.java`: `<ts>_merged.opus`). It also joins live-stream parts (`10,8`)
  that it saved while connected; Gadgetbridge does not turn the stream on, so it has none.
  On "not found" the vendor app tries again up to 3 times, 2.5 s apart.

### Audio format

The files are `.opus`, but they are not Ogg files. They are raw Opus packets of 40 bytes each,
with no header: 16 kHz, mono, 20 ms per packet (16 kbit/s). This is the default
`OpusOption` of the JieLi decoder (`hasHead=false, channel=1, sampleRate=16000,
packetSize=40`). The app decodes them to PCM with a native library and then makes MP3 with
LAME.

## Design

### Approach

Do not decode. Wrap each 40-byte packet in an Ogg Opus container (RFC 7845) in pure Kotlin.
Android and desktop players play Ogg Opus. This needs no native library and keeps the original
audio.

### Components (pure Kotlin, unit-tested)

- `CrpRingRecordingCommands` — the group 10 request frames above.
- `CrpRingRecordingParsers` — file list pages, `10,5` replies, storage check.
- `CrpRingCrc16` — the Nordic CRC-16.
- `OggOpusWriter` — writes `OpusHead`, `OpusTags` and audio pages (one granule = 48 kHz
  samples; 960 per 20 ms packet), with the Ogg page CRC-32 (polynomial `0x04C11DB7`).
- `CrpRingRecordingDownloader` — state machine: storage check (`10,11`) → recording state
  (`10,0`) → list → for each new name: size → chunks → CRC. Input: replies and `fdd6` bytes.
  Output: requests and finished files (bytes). Timeouts are given from outside, as in the
  history fetcher. On a stream-only band each name goes through the mapping query, and the
  segments are joined in frame order. The download is skipped, with a logged reason, if a
  recording is in progress (the file is still growing) or if the checks get no reply. A note
  that is "not found" or has a failed segment is not marked, so the next sync tries again.

### Android side

- `CrpRingDeviceSupport` routes `fdd6` notifications and group 10 replies to the downloader.
  It enables notifications on `fdd6`.
- The downloader runs after the history fetch, in the same busy task.
- Saving runs on a background thread. The Ogg file is written page by page straight to the
  output stream.
- Android 10 and later: `Music/Gadgetbridge/Voice notes/<name>.ogg` through MediaStore (no
  permission needed). Android 6 to 9: the app's external files folder, `voice-notes/`. The
  raw band file is always kept in the app's external files folder, `voice-notes-raw/`.
- Band file names are cleaned before use (only `A-Z a-z 0-9 . _ -`).
- A list of downloaded names is kept in the device preferences, so a name is not fetched
  again. A note is marked only after it is saved.
- A timeout drops the file and sends `{4, 0xFF, name}`. A failed CRC drops the file and sends
  nothing more, as in the vendor app. Files larger than 16 MB are skipped.

### Not in phase 2

Delete on the band, start or stop a recording from the phone, live-stream parts (`10,8`), MP3 export,
transcription, and a voice note screen in the app.

### Open points to check on the band

- The exact file name format (the app parses a timestamp from it).
- Whether `fdd6` data needs a larger MTU to keep up (the app asks for 512).
- Whether the band needs the PIN before it lists files.

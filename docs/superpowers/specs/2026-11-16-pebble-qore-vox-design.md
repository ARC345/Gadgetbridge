# Pebble Qore Vox support: phase 1 (health data)

Date: 2026-11-16. Branch: `pebble-qore-vox` (from `master`).

## Goal

Connect the Pebble Qore Vox band to Gadgetbridge directly over BLE. Show its health
data in the normal Gadgetbridge charts. Voice recordings are not in this phase.

**Success criteria:**

1. Gadgetbridge finds `PBL Qore Vox` in the discovery list and connects to it.
2. The device card shows the battery level and the firmware version.
3. A data fetch stores steps, sleep stages, heart rate and SpO2 for the last 7 days.
4. The charts show these data.
5. "Find device" and a manual heart rate measurement work.
6. The Pebble Vox app can still use the band when the user enters its PIN in Gadgetbridge.

## Facts from the Pebble Vox app

Source: `com.pebblevox.ring` 1.0.1, decompiled with jadx. The app uses the MoYoung
`com.crrepa.ring` SDK ("CRP ring"). This is not the Yawell SDK of the Qore 2, and it is not
the MoYoung watch protocol that Gadgetbridge supports.

### GATT

| UUID (16-bit, base UUID) | Use |
|---|---|
| service `fdda` | CRP ring service |
| `fdd1` | Current steps (read, notify) |
| `fdd2` | Commands from the phone (write) |
| `fdd3` | Replies and events from the band (notify) |
| `fdd5`, `fdd6` | Not used in phase 1 (`fdd6` is notify) |
| `180f` / `2a19` | Battery level |
| `180a` / `2a28`, `2a29` | Firmware revision, manufacturer |
| `180d` / `2a37` | Heart rate (not used in phase 1) |

The app enables notifications on `fdd3`, `2a19`, `fdd1`, `2a37` and `fdd6`. It asks for
MTU 512.

### Frame

```
FD DA <flags> <len> <group> <cmd> <payload...>
```

- `flags`: `0x10` from the phone. The band can set bit 0 as bit 8 of the length.
  `flags & 0xFE` is `0x10` or `0x20`.
- `len`: the full frame length, header included: `((flags & 1) << 8) | len`, 6 to 511.
- A frame can arrive in more than one notification. Join the parts until the length is
  complete. A new header before the end drops the old part.
- The phone splits a long frame into MTU-sized writes on `fdd2`.

All multi-byte numbers are little-endian. A "device timestamp" is the local wall-clock
time written as if the time zone is GMT+8 (seconds). To get real UTC seconds:
`utc = device - (localOffset - 8 h)`, with the local offset at that time (DST included).

### Commands in phase 1

Group 1 sets values. Group 2 queries data. "day" is 0 for today, 1 for yesterday, up to 14.

| Group, cmd | Request payload | Reply payload |
|---|---|---|
| 1, 0 user info | height cm, weight kg, age, gender (0 male, 1 female), step length cm | none |
| 1, 1 time | device timestamp u32, `8` | none |
| 1, 2 daily goals | steps u32, kcal u16, training min u16, distance u32 | none |
| 1, 9 measure HR | 1 start, 0 stop | `[bpm]` (event, same group/cmd) |
| 1, 11 measure SpO2 | 1 start, 0 stop | `[percent]` (event) |
| 1, 41 auth | `{0}` query; `{1, n, PIN utf8}` send; `{-1}` delete | `[sub, result]`. Query: result > 0 means a PIN is set. Send/delete: result 0 means OK |
| 2, 9 manual HR history | none | `[x, (bpm u8, ts u32)*]` |
| 2, 11 manual SpO2 history | none | `[x, (pct u8, ts u32)*]` |
| 2, 13 steps of a day | `{day}` | `[day, steps u24@1, kcal u24@5, time u24@9, distance u24@13]` |
| 2, 14 sleep of a day | `{day}` | `[day, (stage, hour, minute)*]` |
| 2, 15 timed HR of a day | `{day, page}` | `[day, page, bpm u8 * n]` |
| 2, 17 timed SpO2 of a day | `{day, page}` | `[day, page, pct u8 * n]` |
| 2, 18 steps per 30 min | `{day}` | `[day, steps u16 * 48]` |
| 3, 3 firmware | none | UTF-8 version text |
| 3, 6 battery | none | `[percent, millivolt u16]` |
| 5, 2 device info | none | `[n, name, m, version]` |
| 9, 2 find device | 1 start, 0 stop | none |

Notes:

- The u24 values are 3-byte fields inside 4-byte slots.
- Timed HR and SpO2 have one value each 5 minutes from local midnight of that day. There
  are two pages: 0 and 1. Request page 0, then page 1 after the page 0 reply. A value of 0,
  a heart rate outside 40–200, or SpO2 over 100 means "no value".
- Sleep: stage 0 awake, 1 light, 2 deep, 3 REM. Each entry is the start of a stage. The
  stage ends at the next entry. An hour lower than the previous hour is on the next date. A
  stage with a negative length is dropped. The first entry is on the previous date if its hour is 12 or more.
- Group 2 replies to "today" can hold zeros for the time that is still in the future.

### PIN

The app makes a random 8-digit number once (`bind_user_id` in its private settings) and sends
it as the PIN at each connection. If the band has a different PIN, the reply is not 0. The app
then asks the user to erase the band. The PIN is plain text in the BLE traffic, so an HCI snoop
log of the app shows it.

## Design

### Approach

A new protocol family "CRP ring", separate from the MoYoung watch code. It copies only the
structure of other drivers. Other bands with the same SDK can use it later.

### Components

`devices/crpring/`:

- `CrpRingConstants` — UUIDs, groups, commands, sleep stages.
- `AbstractCrpRingCoordinator` — capabilities, sample providers, DAO list for delete.
- `PebbleQoreVoxCoordinator` — name pattern `PBL Qore Vox.*`, fitness band.
- `CrpRingActivitySampleProvider` — read-only. It merges steps, heart rate and sleep
  stages into activity samples (the GloryFit model).

`service/devices/crpring/`:

- `CrpRingFrame` — builds frames and joins notification parts. Pure Kotlin, no Android.
- `CrpRingTime` — device timestamp conversion. Pure Kotlin.
- `CrpRingParsers` — parse functions for each reply. Pure Kotlin, returns plain data.
- `CrpRingDeviceSupport` — connection, init, PIN, events, find device, measurement.
- `CrpRingHistoryFetcher` — the fetch state machine and the database writes.

### Database

- New entity `CrpRingStepsSample` (time sample: timestamp ms, device, user, steps). One
  row each 30 minutes. Schema version goes up by 1.
- Heart rate: `GenericHeartRateSample` (timed and manual values).
- SpO2: `GenericSpo2Sample`.
- Sleep: `GenericSleepStageSample` (timestamp, duration in minutes, stage).

### Connection and init

1. Enable notifications on `fdd3`, `fdd1` and `2a19`. Ask for MTU 512. Read `2a19`.
2. Read the firmware version (`3,3`).
3. PIN: send `{1, n, PIN}`. The PIN comes from the device preference "Auth key". If the
   preference is empty, Gadgetbridge makes an 8-digit PIN and saves it.
   If the reply is not 0, log it and show a toast. Phase 1 does not erase the band.
4. After the PIN reply is OK, send time (`1,1`) and user info (`1,0`) from the Gadgetbridge
   user profile. If no PIN reply comes in 5 s, send them anyway (the vendor app does the same).
   If the PIN is rejected, do not send them.
5. Set the device to "initialized". A `3,6` battery reply is also accepted if it comes.

A sleep entry with no next entry has no end time. Do not save it. The next fetch gets it.

### Fetch order

For each day from 6 (oldest) to 0 (today):

1. Steps per 30 min (`2,18`).
2. Sleep (`2,14`).
3. Timed HR page 0, then page 1 (`2,15`).
4. Timed SpO2 page 0, then page 1 (`2,17`).

Then manual HR (`2,9`) and manual SpO2 (`2,11`) history. Each step waits for its reply
with a 5 s timeout. A timeout skips that step and logs it. After 3 timeouts in a row, the
fetch stops. If the connection drops, the fetch stops at once. After the last step, save all
data in one transaction and send the "sync finished" signal.

Do not save "today" values that are in the future. Do not save zero values.
Saving the same day again replaces the old rows (same device and timestamp).

### Events

- `1,9` and `1,11` from the band: show the value for a manual measurement and save it.
- `fdd1` notify: live steps for the realtime view (logged only in phase 1).
- `2a19` notify: battery update.

### Errors

- An unknown group/command: log it at debug level and ignore it.
- A frame that is not complete in 2 s: drop it.
- A parse error: log the raw bytes and skip that reply.

### Tests

Unit tests (JVM, no Android) for:

- frame build and join (one part, many parts, 9-bit length, bad header);
- time conversion (two time zones, DST);
- each parser with byte arrays taken from the SDK formats;
- sleep entries that cross midnight.

Manual test on the real band, with an HCI snoop log of the Pebble Vox app to compare.

### Not in phase 1

Voice recording download, notifications, HRV, stress, temperature, workouts, settings for
the timed measurement interval, firmware update, band erase.

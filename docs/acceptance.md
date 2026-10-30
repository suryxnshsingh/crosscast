# Acceptance tests

Written before each phase is implemented. A step marked *(Mac)* runs on the Mac. *(TV)* means it is checked on the device over adb. *(you)* needs a person: the iPhone, eyes on the screen, ears on the speakers.

All commands assume the TV is connected (see README → Connect to the TV) and `TV=192.168.29.48:<port>`.

## Phase 1: project skeleton

1. *(Mac)* `./gradlew assembleDebug` succeeds, and so does `./gradlew test`.
2. *(Mac)* `./gradlew :app:verifyRoborazziDebug` renders the placeholder screen at 1920×1080 and it matches the committed golden image. Open `app/src/test/screenshots/*.png` to look at it.
3. *(TV)* `adb -s $TV install -r app/build/outputs/apk/debug/app-debug.apk` prints `Success`.
4. *(TV)* `adb -s $TV shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep airspeaker` lists `dev.suryansh.airspeaker/.MainActivity`, which means it appears in the TV's app list.
5. *(TV)* `adb -s $TV shell am start -n dev.suryansh.airspeaker/.MainActivity`, then `adb -s $TV shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity"` shows `MainActivity` (once the screen is awake).
6. *(TV)* `adb -s $TV logcat -d -s ui` shows `MainActivity created`.
7. *(you)* Open **AirSpeaker** from the Google TV app list. A full-screen dark placeholder ("Room Speaker", "Waiting for AirPlay") is shown, with no system bars or white flash.

## Phase 2: always-on receiver service

Foreground-service strategy, explained in `docs/notes.md` → Foreground service type:
- The idle listener runs as a `connectedDevice` service.
- `mediaPlayback` is added while a session is streaming.

1. *(Mac)* `./gradlew assembleDebug test` succeeds.
2. *(TV)* With the app **not** opened, `adb -s $TV install -r app-debug.apk` triggers `MY_PACKAGE_REPLACED`. Within about 5 s, `adb -s $TV shell dumpsys activity services dev.suryansh.airspeaker` shows `AirPlayReceiverService` with `isForeground=true` and type `connectedDevice`.
3. *(TV)* `adb -s $TV shell dumpsys wifi | grep -i -A2 "multicast"` lists a multicast lock held by `AirSpeaker`.
4. *(TV)* `adb -s $TV logcat -d -s mdns` shows `network available` once the service starts.
5. *(TV)* Kill the process with `adb -s $TV shell run-as dev.suryansh.airspeaker kill -9 <pid>`. Within about 30 s the service is running again under a new pid (START_STICKY).
6. *(TV)* `adb -s $TV shell cmd deviceidle whitelist` includes `dev.suryansh.airspeaker`. This TV has no battery-optimisation dialog, so this is set over adb.
7. *(you)* Reboot the TV (Settings → System → Restart) and do **not** open the app. Turn Wireless debugging back on and reconnect. Step 2's `dumpsys` then shows the service running and `logcat -s mdns` shows it started from `BOOT_COMPLETED`.

## Phase 3: discovery (mDNS)

1. *(Mac)* `./gradlew test` passes, covering:
   - the service-name format `<12 hex>@<name>`
   - the TXT record set
   - name truncation to the 63-byte DNS label limit
   - the device ID is generated once, then stays stable
2. *(TV)* After `adb install -r`, `adb -s $TV logcat -d -s mdns` shows `registered _raop._tcp '<ID>@Room Speaker' port 5000`.
3. *(Mac)* `dns-sd -B _raop._tcp local.` lists `<ID>@Room Speaker`. Then `dns-sd -L "<ID>@Room Speaker" _raop._tcp local.` shows port 5000 and every TXT key: `txtvers ch cn et ek sr ss tp vn vs md am da sv sf fv pw`.
4. *(TV)* After `run-as … kill -9` and the sticky restart, the advertised ID is the same as before (it's persisted).
5. *(you)* iPhone → Control Centre → long-press the music tile → AirPlay icon. **Room Speaker** is listed. It won't play until Phase 4.
6. *(you)* Toggle the TV's Wi-Fi off and back on. Within about 10 s `logcat -s mdns` shows the service registered again and the Mac's `dns-sd -B` lists it again.

## Phase 4: audio pipeline

Debug switches on the TV (create the file to turn one on, delete it to turn it off):
- `adb -s $TV shell "run-as dev.suryansh.airspeaker sh -c 'touch files/force_mute'"` sends silence to the speakers whatever the sender's volume is. It's for testing at night.
- `… touch files/dump_pcm` saves each session's decoded audio to `files/dump.pcm`, so it can be compared against the source.

1. *(Mac)* `./gradlew test` passes, covering:
   - bit-exact ALAC decoding against ffmpeg fixtures, in both verbatim and adaptive modes
   - RSA-OAEP key unwrap, the Apple-Challenge signature, AES-CBC with a clear-text tail
   - SDP and RTSP parsing, clock and anchor maths including 32-bit RTP wrap, jitter-buffer ordering, the volume curve
2. *(Mac → TV, muted)* pyatv streams a 20 s test file (it needs `--manual`; see notes) (`atvremote --id 1E2E5EB64A10 stream_file=test20.wav`). Then:
   - `logcat -s rtsp audio` shows ANNOUNCE → SETUP → RECORD, `in sync`, and then `sync error` lines under 5 ms
   - the pulled `dump.pcm` equals the source PCM, ignoring leading and trailing silence
3. *(Mac → TV, muted)* A 35-minute stream runs with no `re-aligning` and no `AudioTrack.write failed` lines. Missing packets stay at about 0, and the smoothed sync error stays within ±3 ms (drift correction keeps it there).
4. *(you)* iPhone → Room Speaker, play music for **30+ minutes**: clean audio, with no clicks, dropouts or drift.
5. *(you)* Pause and resume on the iPhone: it pauses within about 2 s (classic AirPlay latency) and resumes cleanly. Skipping forward and back starts the new track cleanly.
6. *(you)* The iPhone volume slider changes loudness smoothly, the bottom of the slider is quiet but audible, and muting is silent. The TV's own volume is not touched.
7. *(you)* Disconnect (pick "iPhone" as the output) and reconnect to Room Speaker. It plays again **without** restarting the app.
8. *(you)* While Room Speaker is playing from the iPhone, start AirPlay from the Mac. The Mac takes over and the iPhone stops.

## Phase 5: metadata, artwork, progress

1. *(Mac)* `./gradlew test` passes, covering:
   - DMAP parsing of real-shaped `mlit` bodies (title, artist, album, duration, nested containers, unknown tags skipped)
   - progress `start/current/end` parsing, including 32-bit RTP wrap
   - the DACP service name and request format
2. *(Mac → TV, muted)* pyatv streams a tagged MP3 with cover art. Within 1 s, `adb -s $TV logcat -s meta` shows `track "…" by … on …`, `artwork W×H` (≤ 1080 px) and `progress m:ss / m:ss`.
3. *(you)* iPhone → Room Speaker: skip to the next track. `logcat -s meta` shows the new title, artist and artwork within 1 s of the change.
4. *(you)* After a track with no artwork, the artwork is cleared rather than left over from the previous track.
5. *(you, with Phase 6 UI)* With the receiver playing from the iPhone, `adb -s $TV shell am broadcast -a dev.suryansh.airspeaker.DACP --es command playpause -n dev.suryansh.airspeaker/.service.DebugCommandReceiver` (debug builds only) pauses the iPhone, and `logcat -s meta` shows `DACP playpause → 204`.

## Phase 6: full-screen Now Playing UI

One-time grants on the TV (sideloaded apps can't ask for these through a dialog on Google TV):
`adb -s $TV shell appops set dev.suryansh.airspeaker SYSTEM_ALERT_WINDOW allow` (lets the background service bring the UI to the front)
`adb -s $TV shell appops set dev.suryansh.airspeaker TURN_SCREEN_ON allow` (lets a session wake the TV)

1. *(Mac)* `./gradlew :app:verifyRoborazziDebug` renders the following at 1920×1080. Review the PNGs in `app/src/test/screenshots/`:
   - now playing with artwork
   - now playing without artwork (gradient placeholder)
   - paused
   - the volume overlay
   - the idle clock
2. *(TV, asleep, `files/no_wake` set)* Starting a stream from the Mac makes `dumpsys activity activities` show `MainActivity` resumed or top, launched by the service. The TV stays asleep, which shows the background-launch permission works.
3. *(you)* With the TV asleep, play from the iPhone to Room Speaker:
   - the TV wakes (and the monitor too, if it follows HDMI-CEC)
   - Now Playing appears with the right artwork, and audio is heard within about 2 s
   - it's readable from across the room
4. *(you)* Track change: the artwork and text crossfade, the background follows the new artwork, and the accent colour changes.
5. *(you)* iPhone volume: a volume overlay appears briefly.
6. *(you)* D-pad on the TV remote:
   - centre pauses or plays on the iPhone
   - right skips to the next track, left goes to the previous one
   - Back hides the UI while audio keeps playing
7. *(you)* Stop playing on the iPhone (or pick another output). A dim clock appears, then after the idle timeout (default 3 min) the TV returns to the previous screen.
8. *(you)* While playing, the screen never sleeps (`dumpsys window | grep -i keepscreen`). After the session ends, the normal screensaver and sleep come back.
9. *(you)* Over a few minutes the layout shifts by a few pixels each minute (burn-in protection).

# Acceptance tests

Written before each phase is implemented. A step marked *(Mac)* runs on the Mac. *(TV)* means it is checked on the device over adb. *(you)* needs a person: the iPhone, eyes on the screen, ears on the speakers.

All commands assume the TV is connected (see README → Install) and `TV=<tv-ip>:<port>`.

## Phase 1: project skeleton

1. *(Mac)* `./gradlew assembleDebug` succeeds, and so does `./gradlew test`.
2. *(Mac)* `./gradlew :app:verifyRoborazziDebug` renders the placeholder screen at 1920×1080 and it matches the committed golden image. Open `app/src/test/screenshots/*.png` to look at it.
3. *(TV)* `adb -s $TV install -r app/build/outputs/apk/debug/app-debug.apk` prints `Success`.
4. *(TV)* `adb -s $TV shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep crosscast` lists `dev.suryansh.crosscast/.MainActivity`, which means it appears in the TV's app list.
5. *(TV)* `adb -s $TV shell am start -n dev.suryansh.crosscast/.MainActivity`, then `adb -s $TV shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity"` shows `MainActivity` (once the screen is awake).
6. *(TV)* `adb -s $TV logcat -d -s ui` shows `MainActivity created`.
7. *(you)* Open **Crosscast** from the Google TV app list. A full-screen dark placeholder ("Room Speaker", "Waiting for AirPlay") is shown, with no system bars or white flash.

## Phase 2: always-on receiver service

Foreground-service strategy, explained in `docs/notes.md` → Foreground service type:
- The idle listener runs as a `connectedDevice` service.
- `mediaPlayback` is added while a session is streaming.

1. *(Mac)* `./gradlew assembleDebug test` succeeds.
2. *(TV)* With the app **not** opened, `adb -s $TV install -r app-debug.apk` triggers `MY_PACKAGE_REPLACED`. Within about 5 s, `adb -s $TV shell dumpsys activity services dev.suryansh.crosscast` shows `AirPlayReceiverService` with `isForeground=true` and type `connectedDevice`.
3. *(TV)* `adb -s $TV shell dumpsys wifi | grep -i -A2 "multicast"` lists a multicast lock held by `Crosscast`.
4. *(TV)* `adb -s $TV logcat -d -s mdns` shows `network available` once the service starts.
5. *(TV)* Kill the process with `adb -s $TV shell run-as dev.suryansh.crosscast kill -9 <pid>`. Within about 30 s the service is running again under a new pid (START_STICKY).
6. *(TV)* With the TV asleep, run `adb -s $TV shell dumpsys deviceidle force-idle`. The receiver still answers on port 5000 and stays visible over Bonjour with no battery exemption, because a foreground service keeps its network in Doze. Undo with `dumpsys deviceidle unforce`.
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
- `adb -s $TV shell "run-as dev.suryansh.crosscast sh -c 'touch files/force_mute'"` sends silence to the speakers whatever the sender's volume is. It's for testing at night.
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
5. *(you)* With the receiver playing from the iPhone, `adb -s $TV shell am broadcast -n dev.suryansh.crosscast/.service.DebugCommandReceiver --es command playpause` (debug builds only) pauses the iPhone, and `logcat -s meta` shows `DACP playpause → 204`.

## Phase 6: full-screen Now Playing UI

First, allow "Display over other apps" from the app's setup screen (see "First-run setup" below), which lets the background service bring the UI to the front.

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

## Phase 7: settings and polish

1. *(Mac)* Tests pass, and the screenshot test renders the settings and status screen.
2. *(TV)* Settings work as follows. Each change is checked over adb, or by streaming from the Mac.
   - **Friendly name:** changing it re-registers mDNS within about 2 s, and `dns-sd -B _raop._tcp` shows the new name.
   - **Auto-show Now Playing:** when off, a session starts without bringing the UI forward.
   - **Idle timeout:** the clock closes after the configured time.
   - **Latency offset:** shifts playback, visible as `latency offset` in `logcat -s audio`.
   - **Start on boot:** when off, the boot receiver logs `start on boot disabled` and doesn't start the service.
3. *(TV)* The status block shows:
   - that the receiver is running
   - the IP address and port
   - the Bonjour name and whether it's registered
   - the last session (sender and time)
   - recent warnings and errors
4. *(TV)* Self-healing:
   - Killing the RTSP server (debug: `am broadcast … --es command kill_server`, see README) makes the supervisor log `engine unhealthy; restarting`, and port 5000 accepts connections again within 15 s.
   - A connection whose sender vanishes without TEARDOWN is dropped after 2 minutes with no RTSP or RTP traffic, and the UI returns to the clock.
5. *(you)* A full day of normal use without opening the app manually covers:
   - Wi-Fi drops and comes back
   - a second sender (the Mac) taking over
   - the iPhone locking during playback
   - a long track (over 1 hour), where the progress shows h:mm:ss
   - a track without artwork, which shows the gradient placeholder

## Phase 8: Apple Watch and the daily routine

Nothing new is built for this phase. The Watch controls the iPhone, and the iPhone sends ordinary AirPlay messages, all of which earlier phases handle.

1. *(you)* Start AirPlay to Room Speaker from the iPhone, then control it from the Watch's Now Playing:
   - play/pause, which arrives as FLUSH and resume: the TV shows paused or playing within about 2 s
   - next and previous: the artwork and title change on the TV within about 1 s of the audio change
   - volume (Digital Crown): the TV shows the volume overlay
2. *(you)* Changes made on the Watch show up on the TV exactly like changes made on the iPhone (`logcat -s rtsp meta audio`).
3. *(you)* Switch the iPhone output to headphones (TV goes to the clock, then back to the previous screen after the timeout), then back to Room Speaker (it plays again with no app restart).
4. *(you)* It feels like an Apple TV used as a speaker. Anything that doesn't goes in docs/notes.md.

## Apple-style Now Playing, pause and volume fixes (after Phase 8)

1. *(Mac)* `./gradlew test :app:verifyRoborazziDebug` passes. The screenshots in `app/src/test/screenshots` show:
   - Inter type
   - continuous-corner artwork
   - a background made from the cover's colours
   - the HUDs in the top-right corner
2. *(you)* Play from the iPhone. Within a few seconds the background drifts slowly through the cover's colours, and the TV shows no stutter.
3. *(you)* Pause on the iPhone or the Watch. iOS keeps streaming silence, so:
   - about ¾ s after the sound stops, the progress bar stops
   - about 1 s after that, the artwork settles back and "Paused" replaces the sender name
   - when you resume, the bar carries on from the same time
4. *(you)* Skip a track: the new song starts from 0:00 as it's heard. A silent gap between tracks may briefly freeze the bar.
5. *(you)* The top line shows the iPhone's name (from the SDP `i=` line).
6. *(you)* On the TV remote, OK pauses or plays the iPhone, and ◀ ▶ skip (DACP over IPv6 link-local).
7. *(you)* Seek on the iPhone: the bar jumps to the new position once the audio does.
8. *(you)* Disconnect and reconnect from the iPhone:
   - the volume comes back where you left it, without turning it up
   - half volume on the iPhone sounds about half as loud, not near silent
9. *(TV)* `logcat -s audio meta`: each session logs `volume …`, and `in sync` and `silent for 750 ms` lines appear as audio starts and pauses.

## First-run setup, no adb needed

1. *(Mac)* `./gradlew :app:verifyRoborazziDebug` passes. `setup_allow.png`, `setup_done.png` and `setup_no_settings_screen.png` look right.
2. *(TV)* Fresh install, then open Crosscast from the apps row. The setup screen appears with **Open TV Settings** focused.
3. *(TV)* Press OK. The TV's "Allow display over other apps" screen opens, with Crosscast somewhere in the list. The highlight starts on the top row, which may be another app.
4. *(TV)* Move to Crosscast, press OK, then Back. Setup now says "You're all set". Press OK on Done to reach the idle clock. `appops get dev.suryansh.crosscast SYSTEM_ALERT_WINDOW` shows `allow`.
5. *(TV)* Put the TV to sleep and start a stream. It wakes within a second and Now Playing opens, in each of these cases:
   - Crosscast was closed
   - Crosscast was open in the background
   - Crosscast was frontmost when the TV went to sleep
   - the TV was forced into deep Doze
6. *(TV)* `dumpsys activity services dev.suryansh.crosscast` shows the service in the foreground.
7. *(TV)* With the permission revoked (`appops set dev.suryansh.crosscast SYSTEM_ALERT_WINDOW default`) and the TV awake on the home screen, a stream still plays, Now Playing doesn't open, and Settings shows "Not allowed" and the problem under Recent problems. Opening the app shows setup again.

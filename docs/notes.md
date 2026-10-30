# Notes

Discoveries made along the way: device quirks, Android restrictions and protocol details. Newest entries go at the top of each section.

## Device: Chromecast with Google TV 4K (`sabrina`)

- Android TV 14 (API 34), build `UTTC.250917.004`. The userspace is **32-bit only** (`armeabi-v7a`), even though the kernel is 64-bit (`armv8l`). Any future native code must target `armeabi-v7a`.
- The HDMI output mixer runs at a fixed **48 kHz / 16-bit**. 44.1 kHz AirPlay audio is resampled by the platform.
- The UI renders at **1920×1080 / 320 dpi** while HDMI outputs 3840×2160. Design for 1080p; downscaling artwork to 1080p is enough.
- **Ports in use:** 6466/6467 (TV remote), 8008/8009/8443 (Cast), 9000, 10001, 10042. Ports **5000 and 7000 are free**; classic Shairport defaults to 5000.
- There is **no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` settings activity**, so Phase 2 item 6 has nothing to ask. For development, use `adb shell dumpsys deviceidle whitelist +<package>`.
- **The CPU never suspends**, even when the TV is asleep. `dumpsys alarm` reports the same elapsed time and uptime, and batterystats shows 31 days of run time with uptime equal to realtime. The Chromecast itself holds `chromecast:kernelAwakeFromRecentMdns`. So an idle listener doesn't need a wake lock, and mains power means Doze never kicks in.
- ADB, the network and new activities all keep working while the TV is asleep. `am start` does **not** wake the screen.
- Android 15 blocks starting `mediaPlayback` foreground services from `BOOT_COMPLETED`, but this device runs Android 14, so it isn't affected yet. Re-check this if the TV is updated.

## ADB over Wi-Fi

- On Android TV 14, **USB debugging does not open port 5555**. Use **Wireless debugging** instead, which requires pairing once.
  - On the TV: Developer options → Wireless debugging → Pair device with pairing code
  - Then on the Mac: `adb pair 192.168.29.48:<pairing-port> <code>`
- The connect port is random and changes whenever Wireless debugging is toggled. To find it:
  - `adb mdns services`, or
  - `dns-sd -B _adb-tls-connect._tcp`, then `dns-sd -L <instance> _adb-tls-connect._tcp`
  - Then connect with `adb connect 192.168.29.48:<port>`.
- The TV is at `192.168.29.48` and is called "Bedroom TV". Reserving that IP in the router keeps it stable.

## Engine / protocol

- **TXT records (Phase 3):** we use the set classic Shairport Sync 5.x advertises, not the plan's original list. It adds `ek=1`, `vn=65537` (instead of 3), `vs=105.1`, `sf=0x4` and `fv=76400.10`, and senders use `vs` to decide whether to send progress and artwork. We advertise `tp=UDP`, not `TCP,UDP`, because UDP is all we implement.
- The device ID `1E2E5EB64A10` was generated on first run (locally administered MAC) and is stored in DataStore. It's both the Bonjour name prefix and the Apple-Challenge hardware address.
- NsdManager registers on the TV's hostname `Android_3b9ff9d33efe4cfb836831a65f88a258.local`. On API 33+ we pin the registration to the default `Network`.
- Bionic has no `pthread_cancel`, which rules out porting a current Shairport Sync. See `decision-engine.md`.
- From Shairport `common.c`: the AES key in ANNOUNCE (`rsaaeskey`) is unwrapped with **RSA-OAEP (SHA-1)**, and the Apple-Challenge response is a **PKCS#1 v1.5** private-key signature.
- ALAC is not a guaranteed MediaCodec codec on Android; it's only an OEM extra on some devices. Use our own decoder.
- Classic AirPlay senders ask for about 2.0–2.25 s of latency (Shairport README).

## Foreground service type (Phase 2 decision)

- `AirPlayReceiverService` declares `connectedDevice|mediaPlayback`.
  - While idle it runs as **`connectedDevice`**. That covers interaction with network-connected external devices, and `CHANGE_WIFI_MULTICAST_STATE` satisfies its permission requirement.
  - It adds **`mediaPlayback`** only while a session is streaming (`setStreaming(true)`). If the system refuses the switch, it stays `connectedDevice`, and audio still plays because the process remains foreground.
- **Why not `mediaPlayback` all the time:** Android 15+ blocks `mediaPlayback` foreground services started from `BOOT_COMPLETED`. Using the same idle type on every Android version means the code path tested today on Android 14 keeps working if the TV is updated.
- **Start triggers:**
  - opening the app
  - `BOOT_COMPLETED`
  - `MY_PACKAGE_REPLACED`, so `adb install -r` restarts the receiver without opening the app
  - `START_STICKY` after a process death; this was measured restarting in about 3 s
- On this TV the app is on the **deviceidle whitelist** (`cmd deviceidle whitelist +dev.suryansh.airspeaker`). This persists across reboots.

## Audio pipeline (Phase 4)

- **While the TV is asleep there is no HDMI audio.**
  - Audio policy reroutes output to `AUDIO_DEVICE_OUT_SPEAKER`, a dummy device.
  - The mixer sits in standby ("Last write occurred 1500 ms" ago), and our track stays full and barely drains.
  - AudioTrack logs `device stall time corrected using current time` and `retrograde timestamp`, and blocking writes return 0.
  - **So a session has to wake the device (Phase 6), or nothing is heard.** Timing can't be measured with the screen off.
- The player backs off 5 ms when the sink accepts nothing (earlier it busy-spun). It only re-aligns after 10 consecutive packets more than 50 ms off, so a single bad timestamp doesn't trigger it.
- **Sync simulation** (`AudioPlayerSimulationTest`):
  - Setup: virtual clock, sender +60 ppm, DAC −40 ppm, 50 ms output latency, ±1.5 ms timestamp jitter, 1–8 ms network delay, 0.1% loss.
  - 30 minutes: max error 2.99 ms, mean 1.38 ms, net 7,853 frames dropped (≈ 100 ppm), no re-alignments, no underruns.
  - A stuffing tolerance of ±2 ms kept the error sitting at +2 ms. ±1 ms centres it.
- **ALAC fixtures:** made with ffmpeg from a 1.45 s mix of sweep, pink noise, silence and a quiet tone, at `-compression_level 0` (verbatim) and `2` (adaptive).
  - ffmpeg ignores `-frame_size` for ALAC and always writes 4096-frame packets. That's fine for the decoder, which reads the frame length from the cookie or fmtp.
  - Packets were dumped with `ffprobe -show_data`. The reference is the source WAV, because ffmpeg's CAF decode trims the last 1591 samples.
- **pyatv as a test sender:**
  - It can't discover the receiver; Android's mDNS doesn't answer pyatv's legacy-unicast queries. iOS and `dns-sd` both see it fine. Use `--manual --address … --port 5000 --protocol raop --id 1E2E5EB64A10`.
  - It only sends **unencrypted L16**, starts with `GET /info`, and sends `POST /feedback`.
  - Its sync packets give a latency of 66150 frames, +11025 = 77175.
  - A 20 s stream decoded on the TV matched the source exactly: 882,000 frames.
- `RaopEndToEndTest` acts like iOS over loopback sockets: Apple-Challenge, RSA-OAEP-wrapped key, AES-CBC ALAC. It checks the decoded PCM is bit-exact. Encrypted ALAC from a real iPhone is still to be confirmed.

## Metadata (Phase 5)

- On the TV, through pyatv, everything landed within 0.2 s of ANNOUNCE:
  - `progress 0:00 / 0:20`
  - `track "…" by … on …`
  - `artwork 1080×1080 from 113 KB image/jpeg` (a 1400 px cover, scaled down)
- pyatv's CLI `stream_file` never sends artwork, because its TinyTag-based reader ignores images. `scratchpad/stream_with_art.py` passes a `MediaMetadata` with artwork through the API instead.
- Artwork is cleared when a DMAP text update names a different track. The exception is artwork that arrived within the previous second, since senders may send the image just before the text.
- DACP looks for `_dacp._tcp` named `iTunes_Ctrl_<DACP-ID>` and resolves its port. It then sends `GET /ctrl-int/1/<cmd>` with `Active-Remote` to the sender's RTSP IP. pyatv doesn't advertise DACP, so this needs the iPhone.

## Now Playing UI (Phase 6)

- **Bringing the UI to the front from the service:** Android blocks background activity starts unless the app holds `SYSTEM_ALERT_WINDOW`.
  - Google TV has no settings screen for it, so grant it with `appops set dev.suryansh.airspeaker SYSTEM_ALERT_WINDOW allow`.
  - Verified: *"Background activity start for dev.suryansh.airspeaker allowed because SYSTEM_ALERT_WINDOW permission is granted"*. `MainActivity` resumed while the TV stayed asleep (with `files/no_wake` set).
- **Waking the TV:** use a `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` wake lock.
  - Apps targeting Android 14+ need `TURN_SCREEN_ON` for this; grant it with `appops set … TURN_SCREEN_ON allow`.
  - The activity also calls `setTurnScreenOn(true)`.
  - Waking happens at RECORD, about 2 s before the first audio is due, so HDMI is routed back before playback starts. Confirmed with a real iPhone on 2026-10-30 (see Phase 8).
- **Backdrop:** a 40 px thumbnail, box-blurred three times in software, drawn with bilinear filtering. It costs nothing on the GPU. The Ken Burns drift is read inside `graphicsLayer`, so it redraws without recomposing.
- Screenshot tests disable all motion (`motion = false`), so frames are deterministic.

## Settings and robustness (Phase 7)

- **Renaming** re-registers in under 1 s: old name unregistered at 06:27:48.19, new one registered at 06:27:49.07, and the Mac's `dns-sd` showed it immediately.
- **Supervisor:** every 15 s the service checks `engine.isHealthy` (server socket open and accept thread alive), and restarts the engine if not. The debug `kill_server` command was healed within one tick, and port 5000 accepted connections again.
- **A sender that crashes** (its process killed with SIGKILL) ends the session at once: the Mac's kernel closes the TCP connection, the receiver goes Idle, and the UI shows the clock.
- **Session timeout:** RTSP reads time out every 15 s. A connection with no RTSP request and no RTP packet for 120 s is dropped, matching Shairport's default `session_timeout`. If the sender wasn't gone, iOS reconnects on the next play. A sender silently dropping off Wi-Fi hasn't been simulated, because that needs firewall rules on the Mac.
- **Idle auto-close** was verified with the TV asleep: Idle at 06:29:06, `returning to the previous screen` at 06:32:06, then the launcher was back on top. It runs in `lifecycleScope`, not in composition. Compose stops collecting while the activity is stopped (TV asleep), so the composition-based timer never fired.
- **Paused look is debounced by 2.5 s:** iOS sends FLUSH on every track skip, and new audio is about 2 s away.
- **Resources at idle:** 0.0% CPU, about 98 MB PSS (debug build, with the UI open), 35 threads.

## On the TV with a real iPhone (Phase 8)

- 2026-10-30: the user streamed from their iPhone and reported that everything works as intended. The TV woke from sleep and played through HDMI, which means encrypted ALAC from iOS also works on the device.
- **Restart:** after a TV restart, with the app left unopened, it still worked. `BOOT_COMPLETED` starts the service and the `appops` grants survive the restart.
- **Wireless debugging:** it switched itself off overnight (or with the restart). The receiver doesn't need it.

## Apple-style UI, pause and volume (after Phase 8)

- **Progress kept moving after a pause.** The UI waited 2.5 s before it believed a pause (to ride out track skips), and until then it extrapolated the position on the wall clock.
  - The position now comes from the audio actually heard. `progress` names the RTP time the track starts at. When the player gets in sync, it reports which RTP frame is heard and when (`onHeard`).
  - Position = (heard frame − track start) / 44100. It moves only while audio is heard and freezes the instant a FLUSH arrives (`TrackClock`, unit-tested).
  - This also fixes the bar running ~2 s ahead: `progress` arrives when the sender *sends* a frame, about 2 s before we play it.
- **New `Buffering` status:** audio is arriving but not yet due.
  - A skip is FLUSH → Paused for a moment → Buffering for about 2 s → Playing. A pause is FLUSH → Paused until resumed.
  - The UI waits 1.2 s before it shows a pause, so skips don't make the artwork shrink.
- **Volume was low after every connect:**
  1. The slider was spread over 45 dB, so half volume was −22.5 dB. It now uses the sender's 30 dB as sent: half volume is −15 dB.
  2. Each session started from a −15 default. The last volume a sender set is now kept in SharedPreferences (mute excepted), used until the sender sends one, and returned for `GET_PARAMETER volume`.
  - Still to check in the logs: whether iOS sends its own volume on connect, and what value.
- **Font: Inter and Inter Display (SIL OFL).** SF Pro's licence allows use only on Apple platforms. Inter is the closest open design and has its own display cut for large sizes.
- **Moving background:** three copies of a 48 px, saturated, blurred thumbnail of the cover, turning at different speeds around different points.
  - They're composited on the CPU into a 192×108 bitmap at about 30 fps, then drawn once, scaled up.
  - Three blended full-screen layers would be about 6 MP of fill per frame on the Chromecast's GPU; this is one layer.
  - A 128 px tile of 3 % grain hides 8-bit banding.
  - The scrim darkens brighter covers more (0.18 + 0.5 × mean luminance), so white text stays legible.
- **Artwork:** continuous ("squircle") corners use the usual Bézier fit of iOS corners. While paused, the cover springs back to 84 % and its shadow drops, as in Apple Music.
- **Artwork shadow:** a 64 px pre-blurred image scaled up, not an elevation shadow. Robolectric drew elevation shadows as a stray light strip under the artwork, so the screenshots didn't match the TV. Now they draw the same, the shadow is softer, and the GPU draws one image.
- **Removed:** the accent colour taken from the cover (Palette). Apple's Now Playing is white on the cover's colours; system blue and green remain only for focus and switches in Settings.

## What iOS (AirPlay/980.77.1) really does, seen on the TV on 2026-10-30

- **Pause sends no FLUSH.** iOS keeps streaming at the full packet rate, sending digital silence, and sends a `progress` with the paused position, then the same position again on resume.
  - The player now counts ¾ s of silence in what's heard (|sample| ≤ 8, −72 dBFS) as paused (`onSilent`).
  - Sound coming back is `onHeard`, and the track clock takes the new `progress` from there.
  - Verified: `silent for 750 ms` → `receiver Paused`, then Playing on resume.
- **Skipping sends no FLUSH either.** The stream carries on, and the new track's metadata and `progress` arrive. iOS often sends a `progress` with stale values just before the right one.
- **The sender's name is in the SDP:** `i=Suryansh’s iPhone`. iOS sends no `X-Apple-Client-Name` header.
  - ANNOUNCE headers are only `Active-Remote`, `Content-Length`, `Content-Type`, `CSeq`, `DACP-ID` and `User-Agent: AirPlay/980.77.1`.
  - The SDP is `o=AirTunes … IN IP6 fe80::…`, with `min-latency` 11025 and `max-latency` 88200.
- **The iPhone connects over IPv6 link-local** (`fe80::…%wlan0`) for RTSP, RTP and DACP alike.
  - Android's `URL`/`HttpURLConnection` rejects scoped literals ("Invalid host"), so every DACP command had failed. DACP is now raw HTTP/1.1 over a `Socket`.
- **DACP on iOS supports only a few requests.**
  - `getproperty?properties=dmcp.volume`, `getspeakers` and `playstatusupdate` all return 400.
  - `setproperty?dmcp.device-volume=…` returns 200 but changes nothing: no volume came back, and the slider didn't move.
  - So a classic receiver can't set the iPhone's slider, and can't ask whether it is playing.
- **Volume at the start of a session:** new sessions started at `volume -20.0` (14:09:59 and 14:10:08). A session iOS resumed after the receiver restarted (same session ID) came back at the level set before (−3.94).
- **New sessions always open at −20, and the receiver can't move the iPhone's slider.**
  - So the receiver pins the level it last played at to iOS's opening position (`VolumeCurve`). From there the slider still spans −30 dB at the bottom to 0 dB at the top, linearly on each side.
  - The iPhone's slider opens at about a third while the sound is at the remembered level.
  - User verdict 2026-10-30: "better but not the best", left as is for now.
  - The Chromecast's own HDMI volume was 16 of 25, which adds roughly 15 dB of attenuation on top.

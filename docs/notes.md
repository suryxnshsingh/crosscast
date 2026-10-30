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

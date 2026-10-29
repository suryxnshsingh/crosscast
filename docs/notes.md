# Notes

Discoveries made along the way: device quirks, Android restrictions and protocol details. Newest entries go at the top of each section.

## Device: Chromecast with Google TV 4K (`sabrina`)

- Android TV 14 (API 34), build `UTTC.250917.004`. The userspace is **32-bit only** (`armeabi-v7a`), even though the kernel is 64-bit (`armv8l`). Any future native code must target `armeabi-v7a`.
- The HDMI output mixer runs at a fixed **48 kHz / 16-bit**. 44.1 kHz AirPlay audio is resampled by the platform.
- The UI renders at **1920×1080 / 320 dpi** while HDMI outputs 3840×2160. Design for 1080p; downscaling artwork to 1080p is enough.
- **Ports in use:** 6466/6467 (TV remote), 8008/8009/8443 (Cast), 9000, 10001, 10042. Ports **5000 and 7000 are free**; classic Shairport defaults to 5000.
- There is **no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` settings activity**, so Phase 2 item 6 has nothing to ask. For development, use `adb shell dumpsys deviceidle whitelist +<package>`.
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

- Bionic has no `pthread_cancel`, which rules out porting a current Shairport Sync. See `decision-engine.md`.
- From Shairport `common.c`: the AES key in ANNOUNCE (`rsaaeskey`) is unwrapped with **RSA-OAEP (SHA-1)**, and the Apple-Challenge response is a **PKCS#1 v1.5** private-key signature.
- ALAC is not a guaranteed MediaCodec codec on Android; it's only an OEM extra on some devices. Use our own decoder.
- Classic AirPlay senders ask for about 2.0–2.25 s of latency (Shairport README).

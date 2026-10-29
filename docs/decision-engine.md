# Engine decision (Phase 0)

**Status:** decided, 2026-10-30
**Decision:** Option B, implemented as our own Kotlin classic-AirPlay (RAOP) engine in `receiver-core`. No NDK module.
Shairport Sync is the *reference implementation* we read and port logic from (it is MIT), not something we embed.

## Phase 0 acceptance

- [x] Option A and Option B compared on effort, risks and licences (below)
- [x] One option recommended, with the reason
- [x] List of dependencies to build ([Dependencies](#dependencies))

## Target device (measured over adb, 2026-10-30)

| Property | Value |
|---|---|
| Model | Chromecast with Google TV 4K (`sabrina`, Amlogic) |
| OS | Android TV 14, API 34, build `UTTC.250917.004`, patch 2025-10-01 |
| ABIs | **`armeabi-v7a`, `armeabi` only** (32-bit userspace on a 64-bit kernel) |
| RAM | ~2 GB |
| Display | 3840×2160 HDMI output, UI rendered at 1920×1080, 320 dpi |
| Audio output | HDMI mixer thread, **48 kHz**, 16-bit PCM |

## Option A: port Shairport Sync via the NDK

Spike = source audit of Shairport Sync **5.6.2** (commit `0a34fbd`, 2026-10-25) plus a search for prior Android ports.

**Licence:** fine. Shairport and Shairport Sync are MIT; the bundled parts (Hammerton ALAC, tinysvcmdns, tinyhttp, pair_ap) are MIT, BSD or ISC.

**Work required to build it:**
- Cross-compile with autotools for `armeabi-v7a`, along with libpopt, libconfig and mbedTLS (or OpenSSL).
- Write new backends:
  - audio: AAudio, since there is no Android backend today
  - mDNS: a no-op backend so registration happens through NsdManager in Kotlin
  - metadata: a bridge from the pipe/multicast output to Kotlin
- Run it as a separate process for crash isolation, because Shairport is a daemon with global state.

**Blocker: thread cancellation.**
- The classic code paths call `pthread_cancel` **27** times, `pthread_setcancelstate` 79 times, `pthread_cleanup_push` 82 times and `pthread_testcancel` 9 times.
- Android's libc (Bionic) does not implement it. Bionic's [status.md](https://android.googlesource.com/platform/bionic/+/main/docs/status.md) says: *"Thread cancellation (`pthread_cancel`). Unlikely to ever be implemented."*
- The cancels are on the **session teardown path**: `player.c` cancels the RTP audio, control and timing threads, and `rtsp.c` cancels connection threads. That code runs on every TEARDOWN, sender switch and reconnect, which is exactly what the Phase 4 and Phase 7 acceptance tests exercise.
- There is precedent: Termux kept `shairport-sync` pinned at **3.1.2 (2017)** with the comment *"Cannot simply be updated to a newer version due to `pthread_cancel` being used"*. It moved the package to `disabled-packages` in March 2025 (*"disabling broken package"*).

**Ways around it, all poor:**
1. A signal-based `pthread_cancel` shim. This cancels asynchronously, possibly inside malloc or while a lock is held, which risks deadlocks on random teardowns.
2. Rewrite cancellation to use cooperative flags and socket shutdown. That means maintaining a permanent fork of the most delicate part of the code.
3. Pin a pre-cancellation version from 2017. That gives up eight years of fixes, and Termux still ended up disabling it.

**Verdict:** it would *compile*, but it wouldn't run correctly without forking the code. That fails the plan's "NDK build feasible" gate, so the decision falls to B.

## Option B: JVM/Kotlin

### Existing open-source Java servers

| Project | Licence | Classic RAOP (RSA key) | Metadata / artwork / progress | Output | Activity | Fit |
|---|---|---|---|---|---|---|
| [fgp/AirReceiver](https://github.com/fgp/AirReceiver) | GPL-3.0 | Yes: RSA, AES, Java ALAC, NTP timing | No | `javax.sound` (not on Android) | Last push 2020; Netty 3.2.4 and BouncyCastle 1.46 (2011) | Reference only |
| [serezhka/java-airplay](https://github.com/serezhka/java-airplay) | MIT | **No.** It handles the FairPlay / AirPlay 2 path only and has no `rsaaeskey` handling | Partial | GStreamer / FFmpeg; Spring Boot desktop app | Active (Oct 2026) | Wrong protocol for v1. Possible reference for "Later" |
| bencall/RPlay | Unclear | — | — | — | Last push 2013 | Ruled out on licence and age, not examined further |
| gpfduoduo/AirPlay-Receiver-on-Android | None | — | — | — | Last push 2018 | No licence, so it can't be reused |

None of them can be dropped in. Adopting AirReceiver would mean replacing its networking stack, its audio output and all of its metadata handling, which is most of the code.

### Chosen approach: write our own Kotlin RAOP v1 engine

Classic AirPlay audio is a small, well-understood protocol:
- RTSP text control
- RSA-OAEP (SHA-1) unwrap of the AES key using the public AirPort Express key
- AES-128-CBC packet decryption
- ALAC decoding
- three UDP ports: audio, control and timing

The Android SDK already covers every platform piece:
- `NsdManager` for mDNS
- JCA / Conscrypt for crypto: `RSA/ECB/OAEPWithSHA-1AndMGF1Padding` and `AES/CBC/NoPadding`, plus a PKCS#1 v1.5 signature for Apple-Challenge
- `AudioTrack` for output
- coroutines and `StateFlow` for concurrency and state

**Why this fits:**
- **No native code.**
  - No ABI matrix to build: this device is 32-bit only, and we don't need to care.
  - No JNI crashes.
  - Network input is parsed in a memory-safe language, so a decoder bug becomes an exception rather than memory corruption.
- **Single process.** The engine runs inside `AirPlayReceiverService` and exposes `StateFlow<NowPlayingState>` directly, which keeps Phases 5 and 6 simple.
- **ALAC decoding.** We port David Hammerton's decoder (MIT, ~1,000 lines of C, the default decoder in Shairport Sync) to Kotlin. Android's MediaCodec can't be relied on, because ALAC is not a guaranteed platform codec, only an OEM extra on some devices.

**Main risk: sync and drift quality.** This is the area where Shairport Sync is strongest.
- **Mitigation 1:** port Shairport's classic timing model:
  - Use the timing-port exchange to estimate the offset between the sender's clock and ours.
  - Use control-port sync packets to map RTP timestamps to sender time.
  - Start playback at the negotiated latency (about 2 s).
  - Correct drift by inserting or dropping single frames ("basic stuffing").
- **Mitigation 2:** measure the DAC side with `AudioTrack.getTimestamp()`, and log the sync error every 10 s so the 30-minute test produces data.
- **Mitigation 3:** keep the engine behind a `ReceiverEngine` interface. If the Kotlin timing fails Phase 4 acceptance, a native engine can be swapped in without touching the service or UI.
- **Scope note:** with a single room and no video, tolerance is generous. The goal is "never glitches and never drifts", not sample-accurate multi-room sync.

## Changes to the build plan caused by this decision

1. **Phase 1, item 4:** no `native` module. The modules are `app` and `receiver-core`.
2. **Phase 4, item 3:** port the Hammerton ALAC decoder to Kotlin instead of building Apple's C++ ALAC reference.
3. **Phase 4, item 5:** use **AudioTrack** instead of Oboe/AAudio.
   - Oboe is a C++ API, so using it would add a JNI module purely for output.
   - AudioTrack with a large buffer matches "stability over low latency".
   - The device mixer runs at 48 kHz, so the platform resamples 44.1 kHz audio either way.
4. **Phase 7, item 4:** "restart if the native engine crashes" becomes "a supervisor coroutine restarts the engine if it throws or stalls".

## Dependencies

### Host toolchain

| Need | Status on this Mac |
|---|---|
| JDK 17+ (required by the Android Gradle Plugin; use 21) | Missing |
| Android SDK: `platforms;android-<latest stable>`, `build-tools` | Missing |
| `platform-tools` (adb) | Installed (Homebrew `android-platform-tools`) |
| Gradle wrapper, AGP, Kotlin, Compose compiler plugin | Generated in Phase 1 (latest stable at that point) |
| NDK, CMake | **Not needed** |

### App libraries

- `kotlinx-coroutines-android`
- `androidx.core:core-ktx`
- `androidx.activity:activity-compose`
- `androidx.lifecycle`: `lifecycle-service` and `lifecycle-runtime-compose`
- Compose BOM + `androidx.tv:tv-material` (Compose for TV)
- `androidx.palette:palette-ktx` (accent colours, Phase 6)
- `androidx.datastore:datastore-preferences` (friendly name, device ID, settings)
- `com.jakewharton.timber:timber`
- `androidx.work:work-runtime-ktx`, only if the Phase 2 boot strategy needs it
- Tests: JUnit and `kotlinx-coroutines-test`

### Platform APIs, no extra dependencies

`NsdManager`, `WifiManager.MulticastLock`, `ConnectivityManager.NetworkCallback`, `javax.crypto` / `java.security`, `AudioTrack`, `AudioFocusRequest`, `java.net` sockets.

### Code to write or port in `receiver-core`

| Component | Source | Est. Kotlin lines |
|---|---|---|
| RTSP server (OPTIONS … TEARDOWN, Apple-Challenge) | New; Shairport `rtsp.c` as reference | ~500 |
| Crypto (AirPort key, RSA-OAEP unwrap, AES-CBC) | New; key from Shairport `common.c` | ~150 |
| RTP audio, control and timing ports; resend requests | New; `rtp.c` as reference | ~600 |
| ALAC decoder | Port of `alac.c` (Hammerton, MIT) | ~800 |
| Jitter buffer, scheduler, drift correction | Port of the classic-mode logic in `player.c` | ~600 |
| DMAP metadata, artwork, progress | New | ~300 |
| DACP client (remote control back to the iPhone) | New; `dacp.c` as reference | ~200 |

That's roughly 3,000–4,000 lines, as estimates. Ported MIT code keeps its copyright notices, collected in `THIRD_PARTY_NOTICES` when Phase 4 adds it.

### Test tooling

- `afconvert` and `ffmpeg` (both already on this Mac) to generate ALAC fixtures for decoder unit tests.
- Optional: run `shairport-sync -vvv` on the Mac as a reference receiver, to capture what the iPhone actually sends.
- Optional: `pyatv` as a scripted RAOP sender, so tests can be repeated without the iPhone.

# AirSpeaker

Turns a Chromecast with Google TV into an always-on AirPlay (classic RAOP) audio receiver with a full-screen Now Playing display.

- Engine choice and reasoning: [`docs/decision-engine.md`](docs/decision-engine.md)
- Device quirks and discoveries: [`docs/notes.md`](docs/notes.md)
- Per-phase acceptance tests: [`docs/acceptance.md`](docs/acceptance.md)

## Modules

| Module | Contents |
|---|---|
| `app` | Android TV app: activity, Compose for TV UI, foreground service |
| `receiver-core` | AirPlay engine: mDNS, RTSP, RTP, decryption, ALAC, playback, metadata. It's pure Kotlin, and the protocol code is unit-tested on the JVM. |

## Prerequisites (macOS)

```sh
brew install openjdk@21 && brew install --cask android-platform-tools
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
```

The Android SDK lives in `~/Library/Android/sdk`, and `local.properties` points at it. Android Studio can use the same folder.

## Build and test

```sh
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                          # JVM unit tests
./gradlew :app:recordRoborazziDebug     # re-render UI screenshots into app/src/test/screenshots
./gradlew :app:verifyRoborazziDebug     # fail if the UI no longer matches those screenshots
```

## Connect to the TV (Android TV 14: wireless debugging)

Do this once per Mac:

1. On the TV, unlock developer options: Settings → System → About → press *Android TV OS build* 7 times.
2. Go to Settings → System → Developer options and switch on **USB debugging** and **Wireless debugging**.
3. In Wireless debugging, choose **Pair device with pairing code**, then run `adb pair 192.168.29.48:<pairing-port> <code>`.

Every time you connect:

```sh
adb mdns services                             # shows the TV's current connect port
adb connect 192.168.29.48:<port>
export TV=192.168.29.48:<port>
```

Wireless debugging turns itself off after a reboot. Switch it back on from the TV, and the connect port will be new.

## Install and run

```sh
adb -s $TV install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $TV shell am start -n dev.suryansh.airspeaker/.MainActivity
```

## Logs

Each subsystem logs under its own tag: `mdns`, `rtsp`, `audio`, `meta`, `ui`.

```sh
adb -s $TV logcat -s mdns rtsp audio meta ui        # just this app's subsystems
adb -s $TV logcat -s rtsp audio                     # e.g. only protocol and playback
adb -s $TV logcat --pid=$(adb -s $TV shell pidof dev.suryansh.airspeaker)   # everything from the app process
```

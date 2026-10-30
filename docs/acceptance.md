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

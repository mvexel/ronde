# intervals-watch

Run your [intervals.icu](https://intervals.icu) planned workouts on a Wear OS watch (built for Pixel Watch).

Google's built-in Exercise app on the Pixel Watch can't import structured workouts from other apps, so this
project has two parts:

- **mobile/**: phone app. Fetches planned workouts from intervals.icu (`/events?category=WORKOUT&resolve=true`)
  and sends them to the watch through the Wearable Data Layer. It re-syncs in the background every 4 hours.
- **wear/**: watch app. Lists the synced workouts and plays them step by step: a countdown per step, the
  target range and live value (blue = too low, green = in range, red = too high), and haptics on step change,
  3 seconds before a timed step ends, and when you stay out of range for 10 seconds or more. Heart rate,
  speed/pace, distance and GPS come from Health Services. Power comes from a BLE Cycling Power sensor
  (bike power meter or Stryd).
- **shared/**: plain Kotlin. Holds the intervals.icu `workout_doc` parser, which flattens repeats and resolves
  %FTP / %LTHR / %pace into W / bpm / m/s, the step engine, and formatting. All of it is unit-tested.

Supported: Run and Ride (indoor variants map to treadmill / stationary bike). Steps can be timed,
distance-based or end on lap press. Ramps and power, HR and pace targets are supported.

## Build

```sh
./gradlew :shared:test
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

This needs the Android SDK (`ANDROID_HOME` or `local.properties` with `sdk.dir=`). CI builds both APKs on
every push. Download them from the Actions run (`apks` artifact).

Both apps use the same `applicationId` and **must be signed with the same key**, or the Data Layer won't
connect them. Debug builds from the same machine are fine.

## Install

```sh
adb install mobile/build/outputs/apk/debug/mobile-debug.apk          # phone

# Watch: Settings → Developer options → Wireless debugging, then:
adb pair <watch-ip>:<pair-port>
adb connect <watch-ip>:<port>
adb -s <watch-ip>:<port> install wear/build/outputs/apk/debug/wear-debug.apk
```

On the phone, enter your API key from intervals.icu → Settings → Developer Settings. Leave Athlete ID as
`0` to use the key's own athlete.

## Status / TODO

Version 0.1 is untested on a real device so far.

- [ ] Verify on a Pixel Watch (Health Services data types, GPS acquisition, BLE power).
- [ ] Save the recorded activity: write a FIT file and upload it to intervals.icu
      (`POST /api/v1/athlete/{id}/activities`), or write it to Health Connect. Today the recording is **not saved**.
- [ ] Ambient mode / Ongoing Activity instead of keeping the screen on.
- [ ] Remember the BLE power meter, and pair a specific device instead of picking the first one found.
- [ ] Cadence from the sensor; HR strap over BLE.
- [ ] Store the API key with Android Keystore rather than plain DataStore.
- [ ] App icons.

## Screenshots

CI renders these with [Paparazzi](https://github.com/cashapp/paparazzi) (`./gradlew :wear:recordPaparazziDebug :mobile:recordPaparazziDebug`)
and pushes them to the [`screenshots`](../../tree/screenshots) branch on every push to `main`.

| | | |
|---|---|---|
| ![](../screenshots/watch/list.png) | ![](../screenshots/watch/rideInRange.png) | ![](../screenshots/watch/rideTooLow.png) |
| ![](../screenshots/watch/runTooFast.png) | ![](../screenshots/watch/runDistanceStep.png) | ![](../screenshots/watch/paused.png) |
| ![](../screenshots/phone/synced.png) | ![](../screenshots/phone/setup.png) | ![](../screenshots/watch/summary.png) |

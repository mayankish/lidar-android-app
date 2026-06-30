# lidar-android-app

Kotlin + Jetpack Compose Android app for monitoring and controlling the
bot. **Project 3** of a small multi-repo lidar-mapping robot project I've
been building. See [`DATA_CONTRACT.md`](DATA_CONTRACT.md) for the wire
format this app parses and emits.

```
                       UDP :5005 (broadcast, receive)
base-radio  <======================================>  this app
(lidarbase.local)      UDP :5006 (unicast, send)
```

## Overview

The app resolves base-radio on the local network (mDNS hostname
`lidarbase.local`, with a manual-IP fallback field), listens for
telemetry frames broadcast on UDP `:5005`, accumulates `scan_sample`
points between `scan_complete` markers into the *current* sweep, and
renders them as a live polar plot. Start/Stop/Ping/Set-sweep-range
buttons encode `control_command` frames and send them unicast to
`lidarbase.local:5006`.

## Screenshots

Not included -- this app was written without access to physical ESP32 or
STM32 hardware to drive end-to-end (see "Testing" below), so there is no
live system to screenshot against. The `ScanCanvas` composable in
[`ui/LidarScreen.kt`](app/src/main/java/com/lidarbotsystem/app/ui/LidarScreen.kt)
is straightforward to screenshot once the broader system is running.

## SDK versions

- `minSdk = 26` (Android 8.0) -- required for the `java.time`-free
  coroutine/Compose stack used here; also the practical floor for modern
  `MulticastSocket`/Wi-Fi APIs without compat shims.
- `targetSdk` / `compileSdk = 34`
- Kotlin `1.9.24`, Jetpack Compose BOM `2024.06.00`, Compose compiler
  extension `1.5.14` (must track the Kotlin version -- see
  `app/build.gradle.kts` comment)
- AGP `8.5.2`

## Build instructions

This repo ships Gradle *project files* but deliberately not a generated
`gradlew`/`gradlew.bat`/`gradle-wrapper.jar` (see "Known limitations").
Two ways to build:

**Android Studio (recommended):** open `lidar-android-app/` as a project.
Studio detects the missing wrapper and offers to generate it
automatically on sync; accept, then Build > Make Project, or just press
Run with a device/emulator attached.

**Command line**, if you have a system Gradle install (`gradle -v` to
check):

```sh
cd lidar-android-app
gradle wrapper --gradle-version 8.7   # one-time: generates gradlew + jar
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## mDNS resolution + manual IP fallback

base-radio advertises itself via `mdns_hostname_set("lidarbase")` (see
`esp32-raw-mac-radio/base-radio`), i.e. a bare `.local` hostname, not a
registered NSD service type -- so Android's `NsdManager` (which resolves
DNS-SD service records, not bare hostnames) doesn't apply here.
[`network/MdnsResolver.kt`](app/src/main/java/com/lidarbotsystem/app/network/MdnsResolver.kt)
speaks raw mDNS (RFC 6762) directly over a `MulticastSocket`: it sends a
single `QTYPE=A` question for `lidarbase.local` and parses the first
matching `A` record out of the response, including basic DNS
name-compression-pointer following.

This requires the `CHANGE_WIFI_MULTICAST_STATE` permission (declared in
`AndroidManifest.xml`) and a `WifiManager.MulticastLock` held for the
duration of the query (`MdnsResolver.resolveWithLock`) -- Android drops
incoming multicast packets by default to save radio power otherwise.

If mDNS resolution times out (3s default) or the network doesn't route
multicast (some guest/enterprise Wi-Fi configurations block it), the
"Manual IP" field in the connection row lets the user type base-radio's
IP directly and connect without mDNS at all.

## Data contract types touched

[`data/LidarContract.kt`](app/src/main/java/com/lidarbotsystem/app/data/LidarContract.kt)
implements the full wire format (all five packet types) so the app can
both decode everything base-radio broadcasts and encode
`control_command`:

| Type | Direction | Used for |
|---|---|---|
| `scan_sample` (0x01) | receive | building the live point cloud |
| `scan_complete` (0x02) | receive | starting the next sweep's point list |
| `health_status` (0x03) | receive | battery/fault display |
| `control_command` (0x10) | send | Start/Stop/Ping/Set-sweep-range buttons |
| `control_ack` (0x11) | receive | parsed, not yet surfaced in the UI (see Known limitations) |

## Package structure

```
app/src/main/java/com/lidarbotsystem/app/
├── MainActivity.kt              hosts the Compose tree
├── data/
│   └── LidarContract.kt         wire format: pack/unpack/CRC16/all 5 types
├── network/
│   ├── MdnsResolver.kt          hand-rolled mDNS A-record query/parse
│   └── LidarUdpClient.kt        receive-on-:5005 flow, send-to-:5006
├── viewmodel/
│   └── LidarViewModel.kt        StateFlow state, connect/start/stop/ping
└── ui/
    ├── LidarScreen.kt           controls + polar Canvas rendering
    └── theme/                   Color.kt / Type.kt / Theme.kt
```

## Known limitations

- **No generated Gradle wrapper committed** (`gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar`) -- these are binary/generated
  artifacts; hand-faking the jar would be worse than omitting it. See
  "Build instructions" above for the one-time generation step.
- **`control_ack` is decoded but not surfaced in the UI** beyond keeping
  the connection "alive" -- a real product would toast/log per-command
  ack status; out of scope for a v1 polar-plot viewer.
- **No retry/backoff on UDP send** -- `sendControlCommand` is fire-and-forget,
  matching the data contract's own lack of a request/response
  guarantee at this layer (the firmware doesn't retransmit either, see
  `esp32-raw-mac-radio` known limitations).
- **Fixed `maxRangeMm = 2000` plotting scale** in `ScanCanvas` -- chosen
  to match the VL53L0X's practical range rather than read dynamically
  from a config packet; out-of-range samples are already filtered
  upstream by `LidarViewModel` (`OUT_OF_RANGE` check) so this only
  affects visual scale, not correctness.
- Single Activity, no persistence across process death (StateFlow state
  is in-memory only) -- acceptable for a live-telemetry viewer, not a
  logging tool.

## Architecture

![MVVM architecture: LidarScreen observes LidarViewModel's StateFlows; the ViewModel owns LidarUdpClient (receive on :5005, send to :5006) and MdnsResolver, both built on LidarContract's wire encode/decode](docs/android_mvvm_architecture.png)

## Testing

This app was written against the documented Android/Kotlin/Compose/
coroutines APIs as if for a real device on the same network as a real
base-radio, but has **not yet been run on a physical device or emulator
against a live base-radio** (no ESP32/STM32 hardware
available yet, and no network to test mDNS
multicast against). The wire-format logic in `LidarContract.kt` mirrors
the C implementations field-for-field and byte-order-for-byte-order; see
[`DATA_CONTRACT.md`](DATA_CONTRACT.md) for the cross-language
verification approach used elsewhere in this repo.

## License

MIT -- see [`LICENSE`](LICENSE).

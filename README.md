# BromNav

Offline navigator for food-delivery riders on a **Dutch yellow-plate bromfiets (45 km/h)**, built
for cheap Android phones. Routes follow Dutch moped law, delivery runs are planned automatically,
and receipts are read with the camera.

**Download:** see [Releases](../../releases). The APK contains the routing data and the map of the
Groningen area, so it works without a PC and without mobile data.

## Features

**Two modes, chosen at start**
- 🛵 **Delivery**: receipts, multi-stop runs, urgency, stats.
- 🧭 **Simple nav**: search an address (with recent searches) and go. No stats are recorded.

**Legal moped routing (offline)**
- Embedded [BRouter](https://github.com/abrensch/brouter) with a custom profile
  (`app/src/main/assets/profiles/bromfiets.brf`).
- Never uses cycle paths closed to mopeds (`moped=no`), roads with a compulsory moped path
  (`moped=use_sidepath`), motorroads or motorways. Uses fiets/bromfietspaden (`moped=designated`).
- Long-press the map → "Avoid here" if the map data is wrong somewhere.

**Delivery runs**
- Stops are ordered automatically (exact for up to 8 stops), starting and ending at the restaurant
  (`Config.kt`).
- **ASAP orders** keep a safety margin before their promised time. **Exact-time orders**
  ("req.: 17:30") are never delivered early; arriving after the requested time is penalised, with
  a hard limit of +10 min.
- Manual override (▲/▼, long-press = next), undo for *Delivered*, cancel/restore orders, and full
  editing of an order.
- GTA-style 0–5 urgency stars and a personal speed multiplier learned from your own deliveries.

**Receipt scanner** (📷)
- On-device OCR of Dutch and English receipts.
- A live checklist shows address, time, order number, payment and customer message. Each field
  gets a ✓ after 3 identical reads; the address is also checked against the Dutch address register
  (BAG, via PDOK).
- Tap a pending line to accept it yourself, or use "Add with this…" and fill in the rest.
- Live colour-coded OCR boxes on the camera image.
- Customer messages are translated Dutch → English on the phone (ML Kit, offline after a
  one-time download).
- Payment is shown as ✅ prepaid online or 💶 cash.

**Riding**
- Heading-up map with a scooter marker, smoothed GPS (Kalman filter + outlier rejection), and
  snapping to the route.
- English voice guidance, including *"move onto the moped path"* / *"leave the moped path and join
  the road"* and which side the house number is on.
- Within 200 m of a stop: a huge house number with a left/right arrow, the doorbell name, the
  customer message and tap-to-call.
- Nothing essential sits in the screen corners (phone holders cover them).
- Light / dark / automatic theme.
- **Update maps from internet** (☰): fresh routing data from brouter.de, and a fresh map cut out
  of the public [Protomaps](https://protomaps.com) world build directly on the phone.

## Building

Requirements: JDK 17, Android SDK (platform 35), and a phone with USB debugging for testing.

```bash
cp env.sh.example env.sh   # set JAVA_HOME / ANDROID_HOME
source env.sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds don't contain the maps. On first start they offer to download them, or you can copy
them with `tools/push_data.sh`.

### Offline data

`tools/update_data.sh` downloads the BRouter segment (`data/segments/E5_N50.rd5`) and cuts the
area out of the Protomaps build (`data/tiles/groningen.pmtiles`, needs the
[`pmtiles`](https://github.com/protomaps/go-pmtiles) CLI in `tools/vendor/`). The area and the
segments are configured in `MapData.kt` and the script.

### Releases

`tools/release.sh [--fresh]` runs the tests, builds a signed release APK with the maps inside,
tags the version, and publishes a GitHub release (needs the `gh` CLI). The signing key is read from
`~/.bromnav-release/signing.properties` and is never committed. Bump `versionCode` and
`versionName` in `app/build.gradle.kts` first.

## Testing tools

- `tools/test_routes.py`: legality regression test of the routing profile against a local BRouter
  server (any route over a forbidden segment fails).
- `tools/dbg.sh`: remote control of a debug build over adb (add stops, plan, simulate a ride, OCR an
  image, translate, read the full state as JSON). See the header of the script.
- `tools/ocr_view.py`: OCR photos with the phone and show the recognised boxes on the PC.

## Code map

| Path | What |
|---|---|
| `MainActivity.kt` | UI, modes, navigation flow, GPS |
| `BRouterEngine.kt`, `Route.kt` | embedded routing; route segments, way tags, voice hints |
| `Navigator.kt`, `VoiceGuide.kt` | route following, off-route detection, spoken guidance |
| `Planner.kt`, `TspSolver.kt` | travel-time matrix and stop ordering with time windows |
| `ScanActivity.kt`, `ReceiptParser.kt`, `ReceiptOcr.kt` | live receipt reader |
| `NoteTranslator.kt` | NL→EN translation of customer messages |
| `GpsFilter.kt` | location smoothing |
| `MapData.kt`, `PmtilesExtract.kt` | bundled/downloaded offline data; PMTiles cut-out over HTTP |
| `MapStyle.kt`, `Pal.kt` | light/dark map style and UI palette |
| `PdokGeocoder.kt`, `AddressSearch.kt`, `SearchHistory.kt` | Dutch address search |
| `SpeedModel.kt`, `History.kt`, `StatsActivity.kt` | speed multiplier, stats |
| `DebugBridge.kt` | debug-only remote control used by `tools/dbg.sh` |

Map data © OpenStreetMap contributors. Routing: BRouter. Map tiles: Protomaps.

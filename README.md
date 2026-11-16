# BromNav

Offline navigator for food-delivery riders on a **Dutch yellow-plate bromfiets (45 km/h)**, built
for cheap Android phones. Routes follow Dutch moped law, delivery runs are planned automatically,
and receipts are read with the camera.

**Download:** see [Releases](../../releases). The APK contains the routing data and the map of the
Groningen area, so it works without a PC and without mobile data.

## Features

**Two modes, chosen at start**
- 🛵 **Delivery**: receipts, multi-stop runs, urgency, stats.
- 🧭 **Simple nav**: search a place or an address (with recent searches) and go. No stats are recorded.

**Place search**
- An offline index of about 18,000 named places from OpenStreetMap: shops, restaurants, cafés,
  landmarks, schools, stations and neighbourhoods. It is bundled in the app and refreshed with
  "Update maps".
- Smart local search as you type: accent-insensitive, word prefixes ("alb hei"), typo-tolerant
  ("martinitorn"), category words in Dutch and English ("pizza", "apotheek", "supermarkt",
  "tankstation"), ranked by match quality and distance.
- Mixed with Dutch addresses (PDOK/BAG) and online OSM name search (Photon) for anything else.
- In delivery mode the search only looks up addresses (receipts always have one).
- **Offline address book**: about 196,000 addresses of the Groningen area (OpenStreetMap / BAG) in
  the app, so receipts and typed addresses work without internet. PDOK online is the fallback.

**Legal moped routing (offline)**
- Embedded [BRouter](https://github.com/abrensch/brouter) with a custom profile
  (`app/src/main/assets/profiles/bromfiets.brf`).
- Never uses cycle paths closed to mopeds (`moped=no`), roads with a compulsory moped path
  (`moped=use_sidepath`), motorroads or motorways. Uses fiets/bromfietspaden (`moped=designated`).
- **Vehicle** (Settings → Vehicle): bromfiets with yellow plate (45 km/h), snorfiets with blue plate
  (25 km/h, uses fietspaden `mofa=*`, not G13 / compulsory-cycle-path roads), e-bike up to 25 km/h
  without plate, or a normal bicycle (~18 km/h); the last two follow bicycle rules. One profile,
  variable `vehicle`. Icons: [Material Symbols](https://github.com/google/material-design-icons)
  (Apache License 2.0).
- **Road access layer** (☰ → 🗺 Show road access): colours every road by what the router believes for your vehicle:
  green allowed, yellow moped/cycle path, red not allowed. It is computed on the phone from the
  routing data with the routing profile itself.
- **Your road corrections** (Settings → ✏ Edit road access, or long-press → "This road"): tap a road
  section (junction to junction) to force it *allowed*, *allowed both directions* (ignore one-way)
  or *forbidden*. The dialog shows the OSM tags behind the decision. Corrections are applied inside
  BRouter (patched jar, `tools/brouter-patch/`), drawn as dashed blue/purple lines, and kept across
  map updates as long as the road is not redrawn in OSM.
- Destinations cut off from legal roads (e.g. an address point inside a campus with
  `access=private` roads around it): the route ends at the nearest reachable spot and the app says
  how far to walk.
- Long-press the map → "Avoid here" if the map data is wrong somewhere.

**Delivery runs**
- Stops are ordered automatically (exact for up to 8 stops), starting and ending at the restaurant
  (`Config.kt`).
- **ASAP orders** keep a safety margin before their promised time. **Exact-time orders**
  ("req.: 17:30") are never delivered early; arriving after the requested time is penalised, with
  a hard limit of +10 min.
- **Stats** (Settings → Stats & history): Delivery tab (KPI tiles, charts, laps, orders) and Simple
  navigation tab (trips, average speed and trip, most visited addresses, and "runs": the same
  start → finish ridden more than 3 times, with best / average time and a chart of every run).
- **Settings** (☰ → ⚙): a full screen with sections (Delivery, Roads & map, App). The ☰ menu on
  the map only has voice, clear all stops, the road access layer, and skip/end while navigating.
- **Auto order priority** (Settings): shortest total route / hottest orders first (least time left,
  distance still counts) / on time versus the promised times (default).
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
- The restaurant's own address in the receipt header is never taken. Upside-down readings and
  look-alike characters in the postcode ("9745 0S") are repaired, and a misread street is rejected.
- Receipts already added or already in the run are recognised and rejected, and the list of order
  numbers already done is always visible.
- Order numbers show as their last 3 digits ("010") on the map, in the list and in the voice. Room
  numbers ("46-1") appear on the big house number near the door.

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

## Branches

- `main`: the universal version, for any of 22 Dutch cities and any restaurant. On first start you
  choose the city (Settings → City & offline maps later). The app shows what it needs on the phone:
  routing tiles (server size), map (exact, from the PMTiles directories), places and addresses.
  Then it downloads them; a city counts as installed only with its address lookup table. Cities
  can be deleted again to free space (only routing tiles no other city needs are removed). The
  first time delivery mode opens, you set up your restaurant: search by name or address, plus an
  optional phone / website as printed on the receipts, so its header is never taken for a
  customer. Settings → Start-up mode: ask each time / simple nav / delivery / last used.
- `wabisabi` (legacy): Groningen and Wabi-Sabi built in; no longer developed.
- `universal`, `ui-redesign`: the branches main was built from (kept for history).

## UI

Rebuilt on Material Components (Material 3 theme, light and dark) with libraries from
[awesome-android-ui](https://github.com/wasabeef/awesome-android-ui):
PowerMenu (☰ menu), Balloon (hints), FlexboxLayout (wrapping chips: no overlapping or cut-off
text), Shimmer (calculating / reading placeholders), Konfetti (run complete) and MPAndroidChart
(stats charts). The design tokens and components are in `Ui.kt`. ExpandableLayout was tried for the
stop rows but does not measure inside a RecyclerView, so the rows use Android transitions instead.
In navigation the camera keeps your position in the free map area above the bottom panel.

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

`tools/update_data.sh` downloads the BRouter segment (`data/segments/E5_N50.rd5`), cuts the area
out of the Protomaps build (`data/tiles/groningen.pmtiles`, needs the
[`pmtiles`](https://github.com/protomaps/go-pmtiles) CLI in `tools/vendor/`), and builds the place
index (`data/pois.tsv.gz`, `tools/build_pois.py`, via Overpass). The area and the
segments are configured in `MapData.kt` and the script.

### Releases

`tools/release.sh [--fresh]` runs the tests, builds a signed release APK with the maps inside,
tags the version, and publishes a GitHub release (needs the `gh` CLI). The signing key is read from
`~/.bromnav-release/signing.properties` and is never committed. Bump `versionCode` and
`versionName` in `app/build.gradle.kts` first.

## Testing tools

- `./gradlew testDebugUnitTest`: 100 unit tests. Logic: solver vs brute force, navigator, route
  parsing, time windows, speed model, receipts, GPS filter, place search, road corrections. Real
  data, when `data/` exists: routing legality for all three vehicles, unreachable destinations,
  road layer, planner. The remote PMTiles test runs with `BROMNAV_NET_TEST=1`.
- `./gradlew assembleDebug -Pbench`: a non-debuggable debug build. It runs at release speed but
  keeps the remote control, for timing on the phone (debuggable builds are ~10x slower there).

- `tools/test_routes.py`: legality regression test of the routing profile against a local BRouter
  server (any route over a forbidden segment fails).
- `tools/brouter-patch/build.sh`: rebuilds `app/libs/brouter-1.7.10-ro.jar` (BRouter 1.7.10 +
  the per-link override hook).
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
| `PdokGeocoder.kt`, `AddressSearch.kt`, `SearchHistory.kt` | address + place search dialog (PDOK, Photon) |
| `PoiIndex.kt` | offline smart place search (OSM) |
| `Vehicle.kt` | yellow / blue plate / e-bike setting |
| `RoadLayer.kt`, `RoadOverrides.kt` | road access layer; your allowed / forbidden road sections |
| `SpeedModel.kt`, `History.kt`, `StatsActivity.kt` | speed multiplier, stats |
| `DebugBridge.kt` | debug-only remote control used by `tools/dbg.sh` |

Map data © OpenStreetMap contributors. Routing: BRouter. Map tiles: Protomaps.

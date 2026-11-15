# BromNav

Offline delivery navigator for a **yellow-plate bromfiets (45 km/h) in Groningen**, built for a
cheap Android phone (tested on a a low-end MediaTek phone, 4 GB RAM).

- **Legal routing**: an embedded [BRouter](https://github.com/abrensch/brouter) engine with a
  custom profile (`app/src/main/assets/profiles/bromfiets.brf`). It never uses G11/G13 cycle
  paths (`moped=no`), roads with a compulsory parallel bromfietspad (`moped=use_sidepath`),
  motorroads or motorways. It does use G12a fiets/bromfietspaden (`moped=designated`).
- **Multi-stop runs**: start and end at the restaurant (hard-coded in `Config.kt`). 1–6 stops are
  ordered exactly (every permutation), including deadline penalties.
- **Urgency**: a personal speed factor is learned from your finished legs, and GTA-style 0–5
  "wanted" stars show urgency (5 = later than the app ETA allows, 0 = twice the ETA left).
- **Bike-holder UI**: nothing essential sits in the screen corners. Within 200 m of a stop a huge
  house number appears, with an arrow for the side of the road it's on.
- **English voice**: turn instructions, plus *"move onto the moped path"* / *"leave the moped
  path and join the road"*, and which side the house number is on.
- **Manual override**: ▲/▼ in the stop list (📋) or long-press = "next"; switches the run to manual
  order until you tap the header to auto-optimise again.
- **Undo** for an accidental *Delivered*/*Done*, and **cancel (✕)** to disable an order without
  deleting it (↺ restores, also with undo).
- **Stats**: lap times, time per order, average speed, and per-order history.
- **Receipt scanner** (📷): on-device OCR of Dutch and English receipts. A live checklist (address /
  time / order no / payment / customer message) shows each field with a ✓ once it was read 3 times
  identically; the address is also confirmed in BAG. Tap a ⏳ line to accept it by hand, or "Add with
  this…" to fill in the rest yourself. Live colour-coded OCR boxes over the camera image.
- **Customer messages** are translated Dutch → English on the phone (ML Kit, offline after a
  one-time ~30 MB download).
- **ASAP vs exact time**: "(gevr.: ZSM)" = ASAP, deliver by the printed time; "(req.: 17:30)" =
  exact time, never earlier. Planning and ETAs include waiting; arriving later than the
  requested time is penalised. Payment shown as ✅ paid / 💶 CASH / 💳 PIN.
- **GPS**: fused + GPS at high accuracy, Kalman smoothing with outlier rejection, snapping to the
  route; heading-up map with a scooter marker.

## Build & install

```bash
source env.sh                       # JDK 17 + Android SDK paths
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Offline data (not in git, ~270 MB)

```bash
tools/update_data.sh   # download BRouter segment E5_N50.rd5 + Groningen Protomaps extract
tools/push_data.sh     # copy both to the phone (start the app once first)
```

`tools/update_data.sh` needs the BRouter release and the `pmtiles` CLI in `tools/vendor/`
(see the script for URLs).

## Testing the routing profile on the PC

```bash
# local BRouter server on :17777 (data/profiles holds lookups.dat + the bromfiets profile symlink)
cd data && java -cp ../tools/vendor/brouter/brouter-1.7.10/brouter-1.7.10-all.jar \
    btools.server.RouteServer segments profiles profiles 17777 4 &
tools/test_routes.py 1 40      # random + listed trips; fails on any illegal segment
tools/route.sh "6.5734,53.2182|6.5850,53.2250"   # one route with its way tags
```

Add trips where other navigators route you illegally to `CASES` in `tools/test_routes.py`.

## Debug remote control (debug builds)

The app can be driven from the PC with no tapping; replies come back as JSON state:

```bash
tools/dbg.sh add "Vismarkt 10" 1201 18:45   # geocode + add stop with order no + deadline
tools/dbg.sh go          # optimise + start navigation
tools/dbg.sh sim         # fake GPS rides along the route (~25 km/h)
tools/dbg.sh state       # mode, stops, ETAs, stars, route, banner/card text, spoken phrases
tools/dbg.sh delivered | undo | cancel 2 | restore 1 | move 3 1 | auto | end | clear | resetstats
tools/dbg.sh ocr receipts_test/x.jpg [add]     # OCR an image stored in the app's files dir
tools/dbg.sh translate "Graag achterom"        # test the NL->EN translator
tools/ocr_view.py photo.jpg ...                # OCR photos via the phone and show the boxes on the PC
```

## Layout

| Path | What |
|---|---|
| `app/src/main/java/nl/bromnav/MainActivity.kt` | UI, navigation flow, GPS |
| `BRouterEngine.kt`, `Route.kt` | embedded routing + parsing of segments, way tags and voice hints |
| `Navigator.kt`, `VoiceGuide.kt` | route following, off-route detection, spoken guidance |
| `Planner.kt`, `TspSolver.kt` | travel-time matrix + stop ordering |
| `SpeedModel.kt`, `History.kt`, `StatsActivity.kt` | personal speed factor, urgency stars, stats |
| `PdokGeocoder.kt`, `AddressSearch.kt` | Dutch address search + exact postcode lookup (PDOK / BAG) |
| `ScanActivity.kt`, `ReceiptParser.kt`, `ReceiptOcr.kt` | live receipt reader, parser, OCR helper |
| `NoteTranslator.kt` | on-device NL→EN translation of customer messages |
| `GpsFilter.kt` | Kalman smoothing + outlier rejection of location fixes |
| `MapStyle.kt` | offline MapLibre style for the Protomaps tiles |
| `DebugBridge.kt` | debug-only broadcast interface used by `tools/dbg.sh` |
| `tools/` | data download/push, route tests, debug remote, adb UI driver |

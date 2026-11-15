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
- **Stats**: lap times, time per order, average speed, and per-order history.
- Planned: receipt scanning (ML Kit OCR) — see `the project plan`.

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

## Layout

| Path | What |
|---|---|
| `app/src/main/java/nl/bromnav/MainActivity.kt` | UI, navigation flow, GPS |
| `BRouterEngine.kt`, `Route.kt` | embedded routing + parsing of segments, way tags and voice hints |
| `Navigator.kt`, `VoiceGuide.kt` | route following, off-route detection, spoken guidance |
| `Planner.kt`, `TspSolver.kt` | travel-time matrix + stop ordering |
| `SpeedModel.kt`, `History.kt`, `StatsActivity.kt` | personal speed factor, urgency stars, stats |
| `PdokGeocoder.kt` | Dutch address search (PDOK Locatieserver / BAG) |
| `MapStyle.kt` | offline MapLibre style for the Protomaps tiles |
| `tools/` | data download/push, route tests, adb UI driver |

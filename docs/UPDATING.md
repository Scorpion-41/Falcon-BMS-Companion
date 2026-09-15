# Updating for a new Falcon BMS version

A checklist for refreshing the Android app, BMS Companion for Windows and the browser version after a BMS release (4.38 → 4.39 …) or when new add-on theaters are installed. Each step says **where** things live, so the whole file can be handed to Claude Code ("follow docs/UPDATING.md for BMS 4.39 at D:\Falcon BMS 4.39").

> Never modify the BMS install. Every tool here only reads it. Copy files into the repo if something needs editing.

## 0. Prepare
- [ ] Install/patch BMS and the add-on theaters you want included.
- [ ] Note the install path, e.g. `D:/Falcon BMS 4.39`.
- [ ] `git checkout -b bms-4.39`

## 1. Reference data (extractor)
```bash
cd tools/extractor && npm install
BMS_ROOT="D:/Falcon BMS 4.39" node src/main.mjs
BMS_ROOT="D:/Falcon BMS 4.39" node src/charts.mjs
BMS_ROOT="D:/Falcon BMS 4.39" node src/maps.mjs   # only if terrain changed; ~30 min
BMS_ROOT="D:/Falcon BMS 4.39" node src/geo.mjs
```
- [ ] Read the extractor log for warnings (missing files, unparsed theaters, 0-count sections).
- [ ] `git diff --stat app/src/main/assets/data` should show sensible changes. Spot-check `index.json` (theaters, `primary`, `mainTheater`) and the aircraft and airport counts.
- [ ] Maps (`maps.mjs`, `geo.mjs`): only needed when a theater's terrain changed or a theater with its own terrain was added.
  - New theater: add it to `MAP_SOURCES` in `maps.mjs` (id + terrain folder); `main.mjs` sets `mapId` in `index.json` from the terrain folder name.
  - `maps.mjs` reads `NewTerrain/HeightMaps/HeightMap.raw` (int16 feet, row 0 = north; water = flat areas at or below 0, see `heightmap.mjs`, because land can lie below sea level) and `NewTerrain/Photoreal/GlobalColorMap.dds` (decoded by EZBoards' `texconv.exe` into a temp folder). `MAX_Z` must match `MAX_Z` in `app/.../ui/components/MapBase.kt`.
  - `geo.mjs` needs the Natural Earth 10m GeoJSON files in `tools/extractor/cache/ne` (`ne_10m_admin_0_boundary_lines_land`, `ne_10m_admin_0_countries`, `ne_10m_admin_1_states_provinces`, `ne_10m_admin_1_states_provinces_lines`; public domain, from naturalearthdata.com). It projects them with `NewTerrain/Theater.txt` (`projection.mjs`) and drops province lines over the sea using the heightmap.
  - Checks: `node src/geocheck.mjs` compares BMS airfields with real airport positions (needs OurAirports `airports.csv` in `cache/`; the median should stay under ~1 nm), `node src/cmcheck.mjs` compares the satellite texture with the heightmap. Then look at every style: `./gradlew :desktop:run --args="--maprender <theater id> <folder>"` renders all styles at three zoom levels; coastlines, borders and towns must line up in each.
- [ ] File formats changed? The parsers are split by domain: `db.mjs` (objects DB), `catalog.mjs` (aircraft/weapons/racks), `airports.mjs` (CampObjData, Stations+Ils, ATC, PHD/PDX/FED), `theaters.mjs`, `terrain.mjs`, `images.mjs`, `charts.mjs`.
- [ ] Update the `bmsVersion` default in `tools/extractor/src/util.mjs` (`BMS_ROOT`).

## 2. Manual-derived data (`tools/curated/*.json`)
Only needed when the manuals changed (Threat Guide, Dash-1/-34 HOTAS, checklists, comms, HARM/RWR).
- [ ] Extract text with `pdftotext -layout "<BMS>/Docs/00 BMS Manuals/BMS-Threat-Guide.pdf" tools/pdftext/BMS-Threat-Guide.txt` (tools/pdftext is git-ignored because the manuals are copyrighted).
- [ ] Diff against the previous dump and update the JSON by hand or with Claude. Keep the schemas (see `data/Models.kt`).
- [ ] Re-run `node src/main.mjs` (it copies curated JSON into assets).

## 3. Reading BMS: shared memory
File: `desktop/src/main/kotlin/com/bmscompanion/desktop/bridge/SharedMemory.kt` (objects `FD`, `FD2`, `StringId`), from `<BMS>/Tools/SharedMem/FlightData.h`.
- [ ] Diff the new `FlightData.h` against the previous one. BMS appends fields at the **end** of `FlightData`, `FlightData2` and the `StringIdentifier` enum. Add the same trailing fields in the same order and types (`float` → `l.f()`, `int`/`unsigned long` → `l.i()`, `short` → `l.s()`, `char`/`unsigned char` → `l.b()`, arrays with a count).
- [ ] If a field was inserted in the middle (rare), re-check everything after it.
- [ ] Update the version comment (`FlightData v118, FlightData2 v23, StringData v5`).
- [ ] New StringData ids go in `StringId` (constants for the ids the code reads, and the `names` list used by `--dumpstrings`).
- [ ] RWR symbol ids: compare `Tools/RwrEmulator/Source/ScopeRenderer.cs` with `rwrText()`/`rwrName()` in `app/.../ui/screens/mission/MissionUtil.kt`.
- [ ] Run `"BMS Companion.exe" --dumpstrings out.txt` (or `./gradlew :desktop:run --args="--dumpstrings out.txt"`) with BMS running and check each StringData id maps to the right value (theater name, aircraft, briefings dir, VoiceHelpers). Fix `StringId` if they shifted.
- [ ] `--selftest out.txt` prints the struct sizes (4.38.1: FlightData 1920, FlightData2 1284 bytes). In 3D, open `http://127.0.0.1:47474/api/live`: altitude, heading, fuel and the DED text must look right.

## 4. Reading BMS: briefing, DTC, registry, EZBoards
Files in `desktop/src/main/kotlin/com/bmscompanion/desktop/bridge/`.
- [ ] Print a briefing in the new version and compare `User/Briefings/briefing.txt` with `desktop/src/main/resources/bridge/demo_briefing.txt`. Section titles are listed in `titles` in `BmsFiles.kt → BriefingParser`. The raw `sections[]` keep the app usable even if a parser misses something. Keep the demo file CRLF.
- [ ] Save a DTC and compare `User/Config/<callsign>.ini` keys with `BmsFiles.kt → DtcParser`.
- [ ] `--api /api/info,/api/mission out.txt` writes the API responses from BMS on this PC: check the briefing, DTC and board sections.
- [ ] Registry: `BmsInstall.kt` already picks the newest `Benchmark Sims\Falcon BMS 4.xx` key, so no change is usually needed.
- [ ] Config variable names mentioned in the guides (`g_bTacviewRealTime`, `g_nPrintToFile`, `g_bBriefHTML`, `g_sBriefingsDirectory`, `g_sPicturesDirectory`): check the new Technical Manual and update `docs/SETUP.md`, the app's `MissionSetup.kt`, its PC copy `desktop/src/main/kotlin/overrides/ui/screens/mission/MissionSetup.kt`, the browser copy `web/src/wasmJsMain/kotlin/overrides/ui/screens/mission/MissionSetup.kt`, and the checklist in `desktop/.../ui/PcCards.kt → SetupChecklistCard`.
- [ ] EZBoards: a new version may change its `bin\xbrief.exe` HTML. Check that the Boards tab still renders (`EzBoards.kt → parseHtml`). Test with `--eztest <copy of EZBoards folder> out.txt`, using a copy whose `CONFIG_USER.BAT` has the `SET KNEEBOARD[...]` lines removed so no textures are written.
- [ ] Tacview stream: if other aircraft stop showing, check the ACMI `T=` field layout in `TacviewClient.kt → applyTransform`.
- [ ] Screenshot folder variable (`g_sPicturesDirectory`) or format (`g_bPngScreenshots`) changed? Check `BmsInstall.kt → picturesDir` and `Screenshots.kt`.

## 5. PC version (`desktop/`) and browser version (`web/`)
Both compile `app/src/main/java` directly, so reference data and most screen changes need no extra work. Check:
- [ ] `./gradlew :desktop:compileKotlin :web:compileKotlinWasmJs` still passes.
  - A new Android-only API in shared code breaks both: replace it with common Compose code, or add a stand-in under `desktop/src/main/kotlin/shims/` and `web/src/wasmJsMain/kotlin/shims/`.
  - A new JVM-only API (e.g. `java.time`, a new `String.format` pattern, `Math.xyz`) breaks only the browser version: prefer `kotlin.*` in shared code, or extend `web/.../shims/jvmapis` (one file per package) and `com/bmscompanion/web/Printf.kt`.
- [ ] If one of the files excluded in `desktop/build.gradle.kts` / `web/build.gradle.kts` changed in the app (`Repo.kt`, `MissionLink.kt`, `TheaterMap.kt`, `Charts.kt`, `MissionScreen.kt`, `MissionSetup.kt`, `MainActivity.kt`), port the change to its copies in `desktop/src/main/kotlin/overrides/` and `web/src/wasmJsMain/kotlin/overrides/`. `git diff <last release tag> -- <file>` shows what changed.
- [ ] Setup text changed in the app's `MissionSetup.kt`? Mirror it in the PC and browser `MissionSetup.kt`.
- [ ] Compose Multiplatform upgraded? Check the browser version on a phone: text input (keyboard), pinch zoom, and that `index.html` still loads `bmsc.js`.

## 6. Versions & release
- [ ] `app/build.gradle.kts`: bump `versionCode`, and `versionName = "x.y (BMS 4.39)"`.
- [ ] `desktop/build.gradle.kts`: `pcVersion = "x.y.0"` (numbers only; the MSI needs it to increase for upgrades; it is also the version the PC reports in `/api/info` and the browser version's cache key).
- [ ] Bump `api` in `Bridge.kt → info()` and the discovery reply **only** for breaking JSON changes (and handle it in the app).
- [ ] Update counts and version mentions in `README.md`, and add the version to `CHANGELOG.md`.
- [ ] Build:
  ```bash
  ./gradlew assembleRelease
  powershell -ExecutionPolicy Bypass -File pc/publish-desktop.ps1   # builds the browser version too
  ```
  Copy the APK as `dist/BMS-Companion.apk`. The PC script writes `dist/BMS-Companion-PC.msi` and `dist/BMS-Companion-PC.zip`.
- [ ] Smoke test: BMS Companion with Demo mode (or env `BMSC_DEMO=1`) + the Android app on a phone and a tablet (all Mission tabs), then a real flight.
- [ ] PC smoke test: install the MSI. Server page (checklist, QR code, settings), **Open the full app** and back with **Server**, F11 full screen and back, start it a second time (the first window comes forward), close to tray and reopen, a client on a second machine (**On another PC**). Check the Dashboard (Edit, presets), the Map menu (each style, borders, towns, zoom in close), Tankers & support, the AWACS page, Media (view, download on a client, delete a test screenshot), map wheel-zoom, a chart, and the Home screen at a narrow and a wide window.
- [ ] Browser smoke test: open the address on a phone (portrait and landscape): tabs, search with the phone keyboard, pinch the map, the Mission tab with live data, download and share a screenshot.
- [ ] Privacy check before pushing: `git grep -n -i "users\\\\\|c:/users\|<your windows user>\|<your callsign>"` should return nothing. `local.properties` and `dist/` are git-ignored.
- [ ] Refresh `docs/screenshots` if the UI changed (env `BMSC_DEMO=1` on the PC, debug build, `adb shell am start ... --es route mission`, `?route=mission` in a browser).
- [ ] Tag and upload `BMS-Companion.apk`, `BMS-Companion-PC.msi` and `BMS-Companion-PC.zip` to a GitHub Release, with the `CHANGELOG.md` section as release notes.

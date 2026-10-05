# Updating for a new Falcon BMS version

A checklist for refreshing the Android app, BMS Companion for Windows and the browser version after a BMS release (4.38 → 4.39 …) or when new add-on theaters are installed. Each step says **where** things live, so the whole file can be handed to Claude Code ("follow docs/UPDATING.md for BMS 4.39 at D:\Falcon BMS 4.39").

> Never modify the BMS install. Every tool here only reads it, and every developer check that writes runs against a copy. Copy files into the repo if something needs editing.

## 0. Prepare
- [ ] Install/patch BMS and the add-on theaters you want included.
- [ ] Note the install path, e.g. `D:/Falcon BMS 4.39`.
- [ ] `git checkout -b bms-4.39`

## 1. Reference data (extractor)
```bash
cd tools/extractor && npm install
BMS_ROOT="D:/Falcon BMS 4.39" node src/main.mjs
BMS_ROOT="D:/Falcon BMS 4.39" node src/charts.mjs   # plates + instrument chart PDFs (needs cache/pdfbox-app.jar)
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
- [ ] The Planner's latitude/longitude: `projection` in `index.json` (theater size, centre and `heightmapBytes` of the terrain BMS flies each theater on, written by `projection.mjs` from `main.mjs`, or on its own with `node src/projection.mjs`; WDP's own lookup is `wdpTerrain`, from `wdpterrain.mjs`). A new theater gets it automatically. Check it against BMS itself: fly a few minutes with ACMI recording on and run `--acmicoords <the recording> out.txt` — the theater must PASS (every position within 2 m); a theater that is not 1,024 km prints the projection string until a recording shows what BMS uses there. `--wdppagetest coords` against a fresh `wdpref page Coords` reference if WDP is at hand.
- [ ] **The Planner's data, every theater**: `node src/plannercheck.mjs` must print `PASS`. It fails when any theater's `planner.missing` in `index.json` is not empty (the extractor's own list of what it could not build: `airports`, `radio`, `airfields`, `map`, `projection`, `ppt`), when a file a theater names (`airportSet`, `radioSet`, `airfieldSet`, `pptSet`) is not in the assets, when a projection does not put the theater's own centre in the middle of its square, when an airport lies outside its theater, or when the install's `theater.lst` has a theater `index.json` does not. `index.json` also records `bmsBuild`, the version of `Bin/x64/Falcon BMS.exe` the data was read from (4.38.1.3315 for 4.38.1): check it names the build you meant.
- [ ] **A new theater** (a new add-on in `theater.lst`): re-run `main.mjs` and `plannercheck.mjs` — **no code change**. The app, the Planner included, takes everything theater-specific from `index.json` and the files it names: its own airport and radio set (written for every theater, add-ons too), its ground charts, its terrain's figures (`NewTerrain/Theater.txt` and the heightmap's length), its PPT types (`Campaign/Ppt.ini` in the definition's `campaigndir`). If the theater brings its **own terrain**, the map also needs `maps.mjs`/`geo.mjs` (below), and `plannercheck` says so (`map`). Only a `planner.missing` entry that a re-run cannot fill means code: read the extractor's warning for that theater first.
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
- [ ] Print a briefing in the new version and compare `User/Briefings/briefing.txt` with `desktop/src/main/resources/bridge/sample_briefing.txt` (the parser's fixture, with the names taken out — `--selftest` reads it). Section titles are listed in `titles` in `BmsFiles.kt → BriefingParser`. The raw `sections[]` keep the app usable even if a parser misses something. Keep the file CRLF.
- [ ] Save a DTC and compare `User/Config/<callsign>.ini` keys with `BmsFiles.kt → DtcParser`.
- [ ] `--api /api/info,/api/mission out.txt` writes the API responses from BMS on this PC: check the briefing, DTC and board sections.
- [ ] Registry: `BmsInstall.kt` already picks the newest `Benchmark Sims\Falcon BMS 4.xx` key, so no change is usually needed.
- [ ] Config variable names mentioned in the guides (`g_bTacviewRealTime`, `g_nPrintToFile`, `g_bBriefHTML`, `g_sBriefingsDirectory`, `g_sPicturesDirectory`): check the new Technical Manual and update `docs/SETUP.md`, the app's `MissionSetup.kt`, its PC copy `desktop/src/main/kotlin/overrides/ui/screens/mission/MissionSetup.kt`, the browser copy `web/src/wasmJsMain/kotlin/overrides/ui/screens/mission/MissionSetup.kt`, and the checklist in `desktop/.../ui/PcCards.kt → SetupChecklistCard`.
- [ ] EZBoards: a new version may change its `bin\xbrief.exe` HTML. Check that the Kneeboards page's EZBoards tables still render (`EzBoards.kt → parseHtml`). Test with `--eztest <copy of EZBoards folder> out.txt`, using a copy whose `CONFIG_USER.BAT` has the `SET KNEEBOARD[...]` lines removed so no textures are written.
- [ ] Tacview stream: if other aircraft stop showing, check the ACMI `T=` field layout in `TacviewClient.kt → applyTransform`.
- [ ] Screenshot folder variable (`g_sPicturesDirectory`) or format (`g_bPngScreenshots`) changed? Check `BmsInstall.kt → picturesDir` and `Screenshots.kt`.

## 4b. Reading BMS: theaters, campaign saves, kneeboard pages (the Planner and WDP mode)
Files in `desktop/src/main/kotlin/com/bmscompanion/desktop/bridge/`; `docs/DATA-SOURCES.md` → *The Planner's mission* says what each reads. Run every check below against a **copy** of the BMS folder (`cp -rp`, so the file times survive), from a copy of the jar, with a scratch `APPDATA` whose `BmsDirOverride` points at the copy: while a check runs the program refuses to write into a real install.
- [ ] Theater definitions (`Data/TerrData/TheaterDefinition/theater.lst` + `.tdf`): `--theatertest out.txt <copy>`. A new theater or a moved campaign folder shows here first; `Theaters.kt` needs no change for one that follows the same layout.
- [ ] Planned tracks and the team table: `--camsource out.txt <copy>` (the save, class table and names per theater).
- [ ] Campaign saves: `--camtest out.txt <copy> --any-install`; every [2]-[8] line must pass. A walk that goes inexact means a new save version (`.ver`; 107-110 in 4.38.1): add the branch in `CampaignArchive.kt` from WDP's newest `BMSUtils.dll`. Print a briefing and save the campaign on the same flight first, so item 10 compares the save's briefing texts (`CampaignBriefing.kt`, from BMS's `Data/Campaign/*.b` scripts and `Strings.txt`) with a fresh printed one.
- [ ] BMS's route before 3D: `--plantest route <copy> out.txt`, and `--plantest parse <copy> out.txt` for the DTC keys.
- [ ] A TE's own mission file: `--tesavetest <copy of Data/Campaign> out.txt`; check that the new version's campaign starts are still recognised (`CampaignStarts` in `CartridgeStore.kt`: an `.obj` part, no flights, `Save<n>`, `Te_New*`, `Instant`) and greyed in Open mission.
- [ ] The cockpit kneeboard pages (`KoreaObj/7982-7997.dds` in each theater's 3D data): `--ddstest out.txt <copy>` (a new texture layout shows as an unreadable or unwritable line) and `--kbprinttest <copy> out.txt` (section 1: BMS's shipped copies still found in its `Docs` folders).
- [ ] The two modes: `--missiontest <copy> <scratch APPDATA> out.txt` (switching, Populate from Planner, the snapshot, the clearing at a new mission and a switch, the one mission picture, the same briefing in both modes) and `--contracttest out.txt <copy>` (every route's answer, DevGuard included).
- [ ] Parking numbers: after `airfieldrun.mjs`, `node src/apcverify.mjs` must stay clean, and `--taxirender <folder>` must still find Gunsan's landed-on-36 stands at 04 and 28. If BMS changes how Ground numbers spots (`docs/PARKING.md`), `numberParking` in `airfields.mjs` is the place.
- [ ] The MFD glass: `--rttselftest` and, with BMS running and the export on, `--rtttest`; `--mfdkeystest out.txt <copy>` checks the rocker callbacks against the pilot's key file. A changed texture-area header shows as the glass's "cannot read the picture" state.

## 5. PC version (`desktop/`) and browser version (`web/`)
Both compile `app/src/main/java` directly, so reference data and most screen changes need no extra work. Check:
- [ ] `./gradlew :desktop:compileKotlin :web:compileKotlinWasmJs` still passes.
  - A new Android-only API in shared code breaks both: replace it with common Compose code, or add a stand-in under `desktop/src/main/kotlin/shims/` and `web/src/wasmJsMain/kotlin/shims/`.
  - A new JVM-only API (e.g. `java.time`, a new `String.format` pattern, `Math.xyz`) breaks only the browser version: prefer `kotlin.*` in shared code, or extend `web/.../shims/jvmapis` (one file per package) and `com/bmscompanion/web/Printf.kt`.
- [ ] If one of the files excluded in `desktop/build.gradle.kts` / `web/build.gradle.kts` changed in the app (`Repo.kt`, `MissionLink.kt`, `TheaterMap.kt`, `Charts.kt`, `MissionScreen.kt`, `MissionSetup.kt`, `MainActivity.kt`), port the change to its copies in `desktop/src/main/kotlin/overrides/` and `web/src/wasmJsMain/kotlin/overrides/`. `git diff <last release tag> -- <file>` shows what changed.
- [ ] Setup text changed in the app's `MissionSetup.kt`? Mirror it in the PC and browser `MissionSetup.kt`.
- [ ] Compose Multiplatform upgraded? Check the browser version on a phone: text input (keyboard), pinch zoom, and that `index.html` still loads `bmsc.js`.

## 6. Versions & release
- [ ] Version: bump `NAME` in `app/src/main/java/com/bmscompanion/app/AppVersion.kt` (and `BMS` for a new BMS release) plus `versionCode` in `app/build.gradle.kts`. Nothing else: the APK's `versionName`, the PC's `pcVersion` (numbers only; the MSI needs it to increase for upgrades; it is also the version the PC reports in `/api/info` and the browser version's cache key) and the About page all read that one file.
- [ ] Bump `api` in `Bridge.kt → info()` and the discovery reply **only** for breaking JSON changes (and handle it in the app).
- [ ] Update counts and version mentions in `README.md`, and add the version to `CHANGELOG.md`.
- [ ] Build:
  ```bash
  ./gradlew assembleRelease
  powershell -ExecutionPolicy Bypass -File pc/publish-desktop.ps1   # builds the browser version too
  ```
  Copy the APK as `dist/BMS-Companion.apk`. The PC script writes `dist/BMS-Companion-PC.msi` and `dist/BMS-Companion-PC.zip`.
- [ ] Smoke test: BMS Companion with Falcon BMS running + the Android app on a phone and a tablet (all Mission tabs), then a real flight.
- [ ] PC smoke test: install the MSI. Server page (checklist, QR code, settings), **Open the full app** and back with **Server**, F11 full screen and back, start it a second time (the first window comes forward), close to tray and reopen, a client on a second machine (**On another PC**). Check the Dashboard (its five pages, Customize Dashboard, presets), the MFDs card with the Launcher's Export RTT Textures on, the mode switch (EZBoards → WDP → Populate from Planner → back), the Map menu (each style, borders, towns, zoom in close), Tankers & support, the AWACS page, Media (view, download on a client, delete a test screenshot), map wheel-zoom, a chart, and the Home screen at a narrow and a wide window.
- [ ] Browser smoke test: open the address on a phone (portrait and landscape): tabs, search with the phone keyboard, pinch the map, the Mission tab with live data, download and share a screenshot.
- [ ] Instrument charts: `charts.mjs` renders the chart PDFs in each airport folder with Apache PDFBox — put `pdfbox-app-3.x.jar` in `tools/extractor/cache/pdfbox-app.jar` first (without it the PDFs are skipped and only the plates are written). Only folders that also hold a BMS plate count as an airport, and PDFs over 60 MB are skipped (national AIP volumes). Pages that are "INTENTIONALLY LEFT BLANK" are dropped, and pages printed sideways (a landscape table inside an upright frame) are turned upright: `PageDirs.java` asks PDFBox for the angle of the glyphs in the **middle** of each page (the frame around it is always upright and outvotes a small table), and a page that is drawn rather than typeset — no text to ask about — is judged from its image by `pageSideways` in `charts.mjs`. Check the log line per PDF ("N blank pages skipped, M turned upright") and open one turned page. Card titles come from the file name (`titleFromName`), falling back to the PDF's metadata title; `node tools/extractor/src/charts.mjs` prints nothing about them, so spot-check an airfield with many one-page approach charts (Osan).
- [ ] Installer: `packageMsi` finishes by running `pc/finish-msi.ps1`, which puts the `desktop/installer` bitmaps into the MSI and replaces jpackage's product code with the package's own. Check its three lines appear (two bitmap sizes, one product code). Regenerate the bitmaps with `node pc/make-installer-art.mjs` only when the artwork changes.
- [ ] Install the MSI on a machine that already has the previous version: the running app is closed for you, the old version disappears from Installed apps (one entry, not two), and every setting in `%APPDATA%\BMS Companion` is kept (only the old copy's lock, port and update cache are cleared). `finish-msi.ps1` prints the three sequence positions and fails the build if they are wrong.
- [ ] APK compatibility: `powershell -ExecutionPolicy Bypass -File tools\check-apk-compat.ps1 dist\BMS-Companion.apk` must pass. It catches Java 21 collection methods (`reversed()`, `getFirst()`, `removeFirst()` …) that compile against API 35 but crash on older Android; lint does not flag them.
- [ ] Privacy check before pushing: `git grep -n -i "users\\\\\|c:/users\|<your windows user>\|<your callsign>"` should return nothing. `local.properties` and `dist/` are git-ignored.
- [ ] Refresh `docs/screenshots` if the UI changed (debug build, `adb shell am start ... --es route mission`, `?route=mission` in a browser). Keep the callsign and the LAN address out of frame.
- [ ] Tag and upload `BMS-Companion.apk`, `BMS-Companion-PC.msi` and `BMS-Companion-PC.zip` to a GitHub Release, with the `CHANGELOG.md` section as release notes.

# Updating for a new Falcon BMS version

A checklist for refreshing the app and bridge after a BMS release (4.38 → 4.39 …) or when new add-on theaters are installed. Each step says **where** things live, so the whole file can be handed to Claude Code ("follow docs/UPDATING.md for BMS 4.39 at D:\Falcon BMS 4.39").

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
```
- [ ] Read the extractor log for warnings (missing files, unparsed theaters, 0-count sections).
- [ ] `git diff --stat app/src/main/assets/data` should show sensible changes. Spot-check `index.json` (theaters, `primary`, `mainTheater`) and the aircraft and airport counts.
- [ ] New theater with its own terrain: check the rendered map in `app/src/main/assets/maps/` lines up with the airfields (open Airfields → Map). If it doesn't (as with Israel), add an affine entry to `TERRAIN_WARP` in `tools/extractor/src/main.mjs`.
- [ ] File formats changed? The parsers are split by domain: `db.mjs` (objects DB), `catalog.mjs` (aircraft/weapons/racks), `airports.mjs` (CampObjData, Stations+Ils, ATC, PHD/PDX/FED), `theaters.mjs`, `terrain.mjs`, `images.mjs`, `charts.mjs`.
- [ ] Update the `bmsVersion` default in `tools/extractor/src/util.mjs` (`BMS_ROOT`).

## 2. Manual-derived data (`tools/curated/*.json`)
Only needed when the manuals changed (Threat Guide, Dash-1/-34 HOTAS, checklists, comms, HARM/RWR).
- [ ] Extract text with `pdftotext -layout "<BMS>/Docs/00 BMS Manuals/BMS-Threat-Guide.pdf" tools/pdftext/BMS-Threat-Guide.txt` (tools/pdftext is git-ignored because the manuals are copyrighted).
- [ ] Diff against the previous dump and update the JSON by hand or with Claude. Keep the schemas (see `data/Models.kt`).
- [ ] Re-run `node src/main.mjs` (it copies curated JSON into assets).

## 3. Bridge: shared memory
File: `pc/BmsCompanionBridge/Bms/SharedMemoryLayout.cs`, from `<BMS>/Tools/SharedMem/FlightData.h`.
- [ ] Diff the new `FlightData.h` against the previous one. BMS appends fields at the **end** of `FlightData`, `FlightData2` and the `StringIdentifier` enum. Add the same trailing fields in the same order and types (`unsigned long` = 4 bytes, `char[n][m]` → `fixed byte[n*m]`).
- [ ] If a field was inserted in the middle (rare), re-check everything after it.
- [ ] Update the version comments (`FlightData v118`, `FlightData2 v23`, `StringData v5`).
- [ ] New `StringId` values go before `StringIdentifier_DIM` in the enum.
- [ ] RWR symbol ids: compare `Tools/RwrEmulator/Source/ScopeRenderer.cs` with `rwrText()`/`rwrName()` in `app/.../ui/screens/mission/MissionUtil.kt`.
- [ ] Run `BMSCompanionBridge.exe --dumpstrings out.txt` with BMS running and check each StringData id maps to the right value (theater name, aircraft, briefings dir, VoiceHelpers). Fix the `StringId` enum if they shifted.
- [ ] Verify in 3D: bridge status page `http://127.0.0.1:47474/api/live`. Altitude, heading, fuel and the DED text must look right. `BMSCompanionBridge.exe --selftest out.txt` prints struct sizes.

## 4. Bridge: briefing, DTC, registry, EZBoards
- [ ] Print a briefing in the new version and compare `User/Briefings/briefing.txt` with `pc/BmsCompanionBridge/Resources/demo_briefing.txt`. Section titles are listed in `SectionTitles` in `Bms/BriefingParser.cs`. The raw `sections[]` keep the app usable even if a parser misses something. Keep the demo file CRLF.
- [ ] Save a DTC and compare `User/Config/<callsign>.ini` keys with `Bms/DtcParser.cs`.
- [ ] Registry: `Bms/BmsInstall.cs` already picks the newest `Benchmark Sims\Falcon BMS 4.xx` key, so no change is usually needed.
- [ ] Config variable names mentioned in the setup guide (`g_bTacviewRealTime`, `g_nPrintToFile`, `g_bBriefHTML`, `g_sBriefingsDirectory`): check the new Technical Manual and update `docs/SETUP.md` and `MissionSetup.kt`.
- [ ] EZBoards: a new version may change its `bin\xbrief.exe` HTML. Check that the Boards tab still renders (`EzBoards/EzBoardsRunner.cs → ParseHtml`). Test with `--eztest <copy of EZBoards folder> out.txt`, using a copy whose `CONFIG_USER.BAT` has the `SET KNEEBOARD[...]` lines removed so no textures are written.
- [ ] Tacview stream: if other aircraft stop showing, check the ACMI `T=` field layout in `Bms/TacviewClient.cs → ApplyTransform`.

## 5. Versions & release
- [ ] `app/build.gradle.kts`: bump `versionCode`, and `versionName = "x.y (BMS 4.39)"`.
- [ ] `pc/BmsCompanionBridge/BmsCompanionBridge.csproj`: `<Version>`. Bump `Api.Version` in `ApiModels.cs` **only** for breaking JSON changes (and handle it in the app).
- [ ] Update counts and version mentions in `README.md` and `docs/REDDIT_POST.md`.
- [ ] Build:
  ```bash
  ./gradlew assembleRelease
  powershell -ExecutionPolicy Bypass -File pc/publish.ps1
  ```
  Copy the APK as `dist/BMS-Companion.apk`.
- [ ] Smoke test: bridge `--demo` + app on a phone and a tablet (all six Mission tabs), then a real flight.
- [ ] Privacy check before pushing: `git grep -n -i "users\\\\\|c:/users\|<your windows user>\|<your callsign>"` should return nothing. `local.properties` and `dist/` are git-ignored.
- [ ] Refresh `docs/screenshots` if the UI changed (bridge `--demo`, debug build, `adb shell am start ... --es route mission`).
- [ ] Tag and upload `BMS-Companion.apk` and `BMSCompanionBridge.exe` to a GitHub Release.

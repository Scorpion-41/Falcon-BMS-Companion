# Falcon BMS Companion: notes for AI-assisted maintenance

Three deliverables live in this repo, all built from the same Kotlin/Compose screens in `app/src/main/java`:
1. **Android app** `app/`: Kotlin + Jetpack Compose + Material 3, package `com.bmscompanion.app`, a single APK (charts and every map style/zoom level included).
2. **BMS Companion for Windows** `desktop/`: Compose for Desktop. It reads Falcon BMS (the former C# bridge, ported to Kotlin in `desktop/.../bridge/`), serves the API, the browser version and the bundled data on one port (47474), and is the full app. One window: the light **server page** or the **full app**. Packaged as MSI + portable zip with its own Java runtime.
3. **Browser version** `web/`: Kotlin/Wasm + Compose for Web. The same app running in the browser (iPhone, iPad, any browser); served by the PC program from its resources (`/webapp`).

## Ground rules
- **The BMS install is read-only.** Never write, move or delete anything in the BMS folder, including during testing. To test EZBoards, copy its folder to a temp dir and strip the `SET KNEEBOARD[...]` lines from the copy's `CONFIG_USER.BAT`.
- **No personal data in the repo** (Windows user names, callsigns, absolute user paths, LAN addresses in screenshots). `local.properties`, `dist/`, `posts/` and `tools/pdftext/` are git-ignored. The demo briefing in `desktop/src/main/resources/bridge` is sanitized. Screenshots come from demo mode (env `BMSC_DEMO=1`).
- **Phone layouts must stay usable.** Tablet-specific layouts use `isWide()` (≥840dp) / `isMedium()` (≥600dp), `AdaptiveSplit` and `Masonry` from `ui/components/Adaptive.kt`. The PC window and the browser page feed their width into the same checks.
- **Shared app code must also compile for the PC and the browser.** After changing `app/src/main/java`, run `./gradlew :desktop:compileKotlin :web:compileKotlinWasmJs`. Don't add Android-only APIs to shared screens (stand-ins: `desktop/src/main/kotlin/shims/`, `web/src/wasmJsMain/kotlin/shims/`). Prefer `kotlin.*` over JVM APIs; the browser version only has stand-ins for `String.format` (patterns in `web/.../Printf.kt`), `Math`, `System`, `Locale` casing, `SimpleDateFormat`/`Date`, `synchronized` (per package in `web/.../shims/jvmapis/`).
- **JSON contract changes are additive.** The server serializes the app's `app/.../data/mission/MissionModels.kt` classes directly; update the producing code in `desktop/.../bridge/` and `docs/PROTOCOL.md`.

## Where things are
| Topic | Files |
|---|---|
| Navigation, tabs, routes (`m/...` = pages opened from Mission, owned by the Mission tab); `AppRoot(startRoute, nav)` | `app/.../ui/AppRoot.kt` |
| Bundled data loading, prefs | `app/.../data/Repo.kt`, `data/Models.kt` (PC: `desktop/.../overrides/data/Repo.kt`; browser: `web/.../overrides/data/Repo.kt`, data over HTTP, prefs in localStorage) |
| Theater map widget (projection x=north ft, y=east ft) | `app/.../ui/components/TheaterMap.kt` (PC and browser: `desktop/.../overrides/ui/components/TheaterMap.kt`) |
| Map styles, tile levels, landmarks (borders, provinces, labels, towns), the **Map** menu (`MapLook` prefs), mission towns (`MapFocus`/`MapMission`, set by `PublishMapMission` in `MissionTabs.kt`; `GeoPaths.relevant`) | `app/.../ui/components/MapBase.kt`; data `assets/maps/<mapId>/<style>.webp` + `<style>/<z>/<r>_<c>.webp`, `assets/data/geo/<mapId>.json`; made by `tools/extractor/src/maps.mjs`, `geo.mjs`, `projection.mjs` |
| Tankers & support (TACAN, UHF, location) | `app/.../ui/screens/mission/MissionSupport.kt` (Dashboard card `SUPPORT`, Briefing, Comms) |
| Mission client (polling while screen shown, UDP discovery) | `app/.../data/mission/MissionLink.kt`; PC `desktop/.../overrides/data/mission/MissionLink.kt` (`LinkMode.LOCAL` calls `Bridge.handle` in-process); browser `web/.../overrides/data/mission/MissionLink.kt` (same-origin `/api`) |
| Mission UI | `app/.../ui/screens/mission/*.kt`: tabs and shared plumbing in `MissionTabs.kt` (Dashboard / Map / AWACS / Flight / Briefing / Comms / Boards / Setup); `MissionDashboard.kt` (cards, span masonry, saved layouts), `MissionAwacs.kt` + `AwacsTools.kt` (GCI geometry and calls) |
| Media (BMS screenshots) | `app/.../ui/screens/Media.kt` (grid, viewer), platform share/download actions via `data/ImageActions.kt` (`Platform.imageActions`, `LocalImageActions`; PC `desktop/.../PcImageActions.kt`, browser `web/.../Main.kt`); server side `desktop/.../bridge/Screenshots.kt` (thumbnails, Recycle-Bin delete) |
| Reading BMS: shared memory struct offsets and reader | `desktop/.../bridge/SharedMemory.kt` (mirrors `Tools/SharedMem/FlightData.h`; JNA) |
| Briefing / DTC parsers | `desktop/.../bridge/BmsFiles.kt` |
| AWACS picture | `desktop/.../bridge/TacviewClient.kt` (Tacview real-time telemetry, port 42674) |
| EZBoards | `desktop/.../bridge/EzBoards.kt` (hidden `cmd /c EZBOARDS.BAT companion`; board tables via `bin\xbrief.exe`) |
| API routes, file watching, demo mode, settings (`bridge-settings.json`) | `desktop/.../bridge/Bridge.kt`, `DemoSource.kt`, `BridgeSettings.kt` |
| HTTP server (API, forwarding for clients, browser version, `/assets`), discovery | `desktop/.../PcServer.kt`, `desktop/.../bridge/Discovery.kt` |
| Window (server page / full app, F11 borderless full screen), tray, single instance | `desktop/.../Main.kt`, `SingleInstance.kt`, `DarkTitleBar.kt` |
| Mode (server page / app), where BMS runs, browser access, services | `desktop/.../PcConfig.kt` (`PcConfig`, `PcServices`) |
| Server page and settings cards (status, connect devices + QR, setup checklist, BMS settings, activity) | `desktop/.../ui/PcScreens.kt`, `ui/PcCards.kt` |
| PC replacements of Android-only files | `desktop/src/main/kotlin/overrides/` (Repo, MissionLink, TheaterMap + wheel zoom, Charts + wheel zoom, MissionScreen with pin/full screen/server buttons, MissionSetup); excluded from the app sources in `desktop/build.gradle.kts` |
| Browser version entry, JS bridges (fetch, storage, download, share), page | `web/.../com/bmscompanion/web/Main.kt`, `Browser.kt`, `web/src/wasmJsMain/resources/index.html` (WasmGC check, splash) |
| Developer checks (`--selftest`, `--dumpstrings`, `--eztest`, `--api`, `--maprender`) | `desktop/.../bridge/SelfTest.kt`, `MapRender.kt` |
| Reference data extraction | `tools/extractor/src/*.mjs` (env `BMS_ROOT`) |
| Manual-derived data | `tools/curated/*.json` |

## Common tasks
- **New BMS version:** follow `docs/UPDATING.md` step by step.
- **Build & run on a device:** `./gradlew installDebug`. Debug builds accept `adb shell am start -n com.bmscompanion.app/.MainActivity --es route mission` to open a route directly (useful for screenshots).
- **PC version from source:** `./gradlew :desktop:run` (builds the browser version into its resources first; the first web build downloads Node/Yarn/Binaryen and takes minutes). Env: `BMSC_ROUTE=mission` opens a route, `BMSC_DEMO=1` demo mission for this run, `BMSC_PORT=47490` another port (e.g. when another copy uses 47474), `BMSC_SHOW_ADDRESS=192.168.1.20` + `BMSC_SHOW_PORT=47474` show an example address on the server page (screenshots without a real LAN address). Settings live in `%APPDATA%\BMS Companion\` (`pc-app.properties`, `bridge-settings.json`, `app.lock`).
- **Browser version:** served at `http://<pc>:47474/` by the running PC program (`?route=mission` opens a route). Headless check: `msedge --headless=new --window-size=1180,820 --virtual-time-budget=40000 --screenshot=out.png http://127.0.0.1:47474/?route=mission` (headless windows are at least 500 px wide).
- **Developer checks:** `"BMS Companion.exe" --selftest out.txt` (struct sizes, parser output), `--dumpstrings out.txt` (with BMS running), `--eztest <EZBoards copy> out.txt`, `--api /api/info,/api/mission out.txt`, `--maprender <theater id> <folder> [xFt,yFt]` (all map styles at three zooms as PNGs, headless). From source: `./gradlew :desktop:run --args="--selftest C:/temp/out.txt"` (call gradlew from PowerShell when the path has spaces).
- **Release:** `./gradlew assembleRelease` (copy to `dist/BMS-Companion.apk`) and `powershell -ExecutionPolicy Bypass -File pc/publish-desktop.ps1` (MSI and zip into `dist/`). Release files: APK, MSI, zip. Notes from `CHANGELOG.md`.

## Gotchas
- `String.format("%d", double)` crashes at runtime. Convert with `.toInt()` first.
- `TheaterMap` reads `onTap`/`onUserGesture` via `rememberUpdatedState`. Don't key `pointerInput` on lambdas: live data recomposes 4×/s and would cancel taps.
- `briefing.txt` uses CRLF, and EZBoards' `xbrief.exe` fails on LF-only files.
- BMS RWR `bearing[]` is true bearing in radians. Subtract ownship yaw for the scope.
- StringData ids are verified with `--dumpstrings out.txt` against a running BMS (the header comments skip entries). NavPoint `z` is feet (not tens of feet). The hsiBits `Flying` flag is not set in 4.38.1, so flying detection also uses `pilotsStatus == 3`.
- TACAN has two sources in shared memory: UFC (DED, usually the home base from the DTC) and AUX COMM. `live.tacan` prefers the A/A or Y-band one; both are sent as `tacanUfc`/`tacanAux`.
- Tacview sends ejected crews as `Ground+Air+Light+Human+Parachutist`; they map to kind `crew` before the `Air` check.
- DTC `target_N` is steerpoint N+1. Action `-1` marks a user target steerpoint.
- Some launch environments set `NoDefaultCurrentDirectoryInExePath`, and the EZBoards runner removes it for the child cmd.
- `Falcon BMS.exe` detection uses a Toolhelp snapshot (works when BMS runs elevated); `ProcessHandle` would not see its name.
- PC version: don't use `LifecycleResumeEffect` for polling in PC overrides. The window provides its own always-RESUMED `LifecycleOwner`/`ViewModelStoreOwner` (`Main.kt → WindowOwner`), shared with the `NavHostController` hoisted outside the window, because full screen recreates the window (`key(fullscreen)` with `undecorated`).
- PC version: full screen is a recreated undecorated window sized to the monitor, not `WindowPlacement.Fullscreen` (Java's exclusive full screen minimizes when BMS takes focus).
- Browser version: Kotlin/Wasm `LinkedHashMap` is final (no `removeEldestEntry`); `java.*` stand-ins live in packages `java.util`/`java.text` under `web/.../shims`. JS interop goes through `@JsFun`; converting large downloads byte by byte is fine for thumbnails and charts, avoid it for huge files.
- Browser version needs WasmGC (Safari 18.2+, Chrome/Edge 119+, Firefox 120+); `index.html` shows a message on older browsers.
- Maps: every style, tile level and landmark layer is drawn in the same unit square of the theater (`MapProjection`), so styles always align. The BMS projection is PROJ tmerc from `NewTerrain/Theater.txt` (theater feet = metres × 3.27998; x = north). `HeightMap.raw` is int16 feet. Water is **not** "height ≤ 0": land below sea level (Jordan valley, Arava, Nile delta) has varying heights, while BMS stores each water body as an exactly flat level (Israel: sea -70, Dead Sea -1457, Galilee -771). Use `tools/extractor/src/heightmap.mjs` (`readHeightmap` → `water` mask). Keep `MAX_Z` in `MapBase.kt` and `maps.mjs` equal.
- `data/geo` coordinates must be integers (the app reads them as `List<Int>`; a float fails the whole file).
- Tanker TACAN: BMS assigns 92Y to the first tanker, then 126Y, 125Y… (TO 1F-16CMAM-34-1-1, TACAN). A TACAN in the briefing notes wins. Frequencies follow the callsign in the theater's RadioMap (`data/radio`).
- Never test Media delete on the real BMS folder: point `PicturesDirOverride` at a copy (Falcon BMS settings), and clear it afterwards.

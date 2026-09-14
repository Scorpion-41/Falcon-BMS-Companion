# Falcon BMS Companion: notes for AI-assisted maintenance

Two deliverables live in this repo:
1. **Android app** `app/`: Kotlin + Jetpack Compose + Material 3, package `com.bmscompanion.app`, a single APK (charts included).
2. **Windows bridge** `pc/BmsCompanionBridge/`: C# .NET 8 WinForms, no NuGet packages. It serves BMS mission data to the app on the LAN.

## Ground rules
- **The BMS install is read-only.** Never write, move or delete anything in the BMS folder, including during testing. To test EZBoards, copy its folder to a temp dir and strip the `SET KNEEBOARD[...]` lines from the copy's `CONFIG_USER.BAT`.
- **No personal data in the repo** (Windows user names, callsigns, absolute user paths). `local.properties`, `dist/`, `tools/pdftext/`, and `pc/**/bin|obj` are git-ignored. The demo briefing in `pc/.../Resources` is sanitized.
- **Phone layouts must stay usable.** Tablet-specific layouts use `isWide()` (≥840dp) / `isMedium()` (≥600dp), `AdaptiveSplit` and `Masonry` from `ui/components/Adaptive.kt`.
- **JSON contract changes are additive.** Update both `pc/.../Server/ApiModels.cs` (and the parsers) **and** `app/.../data/mission/MissionModels.kt`, plus `docs/PROTOCOL.md`.

## Where things are
| Topic | Files |
|---|---|
| Navigation, tabs, routes (`m/...` = pages opened from Mission, owned by the Mission tab) | `app/.../ui/AppRoot.kt` |
| Bundled data loading, prefs | `app/.../data/Repo.kt`, `data/Models.kt` |
| Theater map widget (projection x=north ft, y=east ft) | `app/.../ui/components/TheaterMap.kt` |
| Mission client (polling while screen resumed, UDP discovery) | `app/.../data/mission/MissionLink.kt` |
| Mission UI | `app/.../ui/screens/mission/*.kt` (`MissionScreen` hub → Map / Flight / Briefing / Comms / Boards / Setup) |
| Shared memory structs | `pc/.../Bms/SharedMemoryLayout.cs` (mirrors `Tools/SharedMem/FlightData.h`) |
| Briefing / DTC parsers | `pc/.../Bms/BriefingParser.cs`, `Bms/DtcParser.cs` |
| AWACS picture | `pc/.../Bms/TacviewClient.cs` (Tacview real-time telemetry, port 42674) |
| EZBoards | `pc/.../EzBoards/EzBoardsRunner.cs` (hidden `cmd /c EZBOARDS.BAT companion`; board tables via `bin\xbrief.exe`) |
| Bridge UI (status/settings window, setup guide with live checks) | `pc/.../UI/MainForm.cs`, `UI/SetupGuideForm.cs`, `UI/SystemActions.cs` |
| HTTP API / discovery | `pc/.../Server/HttpServer.cs`, `Server/Discovery.cs`, routes in `BridgeService.cs` |
| Reference data extraction | `tools/extractor/src/*.mjs` (env `BMS_ROOT`) |
| Manual-derived data | `tools/curated/*.json` |

## Common tasks
- **New BMS version:** follow `docs/UPDATING.md` step by step.
- **Build & run on a device:** `./gradlew installDebug`. Debug builds accept `adb shell am start -n com.bmscompanion.app/.MainActivity --es route mission` to open a route directly (useful for screenshots).
- **Bridge without BMS:** `dotnet run --project pc/BmsCompanionBridge -- --demo`, then check `http://127.0.0.1:47474/api/info`.
- **Bridge self-checks:** `BMSCompanionBridge.exe --selftest out.txt` (struct sizes, parser output); `--eztest <EZBoards copy> out.txt`.
- **Release:** `./gradlew assembleRelease` (copy to `dist/BMS-Companion.apk`) and `powershell -ExecutionPolicy Bypass -File pc/publish.ps1`.

## Gotchas
- `String.format("%d", double)` crashes at runtime. Convert with `.toInt()` first.
- `TheaterMap` reads `onTap`/`onUserGesture` via `rememberUpdatedState`. Don't key `pointerInput` on lambdas: live data recomposes 4×/s and would cancel taps.
- `briefing.txt` uses CRLF, and EZBoards' `xbrief.exe` fails on LF-only files.
- BMS RWR `bearing[]` is true bearing in radians. Subtract ownship yaw for the scope.
- StringData ids are verified with `BMSCompanionBridge.exe --dumpstrings out.txt` against a running BMS (the header comments skip entries). NavPoint `z` is feet (not tens of feet). The hsiBits `Flying` flag is not set in 4.38.1, so flying detection also uses `pilotsStatus == 3`.
- DTC `target_N` is steerpoint N+1. Action `-1` marks a user target steerpoint.
- Some launch environments set `NoDefaultCurrentDirectoryInExePath`, and the EZBoards runner removes it for the child cmd.

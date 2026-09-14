# Bridge ↔ app protocol (API v1)

Plain HTTP/1.1 + JSON on the local network. All JSON is camelCase, and null fields are omitted. Clients must ignore unknown fields: changes are **additive**, and `api` is bumped only for breaking changes.

- Bridge source: `pc/BmsCompanionBridge/Server/ApiModels.cs`, `Bms/BriefingParser.cs`, `Bms/DtcParser.cs`, `EzBoards/EzBoardsRunner.cs`
- App mirror: `app/src/main/java/com/bmscompanion/app/data/mission/MissionModels.kt`

## Discovery

The app sends the UDP datagram `BMSC_DISCOVER` to the broadcast address(es) on port **47475**. The bridge replies unicast:

```json
{ "service": "bms-companion", "name": "PC-NAME", "port": 47474, "version": "1.0.0", "api": 1 }
```

## Coordinates & units

- Positions `x`/`y` are **BMS theater feet**: `x` = north, `y` = east, origin at the theater's south-west corner (same as `FlightData.x/y`).
- Bearings are **true** degrees unless named `Mag`. Altitudes are feet MSL, speeds knots, fuel lb, time `timeSec` = seconds since midnight (sim time).
- Tacview `U/V` (metres) are converted with 3.27998 BMS ft per metre.

## Endpoints

| Method | Path | Poll rate (app) | Body |
|---|---|---|---|
| GET | `/api/info` | 2 s (heartbeat) | `InfoDto` |
| GET | `/api/live` | 4 Hz | `LiveDto` |
| GET | `/api/contacts` | 1 Hz | `ContactsDto` |
| GET | `/api/mission` | when `info.briefing.modified`, `info.briefing.dtcModified` or `info.ezBoards.lastRun.time` changes | `{version, briefingModified, briefing, dtc, board}` |
| POST | `/api/ezboards/generate` | on user tap (blocks until EZBoards exits, max 90 s) | `EzRunDto` (HTTP 409 when it failed) |
| GET | `/api/ezboards/status` | – | `EzStatusDto` |
| GET | `/` | – | human-readable status page |

Responses larger than 1 KB are gzip-compressed when the client sends `Accept-Encoding: gzip`, and connections are keep-alive.

### InfoDto
```json
{
  "app": "BMS Companion Bridge", "version": "1.0.0", "api": 1, "host": "PC-NAME", "demo": false,
  "bms": { "installed": true, "baseDir": "D:\\Falcon BMS 4.38", "registryVersion": "Falcon BMS 4.38", "version": "4.38.1 (…)",
           "running": true, "flying": true, "theater": "Korea KTO", "callsign": "Viper", "aircraft": "F-16CM-52" },
  "tacview": { "enabled": true, "connected": true, "state": "connected", "objects": 214 },
  "briefing": { "available": true, "modified": 1789382313458, "generated": "9/13/2026 22:42:04", "dtcModified": 1789382000000 },
  "ezBoards": { "configured": true, "path": "…\\Tools\\EZBoards", "autoOnPrint": false, "running": false, "lastRun": { … } }
}
```

### LiveDto (shared memory)
`flying` (hsiBits Flying), `theater`, `aircraft`, `x`, `y`, `altFt`, `hdgTrue`, `hdgMag`, `kias`, `mach`, `gsKts`, `vviFpm`, `gLoad`, `aoa`, `radarAltFt`,
`fuelInternal`, `fuelExternal`, `fuelFlow`, `bingo`, `chaff`, `flares`, `gear`, `speedBrake`, `bullX`, `bullY`, `timeSec`, `lat`, `lon`,
`tacan` ("27X", "12Y A/A"), `beaconBrg`, `beaconNm`, `desiredCourse`, `navMode`, `ilsFreq`, `uhfPreset`, `uhfFreq` (kHz),
`ded` (5 strings), `rwr[]`, `navPoints[]`, `voice`, `pilots[]`.

- `rwr[]`: `{sym, brg (true°), lethality (0..1), launch, lock, selected, new}`. `sym` is the BMS `RWRsymbol` id (see `Tools/RwrEmulator/Source/ScopeRenderer.cs`). The scope radius follows `clamp(lethality, 0.25, 0.8)`, like the BMS RWR emulator.
- `navPoints[]`: `{i, type, x, y, altFt, name?, rangeNm?}`, where `type` ∈ `WP GM PO MK DL CB L1-L4 PT` (StringData `NavPoint`). PPTs (`PT`) carry `name` and `rangeNm`.
- `voice`: `{flight, seats, tanker, awacs, departure, arrival, alternate}` from StringData `VoiceHelpers`.

### ContactsDto (Tacview real-time stream)
```json
{ "t": 0, "connected": true, "state": "connected",
  "contacts": [ { "id": "a07", "kind": "air|heli|missile|ship|bullseye", "x": 0, "y": 0, "altFt": 0, "hdg": 0, "gsKts": 0,
                  "name": "F-16CM-52", "pilot": "…", "group": "Viper1", "coalition": "…", "color": "Blue", "own": false, "friendly": true } ] }
```
`own` = the air contact nearest to the shared-memory ownship (within 2 nm). `friendly` = same coalition as `own`. `gsKts` is derived from position deltas.

### Mission
- `briefing`: parsed `briefing.txt` (`overview`, `situation`, `roster`, `package`, `threats`, `steerpoints`, `comms`, `ordnance`, `weather`, `support`, `roe`, `emergency`, `alternate`). It also always includes `sections[]` with the **raw tab-separated rows** of every section, as a fallback if a BMS update changes a layout.
- `dtc`: parsed `<callsign>.ini`: `steerpoints` (`target_N` → STPT N+1, `isTarget` when action = -1), `weaponTargets`, `ppts` (N = 56+idx), `lines`, `uhf`/`vhf` presets, `iff`.
- `board`: `{time, format, tables[{title, header[], rows[{kind, cells[]}]}]}` parsed from EZBoards' `xbrief.exe --format pcstw` HTML. `kind` is the xbrief row class (`ownflight`, `ownroster`, `odd`, `even`).

### EzRunDto
`{time, ok, durationMs, message, log[] (last 40 lines, ANSI and progress bars stripped), auto}`
Success = exit code 0 **and** a `SUCCESS.` line from `EZBOARDS.BAT`.

## Security

The bridge is meant for a trusted home LAN. It serves read-only data, and the only action it exposes runs the configured `EZBOARDS.BAT`, with no arguments from the client. The firewall rules the bridge offers are limited to the local subnet.

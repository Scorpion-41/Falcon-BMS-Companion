# PC ↔ app protocol (API v1)

Plain HTTP/1.1 + JSON on the local network, served by **BMS Companion for Windows** on the BMS PC (default port **47474**). All JSON is camelCase, and null fields are omitted. Clients must ignore unknown fields: changes are **additive**, and `api` is bumped only for breaking changes.

- Server source: `desktop/src/main/kotlin/com/bmscompanion/desktop/bridge/` (`Bridge.kt` routes, `SharedMemory.kt`, `BmsFiles.kt` briefing/DTC parsers, `TacviewClient.kt`, `EzBoards.kt`, `Screenshots.kt`) and `PcServer.kt` (HTTP server, browser version, forwarding).
- Models: `app/src/main/java/com/bmscompanion/app/data/mission/MissionModels.kt`. The server serializes these same classes, so the Android app, the PC app and the browser version always agree.
- Clients: the Android app on the LAN; BMS Companion for Windows on another PC ("On another PC", a client); browsers (the browser version calls the same API on the PC that served it). On the BMS PC itself, the app window reads the data in-process, without the network.
- Earlier versions (1.2) used a separate `BMSCompanionBridge.exe` with the same API on the same port. Clients work with both.

## Discovery

The app sends the UDP datagram `BMSC_DISCOVER` to the broadcast address(es) on port **47475**. A PC that reads Falcon BMS replies unicast:

```json
{ "service": "bms-companion", "name": "PC-NAME", "port": 47474, "version": "1.3.0", "api": 1 }
```

## Coordinates & units

- Positions `x`/`y` are **BMS theater feet**: `x` = north, `y` = east, origin at the theater's south-west corner (same as `FlightData.x/y`).
- Bearings are **true** degrees unless named `Mag`. Altitudes are feet MSL, speeds knots, fuel lb, time `timeSec` = seconds since midnight (sim time).
- Tacview `U/V` (metres) are converted with 3.27998 BMS ft per metre.

## Endpoints

| Method | Path | Poll rate (app) | Body |
|---|---|---|---|
| GET | `/api/info` | 2 s (heartbeat) | `BridgeInfo` |
| GET | `/api/live` | 4 Hz | `Live` |
| GET | `/api/contacts` | 1 Hz (2 Hz while the AWACS page is open) | `Contacts` |
| GET | `/api/mission` | when `info.briefing.modified`, `info.briefing.dtcModified` or `info.ezBoards.lastRun.time` changes | `{version, briefingModified, briefing, dtc, board}` |
| POST | `/api/ezboards/generate` | on user tap (blocks until EZBoards exits, max 90 s) | `EzRun` (HTTP 409 when it failed) |
| GET | `/api/ezboards/status` | – | `EzStatus` |
| GET | `/api/media` | when `info.media.count` or `info.media.latest` changes, while Media is open | `{dir, available, shots[{name, time, size, w?, h?}]}` newest first |
| GET | `/api/media/thumb?name=` | per visible thumbnail | JPEG, 360 px on the long side (cached) |
| GET | `/api/media/view?name=&max=` | viewer | JPEG downscaled to `max` px (320–4096, default 2400) |
| GET | `/api/media/file?name=` | share / download | the original file (`image/png`, `image/jpeg`) |
| POST | `/api/media/delete` | on user confirm | body: JSON array of file names (or `?name=`); moves them to the BMS PC's Recycle Bin → `{deleted}` |

Responses larger than 1 KB (except images) are gzip-compressed when the client sends `Accept-Encoding: gzip`, and connections are keep-alive. There are no CORS headers: browsers can only call the API from the page BMS Companion serves (see [Security](#security)).

When the PC is a **client** of another BMS PC, `/api/...` calls it receives are forwarded to that PC (so browsers near a laptop still get data). With no data source it answers HTTP 502 with `{"error": "…"}`.

### BridgeInfo
```json
{
  "app": "BMS Companion", "version": "1.3.0", "api": 1, "host": "PC-NAME", "demo": false,
  "bms": { "installed": true, "baseDir": "D:\\Falcon BMS 4.38", "registryVersion": "Falcon BMS 4.38", "version": "4.38.1 (…)",
           "running": true, "flying": true, "theater": "Korea KTO", "callsign": "Viper", "aircraft": "F-16CM-52" },
  "tacview": { "enabled": true, "connected": true, "state": "connected", "objects": 214 },
  "briefing": { "available": true, "modified": 1789382313458, "generated": "9/13/2026 22:42:04", "dtcModified": 1789382000000 },
  "ezBoards": { "configured": true, "path": "…\\Tools\\EZBoards", "autoOnPrint": false, "running": false, "lastRun": { … } },
  "media": { "available": true, "count": 45, "latest": 1789482603481 }
}
```
`media` summarises the screenshot folder (`User\Pictures`, `g_sPicturesDirectory`, the folder BMS reports in shared memory, or the `PicturesDirOverride` setting).

### Live (shared memory)
`flying` (hsiBits Flying, or pilot status "flying"), `theater`, `aircraft`, `x`, `y`, `altFt`, `hdgTrue`, `hdgMag`, `kias`, `mach`, `gsKts`, `vviFpm`, `gLoad`, `aoa`, `radarAltFt`,
`fuelInternal`, `fuelExternal`, `fuelFlow`, `bingo`, `chaff`, `flares`, `gear`, `speedBrake`, `bullX`, `bullY`, `timeSec`, `lat`, `lon`,
`tacan` ("27X", "12Y A/A": the A/A or Y-band source when set, otherwise UFC), `tacanUfc` / `tacanAux` (the UFC/DED and AUX COMM panel channels), `beaconBrg`, `beaconNm`, `desiredCourse`, `navMode`, `ilsFreq`, `uhfPreset`, `uhfFreq` (kHz),
`ded` (5 strings), `rwr[]`, `navPoints[]`, `voice`, `pilots[]`.

- `rwr[]`: `{sym, brg (true°), lethality (0..1), launch, lock, selected, new}`. `sym` is the BMS `RWRsymbol` id (see `Tools/RwrEmulator/Source/ScopeRenderer.cs`). The scope radius follows `clamp(lethality, 0.25, 0.8)`, like the BMS RWR emulator.
- `navPoints[]`: `{i, type, x, y, altFt, name?, rangeNm?}`, where `type` ∈ `WP GM PO MK DL CB L1-L4 PT` (StringData `NavPoint`). PPTs (`PT`) carry `name` and `rangeNm`.
- `voice`: `{flight, seats, tanker, awacs, departure, arrival, alternate}` from StringData `VoiceHelpers`.

### Contacts (Tacview real-time stream)
```json
{ "t": 0, "connected": true, "state": "connected",
  "contacts": [ { "id": "a07", "kind": "air|heli|missile|ship|crew|bullseye", "x": 0, "y": 0, "altFt": 0, "hdg": 0, "gsKts": 0,
                  "name": "F-16CM-52", "pilot": "…", "group": "Viper1", "coalition": "…", "color": "Blue", "own": false, "friendly": true, "wingman": false, "neutral": false,
                  "ias": 310, "mach": 0.82, "fuelLb": 5400, "locked": "a0c" } ] }
```
`own` = the air contact nearest to the shared-memory ownship (within 2 nm). `friendly` = on your side: the same coalition as `own`, **or** a team the campaign has your team allied or friendly with. BMS writes the *country* into Tacview's `Coalition` ("Hellas", "U.S."), so an ally from another nation is only recognised through the campaign's team table (the `.tea` part of the `.cam`/`.tac`); without a readable save the rule falls back to comparing coalition names. `neutral` = a team your side is neutral toward or has no relations with (never set when the campaign cannot be read). `wingman` = in your own flight: the same Tacview `CallSign` as `own` with only the last digit different ("Tiger12" for "Tiger11"). Both are additive; an older app ignores them. `gsKts` is derived from position deltas. `crew` = an ejected crew (Tacview type `…Human+Parachutist`); older versions sent these as `air` named "Ejected Crew", so the app also matches the name. `ias` (knots, from Tacview IAS in m/s), `mach`, `fuelLb` (Tacview FuelWeight) and `locked` (id of the contact this one has a radar lock on; omitted when none) are only present when BMS sends them.

### Mission
- `briefing`: parsed `briefing.txt` (`overview`, `situation`, `roster`, `package`, `threats`, `steerpoints`, `comms`, `ordnance`, `weather`, `support`, `roe`, `emergency`, `alternate`). It also always includes `sections[]` with the **raw tab-separated rows** of every section, as a fallback if a BMS update changes a layout.
- `dtc`: parsed `<callsign>.ini`: `steerpoints` (`target_N` → STPT N+1, `isTarget` when action = -1), `weaponTargets`, `ppts` (N = 56+idx), `lines`, `uhf`/`vhf` presets, `iff`.
- `board`: `{time, format, tables[{title, header[], rows[{kind, cells[]}]}]}` parsed from EZBoards' `xbrief.exe --format pcstw` HTML. `kind` is the xbrief row class (`ownflight`, `ownroster`, `odd`, `even`).

### EzRun
`{time, ok, durationMs, message, log[] (last 40 lines, ANSI and progress bars stripped), auto}`
Success = exit code 0 **and** a `SUCCESS.` line from `EZBOARDS.BAT`.

## Browser version (same port)

With **Browser access** on, the same server also serves the browser version of the app. It is the app compiled to WebAssembly and runs entirely in the browser; it only calls the API above and loads bundled data:

| Method | Path | Body |
|---|---|---|
| GET | `/`, `/bmsc.js`, `/*.wasm`, … | the app files (revalidated with an ETag per version; gzip) |
| GET | `/assets/<data\|img\|maps\|charts>/…` | the bundled BMS reference data and charts (cached for a day), including map styles (`maps/<theater>/<style>.webp`, tiles `maps/<theater>/<style>/<z>/<row>_<col>.webp`) and landmarks (`data/geo/<theater>.json`) |
| GET | `/api/assets?dir=data/curated` | JSON array of file names in a bundled data folder |
| GET | `/icon.png`, `/manifest.json` | home-screen icon and web app manifest |

With browser access off, `/` shows a short status page instead.


## Security

Everything is meant for a trusted home LAN. The server provides read-only data; the actions it exposes to devices are running the configured `EZBOARDS.BAT` (no arguments from the client) and moving chosen screenshots to the Recycle Bin. Settings can only be changed in the PC program itself. The firewall rules it offers are limited to the local subnet. There is no login: anyone on the same network can open the app or the API.

Web pages from other sites can't use the API through a browser on the PC or the network. `/api/...` requests are refused with HTTP 403 and `{"error": "…"}` when they carry an `Origin` other than the address they were sent to, `Sec-Fetch-Site: cross-site` or `same-site`, or a `Host` that isn't an IP address or a local name (`gaming-pc`, `gaming-pc.local`), which blocks DNS rebinding. The apps and client PCs send no `Origin`.

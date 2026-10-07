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
| GET | `/api/contacts` | 1 Hz (2 Hz while the AWACS page is open) | `Contacts` — **no hostile contact** (aircraft, helicopter, ship, ground unit, air defence, ejected crew, a contact of no known side) and only the own side's weapon `events` unless the pilot turned hostiles on (`info.tacview.hostiles`, `BridgeSettings.ShowHostiles`, **off by default**, 1.3.8; `HostileContacts`); with it on, a hostile air defence still only where it is a site the mission knows (the same system within 2 nm, `KnownSams`) |
| GET | `/api/mission` | when `info.briefing.modified`, `info.briefing.dtcModified`, `info.briefing.planModified`, `info.briefing.routeModified`, `info.ezBoards.lastRun.time` or (1.3.8) `info.mission.mode`, `info.mission.populated.at`, `info.mission.populated.changed` changes | `{version, briefingModified, briefing, dtc, board, tracks, route?, plan?, mode, populated?}` (see *Mission* and *Mission source*) — the data of the mode the Mission section is in |
| GET | `/api/mission?source=bms` | in WDP mode, when those BMS files change (the Planner plans from them) | the same shape, always Falcon BMS's own files (EZBoards mode's data), whatever the mode (1.3.8) |
| GET | `/api/mission/source` | – (`info.mission` carries the same on every poll) | `MissionSourceInfo` (1.3.8, see *Mission source*) |
| POST | `/api/mission/source?mode=ezboards\|wdp[&for=<LedgerMission JSON>]` | on user tap (the EZBoards \| WDP switch) | `MissionSourceInfo` with `reset` (what the switch reset by itself, 1.3.8); HTTP 400 for another word. Asks nothing; clears every leftover of the Planner's in the cartridge, the other mode's cockpit pages from an earlier flight and a snapshot of another flight (see *Switching*); the app shows none of it |
| POST | `/api/mission/source/undo?at=<reset.at>` | – (kept for compatibility; the app no longer offers it) | `MissionSourceInfo`, its `reset` with `undone`/`undoMessage`: the cartridge keys that switch cleared written back where each still holds the empty value (1.3.8); 400 without `at`, 409 when that switch is not the last |
| POST | `/api/mission/populate` | on user tap (Populate from Planner, WDP mode) | body: `PopulateSend` → `MissionSourceInfo`; refusals in words (400/409, see *Mission source*) |
| POST | `/api/mission/opened?for=<LedgerMission JSON>[&clean=1]` | when the Planner opens a flight (Open mission… / Pick a flight) | `MissionSourceInfo`; in WDP mode another flight than the last one opened is a **new mission**: the PC clears what the Planner saved for any other flight and discards a snapshot of another flight by itself, the summary in `reset` (`kind` `mission`); nothing in EZBoards mode; 400 without `for` (1.3.8, see *Mission source*). `clean=1` (1.3.9, either mode: another flight than the Planner had): **Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints** (see *The cartridge ledger*) |
| POST | `/api/ezboards/generate` | on user tap (blocks until EZBoards exits, max 90 s) | `EzRun` (HTTP 409 when it failed, and — without running anything — in WDP mode, `message` saying why) |
| GET | `/api/ezboards/status` | – | `EzStatus` |
| GET | `/api/media` | when `info.media.count` or `info.media.latest` changes, while Media is open | `{dir, available, shots[{name, time, size, w?, h?}]}` newest first |
| GET | `/api/media/thumb?name=` | per visible thumbnail | JPEG, 360 px on the long side (cached) |
| GET | `/api/media/view?name=&max=` | viewer | JPEG downscaled to `max` px (320–4096, default 2400) |
| GET | `/api/media/file?name=` | share / download | the original file (`image/png`, `image/jpeg`) |
| POST | `/api/media/delete` | on user confirm | body: JSON array of file names (or `?name=`); moves them to the BMS PC's Recycle Bin → `{deleted}` |
| GET | `/api/cfg` | when the Config page opens or is refreshed | `CfgState` |
| GET | `/api/cfg/lines?kind=&profile=` | when the file or profile changes | `CfgFile` |
| GET | `/api/cfg/defaults` | when the Planner's DTC page needs a Default | `BmsDefaults` — BMS's own `User/Config/*_Def.ini`, **read only** |
| POST | `/api/cfg/backup` | on user tap | `CfgState` — takes the one-time copy and lays down the three profiles |
| POST | `/api/cfg/select?kind=&profile=` | on user tap | `CfgState` — copies that profile onto the file BMS reads |
| POST | `/api/cfg/set?kind=&profile=&key=` | on each change | body: the value, **empty body clears the line** → `CfgFile` |
| POST | `/api/cfg/copy?kind=&from=&to=` | on user tap | `CfgFile` |
| POST | `/api/cfg/restore?kind=&profile=` | on user tap | `CfgFile` — back to the copy taken before any of this |
| GET | `/api/cartridge[?callsign=]` | when the Planner opens, on Open Callsign.ini, when BMS rewrites the file | `CartridgeState` |
| POST | `/api/cartridge/save[?callsign=&te=<theater>\|<file>&for=<LedgerMission JSON>]` (all optional) | on user tap (Save to DTC) | body: `CartridgeEdit[]` → `CartridgeState` (with `mission` when `te` names a save — a TE, a training or a campaign; see *Planner integration*). Written at once, as WDP writes it: there is no backup call before it (`/api/cartridge/backup` of the 1.3.8 test builds is gone). `for` (1.3.8) names the mission the save is for, kept in the cartridge ledger |
| POST | `/api/cartridge/leftovers?do=clear\|keep[&callsign=&for=<LedgerMission JSON>]` | never by a 1.3.8 device (kept for the first 1.3.8 test builds; the clearing is automatic now) | `CartridgeState` — what the Planner saved for another flight than `for` (default: the mission the Mission section shows) and is still in the cartridge, cleared or kept for `for` (1.3.8, *The cartridge ledger*) |
| GET | `/api/attack` | every 2 s, by a VR board's map and the kneeboard layout | `AttackOverlay` — follows the Mission section's mode: in WDP mode the attack of the snapshot Populate from Planner took (`plan.attack`), in EZBoards mode (1.3.8) the cartridge's own, its `[NAV OFFSETS]` laid out on BMS's route (`AttackDrawing.fromCartridge`, what the Mission map draws there too); `cues` empty when there is none; `theater` the id of the theater it was planned in (empty if unknown), and a map of another theater does not draw it |
| POST | `/api/attack` | before 1.3.8: when a Planner attack page's figures settled. Kept so an older client is not refused; since the two modes (1.3.8) what it posts is no longer drawn | body: `AttackOverlay` → the same (HTTP 400 when it is not one); kept in memory only, like the Taxi page's choice |
| GET | `/api/campaign/files[?all=1]` | when the Planner's Open mission window opens | `CampFiles` (see *Planner integration*) |
| GET | `/api/campaign/ato?theater=&file=` | when a save is picked | `CampAto` |
| GET | `/api/campaign/atotargets?theater=&file=[&team=]` | when the Planner's ATO Targets page is shown (1.3.8) | `CampAtoTargets` |
| GET | `/api/campaign/flight?theater=&file=&flight=` | when a flight is picked | `CampFlight` |
| GET | `/api/campaign/objectives?theater=&file=` | when the DataCard's DMPI box opens Target Selection (1.3.8) | `CampObjectives` |
| GET | `/api/campaign/features?theater=&file=&objective=` | when an objective is picked in Target Selection (1.3.8) | `CampFeatures` |
| GET | `/api/campaign/ground?theater=&at=` | when an attack page gets a mission: the ground under its steerpoints (1.3.8) | `CampGround` |
| GET | `/api/campaign/mapintel?theater=&file=&flight=[&team=]` | when the Planner's Map page opens or the save changes (1.3.8) | `CampMapIntel` (see *Map intel*) |
| GET | `/api/campaign/magvar?theater=` | when the Map page opens a theater (1.3.8) | `CampMagVar` |
| GET | `/api/plan` | before the two modes (1.3.8): when the Planner opened | `PlanOverlay` (`id` 0 when there is none) — still answered, no longer merged into `/api/mission` |
| POST | `/api/plan` | before the two modes: on user tap (Send to Mission, now gone) | body: `PlanSend` → `PlanOverlay` |
| POST | `/api/plan/files[?from=]` | before the two modes: Send to Mission → files on the PC | no body → `PlanOverlay` |
| POST | `/api/plan/clear`, `/api/plan/undo` | before the two modes | `PlanOverlay` |
| GET | `/api/kbprint/state` | when the Upd Kneeboard window opens | `KbPrintState` |
| GET | `/api/kbprint/thumb?n=&side=L\|R&w=` | per page half shown | JPEG of what that half holds now |
| POST | `/api/kbprint/file` | on user tap (Print), one call per page file | body: `KbFileSend` → `KbFileResult` |
| POST | `/api/kbprint/shipped?n=<n\|all>` | on user tap | `KbFileResult[]` — puts Falcon BMS's own shipped page back |
| GET | `/api/files/places` | when a file window opens (not on the BMS PC's own window) | `PcPlaces` (see *Files on the BMS PC*) |
| GET | `/api/files/list?path=&ext=` | on each folder the window shows | `PcFolder` |
| GET | `/api/files/stat?path=` | on Save, before the overwrite question | `PcFileEntry` (`exists` false for a new name) |
| GET | `/api/files/read?path=` | when the Planner opens the picked file | `PcFileData` (base64) |
| POST | `/api/files/write?path=&overwrite=0\|1` | when the Planner saves to the picked file | body: `PcFileWrite` → `PcFileEntry` |
| POST | `/api/files/mkdir?path=` | before a Planner window opens in one of WDP's folders (WDP's `CheckForDir`) | `PcFileEntry` of the folder |
| GET / POST | `/api/files/planner` (`?datacards=` on POST) | the Planner's settings: its own folder in the BMS install and the DataCards folder, read and set | `PcPlannerFolders` (see *Files on the BMS PC*) |
| GET | `/api/files/weather?path=&clock=&save=` | Reload WX, after the pilot picked an `.fmap` or `.twx`; the DataCard, for a save's own `.twx` at every Open mission and Pick a flight | `PcWeather` (see *Files on the BMS PC*) |
| GET | `/api/files/picture?path=&max=` | Upd Kneeboard's Browse picture…, after the pilot picked a picture, and for a Picture half's preview (`max=1024`) | `PcPicture` (see *Files on the BMS PC*) |

`AttackOverlay`: `{page, cues[{label, north, east, kind, note}], runIn[{first, second}], theater, profile, mode, tgtStpt, refStpt, saved, beyond[{first, second}]}` — sim feet, north and east (see *Coordinates*). `kind` is `TARGET`, `IP`, `VIP`, `VRP`, `PUP` or `OA`; `note` is the reference point's distance ("8.0 nm to TGT"); `runIn` is the path the jet flies, point by point (north as `first`, east as `second`). Added in 1.3.8 (additive; every map draws the one model, `AttackDrawing`): `profile` "Pop-up", "HADB", "TOSS" or "" (a cartridge whose attack page is not known); `mode` "VIP" or "VRP"; `tgtStpt` the target steerpoint (0 when not known); `refStpt` the steerpoint the lines hang on (the IP in VIP mode, the target in VRP mode); `saved` whether the cartridge's `[NAV OFFSETS]` as saved hold it (true when an older sender says nothing; the Mission map's caption adds "not in the cartridge — Save to DTC" when false); `beyond` the dotted leg past the target (TGT → OA2 on Pop-up and HADB). Since 1.3.8 the builders put out no `IP` cue (the VIP *is* the IP, VRP mode has none), only the mode's OA pair, and no OA2 lying on OA1; no new `kind` is added, so an older client reads every overlay.

Responses larger than 1 KB (except images) are gzip-compressed when the client sends `Accept-Encoding: gzip`, and connections are keep-alive. All responses allow any origin (CORS).

When the PC is a **client** of another BMS PC, `/api/...` calls it receives are forwarded to that PC (so browsers near a laptop still get data). With no data source it answers HTTP 502 with `{"error": "…"}`.

### BridgeInfo
```json
{
  "app": "BMS Companion", "version": "1.3.8", "api": 1, "host": "PC-NAME",
  "bms": { "installed": true, "baseDir": "D:\\Falcon BMS 4.38", "registryVersion": "Falcon BMS 4.38", "version": "4.38.1 (…)",
           "running": true, "flying": true, "theater": "Korea KTO", "callsign": "Viper", "aircraft": "F-16CM-52" },
  "tacview": { "enabled": true, "connected": true, "state": "connected", "objects": 214, "hostiles": false },
  "briefing": { "available": true, "modified": 1789382313458, "generated": "9/13/2026 22:42:04", "dtcModified": 1789382000000,
                "planModified": 1789382400000, "routeModified": 1789382318000 },
  "ezBoards": { "configured": true, "path": "…\\Tools\\EZBoards", "autoOnPrint": false, "running": false, "lastRun": { … },
                "suspended": false },
  "media": { "available": true, "count": 45, "latest": 1789482603481 },
  "mission": { "mode": "wdp", "switched": 1790671813047,
               "populated": { "at": 1790671813371, "from": "PC", "theater": "Korea KTO", "save": "Auto Save.cam",
                              "flight": "21289/0", "callsign": "Cyborg6", "seat": 0, "packageId": "7288", "kind": "campaign",
                              "saveModified": 1790600000000, "cartridge": "Viper.ini", "cartridgeModified": 1790670000000,
                              "missionFile": "Auto Save.ini", "missionFileModified": 1790600000000, "attack": false,
                              "notes": [], "changed": [], "weatherFile": "Auto Save.twx", "weatherFileModified": 1790600000000,
                              "briefingFrom": "printed", "briefingPrinted": 1790599000000 },
               "line": "From the Planner · populated 22:51 · Auto Save.cam · Cyborg6" }
}
```
`media` summarises the screenshot folder (`User\Pictures`, `g_sPicturesDirectory`, the folder BMS reports in shared memory, or the `PicturesDirOverride` setting).
`briefing.planModified` changes whenever what `/api/mission` carries as `plan` may have — since the two modes (1.3.8)
a switch of mode and a Populate from Planner — and `briefing.routeModified` whenever the `route` does (BMS's mission-file route, or the save's flight plan standing in for it);
both are 0 when there is none, and both are part of the key a client refetches `/api/mission` on (1.3.8, additive),
so a client that knows nothing of the modes still follows them. `briefing` itself always describes the briefing BMS
printed, whatever the mode.

`mission` (1.3.8, additive) is `MissionSourceInfo`: which mode the Mission section is in, since when, WDP mode's
snapshot as it was taken (kept across a switch and a restart; `changed` worked out at every call), and the line the
page shows under the switch, worded on the PC. `ezBoards.suspended` (1.3.8) is true in WDP mode: GENERATE NOW is
refused (409, `EzRun.message` = `MissionMode.EZ_SUSPENDED`: "WDP mode: EZBoards is paused so it cannot overwrite the
Planner's pages. Make the cockpit boards with Planner → Upd Kneeboard.") and `autoOnPrint` is not acted on, while
`autoOnPrint` itself keeps the pilot's setting. `kneeboard.runSuspended` (1.3.8, additive) is true in WDP mode too:
`POST /api/kneeboard/open` (Run HTML Briefing) is refused with 409, because html_brief's export writes cockpit pages
1-3 over the Planner's; its exported pages are still served. A PC older than 1.3.8 leaves all three out, which reads
as EZBoards mode with nothing suspended. See *Mission source*.

### Live (shared memory)
`flying` (hsiBits Flying, or pilot status "flying"), `theater`, `aircraft`, `x`, `y`, `altFt`, `hdgTrue`, `hdgMag`, `kias`, `mach`, `gsKts`, `vviFpm`, `gLoad`, `aoa`, `radarAltFt`,
`fuelInternal`, `fuelExternal`, `fuelFlow`, `bingo`, `chaff`, `flares`, `gear`, `speedBrake`, `bullX`, `bullY`, `timeSec`, `lat`, `lon`,
`tacan` ("27X", "12Y A/A": the A/A or Y-band source when set, otherwise UFC), `tacanUfc` / `tacanAux` (the UFC/DED and AUX COMM panel channels), `beaconBrg`, `beaconNm`, `desiredCourse`, `navMode`, `ilsFreq`, `uhfPreset`, `uhfFreq` (kHz),
`ded` (5 strings), `rwr[]`, `mfdLeft[]` / `mfdRight[]`, `navPoints[]`, `voice`, `pilots[]`.

- `mfdLeft[]` / `mfdRight[]`: 20 entries each, `{a, b, inverted}` — the two lines of one MFD option-select button
  legend and whether BMS has boxed it, OSB 1 first and running clockwise from the top left, five to a side. Empty
  when BMS is not publishing them (not in 3D, or an older BMS).

### Cockpit displays (BMS's render-to-texture export)
`GET /api/rtt` → `RttState`: `{available, settingOn, reason?, width, height, areas[], phase?, bmsRunning,
inCockpit, config?}`, each area `{id, label, width, height}` with `id` one of `hud pfl ded rwr mfdleft mfdright
hms`. Only the displays that cockpit publishes are listed. `reason` says why there is nothing, in words meant for a
pilot. Since 1.3.8 (additive): `phase` names the state for the MFD glass — `nobms`, `no3d`, `exportoff`,
`restart`, `nomfds`, `dark` (the MFD pictures are black: not powered), `pitch` (a row layout this version
refuses), `live`; a client that does not know a phase shows `reason`. `config` is `RttConfig {on?, launcher, vrOn?,
fps?}`: the export as BMS will read it from its own files, and whether the value is the Launcher's (its Export RTT
Textures choice, written into its block at the foot of `Falcon BMS User.cfg`, which wins over any line above it).

`GET /api/rtt/img?d=<id>&w=<px>&since=<hash>` → `image/jpeg`, one frame (an early 1.3.8 test build sent PNG; clients decode either).
`w` asks for a width no larger than the display; `since` is the client's `frameHash` (FNV-1a, 32 bits, over the
bytes of the picture it last drew), and when that is still the picture the answer is **204 with no body**. Not
cached; ask again for the next one.

`POST /api/rtt/enable?on=1` → `CfgState`. Sets `g_bExportRTTTextures` in the selected Config profile, so it
needs the Config backup to have been taken and is undone the same way as any other setting. When the Launcher's own
block sets it off, `error` says so: the line is written, but the Launcher's comes later and wins.

`POST /api/mfd/rocker?side=L|R&which=brt|gain&dir=up|down` → `{message}`. Rocks an MFD corner switch with the key
Falcon BMS has bound to it, only while BMS is the window in front (as `/api/mfd/osb`). `sym` and `con` answer that
BMS does not implement them; a half whose callback the key file leaves unbound answers with the callback to bind.
`GET /api/mfd/keys` (`rockers`) says which halves those are before anything is pressed.

Both press routes, and `GET /api/mfd/keys` as `blocked` (1.3.8, additive; "" when nothing is in the way), say so in
a sentence when **Falcon BMS runs as administrator and the PC program does not**: Windows (UIPI) then drops every key
the program sends, and `SendInput` reports nothing. Both elevations are read from the processes' tokens.

### The cartridge, whole, for the Planner's DTC page
`GET /api/cartridge[?callsign=]` → `CartridgeState`: `{available, callsign, file, path?, text, modified, pptIni?,
error?, message?}`. `text` is the whole file as it is on disk (read as Latin-1, as BMS writes it); `path` is its full
path on the PC, as WDP's DTC page names it. `callsign` is the name asked for even when there is no such file, so
`available` is what says it was found; `pptIni` is the theater's own `Campaign/ppt.ini` (read-only), for the page's
PPT names and ranges. Without `callsign`, the pilot BMS has selected; a callsign is only ever a file name in
`User/Config`.

`POST /api/cartridge/save[?callsign=]` with a body of `CartridgeEdit[]` — `{section, key, value}`, `value` null to
remove the key — → `CartridgeState` (`message` on success, `error` in words otherwise). **Written at once, the way
WDP's Save DTC writes it**: no question, nothing to switch on, no copy kept. The edits are applied to the file as it
is at that moment, one key at a time, through a temporary file and one atomic move; every other line stays byte for
byte, and applying the same edits twice changes nothing. From 1.3.8 an optional `te=<theater>|<file>` also writes that
save's own mission file (a TE's, a training's or a campaign's), and the answer carries `mission` (see *Planner
integration → Save to DTC in a Tactical Engagement*).

### The cartridge ledger (1.3.8): what the Planner wrote, for which mission
Falcon BMS keeps lines, PPTs and targets in the cartridge until something overwrites them, so what the Planner saved
for one flight is still there for the next. The PC keeps a **ledger** per pilot,
`<BMS>\User\BMS Companion Planner\Ledger\<callsign>.json` (`CartridgeLedger {callsign, writes[], cleared[]}`; each `LedgerWrite
{file, section, key, value, at, mission?}`, `file` empty for the cartridge or a TE's `.ini` name): the last value Save
to DTC wrote to every key, and the mission it was for. All additive:
- `POST /api/cartridge/save` takes `for=` — a `LedgerMission {callsign, packageId?, flightId?, theater?, save?, seat?,
  printed, opened}` as JSON (the Planner's flight: a save's, else the printed briefing's). A save without it (an older
  device) is recorded with no mission, which never makes a leftover.
- `CartridgeState.ledger`: the ledger, from `GET /api/cartridge` and every save (absent while empty).
- `MissionData.leftovers` (`GET /api/mission`): **always absent since the automatic clearing** (below); the first 1.3.8
  test builds sent `CartridgeLeftovers {callsign, items[], from?, at, now?, sentence}`
  for the mission served — the printed briefing's flight for BMS's files (EZBoards mode and `?source=bms`, so the two
  stay byte for byte the same), the populated flight in WDP mode; absent when there are none. Each `Leftover {section,
  key, value, kind, slot, line?, x, y, at, from?}`: `kind` `steerpoint` (`target_n`, STPT `slot`), `weapon`, `ppt` (slot
  56-70), `line` (`lineSTPT_n`, `line` 1-4) or `nav` (`[NAV OFFSETS]`); `x`/`y` its position. `version` gains
  `-L<count>.<latest write>` while there are any.
- A key is a leftover when the ledger says the Planner wrote it for another flight (callsign, package, flight number,
  theater and save compared wherever both say), it is one of those mission items and places something, and the
  cartridge still holds the value written (numbers to half a foot). Shared rules: `Leftovers` in
  `app/.../data/mission/CartridgeLedger.kt`.
- `POST /api/cartridge/leftovers?do=clear|keep[&callsign=&for=]`: worked out again against the file as it is;
  `clear` writes only those keys back to BMS's empty values (an offset aim point removed), temporary file and atomic
  move, and drops them from the ledger; `keep` gives them to `for` in the ledger and leaves the cartridge alone. The
  answer's `message` says what was done; refused like every cartridge write during a developer check (DevGuard).
- A switch of mode, and a **new mission** (a PRINT of another flight in EZBoards mode; another flight opened —
  `POST /api/mission/opened` — or populated in WDP mode), clear the Planner's leftovers by themselves (a switch: all of
  them; a new mission: every other flight's) and every `[NAV OFFSETS]` key that places something, whoever wrote it
  (at a new mission all but the new flight's own), silently (*Mission
  source*, "What a switch resets"): `CartridgeLedger.cleared` keeps what was cleared for its Undo, and `Leftover.file`
  names a TE's `.ini` there. No device calls `/api/cartridge/leftovers` any more.
- Since 1.3.9 they also clear **BMS's copies** of the Planner's items (BMS's DTC memory writes what was LOADed into the
  cartridge and the next save's mission file): a `[STPT]` value the ledger names for another flight — a PPT, target
  or weapon target compared as numbers (`Leftovers.sameItem`) against the live `writes` and the `cleared` rows, a line
  point exactly against the live `writes` — in the cartridge, the ledger's mission files, the mission file BMS's LOAD
  reads and `Auto Save.ini`; a line only whole. No log of past values is kept (a 1.3.9 test build's `history[]` in the
  ledger file is passed over when read and dropped at the next write).
- **Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints** (1.3.9, `GET /api/planner/settings` → `PlannerPcSettings
  { cleanOpened }`, `POST /api/planner/settings?cleanOpened=1|0`, the same answer; `BridgeSettings.CleanOpenedMission`,
  on by default and set on once by settings layout 4; an older PC answers 404): `POST /api/mission/opened` takes
  `clean=1` (in either mode) when the Planner planned **another flight** than it had (Open mission… or Pick a flight),
  and with the setting on the PC then cleans every line, every PPT and every Open 1/Open 2 steerpoint (STPT 81-99,
  `target_80…98`), whoever made them, from the cartridge and the campaign mission file BMS's LOAD reads for that
  flight — never in a TE or a training, never one the Planner saved for that flight, never STPT 1-24 (the route, the
  precision and Recon targets) or a weapon target. The answer's `reset` (kind `mission`, `now` the
  flight) names the keys, so the Planner reads its cartridge again; the cleaned points go into `cleared` with
  `mission` null when the Planner did not write them (the Undo puts them back; they never become the Planner's rows).
  A PRINT or a switch of mode never cleans them. Shown in the Planner's Settings window on every device.

The 1.3.8 test builds asked first: they had `POST /api/cartridge/backup[?callsign=]` (a one-time copy into
`User/Config/BMS Companion Backup/` that switched saving on) and `enabled`, `backedUp` and `backupDir` in
`CartridgeState`. All four are gone: the app no longer calls the route (the PC answers it 404 now), no longer reads
the fields, and a folder such a build left on a pilot's disk is left as it is and never read.

### Weather (`Data/<theater>/Campaign/*.fmap`)
`GET /api/weather[?theater=&map=1]` → `WeatherState` (`map=1` adds each model’s `WxMap`: a palette of `Wx` areas and one index per theater cell, row-major from the north-west): `{available, current?, theaters[], error?}`; each theater
`{id, name, dir, backedUp, models[]}` and each model `{id, name, readable, edited, uniform, weather}`, where
`weather` is `Wx` — `{type, tempC, pressureMb, visibilityKm, cloudBaseFt, cover, cloudSize, shower, towering, windDirDeg, windKts, windAloftKts, veerDeg}`
in briefing units (knots, not the km/h the file holds). The "models" here are BMS's four **ready-made maps** (Sunny,
Fair, Poor, Inclement `.fmap`, listed under Weather → Map Model), not BMS's weather models (Probabilistic, Deterministic,
Map Model, which each save keeps in its own `.twx`). `edited` is true when the file is no longer the copy taken of BMS's
own **and** the record beside that copy (`bms-companion-weather.txt`) says this program wrote it; both are read from the
BMS folder, not from anything the app remembers. (Up to 1.3.8's first test build, any difference counted.)

`POST /api/weather/backup?theater=<id>`, `POST /api/weather/set?theater=<id>&model=<id>` (body: `Wx`) and
`POST /api/weather/setmap?theater=<id>&model=<id>` (body: `WxMap`) and `POST /api/weather/restore?theater=<id>[&model=<id>]` all return `WeatherState`, with `error` set in words
when anything could not be done.

Added in 1.3.8, all additive:
- `Wx.cover` is **oktas** (FEW 1-2, SCT 3-4, BKN 5-7, OVC 8); the file holds BMS's code 0/1/5/9/13 and the PC converts
  both ways. `Wx.towering` is array 26 (towering cumulus); `Wx.shower` is its older name, read into both and written as
  `towering || shower`.
- `WxMap.contrailFt` is the four contrail altitudes (sunny, fair, poor, inclement) and `WxMap.stratusFt` the two high
  stratus layers (sunny/fair, poor/inclement). An older client's six-entry `contrailFt` is still accepted by `setmap`
  (the first two are taken as the stratus).
- Each theater carries `added[]` (files this program created under its Campaign folder, relative paths — delete to
  undo) and `replaced[]` (BMS update maps it wrote over; each original is in the backup folder).
- **Generated weather.** The body is `WxGenParams` (the generator's parameters, a few hundred bytes; the PC runs the
  same shared model, `app/.../data/weather/WxModel.kt`, to build the file — the grid is never sent). Times are
  `DHHMM`, as BMS names update maps (`10500` = day 1 05:00).
  - `POST /api/weather/generate?theater=<id>&name=<name>` writes `Campaign/BMSC <name>.fmap`.
  - `POST /api/weather/series?theater=<id>&name=<name>&from=<DHHMM>&to=<DHHMM>[&step=<min>]` writes the first map as
    `BMSC <name>.fmap` and one `WeatherMapsUpdates/<DHHMM>.fmap` every `step` minutes (default
    `WxGenParams.movement.stepMin`; under 55 is refused, as BMS disregards those; at most 240 maps). BMS's own update
    maps it overwrites are copied into the backup folder first, once each.
  - `GET /api/weather/generated?theater=<id>` → `WeatherState` (read each theater's `added`/`replaced`).
  - `POST /api/weather/remove?theater=<id>[&name=<name>]` deletes that generated map (or every one) this program added.
  - `POST /api/weather/restoreseries?theater=<id>` puts back every update map from the backup and deletes every one
    this program added.
  All return `WeatherState`; a name of anything but letters, digits, spaces, `-` and `_`, or one that is already a file
  this program did not write, is refused with the reason in `error`.
- **The settings a generated map was made from.** `GET /api/weather/params?theater=<id>[&name=<map>]` →
  `{"name": "BMSC <name>", "params": WxGenParams}`: the named map's, else those of the map written last among the ones
  still there; 404 when there are none. The PC keeps them beside the map's backup
  (`Campaign/BMS Companion Backup/Generated settings/BMSC <name>.json`), which BMS never reads. The theaters listed are
  the theater definitions' (`theater.lst` + each `.tdf`'s `campaigndir`: every one, whether or not it holds one of the
  four ready-made maps), keyed by the app's theater id; the old ids `korea-the-base-theater` and `lkto` are still
  accepted.
- **Every theater, and why not.** Each theater also carries `blocked` (why nothing can be written for it — its campaign
  folder is not there, or it is another theater's — in a sentence; such a theater is listed but never written),
  `stockMaps` (whether the four ready-made maps are there; false in Hellas, Hellas WCP and LHTO, where `backup` only
  makes the backup folder and its README, which switches the theater on), and, for the theater asked about only,
  `gridFrom` (the map a generated one takes its grid from, relative to the campaign folder, or `"built-in"` for BMS's
  59 x 59), `bmsUpdates`, `bmsUpdatesFirst` and `bmsUpdatesLast` (BMS's own update maps in `WeatherMapsUpdates` — every
  `<DHHMM>.fmap` this program did not add — and the first and last of their times as `CampaignTime`
  `{day, hour, minute}`: they go on loading round a series). Each model also carries `differs` (the file is not the
  copy, for any reason) and `copiedAt` (ms since 1970, when the copy was taken). Reading `GET /api/weather` also brings
  the README in each theater's backup folder up to date, once a run (never during a developer check aimed at a real
  install).
- `POST /api/weather/refreshbackup?theater=<id>[&model=<id>]` → `WeatherState`: for the ready-made maps that differ from
  their copy although the record does not say this program wrote them (a BMS update shipped new ones), takes a new
  copy of the file as it is now; the earlier copy moves first to `BMS Companion Backup/Older copies/<date time>/`, so
  nothing in the backup is lost. A map this program wrote is left alone (`restore` is the answer), and so is one that
  matches its copy; named with `model`, it says so in `error`.
- **What the app itself calls, from 1.3.8.** The Weather page is the generator only, so it calls `GET /api/weather`
  without `map=1`, `backup`, `generate`, `series`, `remove`, `restoreseries` and `params`; `restore` with each `model`
  that reports `edited`, to undo what the page's first version wrote; and `refreshbackup` (no `model`) while a model
  `differs` without being `edited`. `set`, `setmap` and `map=1` are no longer called by the app; they stay, unchanged,
  for any other client.

- `rwr[]`: `{sym, brg (true°), lethality (0..1), launch, lock, selected, new}`. `sym` is the BMS `RWRsymbol` id (see `Tools/RwrEmulator/Source/ScopeRenderer.cs`). The scope radius follows `clamp(lethality, 0.25, 0.8)`, like the BMS RWR emulator.
- `navPoints[]`: `{i, type, x, y, altFt, name?, rangeNm?}`, where `type` ∈ `WP GM PO MK DL CB L1-L4 PT` (StringData `NavPoint`). PPTs (`PT`) carry `name` and `rangeNm`. From 1.3.8 (additive) the line points (`L1`-`L4`) and the bullseye (`CB`) are sent rather than dropped, and a steerpoint with offset aimpoints carries them as `oa1Brg`, `oa1RngFt`, `oa1ElevFt`, `oa2Brg`, `oa2RngFt`, `oa2ElevFt` (true degrees, feet; absent when it has none).
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
- `tracks`: `SupportTrack[]`, what the campaign planned for the tankers and the AWACS. `yours`: the tanker or AWACS the
  sim names for your flight in 3D; before 3D (1.3.8) the package's own, from the save that provably holds the printed
  flight (the one `ground` is read from), as WDP mode's snapshot has them.

Added in 1.3.8, all additive (an older client ignores them; an older PC's answer decodes with them absent):
- `route`: `MissionRoute`, Falcon BMS's own mission file beside the save being flown — only when it is provably the
  briefed flight's: its route rows match the printed briefing in count and in action words (a Nav point's word may be
  its waypoint's route action in the save, which BMS prints and the file does not carry — "SEAD" either side of a SEAD
  point), **and** every point equals the briefed flight's waypoint in that save (cell + ½) within 2 ft, found by the
  save header's `SaveFile` (not the file's `[MISSION] title`). Anything else (a file WDP rewrote, a file for another
  flight) is left out. It is where the steerpoint positions come from before 3D; `route.steerpoints` holds every
  `target_n` of the file — the route **and** the precision targets (action −1) — and the route line joins only STPT
  1-24 with action ≥ 0. **When the mission file is not believed** (or there is none), `route` is the printed flight's
  own flight plan in that save, with `fromSave` true (added later in 1.3.8): only when the briefing names the flight
  by callsign, package number and flight number, the save holds that flight, and the briefing's steerpoint table is
  its waypoints row for row (the word BMS prints and the arrival time, to the second). Its `steerpoints` are the
  waypoints at their cells' middles (n = waypoint number, the save's action), `file` is empty and it has no PPTs,
  lines or weapon targets; a device marks those points as the save's, as WDP mode marks the populated flight's. The
  same route is what lets `tracks` believe the save before 3D, so both modes show the same steerpoints and tracks.
- `plan`: `PlanOverlay`. Up to the two modes, what the pilot last sent with the Planner's **Send to Mission**. Since
  them (still 1.3.8) it is absent in EZBoards mode — nothing is laid over a printed briefing — and in WDP mode it is the
  Planner's flight of the snapshot (`source` `populated`, see *Mission source*). Each device merges it into its own
  views (`PlanMerge`), and merges nothing while `state` is `parked`.
- `version` is what a client compares to know it has this data: `<briefing time>-<cartridge time>-<board time>`, with
  `-<routeModified>` when BMS's route is believed or the save's flight plan stands in for it, in EZBoards mode; `wdp-<populated.at>` (`wdp-none` before the first
  Populate) in WDP mode.
- `leftovers`: always absent since 1.3.8's automatic clearing (see *The cartridge ledger*).
- `ground` (1.3.8): the mission's threat picture, which every map rings by default (`MissionGround
  {theater, save, flight, callsign?, airDefences: CampSite[], ships: CampSite[], rings: {system: feet}, bullseyeX?,
  bullseyeY?, threatsFrom?: "briefing"|"route", units: [], targets: [], packageRoutes: [], packageFlights: []}`; the
  last four are always empty — the 1.3.8 test builds filled them with ground units and package routes, and the maps
  no longer draw either). **Only the mission's threats, and only sites the side has spotted**: `airDefences` are the
  sites the printed briefing's Threat Analysis names ("SA-19 (2K22)missile launchers 2 nm west of Buk-myeon",
  `threatsFrom` "briefing"), each the save's battalion of that system that BMS words exactly so (nearest city or town,
  whole cells), else the one nearest the point the words describe within 5 nm, else that point
  (`BriefedThreats`); without a printed briefing of the flight (WDP mode: a save's briefing has no threat section) the
  spotted sites whose ring comes within 3 nm of the route (`"route"`, `MissionPicture.alongRoute`). `ships`: the spotted
  task forces near the route. Each system's ring by the Planner's rule (the theater's `Ppt.ini`, else the threat
  reference). EZBoards mode: placed in the save BMS is flying; when it **provably holds the printed flight** (BMS's
  mission file believed for it, or a flight with the briefing's callsign, package and flight number) also `save`,
  `flight`, the bullseye and the ships, else only the briefing's sites (`save`/`flight` empty); `version` then gains
  `-g<hash>`. WDP mode: taken with the snapshot at Populate. Absent when there is nothing to draw.
- `mode` (`"ezboards"` or `"wdp"`) says which mode this is the data of; absent from an older PC, which reads as
  `"ezboards"`. `populated` (WDP mode) is the snapshot's `Populated`; in WDP mode before the first Populate it is absent
  and so is everything else but `version` and `mode` — every view shows "Not populated yet".
- `briefing.origin`: absent for BMS's printed `briefing.txt`; `"printed"` for that same printed briefing carried in WDP
  mode's snapshot because it is the populated flight's (later in 1.3.8, *Populate from Planner*); `"save"` for a
  briefing the PC built from a flight of a save (`CampFlight.briefing`). Such a briefing has the overview, steerpoint rows, package and ordnance, and (1.3.8)
  the texts Falcon BMS's briefing scripts (`Data/Campaign/*.b`) word out of the campaign,
  made the same way from the save and the theater's `Strings.txt`: `situation` (a campaign's: the team's planned
  offensive or defensive, then the package's mission context, Strings 700+/800+ with its `#` tokens filled; a TE's
  or a training mission's: its team's motto, which is where the mission's author writes), `roe` (campaigns only, as
  BMS prints none in a TE), `emergency` (distress call, CSAR, the alternate field and where it is, "Good Luck!"),
  `overview.packageMission` (the Pkg-Mission line), `overview.targetArea` (a target mission's "4 nm south of Tirana",
  as the printed briefing has it; otherwise what the package is against) and a `sections` entry "Mission Overview"
  with the Station/Target Area and Time on Station/Target rows. A sentence with a token the save cannot fill is left
  out rather than printed half-made. It has no weather text, comm ladder, threat analysis, support list, pilot names
  or TACANs — only a printed briefing has those (the save's own weather is its `.twx`, read through
  `/api/files/weather`, and in WDP mode Populate puts it in the snapshot's briefing).
- `dtc` gains, from the cartridge's own sections (all optional): `open[]` (STPT 81-99, the second target bank —
  they **also** stay in `steerpoints`, so an older reader is unchanged), `navOffsets` (`[NAV OFFSETS]`), `comm`
  (`[COMMS]`), `laserSt`, `laserTgp`, `laserLst` (`[Laser]`), `bingoLbs`, `alowFt`, `mslFloorFt` (`[ICP]`) and
  `ewsNames[]` (the six countermeasure program names). Each `ppts[]` entry gains `code` (the key in the theater's
  `Ppt.ini`: `SA3`, `10`, `AWC`), `rangeFt` (the range as the file holds it) and `marker` (a range under 100 ft: a
  point with no ring — AWACS, tanker, a friendly — whose `rangeNm` is 0); `name` and `rangeNm` keep their meaning.
  Each `lines[]` point gains `line` (1-4): each line joins its own points in order, zero points are skipped, and lines
  are never joined to each other.

### CfgState / CfgFile
```json
{ "available": true, "configDir": "D:\\Falcon BMS 4.38\\User\\Config", "backupDir": "…\\User\\Config\\BackUp",
  "userBackedUp": true, "vrPresent": true, "vrBackedUp": true,
  "user": { "selected": 1, "profiles": [true, true, true] },
  "vr":   { "selected": 1, "profiles": [true, true, true] } }
```
`CfgFile` is `{kind, profile, lines[{key, value, launcher}]}` — every `set` line that profile holds, in file order.
`launcher` marks a line below `LAUNCHER OVERRIDES BEGIN HERE`, which the BMS launcher owns: those are shown, never
written, and carried across unchanged when a profile is applied. `kind` is `user` or `vr`.

A setting at its default is **absent**, not written: BMS's stock `Falcon BMS.cfg` already holds every default. Nothing
under `/api/cfg` does anything until `/api/cfg/backup` has taken its copy.

### BmsDefaults (`GET /api/cfg/defaults`)
```json
{ "available": true, "error": null,
  "files": [ { "name": "HARM_Def.ini", "sections": ["HARM"],
               "values": [ { "section": "HARM", "key": "THREAT 0 0", "value": "0202" }, … ] },
             { "name": "MFD_Def.ini", "sections": ["MFD", "Bullseye"], "values": [ … ] } ] }
```
Every `*_Def.ini` in `User/Config` (EWS, HARM, IFF and MFD in 4.38.1), in name order, each key with its section and
its value **as text** (codes keep their leading zeros), in file order. These files belong to BMS and the route only
reads them. `available` is false when the PC has no BMS install; a file that could not be read is named in `error`
and the others still come back. Added in 1.3.8 (additive; older PCs answer 404 and the page keeps its own values).

### EzRun
`{time, ok, durationMs, message, log[] (last 40 lines, ANSI and progress bars stripped), auto}`
Success = exit code 0 **and** a `SUCCESS.` line from `EZBOARDS.BAT`.

## Mission source: EZBoards mode and WDP mode (1.3.8)

The Mission section — Dashboard, Map, Taxi, Briefing, Comms, Kneeboards, AWACS and the VR boards — is filled from one
of two sources, never a mix. The choice lives on the PC (`bridge-settings.json`: `MissionSource`, `MissionSourceSince`;
brought forward once to EZBoards mode for everyone who had the program before, settings layout 2), so every device
shows the same one; the words the pages use are `MissionMode` in `MissionModels.kt`.

- **EZBoards mode** (`ezboards`, the default): "BMS briefing — filled when you press PRINT in BMS". `/api/mission` is
  the printed `briefing.txt`, the pilot's cartridge, EZBoards' board, the planned tracks and BMS's believed route, read
  again whenever BMS writes them — exactly as before 1.3.8, except that nothing is laid over it (`plan` absent). EZBoards
  runs on PRINT when `autoOnPrint` is on, and GENERATE NOW works. The Planner is not used in this mode.
- **WDP mode** (`wdp`): "Planner — filled from a save file and your cartridge". Nothing comes in by itself:
  **Populate from Planner** takes a snapshot, and `/api/mission` serves it until the next Populate. EZBoards is
  suspended (`info.ezBoards.suspended`): GENERATE NOW answers 409 with `EzRun.message` "WDP mode: EZBoards is paused
  so it cannot overwrite the Planner's pages. Make the cockpit boards with Planner → Upd Kneeboard." and nothing runs
  on PRINT, while `AutoEzBoardsOnPrint` keeps whatever the pilot set. Run HTML Briefing (`POST /api/kneeboard/open`)
  answers 409 too (`info.kneeboard.runSuspended`): its export writes pages 1-3 over the Planner's. Everything else —
  the VR boards, `/api/kneeboard/page`, live data — is the same in both modes. `/api/mission?source=bms` still gives BMS's own files, which the Planner plans from.

**Switching.** `POST /api/mission/source?mode=ezboards|wdp` (or the word as the body) → `MissionSourceInfo`. It asks
nothing. Another word is refused with 400. `info.briefing.planModified` moves, so every client refetches the mission.

**What a switch resets (1.3.8).** Before the mode changes the PC clears **every leftover** of the Planner's, silently
(docs/DATA-STORES.md, "What a switch resets"). The current flight is, switching to EZBoards mode, the printed
briefing's; switching to WDP mode, `for` (the flight the switching device's Planner has open: a `LedgerMission`, as
`/api/cartridge/save` takes it), else the printed briefing's. It began at the earlier of its PRINT (when the briefing is
the same flight) and its `opened` — which only the cockpit pages are judged by.
- the cartridge and the TEs' own mission files: every key the ledger says the Planner wrote (*The cartridge ledger*:
  lines, PPTs, steerpoints and targets, weapon targets, nav offsets) that still holds exactly the Planner's value,
  **whatever flight it was saved for and whenever** — the current flight's too; cleared to BMS's empty values with the
  same safe write as Clear, and kept in the ledger (`CartridgeLedger.cleared`: `LedgerCleared {write, wrote?, at,
  undone}`, the last five switches) for Undo. A key BMS or the pilot wrote since is never touched — except the
  **delivery data**: every `[NAV OFFSETS]` key that places something (Modesel other than none, VIP/VIPPUP/VRP/VRPPUP
  not all zeros, any OA1-n/OA2-n) is cleared whoever wrote it, ledger or not (an earlier build or WDP wrote them; BMS's
  own DTC window never edits them), in the cartridge and in the current flight's TE `.ini`;
- the cockpit kneeboard pages of the theater BMS is on: to EZBoards mode, each half whose owner is BMS Companion's tag
  (Upd Kneeboard); to WDP mode, each half EZBoards or html_brief made — given BMS's shipped page back (the whole file
  byte for byte when the other half is BMS's too, else that half drawn from it), only where the theater ships one;
  never a page file created, each kept in its format; a page made since the current flight began is kept;
- WDP mode's snapshot: kept on a switch to EZBoards mode; on a switch to WDP mode, discarded when it is not the current
  flight's (moved to `wdp-mission.discarded.json` beside it), so the section says "not populated yet"; kept when no
  flight is current.

The answer, and `info.mission` for 30 minutes after (while the mode is still the one switched to), carry the summary:
```
SwitchReset  {at, to, now?: LedgerMission, keys: Leftover[] (each with file: "" = the cartridge, or "<TE>.ini"),
              cartridge?, pages: ["page 2, left", …], snapshot?, done: [sentence], left: [sentence], undo, undone,
              undoMessage?, kind: switch|mission}
```
**A new mission** (1.3.8, `kind` `mission`; absent = `switch`) clears the cartridge and the TEs' files by itself: in
EZBoards mode when BMS prints another flight than the last one printed (and once when the PC program starts), in WDP
mode when a device's Planner opens another flight (`POST /api/mission/opened?for=`) or populates one. It clears every
key the Planner saved for **any other flight** than the new one, whenever (a key saved with no flight known is kept),
still holding the Planner's value, and all the delivery data (`[NAV OFFSETS]`, as at a switch) except what the
ledger says the Planner saved for the new flight itself. So a Populate never lays out an earlier mission's VIP/VRP
attack (it runs this before reading the cartridge), and what an older Planner posted to `/api/attack` is forgotten. It never touches the cockpit pages; in WDP mode it discards a snapshot of another
flight. Its summary is served and undone exactly as a switch's, only when something was done.
No device shows the summary (the pilot asked for no notice: the reset happens silently); it is served, and `undo`
offers `POST /api/mission/source/undo?at=` for the keys (pages are not undone: they are BMS's own originals), for
compatibility. A developer run writes only into a copy (`DevGuard`; the Undo route is guarded like the other writers).

**Populate from Planner.** `POST /api/mission/populate` with a body of `PopulateSend` → `MissionSourceInfo`:
```
PopulateSend       {ref: CampRef, seat? (0-3, default 0), attack?: AttackOverlay, from? (PC|Android|Browser), callsign?}
MissionSourceInfo  {mode: ezboards|wdp, switched, populated?: Populated, line?, reset?: SwitchReset}
Populated          {at, from?, theater, save, flight, callsign?, seat, packageId?, kind?, saveModified, cartridge?,
                    cartridgeModified, missionFile?, missionFileModified, attack, notes[], changed[],
                    weatherFile?, weatherFileModified, briefingFrom?, briefingPrinted}
```
The PC builds the snapshot from:
- the flight `ref` names, read as `/api/campaign/flight` reads it (`CampFlight`: route, loadout, package, support,
  intel, and the `Briefing` made of it with `origin` `"save"` — see *Campaign files*);
- **BMS's printed briefing, when it is that flight's** (added later in 1.3.8; `briefingFrom` `"printed"`,
  `briefingPrinted` its file time): the flight `/api/campaign/flight` marks `briefed` (the printed briefing's callsign,
  package number and flight number are its own in that save), the printed briefing's callsign the flight's, the save's
  theater the one BMS is set to. The snapshot's briefing is then the printed `briefing.txt` exactly as EZBoards mode
  serves it, with `origin` `"printed"` — the same flight shows the same mission in both modes. Otherwise
  (`briefingFrom` `"save"`) it is the save's own `Briefing`, as before. An older snapshot has no `briefingFrom`;
- the pilot's cartridge **as saved** on disk — `callsign`'s, or the pilot BMS has selected — parsed as `MissionData.dtc`
  always is, with the theater's `Ppt.ini` names. Unsaved edits never travel: a page holding some asks first ("Save to
  DTC and populate" / "Populate without them" / "Cancel"), and any other field in the body is ignored. No cartridge is
  a `note`, not a refusal. A save of another theater than the one BMS is set to leaves the cartridge's positions
  (steerpoints, STPT 81-99, weapon targets) out, as the Planner does;
- the mission file beside the save: its steerpoints only where they are that flight's route (`CampFlight.missionIni`
  matches), a TE's or training's PPTs, lines and weapon targets from it too (BMS loads them from there), and the save
  header's bullseye;
- the tanker and AWACS tracks of that save, by the same rules as the printed mission's (the ones overlapping the
  flight's time; the package's own are `yours`);
- the save's own **weather** file, `<save>.twx` beside it (added within 1.3.8; `weatherFile`, `weatherFileModified`):
  read exactly as the Planner's card reads it — through `GET /api/files/weather` with `save=` (another save's file is
  refused: `Auto Save.cam` and `Auto Save.tac` share one) and `clock=` the flight's campaign time (a map-model save's
  update map in force, else its `<save>.fmap`, else the file's own tables) — and each place's weather taken as the
  card takes it (the map's cell the place is in, or the table of the type the file is in). It becomes the briefing's
  `weather`, the table BMS's printed briefing has: `columns` `["Take Off", "Target Area", "Landing"]` (the flight's
  home field, the waypoint BMS marks as the target — at its target's position — else the first strike steerpoint, and
  the landing field), rows `Situation` ("Fair"), `Wind` ("80deg@ 15kts.", or "VRB@ 15kts." when BMS picks the
  direction itself and the file holds none), `Visibility` ("115km"), `Temp` ("23deg C."), `Cloud Base` ("5,000 ft MSL
  base", "None" for a map cell without cloud) and `QNH` ("1013 hPa · 29.91 inHg", which BMS's printed briefing never
  has); no contrail row. `WeatherTable.source` (additive, null on a printed briefing) says where it is from:
  "From Auto Save.twx, as saved at D1 01:02. … BMS may change the weather before take-off." The same table is the
  plan's `flight.briefing.weather` (`PlanMerge` takes that briefing first). A file that is missing, another save's or
  unreadable brings no weather and a `note` beginning "No weather came with it: " with the reason; the Dashboard's
  WEATHER card and the Briefing page show that reason. Over a printed briefing (`briefingFrom` `"printed"`) the
  file's weather is taken only by the Planner card's rule: the print has no take-off weather, or the file was saved
  after the print and says other weather at take-off (the type, the wind's speed, the temperature) — then
  `weatherFile` names it and a `note` says so; otherwise the printed forecast stays and `weatherFile` is absent. Never
  a file Reload WX picked;
- `attack` (the Planner's current attack, 1.3.8: the attack page last changed or applied), its `saved` set by the PC
  from the cartridge as saved; or, when none is sent, the one the cartridge's `[NAV OFFSETS]` lay out on the route.

It is served as `MissionData` with `mode` `"wdp"`, `version` `"wdp-<at>"`, `briefing` the printed one (`origin`
`"printed"`) or the save's (`origin` `"save"`) as above, `briefingModified` 0, `dtc` the cartridge, `board` absent,
`tracks`, `route` as above, `ground` (with a printed briefing, the sites its Threat Analysis names, as EZBoards mode
places them), `populated`, and `plan` a `PlanOverlay` with `source` `populated`, `state` `applied`, the `flight` (whose
`briefing` is the same as `MissionData.briefing`), `ref`, `seat` and `attack`, and an **empty** `dtc` (the cartridge is
`MissionData.dtc`, so nothing is marked as the plan's). `PlanMerge` takes a `"printed"` briefing exactly as it takes
the printed briefing in EZBoards mode, a `"save"` one as the mission with nothing printed under it; no banner either
way, the page's source line says where it came from.

Refusals (`{"error": "<sentence>"}`): 409 in EZBoards mode ("Populate from Planner works in WDP mode: …"), 409 with no
flight in the body ("Open your flight in the Planner first (Open mission…, then pick the flight), then press Populate
from Planner."), 400 for a seat outside 0-3, a theater BMS does not have or a body that is not a `PopulateSend`, the
campaign-file refusals of `/api/campaign/flight` with their own status, 409 when the save is gone or the cartridge
cannot be read, and 409 when the snapshot cannot be kept (then the one before stays served).

**Kept** in `%APPDATA%\BMS Companion\wdp-mission.json` — never in the BMS folder — through a temporary file and an
atomic move, and read back at start. **Changed since:** `populated.changed` names what changed on disk after the
snapshot (`"save"`, `"cartridge"`, `"mission file"`, `"weather file"`: a file time that moved, a file that went, a
cartridge or a weather file that appeared — a TE's weather saved with SAVE WTH moves the `.twx` alone; a snapshot kept
before the weather came with Populate watches no weather file; and `"printed briefing"` when BMS printed a briefing after
the Populate, the next mission being set up), looked at every two seconds at most. It is served for compatibility;
the app no longer shows it (the pilot asked not to be told), and the PC never takes the snapshot again by itself.

## Planner integration (1.3.8)

The Planner (Mission → Planner, the port of Weapon Delivery Planner) works from the printed briefing and the pilot's
cartridge by default, or from one flight of a campaign, TE or training save the pilot opens. It is used in WDP mode
(*Mission source*): what it plans reaches the rest of the app only when the pilot presses **Populate from Planner**,
and reaches the cockpit through **Save to DTC** (the cartridge) and **Upd Kneeboard** (BMS's 3D kneeboard pages).
Every route here needs the BMS PC; on a phone or in a
browser the Planner says so when the link is down. Models: `CampaignModels.kt`, `PlanModels.kt`, `KbPrintModels.kt`
and the additions in `MissionModels.kt`, all in `app/src/main/java/com/bmscompanion/app/data/mission/`.

**Errors.** A route that cannot do what was asked answers `{"error": "<sentence>"}` — HTTP **400** for a request it
cannot take (an unknown theater, a file name that is not in that theater's listing, a body that is not a cartridge),
**409** for a refusal in the present state (a file that ships with BMS, a read-only file, a page file in use), and
**501** for a route this PC has not built yet. A PC older than 1.3.8 answers **404**. Clients show the sentence; the
three `MissionLink` copies hand it back as `PcAnswer {value?, error?, status}` (`status` 0 when the PC could not be
reached). When a developer check is running (`bmsc.devcheck`, or `BMSC_DEV_GUARD=1`), every route that writes into a
BMS folder — `/api/cartridge/*`, `/api/weather/*`, `/api/cfg/*`, `/api/rtt/enable`, `/api/kbprint/file`,
`/api/kbprint/shipped`, `/api/ezboards/generate`, `/api/media/delete`, `/api/acmi/clear`, `/api/radio/cleanup/now`
(BMS's logs folder), and `/api/files/write` for the folder it names — answers **409**
`{"error": "developer run: writes go to a copy only (…)"}` unless that folder is a copy (no `Falcon BMS.exe` in it or
above it, not the registry's BMS folder, not inside it or containing it). EZBoards' refusal adds `"ok": false,
"message"`, media delete's `"deleted": 0`, ACMI clear's `"deleted": 0, "bytes": 0`. Writes into the PC program's own
settings folder are not affected.

### Campaign files (read only)
Falcon BMS keeps each theater's saves in the `campaigndir` of its theater definition
(`Data/TerrData/TheaterDefinition/theater.lst` → `<theater>.tdf`), which is not always `Data/Campaign`: Korea 2012 and
the add-on theaters have their own. These routes list and read those files and never write anything.

- `GET /api/campaign/files[?all=1]` → `CampFiles`. Every theater of `theater.lst`, the one BMS is set to (the
  registry's `curTheater`) first, then by each theater's newest file; each theater's `.cam`, `.tac` and `.trn` files
  directly in its campaign folder, newest first by `sortTime` = the later of created and modified (ties by modified).
  Campaign starts and templates are left out unless `all=1`. A file modified less than 1.5 s ago is left out until
  BMS has finished writing it.
- `GET /api/campaign/ato?theater=<name>&file=<name>` → `CampAto`, the save's whole air tasking order (a big campaign
  is about 60 KB).
- `GET /api/campaign/atotargets?theater=<name>&file=<name>[&team=<1-7>]` → `CampAtoTargets` (added in 1.3.8): the
  Weapon Delivery Planner's ATO Target List (`fclsAtoTargetList`), what the flights of one side are tasked to attack.
  `side` is the team asked for (the Planner sends the team of the flight it has open), else the pilot's own (the
  briefed flight's, a player flight's, the player squadron's; 0 when none is known, and the lists are empty); the side
  is that team and every team the team controlling it is allied or friendly with (`sideTeams`, their names). Each flight
  of the side whose route names a target — the first waypoint after the first with a target, not a take-off or a
  landing — is one row, in `units` when the target is a unit of the save and in `objectives` when it is an objective of
  the start file, each list sorted by `target`. A row (`CampAtoTarget`): `nr` (the target's place in the save's units or
  in the start file's objectives, WDP's "Nr"; -1 when neither holds it), `target` (named as WDP names it), `tot` (the
  target waypoint's arrival) and `takeoff` (the first waypoint's departure), both campaign ms, `x` north and `y` east
  (feet; absent when unknown), `packageNumber`, `flight` (callsign), `flightId` ("num/creator"), `aircraft`, `mission`,
  `squadron` ("36th"), `airbase`, `team`. `clock` is the save's campaign clock: a flight is airborne when its `takeoff`
  is before it, a TOT has passed when `tot` is. `notes` are sentences for the window. 400 for a `team` that is not a
  team number.
- `GET /api/campaign/flight?theater=<name>&file=<name>&flight=<num/creator>` → `CampFlight`, with the flight made into
  a `Briefing` on the PC (`briefing.origin = "save"`).
- `GET /api/campaign/objectives?theater=<name>&file=<name>` → `CampObjectives` (added in 1.3.8): every objective of the
  save's start file, as the Weapon Delivery Planner's Target Selection window lists them for the DataCard's DMPI boxes
  (`fclsTargetSelection`). `types` are the objective types present, in BMS's order (Strings.txt 501-531); each
  objective has its `id` ("num/creator", what `/features` takes), `campId`, `name`, `type`, position (`x` north, `y`
  east), `elevFt` (BMS's height map at the cell WDP's `ReadNewTerrainElvLoc` reads; absent without one) and `control`
  (the team holding it in the start file, "PRC(5)").
- `GET /api/campaign/features?theater=<name>&file=<name>&objective=<num/creator>` → `CampFeatures` (added in 1.3.8):
  one objective's buildings (`ObjectiveRelatedData/OCD_nnnnn/FED_nnnnn.XML`), each with `n` (its index, the waypoint's
  `building`), `name` (its feature record's), its position — the objective's plus the feature's offset, `OffsetY`
  north and `OffsetX` east, as WDP's `FillPriTarget` places it — `elevFt` and `value` (BMS's worth; WDP lists only those
  above 0 unless Show all is ticked). 400 when the start file has no such objective.
- `GET /api/campaign/ground?theater=<name>&at=<north>,<east>;<north>,<east>…` → `CampGround` (added in 1.3.8): the
  ground under 1 to 200 points (theater feet, north first) from BMS's own height map, the cell WDP's
  `ReadNewTerrainElvLoc` reads — what the attack pages stand their ELEVs on. `heights` is feet above sea level, one per
  point in order, `null` where the map says nothing; `error` a sentence when there is no height map. `theater` may be
  left out for the theater BMS is set to, and the app's theater id works as well as the definition's name. Read only,
  two bytes a point; 400 for a malformed point or an unknown theater.

`theater` must be one of the theater-definition names the listing gave; `file` a plain name (no `\`, `/`, `:` or `..`)
that is in that theater's listing. Anything else is a 400.

**Campaign starts.** The files BMS's campaigns and TEs begin from are never opened or written, by any route: an `.obj`
part, no flights, or a start's name (`Save<n>`, `Te_New*`, `Instant`). They are `CampFile.start`, with the reason in
`stockWhy`, and Open mission lists them greyed. Every other save opens and is saved to as it is in WDP, the missions
BMS ships (`TE_BMS_*`, `TR_BMS_*`) included: those are only marked `CampFile.stock` (a start is too), which the list
uses to fold them away under *Show BMS's own missions*. (1.3.8 test builds opened those missions read-only and called
them stock with a `stockWhy`; `stockWhy` is now null for everything but a start.)

```
CampFiles     {theaters[CampTheater], current?, briefing?: CampBriefKey, error?}
CampBriefKey  {callsign, packageId?, flightId?, printed}           the printed briefing's flight; printed = file time
CampTheater   {name, appTheater?, folder, current, files[CampFile], error?}
CampFile      {name, kind: campaign|te|training, modified, created, sortTime, size, start, stock, stockWhy?, title?,
               clock?, flights?, packages?, player?, briefed, version?, error?}
CampRef       {theater, file, flight}                               flight = "num/creator"
CampAto       {theater, file, modified, clock, version, title?, teams[CampTeam], packages[CampPackage], notes[]}
CampTeam      {n, name, allied}
CampPackage   {id, number, owner, mission?, takeoff?, target?, flights[CampFlightRow], tanker?, awacs?, jstars?, ecm?}
CampFlightRow {id, number, callsign, mission, task?, aircraft, count, squadron?, base?, team, takeoff, tot, player,
               briefed, f16}
CampFlight    {row, packageNumber, route[CampWaypoint], loadouts[CampLoadout], fuelLb[], laser[], home?, landing?,
               alternate?, packageFlights[CampFlightRow], support[CampSupport], missionIni?: CampIniCheck,
               briefing?: Briefing, notes[], kind?, intel?: CampIntel, airDefences[CampSite], ships[CampSite],
               bullseyeX?, bullseyeY?, clock?, currentWp?, departures?: CampDepartures,
               packageRoutes[CampPackageRoute], bullseyeName?, sideSupport[CampSupport], fuelBurnt?}
CampPackageRoute {id, route[CampWaypoint]}                         another package flight's route (no targets)
CampDepartures {field, rows[CampDeparture]}                         WDP's Airport Schedule of the departure field
CampDeparture {depart, aircraft, callsign, squadron, packageNumber?, mission, own}
CampIntel     {ground[], fighters[], fighterBombers[], bombers[], support[], helos[]}     vehicle names, sorted
CampSite      {system, name?, x, y, spotted}                        an enemy air-defence battalion or task force
CampWaypoint  {n, x, y, altFt, arriveMs, departMs, action, desc?, routeAction, formation, spacing, target?: CampPlace,
               designated[CampPlace|null]}                          designated: per seat 1-4, save version 104 on
CampPlace     {kind, campId?, name?, building?, x?, y?}
CampLoadout   {stores[Store]}                                       hardpoint order
CampSupport   {role, callsign, track[TrackPoint], aircraft?, tacan?, leg}
CampObjectives {types[], objectives[CampObjective]}
CampObjective {id, campId, name, type, x, y, elevFt?, control}
CampFeatures  {objective, features[CampFeature]}
CampFeature   {n, name, x, y, elevFt?, value}
CampIniCheck  {file, modified, matches, reason?}
```
Positions are theater feet (x north, y east; a waypoint is its cell's middle). `clock`, `takeoff`, `tot`,
`arriveMs`, `departMs` are campaign milliseconds (day 1 00:00 = 0); `modified`, `created`, `printed` are file times
(ms since 1970). `owner` and `team` are `CampTeam.n`; a package is listed under its owner and a flight carries its own
team, which may differ. `CampFlight` has no TACAN: the save's value and the printed briefing's disagree, and the
briefing's is the checked one. `CampFlight.kind` (added in 1.3.8) is the save's kind (`campaign`, `te`, `training`):
a TE's printed briefing has no situation and no ROE, so a page does not send the pilot to PRINT for them.
`CampFlight.intel` (added in 1.3.8) is the Weapon Delivery Planner's own intelligence for the flight
(`FillMissionBriefing`): the distinct vehicle names of every unit hostile to the flight's team or at war with it in the
save's team table (`.tea`) and not destroyed — `ground` the air-defence battalions (class sub-type 1), and the
squadrons by what they fly: `fighters` (8), `fighterBombers` (9), `bombers` (3, 6), `support` (5, 7, 10, 11, 13),
`helos` (4, 12, 14). It is the whole theater, not the route; absent when the team table could not be read.
`CampFlight.airDefences` and `ships` (added in 1.3.8) are the units of the same test (hostile or at war, not
destroyed) that the Planner can put in the cartridge: the air-defence battalions (class sub-type 1) and the task
forces, each with `system` (the unit table's name for what it fields: "SA-2", what the threat reference and the
theater's PPT table are matched on), `name` (the unit as the briefing names it, "500th Air Defense Battalion"), its
cell's middle as `x`/`y`, and `spotted`: whether the flight's side has seen it, by the unit's spotted bit of the team
that controls the flight's team or of the flight's own (in Korea ROK controls the U.S. team, whose own bit is never
set). **Since 1.3.8's last builds only the spotted ones are sent** (`spotted` is then always true): no route gives a
device an enemy site its side has not seen. Empty when the team table could not
be read. `bullseyeX`/`bullseyeY` (added in 1.3.8) are the save header's bullseye, read as `MissionRoute`'s is (whole
cells, X east and Y north, the middle of the cell as BMS places it), absent when the header sets none. `clock` (added in 1.3.8) is the save
header's campaign clock, the DataCard's *Current Time*; `currentWp` the waypoint the flight is flying to as the save
holds it. `departures` (added in 1.3.8) is the Weapon Delivery Planner's *Airport Schedule* (`fclsAptSchedule`) of the
field the flight departs from: every flight of the save, any team, whose first waypoint is tasked against the same
place (the ids' numbers compared, as WDP does), the first 100 in the save's order, those with a departure time,
earliest first and in the save's order among equal times; `field` is the field's name without "Airbase",
"Highwaystrip" and "Airstrip", and each row is WDP's six columns — `depart` (campaign ms of the first waypoint's
departure), `aircraft`, `callsign`, `squadron` (its number and ordinal, "36th"), `packageNumber`, `mission` (Strings.txt
300 + the flight's mission) — with `own` on the planned flight. `packageRoutes` (added in 1.3.8) are the routes of the package's other flights
(`packageFlights`), each by its flight `id`, for the Weapon Delivery Planner's Coordination Card (`FillPackages`,
`FillCommCardPackages`: each flight's take-off, push, target and holding point with their times and altitudes); the
waypoints carry no `target` or `designated`. `bullseyeName` (added in 1.3.8) is what the header calls the bullseye, as
WDP words it (`Bullseye()`: "Bullseye" for the header's name 1, "Rose" for any other), absent with `bullseyeX`.
`sideSupport` (added in 1.3.8; empty from an older PC) is the Weapon Delivery Planner's DataCard support tables
(`Tankers`, `Awacs`, `JSTAR`): every flight of the save on a tanker (28), AWACS (26) or JSTARS (27) mission of the
flight's side — its own team, or a team the controller of its team is allied or friendly with — in the save's order,
each with `aircraft` (the vehicle, "KC-135R"), `tacan` (the channel the save gives the tanker itself, "123Y", from
save version 108; the receiver dials the other side of the band, 60Y) and `leg`, the index in `track` of the waypoint
its station begins at: the first tanker (24) or ELINT (20) action, -1 when the route has none. The same three fields
are filled in `support`. `notes` are sentences for the page (another theater than BMS is set to, a campaign
start, a file older than the printed briefing, a flight that has already flown, more than 24 waypoints, …).

### Map intel and magnetic variation (`/api/campaign/mapintel`, `/api/campaign/magvar`, 1.3.8)
What the Planner's Map page draws of a save beyond the flight (the Weapon Delivery Planner's MAP tab: `SamIntel`,
`ObjHasWorkingRadar`, `JSTAR`, `CheckOwnSide`), and the theater's magnetic variation. Read only, like every
`/api/campaign` route; cached by the save's path, size and time; a part that cannot be read becomes a sentence in
`notes`, never a failure.

- `GET /api/campaign/mapintel?theater=<name>&file=<name>[&flight=<num/creator>][&team=<0-7>]` → `CampMapIntel`.
  `theater` and `file` name a save as the other routes do. `flight` gives the side (that flight's team); `team`
  overrides it; with neither, the briefed flight of that save, else its player flight, else the player squadron's team.
  With `file` left out the PC takes the save and flight BMS briefed (as `/api/campaign/files` marks it: `CampBriefKey`),
  looked for in `theater`, else in the theater BMS is set to, newest save first; the answer's `file` and `flight` then
  say what was used, for `/api/campaign/flight`. 404 `{"error"}` for a save that is not there (or no briefed save);
  409 when no BMS folder is set, for a file BMS is still writing, and for a campaign start (its `stockWhy`); 400 for a
  malformed `flight` or `team` or an unknown theater. A PC before 1.3.8 answers 404 too: the page then falls back on
  `CampFlight.airDefences` and `ships`. Clients wait up to 30 s. **No cheating**: `units` holds only the side's own
  and the others' it has seen (`spotted`, `recent` or `jstar`); an unseen enemy unit never leaves the PC.
- `GET /api/campaign/magvar[?theater=<name or app theater id>]` → `CampMagVar`: Falcon BMS's own variation map,
  `<terraindir>\Weather\MagVarMap_<the terraindir's last folder>.csv` (the theater definition's `terraindir`; Korea's
  `TerrData\Korea` when it names none, as Korea TvT), else the only `MagVarMap_*.csv` there. Every 4.38.1 theater
  reaches one: the Korea variants Korea's, EF2000 Balkans', Hellas WCP and LHTO Hellas'. `theater` left out = the
  one BMS is set to. 404 `{"error": "Falcon BMS has no magnetic variation map for <theater> (…)."}` when there is
  none (the page hides VAR); 409 without a BMS folder. Clients wait up to 15 s and keep it per theater.

```
CampMapIntel  {theater, file, modified (file time), clock (campaign ms), flight?: "num/creator", team, controller,
               teams[CampMapTeam], units[CampMapUnit], radars[CampMapRadar], fields[CampMapField],
               jstars[CampMapJstar], notes[]}
CampMapTeam   {n, name, colour: 0xRRGGBB (the header's team colour: white, green, blue, brown, orange, yellow, red, grey),
               side}
CampMapUnit   {id: "num/creator", campId, kind, domain: "land"|"sea", system, shorad?, name?, owner, side, x, y,
               spotted, seen?, recent, jstar, moveType?, radar, moving, dead, alive?, total?}
CampMapRadar  {id, campId, name, type, x, y, owner, side, working, radars, intact}
CampMapField  {id, campId, name, type: "Airbase"|"Airstrip", airport?, x, y, owner, side, startOwner,
               squadrons[CampMapSquadron {aircraft, name?, owner, side, count?}]}
CampMapJstar  {callsign, aircraft?, x, y, from, to, active, rangeNm: 200}
CampMagVar    {theater, file (relative to the BMS folder), xKm[], yKm[], deg[]}      deg row-major, negative = west
```

- **Sides** (`side`, a string everywhere) are said from `team`'s point of view, from the save's team table (`.tea`) as
  WDP's `CheckOwnSide`: its own team and the team controlling it (`controller`), and teams it is allied or friendly
  with, are `friendly`; hostile or at war `hostile`; neutral or no relation `neutral` (a relation the team leaves at
  none is taken from its controller's record); everything is `unknown` when `team` is 0.
- **Units** are every ground battalion (`domain` "land") and naval task force ("sea") of the save; brigades are left
  out, their battalions are listed. `kind` comes from the class table's sub-type: land `airdefence`, `airmobile`,
  `armor`, `cavalry`, `engineer`, `hq`, `infantry`, `marine`, `mechanized`, `rocket`, `artillery` (self-propelled),
  `missile`, `supply`, `towed`, `recon` (a unit named "Recon…"), `other`; sea `carrier`, `cruiser`, `destroyer`,
  `frigate`, `patrol`, `tanker`, `ship`. `system` is the unit's first vehicle as the class tables name it ("SA-2
  (S-75)", "S-60", "Osa II CLS"), as `CampSite.system`; `shorad` the short-range SAM a unit that is not air defence
  carries (SA-8, -13, -15, -17, -19, KSAM Chun-ma, Avenger), so a unit's threat type is `shorad ?: system`. `x` north
  and `y` east are the unit's cell middle.
- **Seen**: `spotted` is the save's spotted bit of `controller` or of `team` (always true for a friendly unit);
  `seen` the unit's last spot time, else its last combat (campaign ms; absent for neither); `recent` WDP's recon-loss
  rule: seen within 60 min before the clock for a unit that does not move, 20 on foot, 10 tracked, naval or rail, 1 in
  the air, and never for a wheeled one (`moveType` 2, as WDP); `jstar` within `rangeNm` of a JSTARS of the flight's side
  that is on station. `moving` and `dead` are the unit's flags (0x400, 0x20000).
- **Radar**: `alive` while the unit table's radar vehicle slot still holds a vehicle — the roster read as a count, two
  bits a slot — or, for a self-contained system (SA-8, SA-11, SA-13, SA-15, SA-17, Chaparral, Crotale, Roland,
  Chun-ma), while any vehicle of its own type is left; `dead` when none is; `none` for a unit with no radar vehicle
  (guns, MANPADS, most units that are not air defence); `unknown` when the unit table could not be read. `alive` and
  `total` count the unit's vehicles.
- **Radars** are the start file's objectives flagged for a radar (bits 16 or 22, or radar data) whose buildings include
  one — a feature named "Radar …" in `Falcon4_FCD.xml` — with the owner and the buildings' states from the save's
  objective changes (`.obd`; a played save keeps only the objectives that changed) or else the start file's. `working`:
  `intact` of `radars` stand. The page draws WDP's 25 nm ring: BMS publishes no search-radar range.
- **Fields** are the start file's airbases and airstrips with the team holding each now (`owner`), the start file's
  (`startOwner`), `airport` (the app's airport id when the field's campaign id is one, as a string) and the squadrons
  based there (`aircraft`, `name` only for the flight's own side, `count` the aircraft its roster holds).
- **JSTARS** are the flights of the flight's side on a JSTARS mission (27): the station is the middle of the leg its
  ELINT action (20) starts (cell middles), `from` its arrival there and `to` its departure from the next waypoint;
  `active` while the save's clock is between the two.
- **Magnetic variation**: `xKm` are the columns (km east, the header `y\x, 0, 16, …`), `yKm` the rows (km north, each
  row placed by its own first value, ascending); `deg[r * xKm.size + c]` is the variation at (`yKm[r]` north,
  `xKm[c]` east). `CampMagVar.at(northFt, eastFt)` interpolates bilinearly (km = ft / 3279.98), clamped to the grid.

### Send to Mission (`/api/plan`)
Replaced within 1.3.8 by the two modes (*Mission source*): the Planner has no Send to Mission button any more, and
`/api/mission` never carries this plan. The routes below still answer as they did, so an older client is not refused,
and `planner-plan.json` is left where it is.

- `GET /api/plan` → `PlanOverlay`: the plan the PC holds; `id` 0 when there is none (then `canUndo` says whether a
  cleared one can be brought back).
- `POST /api/plan` with a body of `PlanSend` → `PlanOverlay`. `cartridge` is the Planner's cartridge text, saved or
  not (null: the PC takes the pilot's cartridge on disk); `attack` the attack the page worked out; `from` the kind of
  device (`PC`, `Android`, `Browser` — never a name); `ref` and `seat` the flight and seat when a save is open in the
  Planner, and the PC attaches that `CampFlight` itself. Refused with a sentence: no BMS folder, no cartridge for this
  pilot, a body that is not a cartridge (no `[STPT]`, or over 256 KB), a plan for another theater.
- `POST /api/plan/files[?from=]` (no body) → `PlanOverlay` with `source` `files`: a snapshot of what is on the PC now —
  the pilot's `<callsign>.ini`, the mission file beside the current save and the save's header bullseye. This is the
  path for a pilot who plans in Falcas's own WDP; the app cannot tell a WDP write from a BMS write and does not claim
  to. The attack is rebuilt from `[NAV OFFSETS]`.
- `POST /api/plan/clear` → `PlanOverlay` (empty, `canUndo` true). `POST /api/plan/undo` → `PlanOverlay`: swaps the
  previous plan back. A new send makes the one before it the Undo.

Nothing is written into Falcon BMS. The PC keeps the plan in memory and in `%APPDATA%\BMS Companion\planner-plan.json`
(`planner-plan.prev.json` for Undo), never in the BMS folder and never in `bridge-settings.json`, and serves it as
`MissionData.plan`; `info.briefing.planModified` changes with it. Whether it is applied:

| Situation | `state` |
|---|---|
| a printed briefing for the same flight (callsign + package number + flight number) | `applied` |
| no printed briefing, and the plan carries a flight from a save | `applied` (the save's `Briefing` is the mission) |
| a printed briefing for another flight, older than the save | `applied`, with `note` saying so |
| a printed briefing for another flight, newer than the plan | `parked`, with `note` saying why; devices merge nothing |
| no briefing and no flight | `applied` (cartridge items only) |

`notInJet` names what the jet will not have until the pilot saves and loads the DTC (`"STPT 5"`, `"PPT 57"`,
`"LINE 2"`, `"UHF 3"`, `"OA1 on 6"`): positions within 50 ft, frequencies and codes exactly, compared on parsed values
against the cartridge on disk before 3D and against the jet's own navigation points in 3D. It is worked out again when
the cartridge or the jet changes, so a Save to DTC empties it.

```
PlanOverlay   {id, source: planner|files, from?, theater, flight?: CampFlight, ref?: CampRef, seat?, callsign?,
               packageId?, briefing?, state: applied|parked, note?, dtc: Dtc, route?: MissionRoute,
               attack?: AttackOverlay, notInJet[], canUndo}
PlanSend      {cartridge?, attack?: AttackOverlay, from?, ref?: CampRef, seat?}
MissionRoute  {file, save, kind, modified, steerpoints[DtcPoint], ppts[DtcPpt], lines[DtcPoint],
               weaponTargets[DtcPoint], bullseyeX?, bullseyeY?, fromSave}     fromSave: the save's flight plan, not the file
NavOffsets    {mode: vip|vrp|none, vip?, vipPup?, vrp?, vrpPup?, oa[NavOffset]}
NavOffset     {key, stpt, bearing, rangeFt, elevFt}                 key: VIP VIPPUP VRP VRPPUP OA1-<n> OA2-<n>
DtcComm       {comm1?, comm2?, tacan?, ils?, ilsCrs?}               tacan as Live.tacan ("94X"); ils MHz ("109.30")
```
`id` is when the plan was sent (ms since 1970) and doubles as its revision. `theater` is the app's theater id.
`briefing` is the printed briefing's `generated` stamp the plan was made against. `MissionRoute.file` and `save` are
names, never paths; `steerpoints` are `target_n` as BMS wrote them (`n` = index + 1). `bullseyeX`/`bullseyeY` are the
bullseye from the save's own header in theater feet, x north and y east like every other position here (the header
itself stores east first, in cells; the position is the middle of that cell, where BMS's own ACMI records the
bullseye — from 1.3.8, the corner before); it is shown before 3D only, and the live bullseye replaces it in 3D. Bearings
are true degrees; ranges and elevations feet.

### Save to DTC in a Tactical Engagement
`POST /api/cartridge/save?te=<theater>|<file>` (`theater` a theater-definition name, `file` a save name from
`/api/campaign/files`) saves the cartridge as before and also applies the `[STPT]` keys among the edits (`target_`,
`lineSTPT_`, `ppt_`, `wpntarget_`) to that save's own mission file, `<campaign folder>/<file>.ini`, from which BMS's
DTC window loads targets, lines and PPTs (in a campaign its LOAD reads them from there and not from the cartridge).
`file` may be a TE (`.tac`), a training (`.trn`) or, since the fix after 1.3.8's release, a campaign save (`.cam`); in
a campaign's file a `target_n` edit that would zero a point the file places (BMS's route) is left out, and no
`target_n` of it enters the ledger. That file is written in place, with no backup (like WDP), through a temporary file
and an atomic move, keeping BMS's own formatting — the TEs and trainings that ship with BMS (`TE_BMS_*`, `TR_BMS_*`)
the same as the pilot's own; only when the save's own `SaveFile` agrees with the file's name. The answer is
`CartridgeState` with `mission: MissionIniResult {file, written, reason?}`; `written` false with a `reason` when it was
refused — a campaign start (which the Planner never opens), a copy renamed since BMS saved it, a read-only file. The
cartridge part follows the cartridge's own rules above whatever happens to the mission file.

The sentences, word for word: a refusal ends "Only your cartridge was saved." ("No save was named." when
`te` has no `|`); when the cartridge itself was not saved, "Not written, because your cartridge was not saved either.".
`written` is also false, with no refusal, for "… has no mission file of its own (<ini>), so BMS flies it on your
cartridge alone: there was nothing more to write." (none is created), "<ini> already held these steerpoints: nothing
had changed." and "None of the changes were steerpoints, lines, PPTs or weapon targets, so <ini> was left as it is.".
`mission.file` is the `.ini`'s name as on disk; a removal (a value null) is never carried into the TE's file. A TE is
also refused when a save of another kind with the same name was saved after it (`Auto Save.cam` and `Auto Save.tac`
share `Auto Save.ini`).

### Upd Kneeboard (`/api/kbprint`)
Falcon BMS shows the F-16's 3D-cockpit kneeboard from sixteen texture files, `7982.dds`-`7997.dds`, in the `KoreaObj`
folder of the theater definition's `3ddatadir` (the theater BMS is set to; several theaters share one folder). The
left half of each file is the left knee's page *n*, the right half the right knee's page *n*. BMS reads them on
entering the cockpit. EZBoards, html_brief and WDP write the same files.

- `GET /api/kbprint/state` → `KbPrintState`: the folder, the sixteen files, and who made each half now (worked out
  from the file alone). The halves EZBoards writes at every PRINT are read, read-only, from its `CONFIG_USER.BAT`
  `SET KNEEBOARD[F16_<n><L|R>]=` lines. `mode` (1.3.8, additive) is the Mission section's mode on the PC, `ezboards`
  or `wdp`: in WDP mode EZBoards does not run at PRINT, so the window's Mission set takes pages 1 and 2 whatever it
  claims (in EZBoards mode, the first pair it does not claim).
- `GET /api/kbprint/thumb?n=<1-16>&side=L|R&w=<px>` → `image/jpeg`: what that half holds now.
- `POST /api/kbprint/file` with a body of `KbFileSend` → `KbFileResult`, one call per file. Each printed half is a
  1024×1536 PNG drawn on the device that runs the Planner, as base64; the PC scales it by 4/3 vertically into the
  file's half (1024×2048 in a 2048-pixel file). A half not sent is left as it is: its compressed blocks are copied,
  never re-encoded (mip levels 0-8; only the smallest three levels, where the halves share blocks, are rebuilt).
  A `picture` half is sent as `picture`, the file's path on the PC, with no `png`: the PC reads the file at that
  moment (through the same guards and reader as `/api/files/picture`, at the size the page file's half needs) and
  stretches its own pixels over the whole half, as WDP resizes the file itself. A picture that is gone or does not read
  by then leaves its half as it is: `leftRefused`/`rightRefused` (additive) carry the sentence while the other half is
  written as usual, and when every half sent was refused so the file is `refused` with the same sentences.
- `POST /api/kbprint/shipped?n=<1-16|all>` → `KbFileResult[]`: puts Falcon BMS's own page back, from the pages BMS
  ships in its Docs folder, where a file of the same name and size is there (`KbPrintState.shipped`); the same source
  rebuilds a page file that is empty or cut short. Anything else is refused.

The files are written **in place with no backup**: the pages are made again at will by EZBoards, BMS's PRINT or this
Print. Each is written to `<n>.dds.bmsc-new` beside it and then moved over it atomically, so a half-written texture
never reaches BMS. The file keeps its own format, size, mip count and 128-byte header (DXT1, DXT5 or 32-bit BGRA);
only the header's reserved field at offset 32 changes, to `BMSCOMPANION<version>`. Anything else (a DX10 header, BC7,
24-bit, an odd size) is refused, never guessed at. A page file that does not exist is **never created** (some theaters
have one page only). Nothing throws: each file comes back `written`, `unchanged` or `refused` with its reason
(read-only, in use, no such page, a format this program does not write, disk full). `KbHalf.kind` names what was
printed on a half. Only the F-16's pages are written; the F-15C's are not.

```
KbPrintState  {folder?, theater?, twin, bmsIn3d, pages[KbSlot], shipped, ezConfig?, error?, mode?}
KbSlot        {n, file, format, mips, size, left, right, ezLeft, ezRight, modified, missing}
KbHalf        {kind, label, png, picture}                           png: base64 of a 1024×1536 PNG; picture: a path on the PC
KbFileSend    {n, left?: KbHalf, right?: KbHalf}                    a null half is left as it is
KbFileResult  {file, status: written|unchanged|refused, reason?, leftRefused?, rightRefused?}
```
`folder` is relative to the BMS folder; `twin` is true when a `KoreaObj_HiRes` twin exists, which is written as well
in its own size. `size` is the file's width in pixels (the files are square). `left`/`right` are one of `bms`,
`ezboards`, `htmlbrief`, `wdp`, `companion`, `other`, `missing`. `kind` is one of `leave`, `blank`, `test`,
`datacard-l`, `datacard-r`, `coordination-l`, `coordination-r`, `briefing`, `weather`, `targets-l`, `targets-r`,
`routemap`, `attack`, `departure`, `arrival`, `alternate`, `picture` (1.3.8: a picture file on the PC, Browse picture…,
stretched over the half as WDP does and drawn by the PC from the file; the device shows a preview read through
`/api/files/picture`).
The header tag names each half's kind by its place in that list as one base-36 digit (`picture` is `g`).

### Files on the BMS PC (`/api/files`, 1.3.8)
Weapon Delivery Planner opens and saves its files through Windows' file dialogs (Reload WX's `.fmap`/`.twx`, Load and
Save DataCard's `.bdc`, the codewords and package timing `.ini`, Open and Save Callsign.ini, the DTC's backup files,
the pictures it saves). The Planner does the same through `PcFiles` (`app/.../data/PcFiles.kt`): on the BMS PC's own
window reading Falcon BMS on that PC it is **Windows' own dialog** (the common item dialog .NET shows, through COM:
`desktop/.../PcFileDialogs.kt`), with WDP's title, start folder, file name, types and overwrite question; anywhere else
— a phone, a tablet, a browser, a PC window linked to another PC — it is a window listing **the BMS PC's** folders
(`PcFileWindow`), served by these routes. Either way the file is read and written by the PC; a device never touches
its own disk. Models: `PcFileModels.kt`.

- `GET /api/files/places` → `PcPlaces {places[], drives[], readTypes[], writeTypes[]}`, each place
  `{name, path, kind, detail?}`. `kind` is `bms`, `config` (`User\Config`), `campaign` (the campaign folder of the
  theater BMS is set to), `datacampaign` (`Data\Campaign`), `planner` (where the Planner keeps what WDP keeps in its
  own folder: `User\BMS Companion Planner` in the BMS folder, laid out as WDP's), `datacards` (the DataCards folder: the
  one the pilot chose, else `DataCards` in `planner`), `wdp` (Weapon Delivery Planner's own folder, when it is on the
  PC), `documents`, `desktop`, `downloads` — only those that exist — and for a drive `drive`, `network`, `removable` or `cd`.
- `GET /api/files/list?path=&ext=` → `PcFolder {path, parent?, entries[], skipped, truncated, note?}`: the folder's
  folders, then its files whose extension is one of `ext` (comma-separated; `*.x;*.y` is taken too; every file when
  absent), each by name; hidden and system items left out and counted in `skipped`; at most 5,000 entries. `path`
  defaults to `@bms`. A file names the folder it is in; a folder that does not exist is answered with the nearest one
  above it, and `note` says so. A folder that does not answer in 12 s (a network drive that is not connected) is 409.
- `GET /api/files/stat?path=` → `PcFileEntry {name, path, dir, size, modified, read, write, exists}`.
- `GET /api/files/read?path=` → `PcFileData {path, name, size, modified, data}`, `data` the bytes in base64; up to 32 MB.
- `POST /api/files/write?path=&overwrite=0|1`, body `PcFileWrite {data}` (base64) → `PcFileEntry` of the file written.
  Written to a temporary file beside it and moved over it in one step; a file already there is **409** unless
  `overwrite=1` (the window asked the pilot first); a folder that is not there is 404, and is never made by a write.
- `POST /api/files/mkdir?path=` → `PcFileEntry` of the folder: makes it and any folder above it that is missing, as WDP
  makes `DataCards\<mission>\<package>\<callsign>` and its `Files\<part>` folders before it opens a window there. A
  folder already there is answered as it is; a file of that name is 409. A developer run makes nothing inside a BMS
  folder.
- `GET /api/files/weather?path=&clock=&save=` → `PcWeather {path, kind, map?, version, cols, rows, type[], pressureMb[],
  tempC[], windKt[], windDeg[], cloudBaseFt[], cover[], towering[], visKm[], twx?, note?, modified, otherSave?}`, read
  only: Reload WX's file, or a save's own `.twx` (the DataCard asks for it at every Open mission and Pick a flight, as
  `@campaign:<the save's theater>\<save>.twx`). An `.fmap` (version 5 or 8) comes back as its cells, row-major from the
  theater's north-west corner, the surface wind in knots (the file keeps km/h), `cover` as BMS's code (0/1/5/9/13). A
  `.twx` comes back as `twx {version, model, condition, mapUpdates, windDeg, types[4] {windKt[3], fogEndFt, stratusFt,
  cumulusFt, tempC[3], qnhMb[3]}, windHeld, clock?}` (night, dawn/dusk, day; BMS 4.38's version 8 has one figure for
  all three; `clock` is the campaign time of BMS's last weather check in it, a campaign's up to a few minutes before the save's
  clock), and — when its
  model is a map (3) — with the cells of the map BMS flies with it: the newest `WeatherMapsUpdates\dhhmm.fmap` at or
  before `clock` (campaign milliseconds) when the campaign updates its maps, else the `.fmap` of the same name beside
  it; with neither, `note` says so and the file's own tables stand in (as WDP's do). `modified` is the file's own time
  (ms since 1970), so a client tells a file saved again from the one it has. `save` names the save beside a `.twx`
  whose own weather it is asked as (`Auto Save.tac`); `otherSave` is then the sentence saying the file was written
  with another save — a campaign and a TE saved under one name share one `.twx`: another save of that name written at
  the same moment as the file, or, for a campaign, neither the file's time agreeing with the save's nor its clock within five minutes before the
  save's — and
  null when it is the save's. A file that is not a weather file is 400, one that is not there 404, one that does not
  read as one 409.
- `GET /api/files/picture?path=&max=` → `PcPicture {path, name, size, modified, format, width, height, sentWidth,
  sentHeight, data}`, read only: Upd Kneeboard's **Browse picture…** (WDP's Browse Picture), the device's preview of
  a picture (a print sends the path, and the PC draws the half from the file). A `.jpg`, `.jpeg`, `.png` or `.bmp` is
  read by Java's ImageIO, a `.dds` by the PC's own reader (`PictureFile`: DXT1-DXT5, and every uncompressed layout of
  8-32 bits a pixel described by bit masks — BGRA, RGB, 16-bit, luminance, alpha — at the smallest mip level still
  `max` a side); either way it is sent as a PNG (`data`, base64), scaled down to fit `max` pixels a side (64-4096,
  2048 when not given), so every device decodes it the same way. A picture of more than 32 million pixels is read at a
  whole fraction of its size, and only one is decoded at a time (the PC's memory is bounded before decoding); a file
  over 96 MB is refused. `format` is `JPEG`, `PNG`, `BMP` or `DDS <layout>`; `width`/`height` the file's own size.
  Another type is 400, a file that is not there 404, one that does not read as a picture 409 (a DX10/BC7, ATI1/ATI2,
  floating-point or cube-map DDS, a CMYK JPEG, a picture Java cannot read), each with its sentence.
- `GET /api/files/planner` → `PcPlannerFolders {planner, plannerExists, dataCards, dataCardsDefault, dataCardsSet,
  dataCardsExists}` (1.3.8): the Planner's own folder, `<BMS>\User\BMS Companion Planner` ("" with no BMS folder),
  and the DataCards folder in use — `dataCardsDefault` (`<planner>\DataCards`) unless the pilot chose another
  (`dataCardsSet`). `POST /api/files/planner?datacards=<path>` sets it (WDP's Settings → DataCard directory; kept in
  `bridge-settings.json` as `PlannerDataCardDir`) and answers the same: a full path on one of the PC's own drives or a
  place, not a file, not inside the BMS folder other than within the Planner's own and not inside WDP's folder (400
  otherwise, with its sentence); blank puts back the default. Only the setting is written; the
  folder is made the first time a file window opens in it. The Planner's folder itself is made on the first `mkdir` of
  it or of anything in it, together with WDP's layout (`Files\EWS`, `Files\Harm`, `Files\Harpoon`, `Files\Line`,
  `Files\MFD`, `Files\Open`, `Files\PPT\Personal`, `Files\Radio`, `Files\System`, `Files\Target`, `Files\Weapons`,
  `SavedMaps`, `DataCards\PlanPic`), and what an earlier 1.3.8 build kept in `Documents\BMS Companion Planner` is copied
  in then, once (never moved or deleted; a file already there is kept).

**Paths.** `path` is a full path on one of the PC's own drives (`C:\…`, `/` read as `\`, `..` resolved) or a place:
`@bms`, `@config`, `@campaign`, `@datacampaign`, `@wdp`, `@planner`, `@datacards`, `@documents`, `@desktop`,
`@downloads`, optionally followed by `\sub\folder` (`@planner\Files\EWS`), so a device that does not know where BMS
is installed can still start where WDP does. `@campaign` is the campaign folder of the theater BMS is set to;
`@campaign:<theater>` is that theater's own, by its theater definition's name (or the app's id), as BMS's theater
definitions give it (`@campaign:Korea KTO\Auto Save.twx`): where a save of another theater keeps its companion files.
Refused with 400: a network path (`\\server\share`), a device path (`\\?\`, `\\.\`), a relative path, an
alternate data stream (`a.ini:x`), a reserved device name (`CON`, `NUL`, `COM1`…), a place the PC does not have.

**Types.** Any folder may be listed, but only the Planner's files are read or written. Written (and read): `ini ppi
bdc pth lns tgt opn hpn plf ews mfd rad sts wpn hrm txt jpg jpeg png`. Read only: `fmap twx cfg dat dcd lst bmp dds` —
BMS's weather, config and data files, which the Weather and Config pages write under their own rules. Anything else —
a program, a script — is refused with 400, the sentence listing the types.

## Other routes

| Method | Path | Body |
|---|---|---|
| POST | `/api/ezboards/auto?on=1\|0` | turns EZBoards on PRINT on or off from any device → `EzStatus` (in WDP mode it stays suspended whatever it is set to) |
| POST | `/api/contacts/hostiles?on=1\|0` | **Show hostile contacts (live)** on or off from any device's Setup page (1.3.8) → `TacviewStatus`, whose `hostiles` (also `info.tacview.hostiles`, additive; absent from an older PC = off) says which it is. Kept on the PC (`BridgeSettings.ShowHostiles`, off by default, set off once by settings layout 3), so every device and VR board follows it; while off `/api/contacts` carries no hostile contact (see the table above), and each device strips the same again and says so on its map, Picture card, AWACS page and Picture board |
| GET / POST | `/api/planner/settings[?cleanOpened=1\|0]` | the Planner's settings the PC keeps because it acts on them (1.3.9) → `PlannerPcSettings {cleanOpened}`: **Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints** (`BridgeSettings.CleanOpenedMission`, on by default, set on once by settings layout 4; acted on at `POST /api/mission/opened?clean=1`), shown and set in the Planner's Settings window on every device; an older PC answers 404 and the window greys the switch. See *The cartridge ledger* |
| GET / POST | `/api/taxi` | what the Taxi page is showing (`TaxiSelection {airportId, runway, outbound, spot?, at, radio?}`), so a VR board can follow it. `radio` (1.3.8, additive, GET only): the last controller's call to the pilot's own flight from BMS's debug log (`RadioTaxi`, see *The radio*), served only when the PC heard it after the last POST; the live taxi board applies it over the selection. A POSTed `radio` is ignored. `runway` is the runway taxied to, or for the way in (`outbound` false) the runway landed on; `spot` is BMS Ground's number in the network drawn for that trip — the runway's own, or for the way in the reciprocal end's taxi-in network (`routeShown` in `TaxiRouting.kt`); POST sets it (400 for anything else). In memory only, and **per mission**: a GET answers an empty selection once the mission it was made for is no longer the one `/api/mission` serves (a new PRINT in EZBoards mode, a Populate or a switch of mode — `MissionData.missionKey`), so the last mission's runway and spot never stand for the next |
| GET / POST | `/api/boards` | the VR boards' configuration (`BoardConfig`), set by the PC's VR boards page and kept in `bridge-settings.json` as `Boards` |
| GET | `/api/kneeboard/page?i=<n>&max=<px>` | page `n` of the kneeboard UOAF's HTML Briefing tool exported, as `image/jpeg` (`max` 320-3000, default 1400); 404 when there is none |
| POST | `/api/kneeboard/open` | opens the HTML Briefing tool's own window on the BMS PC → `{message}`. **In WDP mode** (1.3.8) nothing is started: HTTP 409 `{error, message}`, both the sentence `MissionMode.HTML_BRIEF_SUSPENDED` (its export writes cockpit pages 1-3 over the Planner's Upd Kneeboard pages); `/api/kneeboard/page` still serves what it exported. `BridgeInfo.kneeboard.runSuspended` says which it is |
| GET | `/api/mfd/keys` | what each of the forty OSBs is bound to in the pilot's key file → `{inFront, blocked, fromKeyFile, keys[{side, n, key}], rockers[{side, which, up, callback, key}]}`; `blocked` (1.3.8, additive): why no press can reach BMS (BMS elevated, the PC program not), else ""; `rockers` (1.3.8, additive): the sixteen rocker halves (`which` gain/sym/con/brt), `callback` the BMS callback (`SimCBEOSB_BRTDOWN_L`, `SimRadarGainUp`; empty for SYM/CON, which BMS has none for), `key` empty when it is not bound (`MfdRockerKey`) |
| POST | `/api/acmi/clear` | moves the ACMI recordings (never `.vhs`) to the BMS PC's Recycle Bin → `{deleted, bytes}` |
| GET | `/api/radio?since=<seq>` | the radio calls from BMS's debug log after `since` (0 = all kept) → `RadioLog` (see *The radio*) |
| POST | `/api/radio/cleanup?on=1\|0&keep=<n>&days=1\|0` | the old-debug-log clean-up's settings (`bridge-settings.json` `RadioLogCleanup`, `RadioLogKeep`, `RadioLogKeepDays`; nothing is deleted) → `RadioLogStatus` |
| POST | `/api/radio/cleanup/now` | moves BMS's old debug logs to the Recycle Bin now (the same rule as the automatic run) → `RadioLogStatus`, its `cleanup.error` the reason when it could not; 409 in a developer run unless the logs folder is a copy |
| GET | `/api/wdp` | the **WDP remote** (`WdpRemote.kt`, the stopgap that preceded the Planner; no screen of 1.3.8 calls it): the real `WeaponDeliveryPlanner.exe` on the BMS PC → `WdpState {available, path, configured, running, hidden, width, height, message?}` |
| POST | `/api/wdp/open[?hidden=0]`, `/api/wdp/show?on=0\|1`, `/api/wdp/close` | starts WDP (its window parked off the PC's screen unless `hidden=0`), shows or parks it, closes it → `{message}` |
| GET | `/api/wdp/frame?w=&q=` | one frame of WDP's window as `image/jpeg` (`w` 320-3000 px, default 1200; `q` 0.3-0.95, default 0.72), no caching; 404 when it is not running |
| POST | `/api/wdp/input?kind=&x=&y=&w=&d=` | a tap, drag, wheel turn or key in the coordinates of the frame shown → `{message}`. Capture works; posted input does not reach WinForms controls reliably, which is why the Planner was ported instead |

### The radio (BMS's debug log, 1.3.8)

With BMS's debug mode on (BMS Launcher or Alternative Launcher), BMS writes `User\Logs\<YYYY-MM-DD_HHMMSS>_xlog.txt`
(the folder `g_sLogsDirectory` names, when set) and adds to it while it runs; with **Display Radio Subtitles** ticked
(BMS: SETUP → SIMULATION, kept per pilot) every radio subtitle is a line `[hh:mm:ss.mmm] <thread> Subtitle: <text>`.
The PC follows the newest log while BMS runs (once a second, appended bytes only, the file opened shared for a moment
and never written; `RadioLog.kt`), joins the lines BMS printed under a call at the same millisecond (an AWACS
picture's groups), keeps this session's last 2,000 calls in memory and files each. BMS's `RadioSubtitles-*.txt`
(`g_bWindowedRadioSubtitles`) is written only on exit and is not used.

```json
{ "status": { "state": "live", "file": "2026-10-05_161445_xlog.txt", "lines": 13, "lastLineAt": 1791200000000,
              "bmsRunning": true, "note": null,
              "cleanup": { "on": false, "keep": 5, "byDays": false, "files": 12, "bytes": 401234, "lastRun": 0,
                           "lastDeleted": 0, "lastBytes": 0, "error": null } },
  "session": "2026-10-05_161445_xlog.txt#1", "last": 10, "own": "Jaguar 2",
  "messages": [ { "seq": 3, "time": "04:12:30", "category": "awacs", "mine": true, "from": "magic 5", "to": "Jaguar 2-1",
                  "text": "Jaguar 2-1 magic 5 picture is multiple groups azimuth split\nSoutheast group bullseye 1 3 7 / 1 3 0 13000 hot hostile" },
                { "seq": 11, "time": "05:01:12", "category": "atc", "mine": true, "to": "Jaguar 2-1",
                  "text": "Jaguar 2-1 Taxi back to the ramp Alpha Charlie park 0 0 4",
                  "atc": { "kind": "park", "spot": 4, "letters": ["A", "C"] } } ],
  "taxi": { "seq": 11, "at": 1791203000000, "time": "05:01:12", "kind": "park", "runway": "36", "spot": 4,
            "letters": ["A", "C"], "text": "…" } }
```

- `status.state`: `off` (no debug log for this run of BMS, or none at all), `waiting` (a log, no subtitle yet; or BMS
  not running, its last log named) or `live`. `note` is the sentence to show; after two minutes in 3D with no line it
  asks for Display Radio Subtitles. The same `RadioLogStatus` is `BridgeInfo.radio` (additive; a PC older than 1.3.8
  leaves it out, which reads as `off`).
- `session` changes when BMS starts a new log, when a log is replaced, and when the mission's picture changed and
  every call was filed again (a PRINT, another flight): a client then drops what it has and asks with `since=0`.
  Clients ask with `since = last - 1`, so lines joined to the last call reach them.
- `category`: `atc`, `awacs`, `tanker`, `mine`, `flights`, `other` (`RadioCategory`); `mine` is true for any call to or
  from the pilot's own flight. Parties: the first callsign is whom the call is for, the second who speaks
  (`CommFile.xml`'s order); a controller is "<field> Approach/Tower/…" or a field BMS names one by.
- `atc.kind`: `out` (taxi clearance, calls 284/387), `lineup` (306), `takeoff` (39, 361), `landing` (38, 361, 287…),
  `vacate` (305), `back` (504), `park` (517), `nospot` (391), `atc` (any other controller's call); `runway` as the
  charts name it ("36", "09L"), `letters` the taxiways said, `spot` Ground's number.
- `taxi` (`RadioTaxi`): the last of those to the pilot's own flight (the way back: to the pilot's own seat), for the
  mission `/api/mission` serves now and at most three hours old; for the way back `runway` is the runway the last
  landing clearance named. The Taxi page and the live taxi board apply each once.
- Clean-up (off by default): BMS's own `*_xlog.txt` and the same sessions' `*_xlog_*.csv`, beyond the newest `keep`
  sessions (or older than `keep` days), to the Recycle Bin, at program start and when a new log starts, and on
  `/api/radio/cleanup/now`. Never the newest session's, never the log being read, never a file written in the last two
  minutes, never anything else (crash dumps, screenshots).

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

Everything is meant for a trusted home LAN. The server provides read-only data; the actions it exposes to devices are running the configured `EZBOARDS.BAT` (no arguments from the client), moving chosen screenshots to the Recycle Bin, and the writes into the BMS folder below. Each is off until the pilot switches it on where the ground rules ask for it, never writes a file in place without a temporary file and an atomic move, and answers a refusal in words:
- Falcon BMS's own config files, through `/api/cfg`, once the pilot has taken the backup: `User/Config` and nothing else, keys limited to `[A-Za-z0-9_]`, values to a single line, the original kept in `User/Config/BackUp`.
- Weather maps, through `/api/weather`, once that theater's originals have been copied aside into its `Campaign/BMS Companion Backup/`.
- The pilot's cartridge, through `/api/cartridge/save`, written at once as WDP writes it (only the edited keys, no backup); with `te=`, also that save's own mission file (a TE's, a training's or a campaign's; in a campaign's never a zeroed route point) in its campaign folder, in place with no backup, and never a campaign start.
- The F-16's 3D kneeboard pages, through `/api/kbprint`, in place with no backup (they are made again by EZBoards, BMS's PRINT or this Print); a page file that does not exist is never created.
- The Planner's own files, through `/api/files/write`, where the pilot saved them in the Planner's file window: only the types WDP's Save dialogs write (`.ini`, `.ppi`, `.bdc`, the DTC's backup files, `.txt`, `.jpg`, `.png`), never a program, a script, a `.cfg` or a weather map; a file already there only after the pilot said Replace. The folders WDP makes for them (`DataCards\<mission>\<package>\<callsign>`, `Files\<part>`, `SavedMaps`), through `/api/files/mkdir`. A Callsign.ini the pilot opened from outside `User\Config` with Open Callsign.ini File is that page's cartridge, and Save to DTC writes it there the same way.

Campaign files are only ever read (`/api/campaign`), and WDP mode's snapshot (`/api/mission/populate`) — like the plan
an older client sends to `/api/plan` — is kept in the PC's own settings folder, never in the BMS folder. Switching mode
writes nothing into Falcon BMS. File names from a client are plain names checked against a listing the PC made, except on `/api/files`, the Planner's file window: there a client sends a path, which must be on one of the PC's own drives (never a network share, which would make the PC log on to a machine the client named); any folder may be **listed** (hidden and system items left out), and only the Planner's file types are read or written, so anyone on the network can read those types of file anywhere on the PC's drives. BMS Companion's own settings can still only be changed in the PC program itself. The firewall rules it offers are limited to the local subnet. There is no login: anyone on the same network can open the app or the API.

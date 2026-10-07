# Where the mission's data lives, and what each mode reads

The Mission section has two sources, switched with **EZBoards | WDP** at the top of the Mission section (and in Setup):

- **EZBoards mode** shows Falcon BMS's own files **as they are now**: the briefing BMS printed, your cartridge, the
  mission file BMS keeps beside the save, and the jet once you are in 3D. Nothing of the Planner is laid over it.
- **WDP mode** shows a **snapshot** that **Populate from Planner** took of the flight open in the Planner. It is not
  read again by itself: after you change your plan (or BMS saves the mission again), press Populate from Planner again.
  The line under the switch names the flight and when it was populated.

This page lists every kind of mission data, the file it lives in, who writes it, who reads it and in which mode. It
ends with what happens when you switch modes and when you start the next mission. In the paths, `<BMS>` is your
Falcon BMS folder, `<callsign>` your pilot name, `<campaign>` the theater's campaign folder (`<BMS>\Data\Campaign` for
Korea, `<BMS>\Data\Add-On <name>\Campaign` and so on for the others) and `<save>` a save's name ("Auto Save").

**The short version.** EZBoards mode and WDP mode never mix *in the app*: in EZBoards mode `/api/mission` is exactly
BMS's files and nothing else, in WDP mode it is exactly the snapshot and nothing else, and the developer check
`--missiontest` proves both, a switch there and back included. What the two modes **share** are Falcon BMS's own files
— above all your cartridge, `<callsign>.ini`, and the cockpit's kneeboard pages — because there is one jet and one
cockpit. Whatever the Planner saves into those, BMS loads, so EZBoards mode shows it too. That is not a leak: it is
what you will fly with. What the Planner saved there for an **earlier flight** is cleared by itself when the next
mission begins ([Starting the next mission](#starting-the-next-mission)), and everything it saved when you switch modes
([What a switch resets](#what-a-switch-resets)).

## EZBoards mode: where each thing comes from

Everything here is read again whenever BMS writes the file (the PC looks once a second). In 3D, the jet itself wins
over every file for steerpoints, PPTs and lines (shared memory).

| Data | File | Written by | Read by | Changes when |
|---|---|---|---|---|
| Steerpoints 1-24 (the route) | the jet in 3D; before 3D `<campaign>\<save>.ini` `[STPT] target_0…23`, **only when it provably is the printed flight's route** (same rows and actions as the briefing — a Nav point may print its route action —, every point on the save's waypoint cells); else the printed flight's own waypoints in the newest save (cell + ½), when the save provably holds it (callsign, package and flight number; the table's words and times), as WDP mode's snapshot places them; the cartridge's `[STPT] target_0…23` over either | BMS (mission file; cartridge at DTC SAVE); the Planner's Save to DTC (cartridge) | Map, Dashboard, Briefing, Taxi (STPT 1 = home), VR boards, tanker tracks | PRINT; BMS saving the campaign; DTC SAVE |
| Steerpoint table (times, actions, formation, comments) | `<BMS>\User\Briefings\briefing.txt`, Steerpoints | BMS at PRINT | Briefing, Dashboard, VR briefing board, EZBoards | PRINT |
| Precision targets (STPT 15-22 after a Recon) and STPT 81-99 | `User\Config\<callsign>.ini` `[STPT] target_14…21` (action −1), `target_80…98` | BMS (Recon, DTC SAVE); the Planner | Map, Briefing, targets | DTC SAVE, Save to DTC |
| Weapon targets | cartridge `[STPT] wpntarget_0…99` | BMS; the Planner | Map | as above |
| PPTs 56-70 (threat rings and markers) | cartridge `[STPT] ppt_0…14` (`x, y, z, range in feet, code`), named from the theater's `<campaign>\Ppt.ini`; **in a TE** the TE's own `<TE>.ini` `[STPT] ppt_…` (BMS loads it over the cartridge), once it is believed as above | BMS; the Planner | Map (rings), Dashboard threats | as above |
| Lines L1-L4 (STPT 31-54) | cartridge `[STPT] lineSTPT_0…23`; in a TE the TE's `.ini`. **BMS's DTC LOAD in a campaign reads lines, PPTs and targets from `<campaign>\<SaveFile>.ini`, not the cartridge** (WDP-PORT D46), so Save to DTC writes the open save's `.ini` too | BMS; the Planner | Map | as above |
| VIP/VRP and OA1/OA2 (nav offsets) | cartridge `[NAV OFFSETS]` (`Modesel`, `VIP`, `VIPPUP`, `VRP`, `VRPPUP`, `OA1-…`, `OA2-…`) | BMS; the Planner's attack pages (Save to DTC) | the jet; **not drawn** in EZBoards mode (no attack overlay) | Save to DTC, DTC SAVE |
| Pop-up / HADB / TOSS attack | — (worked out in the Planner, WDP mode only) | — | nothing in EZBoards mode | — |
| Radios, TACAN, ILS | cartridge `[Radio]` (UHF/VHF 1-20 and comments, ILS presets), `[COMMS]` (Comm1/2, TACAN channel/band, ILS frequency and course); comm ladder in `briefing.txt` | BMS; the Planner | Comms, Dashboard, Briefing | DTC SAVE, PRINT |
| IFF, EWS, MFD pages, HARM tables, laser codes, ICP (bingo, ALOW), FCC, Link 16, map options, bullseye on MFD | cartridge `[IFF]`, `[EWS]`, `[MFD]`, `[HARM]`, `[Laser]`, `[ICP]`, `[FCC_AIM]`/`[FCC_AGM]`/`[FCC_AGB]`, `[LINK16]`, `[MAP_POP]`, `[Bullseye]` | BMS; the Planner | Comms, Briefing, VR boards (HARM table) | DTC SAVE, Save to DTC |
| Briefing text: overview, package, threats, ordnance, support, ROE, emergency | `briefing.txt` | BMS at PRINT | Briefing, Dashboard, VR boards, EZBoards | PRINT |
| Weather | `briefing.txt`, Weather (take-off, target, landing) | BMS at PRINT (the weather loaded then) | Briefing, Dashboard `WEATHER` card | PRINT |
| Tanker and AWACS tracks | the newest settled save of the theater BMS is on, `<campaign>\<save>.cam`/`.tac`, **only when your own route is in it** | BMS | Map, Support | BMS saving the campaign |
| Bullseye | the mission file's save header (with the believed route) | BMS | Map | as the route |
| Live jet, MFD glass, RWR | shared memory | BMS in 3D | Dashboard, Map, MFD page, VR boards | 4 × a second |
| Other aircraft, SAM sites in play | Tacview real-time feed (port 42674) | BMS with ACMI recording on (F) | Map, AWACS, Taxi (deck) | 1 × a second |
| EZBoards' tables | `xbrief.exe` reading `briefing.txt` and the cartridge (writes nothing) | — | Kneeboards page, VR boards | PRINT, DTC SAVE |
| Cockpit kneeboard pages | `<3D data>\KoreaObj\7982-7997.dds` (see [KNEEBOARDS.md](KNEEBOARDS.md)) | **EZBoards** at PRINT or GENERATE NOW (the halves its `CONFIG_USER.BAT` claims); html_brief when it exports | BMS on entering the cockpit | each run |
| html_brief's pages | its own folder (`kneeboards\kneeboard.pdf`, `output\`) | html_brief (reads the **save** and the cartridge) | Kneeboards page, `EXPORTED` VR board | each export |
| The Taxi page's field, runway and spot | the PC's memory (`/api/taxi`), **for this mission only** | the Taxi page | the live taxi VR board | each choice; forgotten at the next PRINT |

## WDP mode: where each thing comes from

Two different readers. **The Planner** always reads BMS's own files, in either mode (it asks for `?source=bms`), and
writes the cartridge with Save to DTC. **The Mission section** shows only the snapshot that **Populate from Planner**
took, kept in `%APPDATA%\BMS Companion\wdp-mission.json` on the PC (never in the BMS folder). The jet in 3D still wins
over the snapshot for steerpoints, PPTs and lines, as in EZBoards mode — it is what the jet has.

| Data | The Planner reads it from | The snapshot takes it from | Written by | Changes when |
|---|---|---|---|---|
| Flight, package, route (waypoint cells), loadout, designated targets | the save, `<campaign>\<save>.cam`/`.tac`/`.trn`, picked with **Open mission…** | the same save, at Populate | BMS (the Planner never writes a save) | BMS saving; the pilot Populating again |
| Steerpoint positions | slot by slot by the answer to "Did you save Precision STPT in the DTC for THIS flight?": Yes = the cartridge, else BMS's mission file, else the save's cell; No = the save's | the save's route; the mission file `<save>.ini` where it is that flight's; the cartridge **as saved on disk** | BMS; the Planner (cartridge) | Populate |
| Precision targets, PPTs, lines, weapon targets, nav offsets, radios, IFF, EWS, MFD, HARM, laser, ICP… | the cartridge `User\Config\<callsign>.ini` (the DTC page; unsaved edits stay on the page) | the cartridge as saved (a TE: also its `<TE>.ini`'s PPTs and lines) | the Planner's **Save to DTC** (only the keys edited); BMS's DTC SAVE | Save to DTC, then Populate |
| Pop-up / HADB / TOSS | the attack pages' inputs (on the device: `wdp_popup_ini`, `wdp_hadb_ini`, `wdp_toss_ini`, back to WDP's defaults at every new mission and switch of mode) and the mission | the attack the Planner sent with Populate, else the cartridge's `[NAV OFFSETS]` laid out on the route | the Planner (the jet gets it through Save to DTC → `[NAV OFFSETS]`) | Populate |
| Briefing text (situation, target area, times, ROE, emergency, intel) | the save (`CampaignBriefing`), and the printed briefing when it is the same flight | **BMS's printed `briefing.txt` when it is the populated flight's** (the Planner's own match: callsign, package and flight number in that save) — exactly what EZBoards mode shows, so the same flight shows the same mission in both modes; else the save's own (no comm ladder, pilot names or TACANs, which only PRINT makes) | BMS | PRINT, then Populate |
| Weather | `<campaign>\<save>.twx` (and `<save>.fmap` under Map Model) — the DataCard's Reload WX | over a printed briefing, its forecast, unless the `.twx` was saved after the print with other weather at take-off (the card's rule); else the `.twx`/`.fmap`, read at take-off, target and landing | BMS (every campaign save, a TE's SAVE WTH) | Populate |
| Tanker and AWACS tracks | the save | the snapshot's save | BMS | Populate |
| Enemy air defences, ships, bullseye | the save (spotted by the team that controls yours) | the save | BMS | Populate |
| DataCard and Coordination Card | the Planner's own page, in memory while the program runs; backups `.bdc`, `Codewords.ini`, `PackageTiming.ini` in the Planner's folder when you save them | not in the snapshot | the pilot (and the mission) | Open mission…, typing |
| Cockpit kneeboard pages | — | — | the Planner's **Upd Kneeboard** (the pages chosen; by default DataCard on page 1, Coordination Card on page 2); EZBoards is **suspended** (GENERATE NOW greyed out, PRINT runs nothing, the PC answers 409) and **Run HTML Briefing** too (its export writes pages 1-3; its exported pages are still shown) | each print |
| The Taxi page's field, runway and spot | — | the PC's memory (`/api/taxi`), for this snapshot only | the Taxi page | each choice; forgotten at the next Populate or switch |

## Every file

| File | What it holds | Written by | Read by | Modes |
|---|---|---|---|---|
| `<BMS>\User\Config\<callsign>.ini` — **the cartridge** | `[EWS]` countermeasure programs; `[MFD]` page layout per master mode; `[Bullseye]`; `[IFF]` modes, codes and time/position events; `[HARM]` threat tables; `[LINK16]`; `[STPT]` — `target_0…23` the route (STPT 1-24), `target_14…21` precision targets after a Recon, `target_80…98` STPT 81-99, `ppt_0…14` PPTs 56-70, `lineSTPT_0…23` lines 1-4, `wpntarget_0…99` weapon targets; `[Radio]` UHF/VHF/ILS presets with comments; `[COMMS]` active radios, TACAN, ILS; `[MAP_POP]`; `[NAV OFFSETS]` VIP/VRP, pop-up points, OA1/OA2; `[ICP]`; `[Laser]`; `[FCC_AIM]`, `[FCC_AGM]`, `[FCC_AGB]` | BMS (DTC SAVE, Recon); the Planner's Save to DTC, **directly, only the keys edited**, as WDP does | BMS (the jet), EZBoards, html_brief, EZBoards mode live, the Planner, Populate | **both** |
| `<BMS>\User\Config\<callsign>.pop` | binary pilot options; the app reads only the key file it names (for the MFD buttons) | BMS | the MFD page | both (not mission data) |
| `<BMS>\User\Config\*_Def.ini` (`EWS_Def`, `MFD_Def`, `HARM_Def`, `IFF_Def`) | BMS's defaults for those sections | BMS | the Planner's DTC page | both |
| `<BMS>\User\Config\BMS Companion Backup\` | left by a 1.3.8 test build | — | **nothing** (never read) | — |
| `<BMS>\User\Briefings\briefing.txt` | the printed briefing: overview, roster, threats, steerpoints, comm ladder, IFF, ordnance, weather, support, emergency | BMS at PRINT | EZBoards mode, EZBoards, the Planner (to join a save's flight to it), Populate (the snapshot's briefing when it is the populated flight's) | EZBoards mode; the Planner and Populate in WDP mode |
| `<campaign>\<save>.cam` / `.tac` / `.trn` | the whole campaign or TE: units, flights, routes, objectives, teams, pilots, clock, bullseye | BMS | the Planner (Open mission…), Populate, tanker tracks, html_brief | both (EZBoards mode only for tanker tracks) |
| `<campaign>\<save>.ini` — **the mission file** | `[MISSION] title`; `[STPT]` laid out like the cartridge's | BMS, beside the save it flies; the Planner **only for a TE** with that TE open (Save to DTC, the `[STPT]` keys edited) | EZBoards mode (route, only when it is the printed flight's), Populate, the Planner | both |
| `<campaign>\<TE>.ini` (`TE_BMS_*`, `TR_BMS_*`, your TEs) | the same; BMS loads it over the cartridge in that TE | BMS (ships them); the Planner (as above) | as above | both |
| `<campaign>\<save>.twx` | the save's weather: model (probabilistic, deterministic, map) and the four types' tables | BMS (every campaign save, SAVE WTH) | the Planner's DataCard, Populate | WDP mode |
| `<campaign>\<save>.fmap` | BMS's copy of the map picked under Map Model | BMS | Populate, the DataCard | WDP mode |
| `<campaign>\BMSC <name>.fmap`, `WeatherMapsUpdates\*.fmap` | weather maps the Weather tab made | the PC (Weather tab) | BMS when you pick the map | neither (it is BMS's choice) |
| `<campaign>\BMS Companion Backup\` | untouched originals of BMS's weather maps, README, the record of maps the app wrote, `Generated settings\BMSC <name>.json` | the PC (Weather tab) | the Weather tab | — |
| `<campaign>\<save>.iff`, `.l16.txtpb`, `.frc`, `.his` | BMS's own | BMS | not read | — |
| `<campaign>\Ppt.ini` | the theater's PPT types (names, ranges) | BMS | names on every PPT | both |
| `<3D data>\KoreaObj\7982-7997.dds` | the cockpit kneeboard, a page per file, left and right knee | EZBoards, the Planner's Upd Kneeboard, html_brief | BMS on entering the cockpit | **both** (one cockpit) |
| `<BMS>\User\BMS Companion Planner\` | the Planner's own folder, laid out as WDP's: `Files\<part>` (DTC backups, personal PPTs `.ppi`), `DataCards\` (backup cards `.bdc`, `Codewords.ini`, `PackageTiming.ini`, `PlanPic\`), `SavedMaps\` | the Planner, when you save into it | the Planner | WDP mode (the Planner) |
| `<BMS>\User\BMS Companion Planner\Ledger\<callsign>.json` — **the cartridge ledger** | every key the Planner's Save to DTC wrote into that pilot's cartridge (and a TE's `.ini`), the value, when, and **for which mission** (flight callsign, package, flight number, theater, save, seat, PRINT or Open-mission time) | the PC at each Save to DTC, and at each clearing — a new mission or a switch of mode — with what it cleared for the Undo (directly, temporary file and move; a failure is logged, never in the way of the save) | the PC: what to clear by itself when a new mission begins or the mode is switched (below) | **both** |
| `<BMS>\User\Logs\<YYYY-MM-DD_HHMMSS>_xlog.txt` (or `g_sLogsDirectory`) — **BMS's debug log** (debug mode only) | everything BMS logs, among it every radio subtitle (`Subtitle:` lines, with Display Radio Subtitles on) | BMS, while it runs | the PC (`RadioLog`, read only, appended bytes once a second): the Radio page, the Taxi page's and the live taxi board's radio cues (`/api/radio`, `/api/taxi` `radio`; this session's calls in the PC's memory only, nothing kept on disk). **Deleted** only by the clean-up, when the pilot turns it on: sessions beyond the newest N (or older than N days), with their `*_xlog_*.csv`, to the Recycle Bin — never the newest session, never other files | both (not mission data) |
| EZBoards' folder (`<BMS>\Tools\EZBoards`) | `CONFIG_USER.BAT` (which halves it claims), its `images\` | the pilot; EZBoards | EZBoards; the PC (claims) | EZBoards mode |
| html_brief's folder (`<BMS>\Tools\html_brief_win`) | `config.ini`, `kneeboards\kneeboard.pdf`, `output\` | html_brief | the Kneeboards page, `EXPORTED` board | both |
| `%APPDATA%\BMS Companion\bridge-settings.json` | the mode (`MissionSource`, `MissionSourceSince`), BMS/EZBoards/html_brief/WDP folders, EZBoards on PRINT, Tacview, VR boards (`Boards`), the Planner's DataCards folder, the old-debug-log clean-up (`RadioLogCleanup`, `RadioLogKeep`, `RadioLogKeepDays`) | the PC | the PC | both |
| `%APPDATA%\BMS Companion\wdp-mission.json` | **WDP mode's snapshot**: the mission as served, what it was made of, the paths and times of the files it was taken from (`Populated.changed` is still worked out and served, but no screen shows it) | the PC at Populate | `/api/mission` **in WDP mode only** | WDP mode |
| `%APPDATA%\BMS Companion\planner-plan.json`, `.prev.json` | the plan an early 1.3.8 build sent with Send to Mission | the PC (`/api/plan`, kept for older clients) | **nothing in the Mission section** | neither |
| `%APPDATA%\BMS Companion\pc-app.properties` | the PC window's settings and its own device preferences (below) | the PC window | the PC window | both |
| device preferences (Android app storage, the browser's localStorage, `pc-app.properties` on the PC) | layout and tools only: tabs, Dashboard pages, map layers, AWACS tools, the Planner's attack inputs (`wdp_popup_ini`, `wdp_hadb_ini`, `wdp_toss_ini` — removed at every new mission and switch of mode, so the pages start on WDP's defaults —, `wdp_performance_ini`), the current attack (`planner_attack_focus`: which attack page, and the `mission_epoch` it was kept with — taken back at start only on that mission, removed wherever the current attack is cleared), the last mission this device saw (`mission_epoch`: the mode and the flight, for [MissionEpoch](#what-each-device-starts-afresh)), the card's switches, last files (`wdp_file_<kind>`), recent saves (`planner_recent`), last flight (`planner_last_flight`, only opens the picker), the Upd Kneeboard plan (`kb_print_plan`: which kind on which half and picture paths, never the mission's content), the weather generator per theater (`wx_gen_params@<theater>`) | each device | that device | both; **no mission content** |

## Switching modes

**What each mode reads.** EZBoards mode: `briefing.txt`, the cartridge, the believed mission file, the newest save
for tanker tracks, EZBoards' tables, shared memory and Tacview. WDP mode: `wdp-mission.json` (the snapshot), shared
memory and Tacview. The Planner, in WDP mode, reads BMS's files directly.

**What stays behind, and why it does not show.**
- **The snapshot** stays in `wdp-mission.json` while you are in EZBoards mode, so that switching back gives you the
  flight you populated. It is never served in EZBoards mode. Switching back shows it with the time it was populated —
  unless it is not the current flight's by then (you printed or opened another flight since), when the switch
  discards it ([What a switch resets](#what-a-switch-resets)).
- **EZBoards' own setting** (run on PRINT) is kept as you set it while WDP mode suspends it. A PRINT made in WDP mode
  does not run EZBoards later: back in EZBoards mode, press GENERATE NOW or PRINT again. Run HTML Briefing is
  likewise refused in WDP mode and offered again in EZBoards mode; nothing about it is stored.
- **The attack** of the snapshot reaches the VR boards' map only in WDP mode; an attack an older Planner posts by
  itself is never drawn.

**What both modes share on purpose** — Falcon BMS's files, which the jet reads:
- **The cartridge.** One file for both. What the Planner saved into it (PPTs, lines, nav offsets, presets, targets)
  shows in EZBoards mode too, because it is what the jet loads. The Planner writes only the keys you edited, so nothing
  else of BMS's is disturbed. What the Planner saved for an **earlier flight** and is still there is **cleared by
  itself** when a new mission begins — a PRINT of another flight in EZBoards mode, another flight opened or populated
  in WDP mode — and everything it saved at every switch of mode, silently (see [Starting the next
  mission](#starting-the-next-mission), [What a switch resets](#what-a-switch-resets)); nothing is ever drawn as a
  leftover, nothing is asked, and there is no button to press.
- **A TE's mission file.** Save to DTC with a TE open writes its `[STPT]` keys too (BMS loads that file over the
  cartridge in a TE), and EZBoards mode reads that TE's PPTs and lines from it once it is the printed flight's.
- **The cockpit kneeboard pages.** Whichever tool wrote a page last is what BMS shows. A switch to EZBoards mode gives
  every half Upd Kneeboard made for an earlier flight Falcon BMS's own page back, and a switch to WDP mode every half
  EZBoards or html_brief made for an earlier flight ([What a switch resets](#what-a-switch-resets)) — where the theater
  ships BMS's originals (Korea, the Balkans, Hellas); elsewhere they stay and the switch says so. Pages made for the
  current flight stay. Upd Kneeboard's window shows who made each half and when, and **Put BMS's page back** clears one.

**Every way one mode's or one mission's data could show in the other, as audited for this page:**

| Path | What could leak | Status |
|---|---|---|
| `/api/mission` in EZBoards mode | the snapshot, the Planner's plan or attack | **none**: `plan` is always null, the answer is byte for byte `?source=bms` (`--missiontest`) |
| `/api/mission` in WDP mode | a printed briefing of another flight, EZBoards' board | **none**: exactly the kept snapshot (`--missiontest`); the printed briefing is in it only when it is the populated flight's, taken at Populate (and then section by section the same as EZBoards mode's: `--missiontest` part 12, `--samebrieftest`) |
| `/api/attack` (VR map) | the snapshot's attack in EZBoards mode; an old Planner's posted attack | **none**: follows the mode; a posted attack is never drawn |
| `planner-plan.json` (Send to Mission, early 1.3.8) | an old plan | **none**: never served in `/api/mission`; nothing in the app sends one |
| a device's poll | a mission fetched just before a switch landing after it; after WDP → EZBoards a failed fetch making the Planner's "BMS files" the snapshot for a round | **fixed**: a held mission of the other mode is fetched again at once, and the Planner's copy of BMS's files never takes a WDP-mode answer (all three `MissionLink` copies) |
| a device's poll on the snapshot's file list | a change from one file to another (same count) not refetched | **fixed**: the list itself is compared |
| the Taxi page's choice (`/api/taxi`, PC memory) | the last mission's runway and spot at the same field taking over the live taxi board, in either mode | **fixed**: served only for the mission it was made for (`MissionData.missionKey`: the PRINT in EZBoards mode, the Populate in WDP mode); the board follows the jet again on an empty answer; the Taxi page starts afresh for each mission |
| the Planner's DTC page | changes made for the flight before, not saved, going into the next flight's cartridge at Save to DTC | **fixed**: when another flight comes (Open mission… of another save, another flight, a briefing of another flight) the page asks **Read the cartridge** or **Keep the changes** — WDP itself reads the cartridge again for every flight it is given |
| the snapshot after a new PRINT | the last mission shown while the next is set up | **by design, named**: the snapshot is the flight you populated until you populate again; the source line names its save, flight and time, and opening another flight in the Planner discards it (a new mission, below). The PC still notes a later PRINT in `Populated.changed` ("printed briefing"); no screen shows it, as the pilot asked |
| the snapshot after a save under a new name | the last mission shown | **as above**: the source line names the snapshot's save and when it was populated |
| the Planner's DataCard | codewords, package timing, Joker, Set Fuel, the class and speed boxes, MSAA typed for the last flight (and printed by Upd Kneeboard) | **kept, as WDP keeps them** (WDP's `ClearDatacard` leaves them); everything the mission fills (airports, flight, fuel, targets, briefing, weather, pictures) is rebuilt. Type over them or Load DataCard/Codewords/Timing |
| the attack pages | the inputs (dive angle, speeds…), typed ELEVs and target ground, and the TGT STPT number | **fixed** (1.3.8): at every new mission and switch of mode Pop-up, HADB and TOSS go back to WDP's defaults (their saved sections removed), the typed ELEVs and target ground clear, and TGT STPT is the new mission's first strike steerpoint (1 when it has none) — [What each device starts afresh](#what-each-device-starts-afresh) |
| the DataCard's delivery | the attack profile chosen with PopUp / HADB / TOSS and its Delivery block, the attack pictures in the plan boxes | **fixed**: back to "None" (empty) with no profile chosen at every new mission and switch of mode |
| the Planner's Map page | the selection, a move waiting, Measure, the HSD preview's centre, the package flights shown, Viewing, the cached intel | **fixed**: started afresh at every new mission and switch of mode; the layers, the HSD range and the map style are the pilot's and stay |
| the Mission map and the VR map board | the selection, the AWACS tools, the framing | **fixed**: cleared, and the map flies to the new route, at every new mission and switch of mode; the layers are the pilot's |
| the Upd Kneeboard plan | which kind goes on which half, picture paths | **kept on purpose** (WDP's Setup.ini keeps it); every page, the Attack profile included, is drawn from the mission open at print time — the attack page last worked on is forgotten at a new mission |
| the cartridge | the Planner's saves showing in EZBoards mode | **shared by design** (above) |
| the cartridge, next mission | lines, PPTs, steerpoints, weapon targets and the VIP/VRP/OA the Planner saved for an earlier flight, drawn on the next flight's map as if they were its own, in either mode | **fixed**: the ledger tells them apart, and the PC **clears them by itself when a new mission begins** (a PRINT of another flight, another flight opened or populated in WDP mode), with every nav offset but the new flight's own; nothing is drawn as a leftover, nothing is asked, nothing is shown |
| the cartridge, a switch of mode | the other mode's mission reaching this one through the cartridge | **fixed**: a switch clears everything the Planner saved that is still unchanged, and every nav offset ([What a switch resets](#what-a-switch-resets)) |
| a save's own mission file (a TE's; a campaign's since the line fix after 1.3.8's release) | the Planner's PPTs and lines for another flight of the same save | **fixed**: cleared with the cartridge at a new mission and a switch (the mission files the ledger names; never a campaign file's `target_n`, which are not in the ledger; the nav offsets in the current flight's TE file); otherwise BMS does not load the file |
| the kneeboard DDS pages | one tool's pages staying after a switch | **fixed** for an earlier flight's: the switch puts BMS's own page back over the other mode's halves (where the theater ships BMS's originals; listed as left where it does not); the current flight's pages stay |
| the snapshot after a switch to WDP mode | another flight's Populate shown as the mission | **fixed**: discarded at the switch when it is not the current flight's, "not populated yet" |
| html_brief | an export of the last mission | flagged when BMS printed a newer briefing than the export |
| device preferences in the Mission section | — | **none** hold mission content |

## What a switch resets

Switching between EZBoards mode and WDP mode resets, by itself, silently and without asking, what the Planner left in
Falcon BMS's files, so the two modes never reach into each other's mission. The pilot's rule: **every** leftover goes,
at every switch; only what BMS or the pilot changed since the Planner wrote it is never touched.

**The cartridge and the TEs' own mission files.** Every mission item the ledger says the Planner wrote that **still
holds exactly the Planner's value** — whatever flight it was saved for and whenever, the current flight's too — is
written back to BMS's empty value. And the **delivery data goes whoever wrote it**: every `[NAV OFFSETS]` key that
places something (VIP, VRP and their pull-up points, the offset aim points, `Modesel`), in the cartridge and in the
current flight's TE file, ledger or not — an older build or WDP itself wrote them before the ledger existed, and BMS's
own DTC window never edits them, so they only ever come from a planner.

**The cockpit pages and the snapshot** go only when they are an **earlier flight's**. The current flight is, switching
to EZBoards mode, the printed briefing's; switching to WDP mode, the flight the Planner has open on the device that
switched (a save's flight), else the printed briefing's. It **began** at its PRINT (when the printed briefing is that
flight) or when it was opened in the Planner, whichever came first; a page written before that moment belongs to an
earlier flight. With no current flight known (nothing printed, nothing open), no page and no snapshot is touched.

**Switching to EZBoards mode resets:**
| What | Where | How |
|---|---|---|
| Lines L1-L4 the Planner saved | cartridge `lineSTPT_n` | BMS's empty point |
| PPTs 56-70 | cartridge `ppt_n` | BMS's empty PPT |
| Steerpoints and targets (STPT 1-24, 81-99, a Recon bank the Planner wrote) | cartridge `target_n` | `0, 0, 0, -1, Not set` |
| Weapon targets | cartridge `wpntarget_n` | as a steerpoint |
| VIP/VRP and offset aim points — **every one that places something**, whoever wrote it | cartridge `[NAV OFFSETS]` | `0,0,0,0`, `Modesel=none`, OA keys removed |
| The same keys in the TEs' own mission files | `<campaign>\<TE>.ini` `[STPT]` (the files the ledger names; the nav offsets in the current flight's TE) | as above |
| Upd Kneeboard's cockpit pages made for an earlier flight (each half with BMS Companion's tag) | `<3D data>\KoreaObj\7982-7997.dds` | Falcon BMS's shipped page back (the whole file when the other half is BMS's, else that half), the file's format kept |

WDP mode's snapshot is **kept** (it is not shown in EZBoards mode).

**Switching to WDP mode resets:**
| What | Where | How |
|---|---|---|
| The same cartridge and TE keys as above | as above | as above |
| EZBoards' and html_brief's cockpit pages made for an earlier flight (each half `IMAGEMAGICK` or html_brief's layout) | `KoreaObj\7982-7997.dds` | Falcon BMS's shipped page back |
| WDP mode's snapshot, when it is not the current flight's | `%APPDATA%\BMS Companion\wdp-mission.json` | moved aside to `wdp-mission.discarded.json`; the section says "not populated yet" — Populate again |

**What a switch never touches:** keys BMS or the pilot changed since the Planner wrote them (only a key still holding
exactly the Planner's value goes; numbers to half a foot, so BMS's own six-decimal rewrite at DTC SAVE or FLY changes
nothing); keys BMS wrote (not in the ledger), the nav offsets and BMS's copies of the Planner's own values excepted
([Starting the next mission](#starting-the-next-mission)); the radios, IFF, EWS, MFD, HARM, laser and other
settings (not mission items); the current flight's pages and snapshot; a page whose owner is BMS, Falcas's WDP or
unknown; a page in a theater whose BMS ships no originals (Korea KTO, the Balkans and Hellas ship them; elsewhere the
page is left and the PC's log names it); a page file that does not exist (never created); the `KoreaObj_HiRes` twins.
App-side state that is already per mission stays as it is: the Taxi page's choice, each device's Mission cache, the
Planner's "another flight" question. Each device starts its own view of the mission afresh at the switch too ([What
each device starts afresh](#what-each-device-starts-afresh)).

**How it is written.** The cartridge and a TE's file as the Planner's Save to DTC writes them (directly, no backup,
temporary file and move, nothing thrown). Then **LOAD** the DTC in BMS: the jet takes the cartridge at LOAD. The pages
go back exactly as **Put BMS's page back** does. A developer check never writes the real install.

**What you see: nothing.** No notice, no OK, no question, on any device — the pilot asked for none. The PC still
keeps a summary of the last switch (`SwitchReset`, served in `/api/info` for half an hour) and the cleared values in
the ledger (`cleared`, the last five resets), and `POST /api/mission/source/undo?at=` still writes them back where the
file still holds the empty value; no screen offers it. The server page's recent activity names what was reset
and what was left.

## Starting the next mission

**What refreshes by itself.** In EZBoards mode a PRINT is the new mission: the briefing is read again; the mission
file's route is believed only once it matches the new briefing (the last flight's is left out, never shown), else the
printed flight's own waypoints in the save when the save provably holds it; the tanker tracks only when your new route
is in the newest save; EZBoards runs if you set it to run on PRINT; the Taxi page and the live taxi board forget the
last mission's choice. In the Planner, **Open mission…** of another save replaces the flight; the DataCard's mission
parts, its weather and the attack pages are rebuilt; the DTC page asks before keeping unsaved changes; Performance
starts from the new weather and fuel.

**What is a new mission.** EZBoards mode: a PRINT of **another flight** than the last one printed (the PC's tick sees
the new `briefing.txt`; a briefing printed while the program was closed is looked at once when it starts). WDP mode:
**another flight opened** in the Planner on any device (Open mission… or Pick a flight; `POST /api/mission/opened`),
or populated. The same flight printed or opened again is the same mission, and nothing is cleared.

**What the PC clears by itself at a new mission.** In the cartridge and the TEs' own mission files: every key the
ledger says the Planner wrote for **any other flight** than the new one, whenever, that still holds that value (lines,
PPTs, steerpoints and targets, weapon targets) — never BMS's, never the new flight's, never a key saved with no flight
known; and **every nav offset** that places something (VIP/VRP and their pull-up points, the offset aim points), whoever
wrote it, except what the ledger says the Planner saved for the new flight and the file still holds — in the cartridge
and the new flight's TE file. In WDP mode another flight's snapshot is discarded too ("not populated yet"). **Not** the
cockpit kneeboard pages: the mode's own tool (EZBoards at PRINT, Upd Kneeboard) writes them for the new mission; only a
switch of mode puts BMS's pages back. **Nothing is shown and nothing is asked**; the server page's recent activity names
what was cleared. Then **LOAD** the DTC in BMS.

**BMS's copies of them go too** (1.3.9). BMS's DTC memory keeps what the pilot last LOADed, and its SAVE, FLY and
campaign save write that memory into the cartridge and into the next save's mission file (`<SaveFile>.ini`,
`Auto Save.ini`). So a line the Planner drew for one flight, LOADed, then cleared by a new mission, came back in BMS's
own writes, where no ledger row named it — and every later LOAD brought it back ("Line 1 loads in every mission"). A
new mission and a switch therefore also clear, in the cartridge, every mission file the ledger names, the mission file
BMS's LOAD reads now (the newest save's) and `Auto Save.ini`, each `[STPT]` item that holds **a value the ledger names
for another flight** and none for the new flight. The ledger is the only memory — its live rows and what the last five
resets cleared (`cleared`); no log of past values is kept. A PPT, a target or weapon target of action −1 (a precision
target; BMS's route, action 0 and up, never) is compared as numbers, not text, against both: BMS writes a PPT back with
its height as 0 and its range as a float (north and east to a foot, a PPT's range to a foot and its code, a target's
name). A line point only exactly, against the live rows, and **a line goes only whole**: when every point it places is
such a value; a line the pilot added a point to in BMS is kept. A value the Planner never wrote — a line or PPT the
pilot drew in BMS — is never one of them. BMS's memory itself still holds the old items until the next LOAD, which is
why the Steps say LOAD (then SAVE) after opening the new mission.

**Start each opened mission with clean lines, PPTs and Open 1/2 steerpoints** (1.3.9; the Planner's Settings, on by default; the PC's
setting `CleanOpenedMission` in `bridge-settings.json`, `GET`/`POST /api/planner/settings`). A line or PPT comes back
from BMS's memory long after the ledger forgets it. So when the Planner plans **another flight** than it had — Open
mission… or Pick a flight (`POST /api/mission/opened?clean=1`; the same flight planned again, Reload from BMS or a seat
changed, is not one) — the PC cleans **every** line (`lineSTPT_0…23`, each line whole), **every** PPT (`ppt_0…14`)
and **every** Open 1/Open 2 steerpoint (STPT 81-99, `target_80…98`), whoever made them, from the cartridge and from the campaign mission file BMS's LOAD reads for that flight —
`<SaveFile>.ini` of the save opened (`Auto Save.ini` when that is it; not when an `Auto Save.tac` was saved after it).
Kept: a line, PPT or STPT 81-99 the Planner saved for that flight (its live ledger rows); BMS's route (STPT 1-24),
precision and Recon targets (STPT 15-22) and weapon targets; everything of a **TE or a training**, the cartridge too (the save a `.tac`/`.trn`: BMS's
2D map empties the four lines, loads them from the TE's own `<TE>.ini` and saves the cartridge, and a TE's LOAD reads
lines and PPTs from that cartridge — cleaning it after the map was built would take the TE's authored items from the
jet). Nothing else cleans them: not a PRINT, not a switch of mode; until the next Open mission… the pilot's own lines and
PPTs stay. The Planner then reads its cartridge again (empty of lines and PPTs), and the maps suggest the mission's
threats and its tanker and AWACS tracks — the Mission map draws them, the Planner's Map page lists them, one tap from a
PPT or a line; nothing reaches BMS until the pilot adds them and saves. The cleaned points are in the ledger's `cleared`
(under no flight when the Planner did not write them) for the Undo route. Off: nothing is cleaned at Open mission….

**What does not, and why.**
- **The cartridge** keeps whatever BMS saved into it. Falcon BMS's own manual says so: in a campaign, PPTs and lines
  saved to `<callsign>.ini` "remain there until you overwrite them" (UM §5.1.9-5.1.11), and FLY saves the 2D DTC
  window's copy back into it. Save the DTC for the new flight in BMS (or with Save to DTC) before you look at its PPTs
  and lines; what the **Planner** saved for an earlier flight is cleared by itself (above).
- **WDP mode's snapshot** is never retaken by itself. Populate again after Save to DTC, or when BMS saved the mission
  again.
- **The cockpit pages** stay as the last tool wrote them; make them again (EZBoards at PRINT, or Upd Kneeboard) after
  the cartridge is saved.
- **The DataCard's typed boxes** (codewords, timing, Joker…) stay while the program runs, as in WDP.

### What each device starts afresh

At every new mission and every switch of mode each device (the PC's window, a phone, a browser, a VR board) clears
its own state of the last mission (`MissionEpoch`: the mode and the flight it last saw are kept in its preferences as
`mission_epoch`, so a mission that changed while the app was closed counts too):
- **the maps' view**: the Mission map's selection and AWACS tools, its framing (it flies to the new route); the
  Planner's Map page's selection, a move waiting, Measure, the HSD preview's centre, the package flights shown,
  Viewing, the status line and the save's cached intel;
- **the attack and the delivery**: Pop-up, HADB and TOSS back to WDP's defaults (the values a fresh install starts
  with: their saved sections removed), no typed ELEV or target ground, TGT STPT on the new mission's first strike
  steerpoint (1 when it has none) and IP STPT following it again (the steerpoint before); the DataCard's Delivery block
  back to "None" with no profile chosen and its attack pictures gone; the attack page last worked on forgotten, so Upd
  Kneeboard's Attack profile is the new mission's. The nav offsets are cleared from the cartridge by the PC (above),
  the DTC page drops them from its NAV OFFSETS tab too, and the snapshot's attack goes with the snapshot.

Not cleared: what is the pilot's and not the mission's — which layers are on, the map style, the HSD range, units,
Mil/Civ, metric, the Upd Kneeboard plan.

### One mission picture

Every map of the mission draws the same things by default, in the same drawing — the Mission section's **Map**, the
VR **map board** and the Planner's **Map** page: the route and its steerpoints (no leg between a refuel point and a
landing; after the first landing, thin and dashed); the tanker, AWACS and JSTARS tracks as WDP's boxes about the
station leg (`PlannerIntel.orbitBox`), without their transits; the cartridge's PPT rings; **the mission's threats**;
the flight's own airfields (departure, arrival and alternate; the theater's others under **All airfields**, off); the
bullseye; and in 3D the live traffic. **No ground units and no package routes** on the mission maps.

**The mission's threats**, each ringed by one rule (the theater's `Ppt.ini` range, else the threat reference;
`siteRingFt`) in one red on every map:
- the sites the printed briefing's **Threat Analysis** names ("SA-19 (2K22) missile launchers 2 nm west of
  Buk-myeon"), placed in the save: the battalion BMS words exactly so, else the nearest of that system within 5 nm of
  the described point, else the point;
- with no printed briefing of the flight (WDP mode from a save), the sites the flight's side has spotted whose ring
  reaches within 3 nm of the route;
- the cartridge's PPTs;
- in 3D, the Tacview feed's SAM sites **only where they are one of these** (same system within 2 nm).

**No cheating.** BMS streams every SAM in the theater over Tacview, and the save holds every unit. An enemy site the
flight's side has not spotted never leaves the PC: the save's air defences and ships are served spotted only (by the
team that controls the flight's), the Map page's intel carries seen units only, and `/api/contacts` passes a hostile
live SAM only at a site the mission knows. Every other hostile contact of the feed (aircraft, ships, ground units)
leaves the PC only when the pilot has turned on **Show hostile contacts (live)** in Setup (`ShowHostiles` in
`bridge-settings.json`, off by default; `HostileContacts`). WDP's own "cheat" layers are not ported. The Planner's Map page keeps WDP's
every-spotted-site layer behind **All known SAMs** (off), and its package flights start off as in WDP.

Where it comes from: **EZBoards mode**, after PRINT, from BMS's files — the printed briefing, the cartridge, the
believed mission file (or the save's own waypoints of the printed flight), and the save BMS is flying (the newest of
the current theater), whose sites, bullseye and ships are served as `MissionData.ground` **only when that save
provably holds the printed flight** (BMS's mission file beside it is believed for it, or the save has a flight with the
briefing's callsign, package and flight number); otherwise only the briefing's threats are drawn,
placed by its own words. **WDP mode**, from the snapshot Populate took (the same `ground`, read from the populated flight's save,
with the printed briefing's Threat Analysis when it is that flight's); the **Planner's Map page** from the mission it
has open, adding its editing (add, move, take out, into the DTC) and WDP's own options. Items the cartridge holds draw
as the cartridge's on each map.

### The ledger: how what the Planner saved for an earlier flight is told apart

The case that started this: lines drawn in the Planner for a mission flown in WDP mode were still on the map of the
next mission, flown in EZBoards mode. Nothing of the app's own carried them over — they were in the cartridge, where
the Planner's Save to DTC had put them and where BMS keeps them until something overwrites them, so EZBoards mode
showed them (and the jet loaded them) as the new mission's.

**The ledger.** Every Save to DTC now tells the PC which mission it is for (the flight open in the Planner: a save's
flight, else the printed briefing's — callsign, package and flight number, theater, save, seat, and when it was
printed or opened), and the PC keeps, per pilot, the last value the Planner wrote to every key, with that mission
(`<BMS>\User\BMS Companion Planner\Ledger\<callsign>.json`, the Planner's own folder: written directly, never thrown).
A cartridge key is a **leftover** when all three hold:
1. the ledger says the Planner wrote it for **another flight** than the one shown now — EZBoards mode: the printed
   briefing's flight; WDP mode: the populated flight; the Planner: the flight it has open. Two missions are the same
   flight when callsign, package, flight number, theater and save agree wherever both say; with either callsign
   unknown nothing is called a leftover;
2. it is a **mission item**: a steerpoint or target (`target_n`), a weapon target (`wpntarget_n`), a PPT (`ppt_n`), a
   line point (`lineSTPT_n`) or a nav offset (`[NAV OFFSETS]`, VIP/VRP and the offset aim points) — and it places
   something (not BMS's empty slot);
3. the cartridge **still holds exactly the value the Planner wrote** (numbers to half a foot, so BMS's own six-decimal
   rewrite at DTC SAVE or FLY changes nothing). A key BMS or the pilot changed since is theirs and is never touched.

**What the app does with them: it clears them by itself**, and shows nothing — a new mission regenerates its own
picture, so marking or asking about the last one's helps nobody. When a new mission begins the PC writes the leftovers
(the keys saved for any other flight) back to BMS's empty values, and at a switch of mode every key the Planner wrote
that still holds its value (`0.000000, 0.000000, 0.000000, -1, Not set` for a steerpoint, the empty PPT and line
slots, `0,0,0,0` and `none` for the nav offsets, an offset aim point removed), the same safe write as Save to DTC,
after checking each still holds the Planner's value; the nav offsets go whoever wrote them (rule 1 does not apply to
them, [What a switch resets](#what-a-switch-resets)), and so do BMS's copies of the Planner's values ([Starting the next
mission](#starting-the-next-mission)). The ledger keeps what was cleared (`cleared`, the last five resets) for the Undo
route, which no screen offers, and to know BMS's copies of a PPT or target by; no log of past values is kept.
Then **LOAD** the DTC in BMS (the jet loads the cartridge at LOAD, not when the file changes); the Planner's DTC page
reads the file again (asking first over changes not saved). `MissionData.leftovers` and `POST /api/cartridge/
leftovers` stay in the API for older devices; `leftovers` is always null now and no device calls the route.

**What it cannot tell.** Keys BMS wrote (a Recon's targets, PPTs or lines placed in BMS's own 2D DTC window, anything
saved before 1.3.8) are not in the ledger and cannot be dated, so nothing new is shown for them — unless one holds a
value the Planner wrote for another flight, which makes it BMS's copy of the Planner's (above). BMS's own **CLEAR** in
the 2D DTC window wipes the whole cartridge (UM §5.1.11) — the blunt way to start from nothing — and the ledger then
finds nothing left of the Planner's.

**Everything that can outlive its mission, audited:**

| Item | Where it lives | Who wrote it | How the app tells it belongs to an earlier mission | What happens |
|---|---|---|---|---|
| Lines L1-L4 | cartridge `lineSTPT_0…23` (a TE: its `.ini` too) | BMS (2D DTC) or the Planner | the ledger (Planner's only) | the Planner's (a line only whole): cleared by itself at a new mission (another flight's) or a switch (all of them), silently; and with Start each opened mission with clean lines and PPTs (on by default) every line in the cartridge and the campaign mission file LOAD reads, whoever drew it, when the Planner plans another flight (never a TE's) |
| PPTs 56-70 | cartridge `ppt_0…14` | BMS or the Planner | the ledger | as above |
| Steerpoints, precision targets (STPT 15-22, 81-99) | cartridge `target_n` | BMS (Recon, DTC SAVE) or the Planner (From mission…, the Map page) | the ledger; BMS's Recon bank is BMS's and stays unmarked | as above. In 4.38.1 a cartridge steerpoint wins over the mission file's route, so a leftover route point is the most misleading of all |
| Weapon targets | cartridge `wpntarget_0…99` | BMS or the Planner | the ledger | as above |
| VIP/VRP, OA1/OA2 | cartridge `[NAV OFFSETS]` (a TE: its `.ini` too) | the Planner's attack pages (Save to DTC), an older build, WDP | none needed: BMS's own DTC window never writes them | every one that places something is cleared at a switch, and at a new mission all but the new flight's own; WDP mode's snapshot lays out an attack from them only when Populate sent none |
| A TE's PPTs, lines, targets | `<TE>.ini` `[STPT]` | BMS (ships them) and the Planner (Save to DTC with that TE open) | recorded in the ledger with the file's name | never shown as a leftover: the file *is* that TE's mission, and BMS loads it only in that TE; cleared with the cartridge at a new mission and a switch |
| Radio presets and comments, IFF, EWS, MFD, HARM, laser codes, bingo, ALOW, `[Bullseye]` display | cartridge sections | BMS or the Planner | recorded in the ledger | **not** leftovers: settings a pilot carries from mission to mission, and nothing on a map. Check them on the DTC page |
| The bullseye drawn on the map | the save header (before 3D), the jet (in 3D) | BMS | always the shown mission's save | nothing carries over |
| Cockpit kneeboard pages | `KoreaObj\7982-7997.dds` | EZBoards, Upd Kneeboard, html_brief | Upd Kneeboard's window names each half's owner and date; the page file's time against the current flight's start | a switch of mode puts BMS's own page back over the other mode's halves made for an earlier flight; otherwise make them again for the new mission (KNEEBOARDS.md); **Put BMS's page back** clears one |
| WDP mode's snapshot | `wdp-mission.json` | the PC at Populate | the flight it was taken of, against the flight opened in the Planner | served in WDP mode only; Populate again; discarded when another flight is opened or populated, and at a switch to WDP mode when it is not the current flight's |
| The VR boards | the PC's `/api/mission`, `/api/taxi` | — | the same answers as the Mission section | follow the mission served; the map board is the Mission map and starts afresh with it |
| The maps' view of the mission (selections, Measure, HSD centre, framing), the attack pages and the card's delivery | the device's memory and preferences | — | the device's `mission_epoch` against the mission now | started afresh at every new mission and switch of mode ([What each device starts afresh](#what-each-device-starts-afresh)) |

The order that keeps everything on the same mission is the one [KNEEBOARDS.md](KNEEBOARDS.md) gives: weather chosen
and saved in BMS → DTC saved → PRINT → (WDP mode) Open mission… → Save to DTC → LOAD in BMS → Populate → Upd
Kneeboard → commit.

## Checks

`--missiontest <a copy of the BMS folder> <scratch APPDATA> out.txt` drives both modes against a copy: EZBoards mode
equal to BMS's files, WDP mode equal to the kept snapshot, a switch there and back with nothing of the snapshot left,
the taxi choice per mission, and the snapshot's `changed` list for each file and for a PRINT. Its part 8
(`LeftoversTest`) saves a line, two PPTs and a VIP for a made-up flight A, has BMS print flight B, and finds A's keys
back to BMS's empty values with nothing pressed (a PPT BMS wrote, and one it rewrote, left alone), the Undo route
exact, and the same flight printed again clearing nothing. Its part 9 (`SwitchResetTest`) plans flight A in WDP mode
(a line, PPTs, a VIP, STPT 85, Upd Kneeboard's pages 2 and 3), has BMS print flight B, switches to EZBoards mode (every
key the Planner wrote cleared, A's and B's, BMS's left alone; page 3 BMS's shipped file byte for byte, page 2's left
half BMS's with its EZBoards right half kept), undoes, switches back (the snapshot that is not the current flight's
discarded); the copy's pages are put back with the rest. Part 10 (`MissionPictureTest`) checks the one mission picture
and that no unspotted site leaves the PC; part 11 (`MissionEpochTest`) what each device starts afresh; part 12
(`SameBriefingTest`, also `--samebrieftest`) that the same flight shows the same briefing, steerpoints and threats in
both modes.

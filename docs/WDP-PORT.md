# WDP for Falcon BMS 4.38.1

Weapon Delivery Planner is **Falcas's** program. It is the tool the Falcon BMS community plans a delivery with, and
it is his work — the algorithms, the data, the layout and twenty years of getting it right.

The Planner (Mission → Planner) is **WDP for Falcon BMS 4.38.1**: it runs on a phone, a tablet, in a browser and on
the PC rather than only on the Windows machine beside the sim, and it is made for one version of the sim. Nothing here
is claimed as BMS Companion's own: where a page computes something WDP computes, it computes what his program
computes, and the About page, the Planner's own strip ("WDP by Falcas") and the last page of its Guide say whose work
it is. The Planner works in the Mission section's **WDP mode** (the switch at the top of the section): in EZBoards mode
it is greyed out, and what it plans reaches the rest of the app only through **Populate from Planner**.

If you maintain the Planner, four rules decide what goes where.

- **It looks like WDP.** Every page and window is drawn from WDP's own layout (below), so someone who has used the
  program for years knows the page at a glance. Controls keep their places even where the code behind them is new.
- **Falcas's code only where the app has nothing of its own.** The attack geometry (Pop-up, HADB, TOSS), the
  ballistics, the engine and performance tables, the ATIS wording and the latitude-and-longitude grid (which is BMS's
  own, D26) are his, ported and checked against his program's own answers. Everything the app already knows from
  Falcon BMS 4.38.1 itself comes from the app: airports, runways,
  ILS, TACAN and frequencies, charts, HARM codes, theaters and their terrains, radios, aircraft, weapons, pylons
  and racks, the briefing and the cartridge. WDP's own tables of those things are not shipped.
- **WDP's bugs are fixed, and each fix is written down with its evidence** ([below](#wdps-bugs-fixed)). The comparison
  tests carry every fix as an expected difference, by number, so a difference nobody wrote down still fails.
- **What the app does not do is not on the page.** WDP's buttons for paper printing, the save timer, a Tactical
  Engagement's copy buttons and rewriting the theater's `ppt.ini` are taken off the page (`WdpPage.hidden`), not
  answered with a message. What WDP did from several places the Planner also does from its own toolbar: **Save to
  DTC** saves every page (WDP's own Save DTC buttons stay where pilots look for them: five tabs of the DTC page and the
  card; the other nine tabs' are **From mission…**), the kneeboard writer is **Upd Kneeboard** (the card's own button
  is gone), File > Open is **Open mission…** ([below](#the-planners-shell-and-windows)). A theater comes from the app's data, so a new one needs no
  code.

## Checked against the program, not against a reading of it

A port verified by re-reading the source is verified by the one person least able to see their own mistake. So the
Planner is checked against **the real program's own answers**.

`tools/wdpref` is a small C# harness. It loads the shipped `WeaponDeliveryPlanner.exe`, drives its real controls by
reflection over a grid of inputs, and writes what they show to a TSV or CSV. The app's checks run the same inputs
through the Kotlin and demand the same labels — except where a fix is listed.

```bash
# once, on a PC with the .NET SDK and a copy of WDP
cd tools/wdpref && dotnet build
# run it from WDP's own folder, so its dependencies resolve
wdpref.exe WeaponDeliveryPlanner.exe wdpref.csv

# then, any time the Planner changes
"BMS Companion.exe" --wdpporttest wdpref.csv out.txt
"BMS Companion.exe" --wdppagetest <page> <reference.tsv> out.txt
```

Set every input explicitly when you add a reference (WDP otherwise reads the machine's `Setup.ini`), and set
`blnVersion` true (BMS, not Allied Force). Neither the harness nor the app ships any part of WDP. The references are
test fixtures; the decompiled source is a reading aid and stays out of this repository.

### The allow-lists

Each comparison names the fixes it expects in `desktop/src/main/resources/wdp/expected-diffs/<page>.txt` (the
Performance page's are in `WdpPerformanceTest.kt`): one line per fix, its number, the labels it may change, and
where it matters the shape of the change (`when angleOff!=0`, `value 360.0>0.0`). Anything no line explains fails.
The numbers are those of the table below.

| Page | Check | Allow-list | Last result |
|---|---|---|---|
| TOSS | `--wdptosstest toss.tsv` | `toss.txt`: D3, D4, D5 | PASS, 1,296 slider rows |
| HADB | `--wdppagetest hadb hadb.tsv` | `hadb.txt`: D4, D5, D8 | PASS, 3,300 sequences |
| Pop-up | `--wdppagetest popup popup.tsv` | `popup.txt`: D2, D8, D64 | PASS, 2,068 sequences, 99,071 samples |
| DTC | `--wdppagetest dtc dtc.tsv` | `dtc.txt`: A1, D11-D14, D17, P1-P5, N0, N1, V1 | PASS, 6,574 rows |
| DTC, 4.38.1 cartridge | `--wdppagetest dtc <copy of a cartridge>.ini` | — (every case must hold) | PASS |
| DataCard | `--wdppagetest datacard datacard.tsv` | `datacard.txt`: D20, D21, D23, V1 | PASS, 2,400 rows |
| Performance | `--wdppagetest performance perf.tsv` | in the test: D28-D31 | PASS: as the program 22,400 labels identical; as the page, every difference a D28-D31 fix; the inputs from BMS's data checked too (fields' elevation, the theater's stores, D66, a save's burnt fuel) |
| Performance, end to end | `--wdppagetest performance perf.cases.tsv` (the check writes `perf.cases.tsv.in`; `wdpref page PerfCase` answers it) | in the check: D28, D29, D31 | PASS: 21 scenarios on identical inputs (empty and loaded, pitch 10 and 13, AB and MIL, 5-35 °C, head- and tailwind, QNH in inches and hPa, the cruise radios, the turn calculator, BMS's training mission 1, WDP's own Osan picked by hand against BMS's): the page driven as a pilot drives it (Type, Select APT, the Loadout window, every box) is its arithmetic label for label; the port as WDP is WDP's; 1,043 labels WDP's, 133 a documented fix; the loadout meets 7 of 9 of the gross weights BMS's Training Manual prints (D33) |
| Engines | `--wdppagetest engines engprobe.tsv` | — | PASS, 44,000 calls, none differs beyond the last bit |
| Ballistics | `--wdpporttest wdpref.csv` | — | PASS, 720 releases |
| Coordinates | `--wdppagetest coords coords.tsv` | `coords.txt`: D26 (the Falklands, Korea TvT) | PASS: 8,014 of WDP's prints identical, the Falklands' 2,067 and Korea TvT's 2,017 as D26; BMS's own figures carried in the test (24 ACMI positions, 12 KTO and 10 Balkans AIP airports) within 1.9 m |
| Coordinates against BMS | `--acmicoords <recording> out.txt [theater]` on every recording at hand | — | PASS: 20 recordings (Korea 8, Hellas 9, Israel 3), every position within 2 m on the theaters of its terrain |

The Performance and coordinates checks also run WDP's own arithmetic (`wdpSlips`, `wdpCoordData`), kept beside the
fixed code for exactly that: the program as it is must still come out identical, so a fix can never hide a slip in
the port.

## What comes from where

| On the pages | From | Not from |
|---|---|---|
| Airports: names, ICAO, TACAN, elevation, runways, ILS, frequencies | the app's airport data for the theater, keyed by BMS's own campaign IDs (`Repo.airportSet`); the elevation is the field's ATC file's, else the ground BMS puts the jet on, its height map under the runways (`elevation.mjs`, docs/DATA-SOURCES.md) | WDP's `Airports.xml` (its CampIds are stale in 4.38.1: forum topic 32399) |
| Ground and parking charts (card **C**, Performance **Charts**, DTC **Charts**) | the app's airfield data, drawn as the Taxi page draws it; instrument charts from `data/charts.json` | WDP's chart pictures |
| HARM codes and symbols | `curated/harm.json` (Dash-34 4.38, Training Manual 4.38.1) | WDP's `HarmList.ini` (deleted) |
| Radio presets "Default" | the briefing's comm plan (User Manual 4.38.1 §5.1.5), the departure's TACAN and ILS | WDP's built-in list (its Blue and Red lists stay, under those names) |
| EWS, MFD and HARM "BMS default" | BMS's own `User/Config/*_Def.ini`, read through the PC (`/api/cfg/defaults`) | WDP's defaults |
| PPT types and rings | the theater's own `Campaign/Ppt.ini` (`Repo.pptTable`), radii from the threat reference | WDP's `RadarZone.ini` |
| The mission: flights, routes, packages, loadouts, targets, support tracks | a save picked with **Open mission…**, read on the PC by `CampaignArchive` with WDP's own `BMSUtils.dll` record layouts and served over `/api/campaign/*`; or the printed briefing, the cartridge and BMS's own route | WDP's File > Open |
| The briefing texts of a save with no printed briefing (situation, ROE, emergency, intel) | `CampaignBriefing`: the logic of BMS's own briefing scripts (`Data/Campaign/*.b`) over the theater's `Strings.txt`; intel as WDP's `FillMissionBriefing` lists it | — (WDP showed the team's motto) |
| Theaters and their folders (campaign, objects, 3D data) | BMS's theater definitions, `theater.lst` + each `.tdf` (`Theaters`) | WDP's folder-name guess (D49) |
| Tanker, AWACS and JSTARS TACAN, UHF, location | the same support list as the Briefing page (`supportAssets`); a save's own tracks with the half cell (D43) | WDP's campaign reader |
| The cockpit kneeboard pages | **Upd Kneeboard**: drawn on the device, written by the PC in each file's own format (`Dds.kt`, `KneeboardPrint.kt`); the Mission set on pages 1 and 2 in WDP mode, where EZBoards is paused (on the first pair EZBoards does not claim in EZBoards mode) | WDP's DevIL writer (D36, D47) |
| A picture on a kneeboard page (WDP's **Browse Picture**, "Selected Picture") | **Browse picture…**: the Open picture window on the BMS PC (`PcFiles`, WDP's title and its jpg/png/bmp/dds types after one showing all four, starting at the half's own picture, else the last picture, else the Falcon BMS folder), a preview read by the PC and sent as a PNG (`/api/files/picture`, `PictureFile.kt`: ImageIO; `.dds` in DXT1-DXT5 or any uncompressed masked layout, as DevIL reads them), a chooser with the page number, **← Insert left** / **Insert right →** and Cancel. **Printed by the PC from the file itself** (`KbHalf.picture`), stretched over the half at the page's full size, as WDP resizes the file to 1021 x 2046 without keeping its shape (`cntDataCard.cs:47281-47295`, `:47364-47378`); read again at each print, and a file gone by then leaves its half as it is with the reason. A half set to another kind keeps its picture (WDP's `m_Left[n].File`), so its **Picture…** reopens the chooser on it; the plan and every half's picture are kept on the device between launches, as WDP's Setup.ini keeps `[Kneeboard]` `Left1-16`/`LeftFile1-16` (`fclsMain.cs:9398-9428`, `:10292-10378`). **Deliberate differences**: the picture covers the whole 1024 x 2048 half, where WDP draws it at (2,1) and leaves a rim of the old page; transparent parts print white, where WDP blends the picture over the old page; a CMYK JPEG, which GDI+ reads and Java does not, is refused with a sentence saying to save it as RGB | WDP's `Image.FromFile` / DevIL on its own disk (`fclsKneeboardMulti.cs:2676-2860`) |
| Latitude and longitude | WDP's own grid over the terrain BMS flies the theater on (`Theater.projection`: size, centre, heightmap length), which is BMS's own figure (D26); the Falklands: `Theater.txt`'s projection string | WDP's lookup of the terrain (none for Korea TvT) |
| Aircraft, stores, weights, pylons and racks | the app's aircraft and weapon data, the mission theater's own: its variant of the jet, and each store's weight, drag and fuel, the pylons and racks and the conformal tanks from `data/wdp/racks.json` (`wdpracks.mjs`) | — (WDP read the same files of the running theater; the app had no racks) |
| Weather, QNH | a save's own `.twx` and the map it flies with, read again at every Open mission (`/api/files/weather`, `Twx.kt`, `Fmap.kt`), or BMS's printed briefing of the same flight when it is newer or gives the same weather, with the `.twx`'s QNH (4.38.1's briefing prints none); a file Reload WX picks over both (D68) | WDP's `.twx`/`.fmap` reader (its overflows are not ported; it cannot read 4.38's version 8 `.twx`) |
| The ground under an attack page's target | BMS's own height map, the cell WDP's `ReadNewTerrainElvLoc` reads, on the PC (`/api/campaign/ground`, `TerrainHeights`) | WDP's reader, which opens the 2 GB file for writing with no sharing |
| Theaters | `data/index.json`, one entry per 4.38.1 theater | WDP's `Theater.xml` |
| Attack geometry, ballistics, engine tables, ATIS wording | **Falcas's code** | — |

**Theaters.** The Planner reads only fields every theater carries (`airportSet`, `radioSet`, `airfieldSet`,
`projection`, `pptSet`), and the extractor states whether each theater has them (`planner` in `index.json`;
`node src/plannercheck.mjs` fails otherwise). The theater is the one BMS reports, matched **exactly**
(`plannerTheater`): a theater whose name only starts like another's would put every coordinate in the wrong
projection. A theater installed after the app was built is not found, and the Planner says so on its header —
coordinates, airports and charts wait for a data update, while the geometry, ballistics and performance still plan.
Korea TvT flies KTO's terrain and prints KTO's latitude and longitude (WDP found no terrain for it and hid its coordinates).

## What is ported

| Ported | From | Checked |
|---|---|---|
| `data/wdp/Ballistics.kt` | `clsBallistics` | 720 releases — 3 drag categories × 8 dive angles × 5 speeds × 6 heights — agree to the foot and to the tenth of a second |
| `data/wdp/Atmosphere.kt` | `clsMeteo`, with D28 fixed (the lapse stays WDP's 2 °C per 1,000 ft, which is BMS's: D32 withdrawn) | the fixed pressure altitude against the standard formula (Osan, 42 ft: 29.92 → 42, 30.42 → −458, 29.42 → 542); WDP's own kept as `wdp*` for the comparison |
| `data/wdp/TossPlan.kt` + `ui/screens/wdp/TossWiring.kt` | `cntTOSS` — sliders, compass shortcuts, the reference knob, TAS, turn radius, pull-up, bomb range, the pull-up point, OA1/OA2, the VRP and VIP, the DED figures, the HUD-visibility check | `--wdptosstest`: 1,296 slider combinations, 36 labels each, **character for character** except D3-D5; a geometry section re-lays every point in feet |
| `data/wdp/HadbPlan.kt`, `PopupPlan.kt` (+ `PopupNet`, `PopupX87`, `AttackMap`, `AttackGeometry`) and their wirings | `cntHADB`, `cntPopUp` | `--wdppagetest hadb` / `popup` against the real pages' own events; the geometry sections |
| `assets/data/wdp/engines/engines.wdpc` + `data/wdp/Engines.kt`, `EngineCode.kt` | `clsGE100`, `clsGE129`, `clsPW200`, `clsPW220`, `clsPW229` | the methods are **translated, not sampled** (`tools/extractor/src/wdpengines.mjs`: every statement the page reaches, as a small prefix code the app runs); `--wdppagetest engines`: 44,000 calls to the 22 methods of all five engines at random inputs, **none differs** beyond the last bit of a double |
| `data/wdp/PerformancePlan.kt`, `PerformanceLoadout.kt` + `ui/screens/wdp/PerformanceWiring.kt` | `cntPerformance` — take-off, MIL and MAX AB climb, cruise altitudes and ceilings, weights and fuel, wind, QNH, the turn calculator; Select APT, Loadout (`fclsLoadout`) and Charts (`fclsChart`) | `--wdppagetest performance` (above) |
| `data/wdp/DtcPage.kt` and kin + `ui/screens/wdp/DtcWiring.kt` | `cntDTC`, `clsLoadDTC`, `clsSaveDTC` | `--wdppagetest dtc`, `--cartridgetest` |
| `data/wdp/DataCardPlan.kt` + `ui/screens/wdp/DataCardWiring.kt` | `cntDataCard` | `--wdppagetest datacard` |
| `data/wdp/WdpCoords.kt` + `PopupCoords.kt` | `fclsMain.InitNewTerrain`/`InitTransverseMercator` and the conversions of `clsCoordinates`: WDP's grid, which is BMS's own latitude and longitude (D26) | `--wdppagetest coords`, `--acmicoords` |
| `desktop/.../bridge/CampaignArchive.kt`, `CampaignFiles.kt` | WDP's File > Open over `BMSUtils.dll`'s record layouts (the directory, `.cmp`, `.uni` with every version branch, `.obj`, `.tea`, `.plt`), with D41-D43, D48 and D49 fixed | `--camtest`: every unit and objective part of the install's 517 saves walks to its last byte with its header's count, 99.5 % of waypoint targets resolve, a save's briefing equals the printed one row by row |

## WDP's bugs, fixed

Evidence: **forum** = posts of WDP's thread on the BMS forum (2011-2026); **code** = WDP 3.7.24 decompiled; **run** =
seen in the real program or the port; **manual** = BMS's 4.38.1 documents. Each fix is also described where it is
made ("Fixed" in the KDoc of the file named).

| # | Page | WDP did | Evidence | Now | Where |
|---|---|---|---|---|---|
| D1 | TOSS | VIP bearings measured with north and east exchanged (`CoordFlow` read its own lat/lon labels back east first) | run: the comparison, the guide's case (IP 8 nm on 020°) | true north and east: TOSS reads 020.0° / 48,610 ft, as Pop-up does | `TossPlan.kt` |
| D2 | TOSS | VIP offset aim points measured from (0,0) (`intOA1_X/Y`, `intOA2_X/Y` never assigned) | code | from the points themselves | `TossPlan.kt` |
| D2 | Pop-up | `VIP_Type2` measured OA2 from OA2's north and OA1's east | code, run | from OA2 | `PopupPlan.kt`; `popup.txt` |
| D3 | TOSS | DEST OA1 showed the pull-up point while Save to DTC wrote the real OA1; `VIP=` lines written in VRP mode; "VPP"; in VIP mode the four lines named the steerpoint `Setup()` read from `[TOSS] Waypoint` (`strIPpoint`, the target's own number) until the TGT STPT box was next moved, while the save wrote the IP (box − 1) | code (`cntTOSS.Setup` l.4740-4741, `FillLabelsBMS`), run (WDP on the last Korea save: VIP-TO-TGT "3" for target 3, saved `VIP` 2) | the DED shows what is saved; only the selected mode is saved (since D88 the other mode's lines are cleared, not kept); TGT-TO-VRP; the IP's number on the VIP lines | `TossPlan.kt`; `toss.txt` |
| D4 | TOSS, HADB | `Tan` where the angle a turn radius subtends is `Atan`; TOSS also `cos(sin(angle off))`, and `VRP()` ran on the previous flow's OA1 | code (the port's own notes), run | `atan(R/D)`, `cos(angle off)`, OA1 before the VRP; nothing moves at 0° off | `TossPlan.kt`, `HadbPlan.kt` |
| D5 | HADB, TOSS | a bearing printed "360.0"; HADB's ingress TAS one calculation behind its altitude | run | north is 0.0; the TAS follows the altitude it depends on | `hadb.txt`, `toss.txt` |
| D7 | attack pages | the ELEV lines could not be set; HADB and TOSS handed the jet heights above the target as if it stood at sea level, where the Pop-up page adds the ground under the target from BMS's height map (`GetTerrainHeight`: on the last Korea save 65 ft under steerpoint 3, its Setup.ini keeping `IngressAlt=565` for a 500 ft ingress) | forum #2322, code, run | every page's ELEVs are WDP's heights above the target plus the ground under it (Training Manual 4.38.1 p.179), a WDP 0 stays 0 = ground level (Dash-34 4.38: "pilots should normally enter 0"). The ground is the same cell of BMS's height map, read on the PC (`/api/campaign/ground`, `TerrainHeights`), else the cartridge's target point; every ELEV, and the ground itself, can be typed, and each shows it can | `AttackGeometry.kt`, `WdpGround.kt` |
| D8 | Pop-up, HADB | TGT STPT started at 3, so a target on steerpoint 1 or 2 could not be planned | code (designer `Minimum = 3`) | 1 to 25 | `popup.txt`, `hadb.txt` |
| D9 | TOSS | lost its settings on the way back: `fclsMain` writes the attack heading as `AngleOff` (out of range above 90 → 0), the turn as 0/1 where `Setup` takes only "Right" (a right turn came back left), the release CAS in knots where `Setup` compares it with the slider's tens (always back to 500), and `Ref` inverted and then forced to VRP by `Ref()` before any IP is known | code (`fclsMain.cs` l.9373-9387, `cntTOSS.Setup`), run (the harness: `[TOSS] AngleOff=180` → 0) | `[TOSS]` kept as the other pages keep theirs, read back as written; a value outside its slider goes where WDP's `Setup` puts it (G and OA2-to-PUP 3, release angle, angle off and heading 0, release CAS 500) | `TossWiring.kt` |
| D10 | TOSS, HADB | threat rings mirrored about the map's diagonal (a PPT's east passed as its north) | code | drawn where they are | `AttackMap.kt` |
| D11 | DTC | every ILS below 109.00 raised to 109.00 | code; 4.38.1 airports use 108.10-108.95 (Daegu 108.70, Gimpo 108.30, Ben Gurion 108.70…) | 108.10-111.95 kept | `DtcCartridge.kt`; `dtc.txt` |
| D12 | DTC | HARM list: `SA8/SA9/SA13/SA19/PATRIOT/NIKE/SKYGUARD` with code −1, a row named "-1", Patriot and Hawk sharing 230, SNOWDRIFT twice | forum #2302, #2356-#2357 | the app's list (40 codes, both the 1xx whole-system and 2xx radar codes); an unknown code shows its number and is kept | `DtcPage.kt`; A1 in `dtc.txt` |
| D13 | DTC | IFF codes lost their leading zeros (0554 → 554); a time criterion −1 written as 18446744073709551615 | run; BMS's `IFF_Def.ini` | four digits kept, −1 kept | `DtcSave.kt` |
| D14 | DTC | MFD de-duplication: 11 of 72 selector handlers compared the wrong boxes | code | one rule for all 72 | `DtcPage.kt` |
| D15 | DTC | "Default" wrote WDP's MFD pages (`Display0-0-0=0`) | code | BMS's own `MFD_Def.ini` (and EWS/HARM), offered as "BMS default" | `DtcWiring.kt` |
| D16 | DTC | precision steerpoints lost or shifted on save | forum #2304, #2150 | only edited keys are written; steerpoints 81-99 round-trip unshifted | `DtcEdits.kt`, the 4.38.1 cartridge cases |
| D17 | DTC | positions written in single precision; steerpoint names dropped | run | the exact figure; names kept (P1) | `DtcSave.kt` |
| P1-P6, N0, N1 | DTC | Open 2 read one key late; weapon-target names cleared by the wrong reader; the second OA2 read into OA1; IFF digits read from the left; an emptied Profile 1 ripple box showed 1 but took Profile 2's number (`mxtP1_Pulse_Leave`); the second offset-aimpoint pair read from the steerpoint after the first (`OA1-4`) where the file keeps it on its own steerpoint (`OA1-6`), so the NAV OFFSETS tab showed zeros for the pair an attack page had just saved; OA1-0/OA2-0 written for nobody; the last attack page's offsets replacing the cartridge's on the next save | run (the comparison), code | each put right | `dtc.txt`, `DtcCartridge.kt`, `DtcPage.kt` |
| D18 | DTC (port) | the status line stuck on "Loading…"; a partly typed ILS gave an error box; a pilot with no cartridge left the page misleading; the page read the cartridge once, when the Planner was first drawn — before the link to the PC was up on a phone, a browser or a PC still starting — and never again ("The PC could not be reached" until the pilot chose Re-read DTC from BMS) | run (guide §15-1/2/3; the Planner drawn before the link, 2026-09-30) | fixed: a mission that arrives while no cartridge is on the page reads it again | `DtcWiring.kt` |
| D19 | DataCard | training and TE missions failed to load: an overflow when take-off is earlier than the taxi allowance (`FillCommCard`), and the same class elsewhere (`TasToIas` above ~93,195 ft, a speed past a short) | forum #2301, #2319, #2343; code `cntDataCard.cs:43404` | nothing stops the plan: that cell is blank | `DataCardPlan.kt` |
| D20 | DataCard | `GetTime` truncated (03:58:13 printed 03:58:12) and could print minute 60 | code, run | rounded to the second, carried into the hour | `datacard.txt` |
| D21 | DataCard | formatting slips: an "m" before a hold on rows 9 and 11; the landing rows; a window printed `05:10:00-12:00`; "Else" + VRP + nm put OA2 in OA1's box; the fourth flight's TACANs from the second's size; the second laser box checked the first; a 25-waypoint flight's fuel ladder read a stale entry | code | each put right | `datacard.txt` |
| D22 | DataCard | support block: LOC wrong, frequencies not the briefing's; blank in the port | forum #1907, #2311-#2316, #2320; run | the Briefing page's own support list (TACAN 059Y / 356.850 on the guide's mission) | `DataCardWiring.kt` |
| D23 | DataCard | the airports' UHF column showed Ground and VHF Approach | code | Tower UHF and VHF; Ground, Approach and Ops on their own lines | `datacard.txt` |
| D24 | DataCard | a field with no TACAN of its own left blank | forum #1782 | the nearest TACAN within 6 nm, marked `*` | `DataCardSources.kt` |
| D25 | DataCard | civil visibility "71SM"; Force QNH printed from a float product | run; forum #2303 | capped at 9999 m / 7SM as METAR does; QNH as the briefing gives it, else the save's `.twx` (D68), "STD" when neither does | `DataCardSources.kt` |
| D26 | every coordinate | **Not a WDP bug: WDP's latitude and longitude are BMS's own**, and `Theater.txt`'s projection string (3.27998 ft/m, its `x_0`/`y_0`), which the port once took for "BMS's own projection", lies ~200 m off on every 1,024 km theater; real-world airfield positions (seven within 0.81 nm) cannot tell 200 m apart, BMS's own figures can (the table in [Latitude and longitude](#latitude-and-longitude)). What WDP does not do: print anything on Korea TvT (its `.tdf` names no terrain; BMS flies it on `Terrdata\korea`) — and on the 2,048 km Falklands its grid counts 1,024 km, putting every latitude ~9° north of the theater's own centre (47°09.5′ S); whether BMS's own figure there agrees is **not checked** (no recording, no AIP with BMS coordinates), so that is not claimed as a WDP bug | run: BMS 4.38.1's ACMI recordings write each object's lat/lon beside its U/V (feet ÷ 3.28084): 148,441 positions of 88,340 objects in 20 recordings (Korea 8, Hellas 9, Israel 3) — WDP's grid within 1.3 m (mean 0.6 m, the print's rounding), the projection string 144/168/184 m on average, up to 243 m, never within 2 m; manual: the "BMS coord" of BMS's KTO AIP (22 airbases: grid within 1.9 m, string 147 m on average) and the Balkans AIP's "BMS GPS COORD" (103 of 104 airports within 2 m, median 0.0 m; string 219 m; the other, Taszar, is 1.27 km from both, a misprint) | WDP's grid (`WdpCoords.bmsGrid`) over the size, centre and heightmap length of the terrain BMS flies the theater on: WDP's own strings wherever WDP reads that terrain, KTO's on Korea TvT; the Falklands keep the projection string, not verified (`S51,49.468 W058,25.939`, a hemisphere letter where WDP floored to `-52,10.632`) | `coords.txt`, `WdpCoordsTest.kt`, `AcmiCoords.kt` |
| D28 | Performance | pressure altitude = elevation − \|QNH − 29.92\| × 100: wrong sign and a tenth of the size, so a low-pressure day read as a sea-level one | code, run; BMS's own atmosphere: "-1 Mb by 30 feet", "Low pressure / high temperature will decrease performance" (User Manual 4.38.1 §4.1) | elevation + (29.92 − QNH) × 1000 (BMS's 30 ft a hPa is 1,016 ft an inch, within 1.6 %): the take-off factor now rises for a low QNH. Gunsan (22 ft) at 1010 hPa: WDP 1.394, the page 1.404 | `Atmosphere.kt` |
| D29 | Performance | Actual Temp did nothing (snapped back to ISA) | code, run | the picked temperature is used; a non-number is refused | `PerformancePlan.kt` |
| D30 | Performance | Cruise Alt compared as text ("9000" became the service ceiling); runway 3's metres from runway 4 | code, run | compared as a number; its own runway | `PerformancePlan.kt` |
| D31 | Performance | the climb and fuel charts always given ISA deviation 0 (`cntPerformance.ISA_Dev` is declared at l.207 and never assigned, while the page prints the real deviation as "ISA dev"); the GE-100's AB schedule dead for drag 101-200 (`> 150 && <= 150`, `> 200 && <= 200`, so drag 101-200 got the heaviest schedule, 480 / 0.85, slower than drag 201-300's 515 / 0.88); every engine's refusal cell 12,000-15,000 ft read from the wrong end (`array10[1] - array9[0]`) | code; the charts carry their own corrections per °C above standard (MIL climb distance +0.5 %, time +0.25 %, fuel +0.47 %; MAX AB +0.75 %, +0.42 %, +0.54 %); the GE-129's chart gives the intended rows; BMS: "high temperature will decrease performance" (User Manual 4.38.1 §4.1) | the real deviation (the user's day, 25 °C at Osan, ISA+10, the save's stores, climb to 21,000 ft: MIL 24.9 → 26.1 nm, 608 → 637 lb, 3:17 → 3:22; MAX AB 5.3 → 5.7 nm, 684 → 721 lb, 0:44 → 0:46); the GE-129's schedule (drag 132: 565 / 0.90 where WDP says 480 / 0.85); the cell's two edges blended (the chart is linear across a cell) | `PerformancePlan.kt` |
| D32 | Performance | *withdrawn.* The port used the ICAO 1.98 °C per 1,000 ft for the page's ISA deviation and turn calculator, where WDP uses 2 | BMS's atmosphere: "a dry adiabatic lapse rate of -2°C per 1000 feet" (User Manual 4.38.1 §4.1, p.49) | WDP's 2 °C per 1,000 ft, digit for digit: the turn calculator at 26,000 ft is −37 °C again (the port said −36, and 571 kt TAS where WDP says 569). The DataCard's own ISA temperature is WDP's 1.98 and stays | `Atmosphere.kt`, `PerformancePlan.kt` |
| D33 | Performance | pylons and racks weighed nothing: WDP adds each loaded hardpoint's pylon and rack (`fclsLoadout.WeightsDragsFuel`, l.4836-4845) but finds them through `Sim\acdata\<jet>.dat` (`fclsMain.ReadAcRackGroup`, l.13795-13804), which 4.38.1 does not have, so it totals the stores alone; the runway not chosen by the wind; QNH not filled | code, run; BMS's own gross weights: the Training Manual 4.38.1 prints the loadout screen's gross weight for nine training flights, and empty + the flight's fuel + its tanks + its stores **with** BMS's pylons and racks gives 7 of the 9 to the pound (missions 1, 7, 9, 17A, 17C, 18, 19), the stores alone 1 (mission 1, wingtips only, no pylons); the other two print a figure for other stores than the mission file carries. The drag is in WDP's unit: mission 1's "Drag Factor: 9.0" is 1 + the AIM-9M's 4 + the ASQ-T50's 4 — WDP's clean jet (`PlaneDrag = 1`) plus BMS's `Drag`, the field WDP reads as `DragIndex` for stores, pylons and racks alike | BMS's own pylon and rack weight and drag (`racks.json`): the user's save carries 4,779 lb / drag 132 where WDP shows 4,141 / 102 (2 × 138 lb / 5 on 2 and 8, 2 × 95 / 2 on 3 and 7, 172 / 16 on 5); the runway into the wind; the briefing's QNH through the card | `PerformanceLoadout.kt`; `--wdppagetest performance <x>.cases.tsv` |
| D34, D35 | loading | airbases not filled in 4.38.1; theaters needing their own `Theater.xml` entry | forum topic 32399, #2244-#2249, #2331-#2332, #2352-#2354 | BMS's own campaign IDs and the app's theater list | — |
| D36 | Upd Kneeboard | WDP deleted each kneeboard file and saved it again with the error ignored, so a failed save left no page or a 0 KB one; rewrote all sixteen files on every save, as DXT1 without mip-maps; previewed `objectdir` but wrote `3ddatadir`. (The 4.37 "vanishing pilot" the thread blamed on WDP followed any changed DDS and was fixed by a BMS update.) | code (`cntDataCard.cs:47148-47413`, `File.Delete` at `:47384`, the save's try at `:47386-47395`); forum #2071-#2072, #2153-#2158, #2299-#2300, #2278-#2279; the output measured (DevIL, DXT1 2048², one level) | **Upd Kneeboard**: only the pages chosen, each written beside itself and moved into place (D47), from the theater definition's `3ddatadir` for preview and write alike. No backup, by the user's decision: a page is made again at will, and **Put BMS's page back** restores BMS's shipped page | `KneeboardPrint.kt`, `KneeboardPrintWindow.kt` |
| D38 | DataCard | *withdrawn.* The port took the card's Drag and Config rows (A-A, A-G, ECM, Tanks) for never shown, `FillOrdnance` being called from nowhere (`cntDataCard.cs:39500`). They are written by the Performance page's loadout window, which WDP opens hidden and closes whenever a flight is loaded (`cntPerformance.cs` l.4230-4253 → `fclsLoadout.cs` l.5060-5066): each store by its SMS name and the loadout's total drag | run (the user's WDP on the last save: `2x A120B 4x 120C5`, `1x AL184`, `2x TK370`, Drag 102) | WDP's rows: the seat's stores, each by BMS's SMS name (`Falcon4_SWD.xml` WpnName, the Arsenal's `simName`); the drag is the Performance page's, with its other take-off figures (pylons and racks included, D33) | `DataCardWiring.ordnance`, `DataCardSources.smsName`, `WdpHandOff.kt` |
| D39 | typing (port and WDP) | the DTC page's pilot box was drawn under the pilot's name, which took the press ("change callsign" could not be typed in: WDP brings it to the front); a box followed its page's value, so a page that answered a key a frame late, or not at all, threw the caret to the end or lost the key; a typed text or up/down box was never left before a button's Click, so Save straight after typing missed it; WDP's fuel KeyUp rewrote the box being typed in rounded to 100 (the first key of "4520" became "100"); the Names window's names could not be typed (DropDown combos) | run (`--wdptypetest`), user report | every box behaves as a Windows TextBox: its own text and caret while it has the keyboard, the page's answer taken when it is a validation; Leave before Click; Enter commits (a window's AcceptButton, WDP's KeyDown for Return), Tab moves on, Escape is Cancel; the pilot box in front with the keyboard; the fuel figure rounded when the box is left, as WDP's Leave does | `WdpForm.kt`, `WdpDialogs.kt`, `DtcWiring.kt`, `DataCardWiring.kt`, `DataCardWindows.kt`; `wdptypetest.txt` |
| D40 | attack pages, DataCard | WDP sets all three pages' TGT STPT to every strike steerpoint in turn and so ends on the **last**, while its card names the **first** as the Primary target: with two strikes the card and the Delivery block disagree | code (`cntDataCard.cs` l.41238-41297) | the pages open once on the first strike steerpoint, the card's Primary; after that each keeps its own | `WdpMission.defaultStpt` |
| D41 | Open mission | BMS's own files refused by name only, missing `te_new_nt`, `Instant` and the Falklands' `Save6`/`Save7` | code (`cntDataCard.cs:40359`); the install's 169 starts all have an `.obj` part and no flights | campaign starts by structure (an `.obj` part, or no flights) and by their names (`Save<n>`, `Te_New*`, `Instant`): listed greyed with the reason, never opened or written. BMS's own TEs and trainings open and save like the pilot's, as in WDP | `CampaignStarts` (`CartridgeStore.kt`), `CampaignFiles.stockWhy` |
| D42 | Open mission | saves opened with `new FileStream(file, FileMode.Open)` — read/write access, which fails on a read-only install or while BMS holds the file | code (`fclsMain.cs:19611, 19669, 19713, 19776, 13139, 16529`) | read only, the whole file at once, no lock kept; a file modified in the last 1.5 s waits until BMS has finished writing it | `CampaignArchive.kt`, `CampaignFiles.kt` |
| D43 | tanker and AWACS tracks | a track drawn from the waypoint's cell corner, without the half cell the flight plan adds: 0.27 nm south-west | code (`fclsMain.cs:22430` against `cntDataCard.cs:41352`) | the cell's middle, as every other waypoint | `PlannedRoutes.kt`, `CampaignFiles.kt` |
| D44 | flight picker | packages filed under their owner, so a U.S. flight in a ROK package is listed only under ROK; a side question first | code (`fclsSelection`, `fclsSide`); run on the Korea save | team → package → flight, a package under every team with a flight in it ("package of ROK"), allied teams first, no side question | `FlightPickerWindow.kt` |
| D45 | steerpoint positions | after a flight is picked WDP asks "Did you save Precision STPT in the DTC for THIS flight in BMS? … DEFAULT ANSWER SHOULD BE: NO!" (a campaign always; a TE only when its `<save>.ini` is beside it, "If you are not sure, choose NO."), yet its box's default button is **Yes**; Yes keeps the mission file's precision slots (action -1) and still takes every other route point from the save's grid cell; No takes every steerpoint from the cells and loses BMS's Recon targets | code (`cntDataCard.cs` l.40486-40497 the question, `MessageBoxButtons.YesNo` with no default given; l.41347-41370 `CreateFlightplan`), forum #2304-#2305 | **the question is asked again, as WDP asks it**: word for word, at the same moment (Plan this flight in the flight picker, which Different Flight opens for a save), a campaign always and a TE only with its mission file, with **No** the default button (Enter; Escape and × answer No too). **No**: every steerpoint of the flight at the save's position (its cell's middle), as WDP's No; the cartridge's positions are not used. **Yes**: the cartridge's position for every slot that has one (precise: BMS's Recon, the pilot's edit), where WDP's Yes took the grid cell for the route points, and **where the cartridge's slot is empty, the mission file's** (BMS's route beside the save, else the save's cell), named in the strip ("STPT 8 empty in your DTC"). The flight picker shows what each answer gives before the press; the identity strip and the source line say which was chosen ("Precision STPT: Yes · STPTs: 7 from your DTC, 1 from the save"); the attack pages' TGT STPT and IP STPT tips name each steerpoint's source (the Campaign lamp that did so went with D87). The printed briefing is never asked about: the cartridge, else BMS's route | `FlightPicker.plan`, `PlannerMissionState.precision`/`asksPrecision`/`precisionQuestion`, `WdpMission.slots`/`sourceLine`, `WdpDialogs.message(default)` |
| D46 | Save to DTC in a campaign | WDP's campaign save copies the cartridge's steerpoints into the mission file beside the save; in 4.38.1 those slots are empty, so it zeroes BMS's route | code (`fclsMain.cs:18203-18217`) | a campaign save writes the cartridge only; in the pilot's own TE the edited `[STPT]` keys also go into the TE's `.ini`, which BMS loads over the cartridge there (TM §11.3 Note 1) | `CartridgeStore.saveTe` |
| D47 | Upd Kneeboard | the half not printed was decoded and encoded again (DXT1, PSNR 80 dB, the mip chain dropped), and every file was rewritten whatever was chosen | measured (WDP's own DevIL, run in a scratch folder over a copy of BMS's `7982.dds`) | only the halves chosen are drawn; the other keeps its compressed blocks on every level at least 8 px wide, and only the three smallest levels are rebuilt from both; the file keeps its format, size and mip count; written to `<n>.dds.bmsc-new` and moved over the page; a page file that does not exist is never created; a format it does not know (DX10, BC7, DXT3, 24-bit) is refused | `Dds.kt`, `KneeboardPrint.kt` |
| D48 | reading a save | `ReadPlt` skips a byte before the callsign table | code (`fclsMain.cs:13221`); the part sums exactly without it (2 + 800×4 + 2 + 169 = 3,373 bytes) | no byte skipped | `CampaignArchive.kt` |
| D49 | every page | the theater picked by a substring of the save's folder name, falling back to Korea | code (`cntDataCard.cs` l.40383-40401, `fclsMain.cs` l.7078) | the theater definition whose `campaigndir` holds the file (`theater.lst` + `.tdf`), which also finds the six Korea 2012 theaters and LKTO Papa | `Theaters.kt` |
| D50 | DTC page | the Clear All buttons zero every point at once and the next save writes it, with no warning: one misclick loses a Recon target set | code (`cntDTC` btnClearStpt_Click → ClearSTPT); a pilot's loss on 2026-09-28 | Clear asks when a placed point would go, naming them; any save that would set a placed point to 0,0,0 asks once | `DtcPage.confirmClear`, `DtcWiring.zeroed` |
| D51 | Performance | a flight of another aircraft than the F-16: WDP computes nothing and leaves the last F-16's type, engine, weights and figures on the page, so the DataCard's gross weight and fuel are an F-16's | code (`cntPerformance.DoCalculations` returns at once) | Type shows the flight's aircraft by name, its own BMS weights and stores, the figures blank; the card gets that jet's gross weight and fuel, no speeds | `PerformancePlan.otherAircraft` |
| D52 | Performance | the briefing's F-16 not found in WDP's type list when BMS names it its own way (F-16CJ-50, F-16CG-40, F-16DG-40 IAF, F-16C 50+ THK, F-16D-52+ RSAF, F-16C 393 HAF…: 15 of the app's 68 F-16 names), so it ran as whatever jet the list last had, with no stores | run | found by name, then model letter + block, block without an unlisted "+", name without the model's M, BMS's data file names, the model alone | `PerformancePlan.typeItem`, `missionAliases` |
| D53 | Performance | Type "F-16C-42 EAF" / "F-16C-42-EAF" is listed but has no case in `clsAircraftData.Aircraft`, so the engine and figures stayed the last jet's | code (`cntPerformance.cs` l.3616) | F100-PW-220, as the list's F-16C-42 and F-16CM-42 (the comparison keeps WDP's answer) | `PerformancePlan.aircraft` |
| D54 | Pop-up, HADB, TOSS | with TGT STPT on a slot with no position WDP plans against a stand-in point of its own (1510000, 1510000) and draws the plan over whatever terrain that is, mid-theater | code, run | the map is drawn on black with a caption saying why ("No mission yet: Open mission… or press PRINT in BMS", "STPT n has no position in this mission…"), and the page offers no attack to Populate from Planner or the kneeboard | `HadbPlan.attackPlot`, `WdpAttackMap.kt` |
| D55 | DataCard | **WDP shows no weather at all on a BMS 4.38.1 save, and Reload WX did nothing visible.** WDP's `ReadWeather` has no layout for BMS 4.38's version 8 `.twx`: it reads the file with version 7's, runs past the end of the 728-byte file and throws, and its catch returns false — for every version 8 file (all 347 on a 4.38.1 install). So on every 4.38.1 campaign save, TE and training, File > Open blanks the ATIS (`Atis1 = ""`) and makes no weather list, and the Performance page keeps Setup.ini's temperature with wind 0/0 and 29.92; Reload WX on a `.twx` fails the same way and says nothing. An `.fmap` picked with Reload WX is read (`if (num < 0)` is right: `ReadFMapData`/`ReadFMapDataNew` return -1 when they succeed and 2, 4 or 5 when they do not), but `CreateAtis` reads the map only when the weather model is already 3, which the button never sets, and a map that could not be read says nothing | code (`fclsMain.cs:11396-11713`: a buffer of the file's length + 1 read at version 7's offsets, the catch at l.11706-11713; `cntDataCard.cs:28378-28419` Reload WX, l.40429-40447 File > Open, `CreateAtis` l.33675), traced twice from the source; run (WDP's read replayed on the last save's `Auto Save.twx` and on the install's 347 version 8 files: every one gives up) | the file picked, and the save's own `.twx` (D68), is the card's weather: an `.fmap` (version 5 or 8) at each field's own cell; a `.twx` read with version 8's own layout (`Twx.kt`), as the table of the type the save is in or — when its model is a map — the map BMS flies with it at the save's clock (`GetFmap`'s choice, D71). Two things WDP's rule for the older files would get wrong on version 8 are done right: version 8 **counts the type from 0** (0 Sunny … 3 Inclement, handed on as WDP's 1-4; WDP's rule reads 0 and 1 both as Sunny, which plans a Fair save on the Sunny table, 5 kt and 29 °C where BMS briefed 080° at 15 kt and 23 °C), and heading model 1 holds **no wind direction** (all 275 such files have 0, which WDP's rule would read as 360): VRB in the ATIS, and a calm take-off on the Performance page. The ATIS, the weather list (target, departure, arrival, alternate, fields around the departure), Force QNH and the Performance page's take-off weather follow; the card says which file its weather comes from; a file that cannot be read says why. Version 8's layout: `Twx.kt` | `DataCardWiring.reloadWx`, `CardWeather`, `Twx.kt`, `PcFileRoutes.weather` |
| D56 | DataCard | map weather: `GetCell` rounds the grid position to the nearest cell edge (up to half a cell north-east of the field, and one row past the map on its southern edge); `CreateCloud` says CAVOK under any cloud base above 5,000 ft (`Height > 5000 && ~HasTowerCumulus != 0` holds for 0 and 1 alike); `GetDP`'s steps leave out their own edges (exactly 1,000 m or 5,000 m of visibility gave a dew point of 0 °C) | code (`cntDataCard.cs:36173`, `:35522`, `:35341`) | the cell the field is in; CAVOK only with no cloud below 5,000 ft, no towering cumulus and 10 km or more; each step includes its lower edge | `CardWeather` |
| D57 | Weather page (port) | a version 8 map's visibility (BMS's saves and training maps) read from the shower array: 0 km in every cell | files (`Save0.fmap`: array 27 holds only 1, array 28 kilometres, array 29 the cloud base again); code (`ReadFMapDataNew`: HasShowerCumulus from version 7, FogEndBelowLayerMapData after it) | array 28 | `Fmap.visibilityKm` |
| D58 | DataCard | Load DataCard looked in the program folder's DataCards even when WDP's settings name another DataCards folder — where Backup DataCard writes; Save Airport Schedule wrote the picture to the name it suggested, whatever name the pilot chose (`bitmap.Save(text4, …)`) | code (`cntDataCard.cs:28549-28579`, `fclsAptSchedule.cs:266`) | both use WDP's DataCards folder; the picture goes where the pilot saved it | `DataCardWiring.kt`, `AptSchedule.kt` |
| D59 | DTC | Load (Lines) opened in a folder named "True" or "False" (`InitialDirectory` given a comparison); every backup reader (`CheckPPTHeader` …) rewrote the pilot's file when it lacked its `[section]` line; the "PPT and Line" (`.plf`) windows exist but no button opens them and neither saves nor reads the file (no `plf` case) | code (`cntDTC.cs:47725-47735`, `:54680-54730`, `:47660-47700`) | Files\Line; the header added when reading, the file left as it is; `.plf` not offered | `DtcFiles.backup`, `WdpFiles.withHeader` |
| D60 | Performance | a 4.38.1 save's departure field not found: WDP's own airport table (`Database\Korea\Airports.xml`) has Osan as CampId 362 where the save's objective is 1784, so the lookup answers −1, `GetAirportRow(-1)` falls back to row 0 and the page plans the flight from **Afyon** (3,310 ft, 14R 9,952 ft); that is all the missing field does: WDP's ATIS on that save is blank and its temperature Setup.ini's 25 °C, with no wind and 29.92, because WDP cannot read the save's version 8 `.twx` at all (D55) — it would be the same with Osan found. Picked by hand in Select APT, WDP's Osan is 97 ft, 9,006 ft: WDP's Korea database is not 4.38.1's KTO (its Osan row also has campaign ID 362 where BMS has 1784, TACAN 94X where BMS has 116X, and another position) | run (WDP on a Korea campaign save of an F-16CM-40 flight out of Osan: factor 1.851, lift-off 168, rotate 153, refusal 178); BMS's `TerrData\ATC\Osan.dat`: CampID 1784, `#INFO 42` (the field's altitude) | the save's own field, from BMS's data: Osan AB, 42 ft, 09L/27R 9,010 ft (WDP's own arithmetic on it, with WDP's stores-only loadout: factor 1.46, lift-off 170, rotate 155, refusal 186). With WDP's hand-picked Osan the only take-off figure that moves is the factor (empty jet, 20 °C: 1.436 at 97 ft, 1.43 at 42 ft; `g1`/`g2` of the end-to-end check) | `PerformanceWiring.fieldFor` |
| D61 | Coordination Card | the transition level (TRANSIT ALT column, TRANSIT LVL, the holding altitude's FL/feet switch) is the TL of the airport the main form has selected in WDP's database (`FillCommCard`: `AirportTable[GetAirportRow(intSelApt)].TL`, else 180); on the last Korea save it printed `11500'` / `FL115`, a TL only the Aegean, EMF and Ikaros databases carry (Afyon), because its Korea lookup had fallen to another theater's row (the Performance page showed Afyon), where its own Korea database says 140 for every field | run (the user's WDP on the last save, read-only window dump: `11500'` ×5, `FL115`); code (`cntDataCard.cs` l.43331-43333); WDP's `Database/Korea/Airports.xml` (TL 140 ×91); BMS's KTO AIP 2.1.1 "In KTO the transition altitude is 14000 feet and the transition level is FL 140" and the Comms & Nav book 3.1.9 | every theater on KTO's terrain: FL140, `14000'`; the others WDP's fallback, FL180 (no BMS source gives theirs) | `DataCardWiring.KTO_TRANSITION_LEVEL` |
| D62 | Coordination Card | the bullseye row prints the save header's cell (`BullseyeX` east, `BullseyeY` north) at its **corner** (`FeetToCoordsBoth(BullseyeY·KM, BullseyeX·KM)`), half a cell (0.38 nm) south-west of the bullseye the sim flies with: on the last Korea save `39,43.101 / 125,08.542` | code (`cntDataCard.cs` l.44074-44076); run (the user's WDP on the last save, read-only window dump); BMS 4.38.1's own ACMI records the bullseye object at the cell's **middle**: Korea's header cell 310/650 is `U 310418.62, V 650329.50` m = (310.5, 650.5) × 3279.98 ft ÷ 3.28084, and Israel's and Hellas's recordings sit on a cell's middle to the centimetre as well; BMS's lat/lon for it there is 39.722960 N, 125.148048 E (`39,43.378 / 125,08.883`) | the middle of the cell, as every campaign position is, for the card, the tanker LOC and the Mission map before 3D (`MissionDtcFile.bullseye`); printed through the pages' projection (D26) | `MissionDtcFile.bullseye`, `DataCardWiring.bullseyeBox` |
| D63 | DataCard | the support rows' LOC: a tanker's is measured from the bullseye (north, east) to the middle of its station leg taken as (east, north) — `Tankers` puts `CenterX`, the grid's east, where the bullseye's north is — and an AWACS's or JSTARS's to a corner of a box 30,000 ft off its first station point in a direction taken from `Math.Sin`/`Math.Cos` of an angle in **degrees** (`Awacs`, `JSTAR`: `P.Track` answers degrees, which `Tankers` turns into radians with `P.DTR` and these do not). On the last Korea save WDP prints Copper4 `108 / 23`, Texaco4 `183 / 102`, Dragnet4 `123 / 240`, Sentry6 `147 / 188`: both tankers inside 102 nm of the bullseye, over North Korea | code (`fclsMain.cs` l.22393-22520 `Tankers`, then `Awacs`, `JSTAR`); run (the user's WDP on the last save, read-only window capture; all four figures reproduced from the save with WDP's formulas); the flight's own Refuel steerpoint (STPT 7, 37°02.2′ N 128°54.7′ E) lies on Copper4's station leg as the port places it, about 1 km from the leg, 240 nm from the bullseye | the bearing and range from the bullseye (the header's, D62) to the middle of the station leg, for all four: Copper4 `133 / 240`, Texaco4 `157 / 206`, Dragnet4 `126 / 232`, Sentry6 `152 / 184` | `DataCardWiring.sideTables`, `CampaignFiles.sideSupport` |
| D64 | Pop-up | profile Type 2 laid its VRP and pull-up point out from the ingress **altitude** (`VRP_Type2`: `intIngressAlt / tan(climb)`, and `intIngressAlt` carries the ground under the target), where every other height of the profile is measured from the target: only Type 2's run-in moved with the target's elevation — on the last Korea save's steerpoint 7 (3,396 ft) the VRP came 3,860 ft and the pull-up point 2,968 ft nearer the target than the same attack on flat ground, and Type 1 does not move at all | code, run (the harness, the same attack with and without the terrain: VRP 46,359 / 50,219 ft, PUP 19,903 / 22,871 ft) | the ingress height above the target; the ground moves only the ELEVs | `PopupPlan.kt`; `popup.txt` |
| D65 | attack pages | the Coordinates box's target "Elv" printed the steerpoint's altitude (`decOrig_TGT.Z`: 21,000 ft over a CAP point, the flight's altitude over a strike steerpoint), which nothing on the page uses, and never showed the ground the ELEVs stand on | code (`Get_Coords`: `lblTGT_elv` = Z, `TargetElv` = the height map) | the ground under the target the page plans on (BMS's terrain, the DTC's target point, or what the pilot typed), with a pencil: a tap types it. The IP's Elv stays WDP's | `PopupWiring.kt`, `HadbWiring.kt`, `TossWiring.kt` |
| D66 | Performance | the conformal-tank F-16s (Type "F-16C-52+CFT", "F-16I-52+ CFT", …) planned from WDP's own table: 21,200 lb empty, 10,219 lb of fuel, no drag of their own. The port had BMS's jet without its tanks (20,300 / 7,162) | code (`clsAircraftData.cs` l.231-237, 342-372); BMS's flight model (`F-16C-52+ CFT HAF.txtpb`: `has_cft: true`, `cft_empty_weight: 1700`, `cft_fuel: 3060`, `cft_drag: 20`, the same on every F-16 that has them); BMS's own saves: an F-16C B52+ HAF flight's spare slots start with 10,222 lb (7,162 + 3,060), an F-16I-52+'s with 8,980 (5,920 + 3,060), where the flying aircraft without tanks have 7,162 / 5,920 | BMS's: the jet's own weights plus the tanks' 1,700 lb and 3,060 lb, and their drag of 20 (F-16C-52+CFT: 22,000 / 10,222, drag 21 clean); a save's flight carries them when its fuel is above the jet's own tanks (`cftFromFuel`) | `PerformancePlan.cft`, `racks.json` (`wdpracks.mjs`) |
| D67 | ATO Target List | the Lat and Long columns print `00,00.000` / `000,00.000` for every target: the window converts with a `new clsCoordinates()` it never gives the theater (no `EnableNewTerrain`, no Transverse Mercator meta, `CampW` 0), so `FeetToCoords` refuses every position as east of a zero-wide theater. Beside it: numbers and times sort as text (Package "10" before "9", TOT "10, …" before "2, …"); the start file's first objective is named `GetTgtName(…) (<id>)`, blank before the id (`num == 0`); a target neither table holds is listed with the previous row's Lat/Long; the side's own team is dropped where its controller is neutral toward it (LHTO) | code (`fclsAtoTargetList.cs` `FillUnits`/`FillObjectives`: `new clsCoordinates()`, `GetTargetName`; `clsCoordinates.cs` l.212-223 `FeetToCoords`: `FeetE > m_CampW` → `"00,00.000/000,00.000"`; every other converter is set up first, `clsLoadDTC.cs` l.343-344); run (the user's screenshot of WDP on the last Korea save: every row `00,00.000`) | each target's latitude and longitude through the Planner's projection (D26), from its position in the save (a unit at its cell's middle, an objective at its own); sorts by number and time; every objective by its own name; an unknown target named "Unknown target (<id>)" with no position; the side's own team always counted, as the DataCard's support tables count it (D63's `sideSupport`) | `CampaignAtoTargets.kt`, `AtoTargetListWindow.kt` |
| D68 | DataCard | the card's weather is the save's `<save>.twx` alone (for a 4.38.1 save, none at all: WDP cannot read version 8, D55), read once at File > Open (`OpenFile` → `ReadWeather` → `CreateAtis`) from the folder beside the save: WDP never reads BMS's printed briefing, so weather BMS briefed after a later change is not seen, nor is weather saved after the file was opened until the file is opened again; and it takes `<save>.twx` whatever save wrote it — `Auto Save.cam` and `Auto Save.tac` share one `Auto Save.twx`, so the older of the two gets the other's weather | code (`fclsMain.OpenFile`, `ReadWeather`; nothing in WDP reads `briefing.txt`); files (a campaign's `.twx` is written in the same millisecond as its `.cam` and its last-check field is BMS's last weather check, up to a few minutes before the save's clock — 20 s to 2 min on the test saves; on the development install `Auto Save.twx` was the campaign's while `Auto Save.tac` was nine months older) | the card's weather is the first of: a file Reload WX picked (until another mission); the save's own `.twx` (and the map it flies with) — read again at every Open mission and Pick a flight, from the save's own theater's campaign folder (`@campaign:<theater>`), a file saved again (another time or clock) replacing the one on the card — when no printed briefing of the same flight gives weather, or when the `.twx` was saved after the briefing was printed **and** says other weather (type, wind speed or temperature at the departure: the same weather keeps BMS's forecast, which has the wind direction BMS picked and three columns); else the printed briefing, with the `.twx`'s QNH where the briefing prints none (4.38.1's never does). A `.twx` another save wrote (another save of that name written at the same moment, or for a campaign neither the time nor the clock agreeing: `PcFileRoutes.otherSave`) is not used, and the card says why, as it does for one that is not there; the weather list opens with where its weather comes from and as of when ("from Auto Save.twx, as saved at D1 01:02", "BMS may change the weather before take-off"), and the Planner's header says when the save's file was not used or was taken over a printed briefing. A PRINT made while a save's flight is planned is joined at once | `DataCardWiring.weather`, `saveWeather`, `pickedAgain`, `briefingPrinted`; `PcFileRoutes.weather` (`save=`, `modified`, `otherSave`), `Twx.kt` (`clock`) |
| D69 | DataCard | the ATIS trend (`GetTrend`) is worded from the `.twx`'s deterministic schedule: a scheduled type change within two hours of the take-off becomes `BECMG`, anything else `NOSIG` | code (`cntDataCard.GetTrend`); files (every 4.38.1 campaign save on the development install carries BMS's default schedule, all Sunny at one-day steps, while those campaigns changed type within an hour of the save: the stored schedule is not what BMS flies) | the trend is the arrival field's own weather in the same file (or the briefing's landing column): `BECMG` with its sky when its type differs from the departure's, else `NOSIG` | `CardWeather.atis` |
| D70 | DataCard | a weather map is sampled for the ATIS at the take-off waypoint's cell (`CreateAtisFmap`: `waypoints[0].GridX/Y`; `ShiftGridLoc` works out the map's drift and discards it), which may be another cell than the field's | code (`cntDataCard.cs` l.33875-33881, `ShiftGridLoc`) | the departure field's own position, as every other field of the weather list is sampled; the first placed steerpoint only when the field is not known. Like WDP, the map is taken at the save's clock with no drift | `DataCardWiring.fileDeparture`, `CardWeather.column` |
| D71 | DataCard | an update map's name (`WeatherMapsUpdates\dhhmm.fmap`) is read with `Math.Round` (`AutoMapToTime`): minutes 51-59 — and 50 at an odd hour, by banker's rounding — read as the next hour's :00, so `10155.fmap` (D1 01:55) is D1 02:00. The Weather tab's series write such names (55-minute steps) | code (`fclsMain.cs` l.12769-12794) | the name is read as written (`CampaignTime.ofFmapName`): the newest map named for a time at or before the save's clock | `PcFileRoutes.updateMap` |
| D72 | DataCard | the ATIS runway into the wind (`PreferedRwy`, which also sets the Performance page's runway) scores the ends wrongly for a wind from the north-west quadrant: an end heading above 270° scores 360 − heading whatever the wind (a heading of 360 scores 0 under any wind from 270-360), the second end's score is written into the first's, and the fourth end is scored twice | code (`cntDataCard.cs` l.42599-42660) | the end whose heading is nearest the wind, from a weather file's take-off wind too (the save's `.twx` or its map, Reload WX's file), unless the pilot picked one on the card; a variable wind (a `.twx` whose direction BMS picks) keeps the runway there is; the Performance page takes it | `CardWeather.intoWind`, `DataCardWiring.weatherFromFile` |
| D73 | Map page | a theater missing from WDP's own database gets a black `Empty.png` ("No Map found for this theather") and loses the grid with it, its extent coming from WDP's `Theater.xml`: Hellas, Hellas WCP, LHTO, Falklands, EF2000 BTO and OFMKTO, 6 of 4.38.1's 19 | code (`fclsMain.cs` LoadMap l.23269-23402, DrawMapGrid l.26298); WDP's `Database` holds 14 theaters | the app's own maps of every theater in `index.json`, in four styles ("Chart" is WDP's white map); the grid from the theater's own coordinate data (`WdpCoords`) | `WdpMapPage.kt`, `PlannerIntel.graticule` |
| D74 | Map page | the route on 4.38.1: the Callsign.ini view draws none (the cartridge keeps its route slots empty); picking a flight centres the map between the theater's south-west corner and the precision targets (`FltplanCenter` keeps STPT 1's 0,0 as its minimum); the Mission.ini view draws `<save>.ini`, which can be another flight's (`Auto Save.ini`) | code (`FltplanCenter` l.29417-29457, `DrawMapStpt` l.27462; `cntDataCard.cs` SetMapLocation l.40816) | one view, slot by slot, as the jet flies it (the cartridge's point, else BMS's route where it is provably this flight's, else the save's: `WdpMission.slots`), the first view framed on that route; **Viewing** shows the cartridge's half or the mission file's on its own | `PlannerMap.shown`, `WdpMapPage.kt` |
| D75 | Map page | airport labels, frequencies, TACANs, the hover box and the chart click are looked up in WDP's `Airports.xml` by a campaign ID 4.38.1 renumbered (as D60): a field shows only its objective's name, or another field's data | code (`DrawMapAirports` l.26872, `MouseContact` l.30135-30890, `ShowChart` l.30981) | the app's airports (BMS's `Stations+Ils.dat` and campaign IDs): name, ICAO, elevation, TACAN and range, every frequency, runways and ILS; the chart is the app's ground chart and instrument charts; the symbol in the colour of the side holding the field now, from the save's objective changes | `WdpMapLayers.kt`, `DtcChartWindow`, `CampMapIntel.fields` |
| D76 | Map page | air-defence types come from WDP's `VehToPpt.ini`, whose names predate 4.38.1: SA-11 (9K37M1), SA-17 (9K37M2), SA-20A (S-300 PMU1), KSAM Chun-ma, Avenger and the Stinger/Mistral squads are never threats; there is no SA-20 box and SA-12 has no vehicle; `Recon()` leaves SA-10, -11 and -12 out of its "any type ticked" test (and tests SA-4 twice), so with only those ticked nothing is drawn; Crotale, Roland and Chaparral have no `Ppt.ini` entry and ring at 0 | code (`Recon` l.29597, `SAMsNew` l.21541-22232, `GetSAMRng` l.19411); `VehToPpt.ini` against 4.38.1's `Falcon4_VCD.xml` and `Ppt.ini` | the type is the system the save's unit fields, as the class tables name it; the ring the theater's `Ppt.ini` range, else the app's threat reference; the Types list is the systems the save holds, per side, and covers every air-defence ring | `CampMapUnit`, `PlannerIntel.threats`, `WdpMapOptions.kt` (`TypesBlock`) |
| D77 | Map page | a unit counts as spotted when any team's bit is set (`spotted != 0`), and only the last JSTARS looked at gives its 200 nm (`DistToJstar`) | code (`SamIntel` l.20343-20562, `DistToJstar` l.22369); on the Korea test save the flight's own bit marks 0 of 178 sites, the controlling team's 90 | the bit of the team that controls the flight's team, or the flight's own, as `CampaignBriefing.sites`; WDP's recon-loss time by move type is kept (`recent`), and every JSTARS on station counts | `MapIntel.kt` |
| D78 | Map page | radar alive: the radar vehicle's roster slot is read by its low bit only (`bitArray[RadarVehicle*2]`), so a slot holding two radars reads as destroyed; a battalion whose class has no radar vehicle (AAA, MANPADS) is never a threat, yet appears under No Radar | code (`SamRadarAlive` l.29609); `--mapinteltest`: 47 radar slots hold 2 or 3 on the Korea save (141 Balkans, 215 EF2000, 64 KTO 80s) | the slot's two bits read as a count; a unit with no radar vehicle is a threat of its own range, and No radar shows only a radar that is gone | `MapIntel.kt` |
| D79 | Map page | package routes, the JSTARS station and Auto PPT's distances use the waypoint cell's corner, without the half cell: 0.27 nm south-west, as the tracks of D43; the bullseye rings sit on the header cell's corner, as D62 | code (`DrawMapStptPackage` l.27281, `JSTAR` l.22585, `AutoFillPPT` l.30014, `DrawMapBullseye` l.26461) | the cell's middle, as for every waypoint | `CampaignFiles.kt`, `MapIntel.kt`, `WdpMapLayers.kt` |
| D80 | Map page | Extra Lines' 100 nm ring is an ellipse 200 nm tall: its height argument is `num * 20` | code (`DrawMapBullseye` l.26564) | a circle | `WdpMapLayers.kt` (`drawBullseyeRings`) |
| D81 | Map page | Clear PPT and Clear Lines empty all fifteen PPTs or all four lines without a word, and Auto PPT empties the PPTs before refilling them — with more than fifteen threats and none near the route, it leaves them all empty | code (`btnClearPPT_Click` l.17131, `btnClearLines_Click` l.17092, `AutoFillPPT` l.30014) | each asks first when a PPT or line holds a point, naming them (as D50); Auto PPT with no ring near the route says so and leaves the PPTs as they are | `DtcWiring.clearPpts`/`clearLines`/`replacePpts`, `MapActions.autoPpt` |
| D82 | Map page | magnetic variation is read from `MagVarMap_<WDP's database name>.csv` under WDP's idea of the theater's folder, so a theater outside its database has none; and the grid is stored one row off (`num3` starts at size/16 + 1, row 0 never filled), so a point reads the value 16 km north of it and the north edge reads 0.00 | code (`ReadMagVar` l.16684, `GetMagVar` l.16734); `--mapinteltest` (Korea's corners -6.07 / -7.18 / -8.60 / -10.47) | the file under the theater definition's `terraindir` (every 4.38.1 theater reaches one), each row placed by its own y value, interpolated bilinearly | `MagVarMap.kt`, `/api/campaign/magvar`, `CampMagVar.at` |
| D83 | Map page | search radars are told by WDP's list of feature class numbers (53, 54, 72-77, 204), which 4.38.1 renumbered: 74 is now "Wall High 2", and the EW Dome (198) and P-37 (595) are missing | `Falcon4_FCD.xml` of 4.38.1 against `ObjHasWorkingRadar`; `--mapinteltest` prints both lists | a radar is a feature whose `Falcon4_FCD.xml` name begins "Radar…" | `MapIntel.kt` (`radarFeatures`; `radarRule` for the check) |
| D84 | DTC | picking another flight reads the Callsign.ini again over the DTC page without a word, dropping any edit not saved; the Planner had gone the other way and kept them, so changes made for one flight went into the next flight's cartridge at Save to DTC | code (`SelectNewFlight` → `LoadCallsign`, `cntDataCard` l.40459) | with changes not saved, another flight asks **Read the cartridge** (WDP's re-read) or **Keep the changes**; with none, nothing to ask (docs/DATA-STORES.md) | `DtcWiring.kt` (`askAnotherFlight`), check in `--wdppagetest dtc <a copy of a cartridge>` |
| D85 | DTC, Map page | a tanker track goes into a line as its station box: the corners 30,000 ft either side of the station leg (the first tanker waypoint and the next) in its own order and the first again, five points and a zero, so the HSD draws it closed (the map's *Add to line n*, Change Area's list). An AWACS's or JSTARS's track is never offered as a line (its box is worked out, in degrees taken as radians, D63, and only drawn). The Planner laid the leg's two points, one line down the middle of the box, and the Mission map drew a box of its own, twelve miles wide | code (`fclsMain.cs` `lsbTanker_Click` l.17529-17577 and `SetTankerLine`, `Tankers` l.22393-22460, `DrawMapTankerTracks`; `fclsChangeLine.FillTanker`); `--dtcfromtest` works the corners out with WDP's own formula and compares them point by point | every tanker, AWACS and JSTARS track with a station leg is laid as WDP lays a tanker's: the same five corners in the same order (AWACS and JSTARS in radians), from From mission… on the Lines tab, Change Area and the Map page's *Add as line*; the Mission map (both modes) and the Planner's Map page draw that same box (`PlannerIntel.orbitBox`), so the HSD shows what the maps show. The leg is WDP's (the save's `leg`), which on the test saves is also the leg the longest hold marks | `DtcFromMission.lineOptions`/`stationLeg`, `PlannerIntel.orbitBox`/`orbitLine`, `DtcMissionFacts.kt`, `MissionMap.kt`, `WdpMapPage.kt`; checks in `--dtcfromtest`, `--wdpmaptest` |
| D86 | DataCard | a press on another flight's callsign in the package rows (`rbnCallsignN_Click` → `SelectCallsign`, after **Enable Change**, `btnChangeToFlight`) switches the card to that flight — its airports, flight plan, package slot and times — while the briefing, the route, the cartridge, the attack pages and the Performance page stay our flight's, so the card mixes two flights; the Different Flight window over a printed briefing (`fclsSelection` → `SelectNewFlight`) did the same in the port | code (`cntDataCard.cs` l.28518, l.29947; `SelectCallsign`); the user's report: the card's data changed to the package flight while the briefing stayed the original's | the card stays on our own flight: the other flights are greyed, for reference, with the tip *"Another flight of your package, shown for reference. To fly and plan it: pick it and your seat in BMS's ATO, save the campaign or TE, then reopen it with Open mission…"* (a long press by finger, and the Guide's DataCard card); Enable Change is removed; our own callsign opens the Performance page's Loadout window, as WDP's opens `fclsLoadout`, and its OK writes the card's Config rows (`DatacardLabels`). Different Flight is Pick a flight on the save the card is planned from, which changes briefing, route, cartridge and card together (`PlannerMissionState.plan`); over a printed briefing whose save is not known, its window shows the package and another flight says how to plan it (Open mission…) | `DataCardWiring.kt` (`values`, `rbnCallsign`, `differentFlight`, `loadoutRows`, `OTHER_FLIGHT_TIP`), `WdpHandOff.join`, `PerformanceWiring.openLoadout`/`onLoadoutApplied`; `--wdpclicktest`, `--wdppagetest datacard` (the plan is WDP's; the lock is the wiring's) |
| D87 | Pop-up, HADB, TOSS | the IP is always the steerpoint before the target (`STPTChange`: `strIPpoint = strWaypoint − 1`; `Get_Coords` reads index N − 2; `SaveNavOffsets` writes the VIP lines on N − 1), so a route whose IP is two points back, or a target with a hold or a split before it, cannot be planned from its real IP. And the Selection box's **Campaign** and **TE** buttons (`btnCamp_Click`/`btnTE_Click` → `blnCampTE`, `CampTE`) only choose which cartridge table `Get_Coords` reads target and IP from when the DataCard says Precision: Campaign = `tblCampSTPT`, the pilot's `Callsign.ini` (`clsLoadDTC.GetCallsignSTPT`), TE = `tblMissionSTPT`, a TE's own mission `.ini` (`GetMissionSTPT`); `strDTC` is "Camp", "TE" or "Both" by which files the DTC page has loaded (`cntDTC` l.47078, l.47167; `CreateFlightplan` sets "Both"), and without Precision both read the save's waypoint cells. Nothing else on the page depends on them | code (`cntPopUp.cs` `Get_Coords` l.7112-7200, `CampTE` l.7460, `btnCamp_Click` l.5129; the same in `cntHADB.cs`/`cntTOSS.cs`; `clsLoadDTC.cs` l.319, l.3661); the user's request | **IP STPT** beside TGT STPT: the steerpoint before the target, following it, until the pilot picks another (any of 1-24 but the target; the arrows step to the next one with a position; one with no position is no IP, so VIP is blocked as for a target on STPT 1); the VIP lines, their steerpoint in the DED and in Save to DTC, the Coordinates box, the map, the card's Delivery block and the kneeboard's attack page all take it; a new mission or flight follows the target again. With the IP left to follow, every figure is WDP's (the page tests are unchanged). The Campaign and TE buttons are removed: the Planner has one steerpoint table, slot by slot (`WdpMission.slots`: the cartridge, else BMS's mission file beside the save — for a TE its own `.ini` — else the save's cell), and Save to DTC writes the cartridge and a TE's `.ini` alike (D46), so there is nothing to choose; where each steerpoint came from is the two boxes' tip. The page's **Save to DTC** also fills the DataCard: once it has written the cartridge, the card's Delivery block becomes this page's attack, profile and every field, as the card's own PopUp / HADB / TOSS button fills it, with nothing left waiting for the cartridge (`DataCardWiring.attackSaved`, run through `DtcWiring.afterSave`, so after the zeroing question too); a save that fails leaves the card as it was and says so — so the card and the cartridge always agree (the 1.3.8 test builds' separate Send to DataCard is gone; Pop-up and TOSS give its row to IP STPT at the VRP). The controls are the Planner's additions to WDP's layout (`tools/extractor/src/wdpadded.mjs`: TGT STPT 50 px left in its row). Since D94 IP STPT shows only where an IP means something (VIP mode, or VIP blocked), never on HADB; with the Campaign table gone HADB rings the threats always (WDP: only with Campaign chosen) | `PopupPlan`/`HadbPlan`/`TossPlan` (`ipStpt`), `AttackSelection.kt`, the three wirings, `DataCardWiring.useAttack`, `WdpPage.hidden`; `--wdppagetest <toss\|hadb\|popup> geometry` (D87 cases: another IP, every VIP line lands from it and names it, back to WDP's rule, an IP with no position), `--wdpoutcome tgtstpt` (IP STPT on TOSS, Save to DTC's card fill against the card's own TOSS button), `--attackmaptest` (the save fills the card, a failed save does not) |
| D88 | Pop-up, HADB, TOSS; DataCard | Save to DTC wrote the selected reference's four lines and kept the other's (D3 kept what the cartridge held; WDP itself wrote all eight, HADB's VIP as zeros, falcas `cntHADB.cs` l.5814-5837), so a cartridge could carry a VIP and a VRP for one attack. And the card's **Save DTC** handed on only the profile's name: the DTC page copied `m.popUpNav` …, which only an attack page's own Save to DTC filled, so the cartridge got zeros or an older attack, not what the card showed | manual (Dash-34 p.426 l.14832-14836: "OA and PUP geometry will change if one mode is selected but the offsets were intended for the other"); code (`DtcWiring.saveNavOffsets`, `cardEntries`); the attack study | the other reference's lines are **cleared** (its VIP/VRP and pull-up line on steerpoint 0, its OA pair taken off); one path stages an attack on the DTC page (`DtcWiring.stageNavOffsets`) for the page's Save to DTC, Send to DataCard (staged, not saved) and the card's Save DTC (`CardEntries.attackOffsets`/`attackModesel`, the card's own copy of the page's offsets), so the two saves write byte-identical `[NAV OFFSETS]` | `DtcWiring.kt`, `WdpCartridge.kt`, `DataCardWiring.kt` (`cardOffsets`); `--attackmaptest` |
| D89 | Pop-up | `DTC()` handed the card the HUD check's caption ("Target visible in the HUD", whatever the check said) as `strTGTHUD` | code (`cntPopUp.cs` l.7457) | the check's answer (YES/NO), as HADB and TOSS hand on | `PopupPlan.dtc()`; `popup.txt` |
| D90 | DTC page | `[NAV OFFSETS]`' offset aimpoints were read and deleted on steerpoints 0-23 only, so a pair on 24 or 25 was never read back nor deleted; the two pairs were assigned by order, so an IP after the target swapped the VIP's pair and the VRP's | code (falcas `clsLoadDTC.cs` l.2276-2290, `clsSaveDTC.cs` l.665-678) | read and delete 0-25; `OA*_1` is the pair on the VIP line's steerpoint and `OA*_2` the pair on the VRP line's (order only where neither names one) | `DtcCartridge.navOffsets`, `DtcSave.navOffsets` |
| D91 | DataCard (TOSS) | the Delivery block's Turn line stayed empty for TOSS (`cntTOSS.strTurn` is never assigned) | code (falcas `cntDataCard.cs` l.39832) | the turn direction, as for HADB (the port filled it already; recorded here) | `DataCardWiring.takeAttack` |
| D93 | Pop-up, HADB, TOSS | the attack, pull and approach headings are true figures, flown on a magnetic HUD and HSD, and nothing says so | manual (Dash-34 p.103, the HUD's "MAGNETIC HEADING"; the DED's TBRG is true, p.423) | the fields stay true (as WDP prints them and the DED takes bearings); each heading figure's tip adds "True; magnetic M xxx at the target" where BMS's variation map is known (`/api/campaign/magvar`) | `AttackSelection.magneticTip`, the three wirings |
| D94 | Pop-up, HADB, TOSS | IP STPT (D87) showed in VRP mode and on HADB, where no IP is used (the VRP is laid from the target, Dash-34 p.425); and a VRP attack could not be turned into a VIP one without placing a steerpoint by hand | manual (Dash-34 p.424-425); the attack study | **IP STPT** (and the Coordinates box's IP lines) only in VIP mode — and while VIP is blocked, so an IP can be picked — never on HADB; **IP STPT at the VRP** (Pop-up and TOSS, VRP mode; in the Coordinates box's top row): a steerpoint placed on the VRP (the first free slot after the route or the IP STPT's slot), at the VRP line's ELEV, action −1, and the page planned as VIP from it. **One question** first (the user's request): where the VRP is, to finish the Input Panel and check the attack on the map before creating it (the steerpoint stays where it is placed; a later change moves the attack, not the steerpoint), what each slot holds (a cartridge point by name is replaced, a route point is moved and the route runs through the VRP) and how to delete it (DTC page, STPT tab, Change on its row, Clear, Apply); buttons **Create IP STPT** (or one **Create IP STPT n** per slot) and Cancel — no checkbox, every time, as it is a deliberate act. No box after it: the page shows "IP STPT n created at the VRP — delete it on DTC → STPT if no longer needed." over the map and under the Selections and DED Data panels, and once the Input Panel puts the VRP more than 100 ft from it "IP STPT n is no longer at the VRP for these inputs — press IP STPT at the VRP again, or delete it on DTC → STPT." — only for the steerpoint this button created this session, while it is the IP STPT and holds the point it was put on (`AttackSelection.ipNotice`); the button stays in VIP mode for it, and pressing again moves that same steerpoint. A slot placed or edited on the DTC page in this session wins over the route for the attack pages, whatever the Precision answer (`WdpMission.placed`) | `AttackSelection.kt` (`selectors`, `ipAtVrp`), `PopupWiring`/`TossWiring`, `DtcWiring.placedThisSession`, `WdpMission.placed`, `wdpadded.mjs`; `--attackmaptest` |

**Looked at and not changed**, because WDP was right: the Pop-up's aim-off OA2 does lie beyond the target (D6: the
dive line meets the ground past the release point); an ELEV of 0 means ground level, not sea level (the Dash-34);
HADB's and TOSS's approach headings agree once their different default sides are taken into account (D8); the
Performance page's standard day of 2 °C per 1,000 ft, which is BMS's (D32, withdrawn); pitch 13 and Full AB when
Setup.ini says nothing (both programs open on their own saved pitch, 13 when there is none). BMS's own example agrees
with both: for Training Manual 4.38.1 mission 1 (F-16DM-52 at Gunsan, AIM-9M + ASQ-T50, drag 9, 9 °C) the manual's
WDP figures — rotation 126 kt, MIL climb 445 kt / M0.84 to 10,200 ft in 40 s and 5.0 nm — are what WDP and the page
give at pitch 13 (`e2` of the end-to-end check). The DataCard's `.bdc` load (the attack study's B22): WDP's
`LoadDataCard` never restores the attack profile and sets the Pop-up Type from the VIP/VRP flag; the port's
**Load DataCard** reads only the card's own boxes and touches no attack page, so neither slip carries over and there
is no D-row (D92 is unused).

**Kept on purpose**, because they are the model's resolution rather than mistakes: the float constants and x87
rounding where they only move a last digit; banker's rounding where WDP rounds that way; the fitted −20.915 ft/s² for
a retarded bomb and the per-category drag coefficients (below); "Mach .85" notation. WDP's texts are kept as written,
spelling included. On the attack pages, verified against the program and relied on by the page tests: Pop-up's
`atan(angle in radians)·R` terms (`PopupPlan`, falcas `cntPopUp.cs` l.6048-6050); TOSS's `PullUp()` not run from
`ProgramFlow` and `dblReleaseAngleRad` never assigned, so the horizontal tracking term is 0 (`TossPlan.kt`); Pop-up's
VIP-mode OA2 ELEV 0 against the VRP mode's ingress altitude (falcas l.6805/6854 against l.6296).

**Removed from the pages** (`WdpPage.hidden`; `--wdpclicktest` fails if one shows): the card's Print, Print Preview,
Start Timer, the Mission.ini lamp and its own Upd Kneeboard button (the toolbar's **Upd Kneeboard** does their job, in
one place); the card's Enable Change (D86); the DTC page's Tactical
Engagement buttons (Open and Save TE.ini File among them), copy buttons and lamps, Save List as jpg and Save PPT.ini;
the attack pages' Campaign and TE buttons (D87: on Pop-up and TOSS their row holds **IP STPT at the VRP**, and where
a steerpoint came from is the TGT STPT and IP STPT boxes' tip); Reload Tiles (the app's map has no tiles to reload).
**On the pages and in the toolbar**: WDP's own cartridge buttons are on their pages, where pilots look for them, as
well as in the Planner's toolbar (Save to DTC, Re-read DTC from BMS in its menu) — the DTC page's Save DTC on the five
tabs that are not From mission… (`DtcWiring.SAVE_DTC`: IFF, EWS, MFD, NAV OFFSETS, WEAPONS), MAIN's Open and Save
Callsign.ini File, the card's Get DTC File, Save DTC and "Callsign.ini saved" lamp (`DataCardWiring.DTC_CONTROLS`),
and Different Flight. **Get DTC File** is MAIN's Open
Callsign.ini File (WDP's window in the game's `User\Config` on the pilot's own file; `WdpCartridge.open`), after which
the card takes the cartridge's boxes afresh. WDP's own Get DTC File opened a TE's `Mission.ini` ("Open TE.ini File") as
its second cartridge, which the Planner does not keep on the page: it reads the mission's route itself (BMS's route
beside the save), so the file a pilot gets here is the cartridge BMS loads into the jet. **Save DTC** hands the card's
entries to the DTC page and saves, as the toolbar does. The **lamp** is WDP's `CheckCallsignSaved`: off with no
cartridge, green while nothing waits for the cartridge, red while the card's entries or the DTC page's edits do (the
toolbar's count). A TE's
targets, lines and PPTs live in `<TE name>.ini` beside the `.tac` in 4.38.1 (User Manual §5.1; TM §11.3): when the
pilot's own TE is open with **Open mission…**, **Save to DTC** writes them there as well as into the cartridge (D46).

## Not tested — needs Falcon BMS running

- which section BMS reads `TACAN Channel/Band/Domain`, `ILS Frequency` and `ILS CRS` from (`[COMMS]`, as WDP writes
  them), and whether `ILS Frequency` or the `[Radio] ILS_1` preset wins — the Planner follows the 4.38.1 User Manual
  and WDP, and shows `ILS_1` when there is no `ILS Frequency`;
- whether BMS loads `[NAV OFFSETS]` as the Planner writes it, and a typed latitude/longitude lands on the target
  (compare the DED STPT page with the Planner's print; the Planner's print is BMS's ACMI's and AIPs' figure to a
  metre, D26, but the DED itself has not been read);
- the Falklands' latitude and longitude: no recording of that theater has been checked (`--acmicoords` on one
  decides whether it needs BMS's grid, scaled to 2,048 km, or keeps the projection string);
- the rack a store hangs on (the first pylon and rack that accept it, as WDP chose), against the Loadout BMS shows;
- which Link 16 plan the jet loads when the briefing and the cartridge differ; the card's Current Time and support
  LOC with a live bullseye;
- the training-mission briefings of forum #2343, printed and loaded;
- which bullseye value in the save's header is north (the Planner reads it as WDP does, X east and Y north, and marks
  a bullseye from the save as such);
- whether BMS's FLY auto-save undoes a Planner save the pilot did not LOAD in BMS's DTC window first (the Guide's step 6
  says to LOAD);
- the weather BMS flies against the card's: the card plans the save's `.twx` (or its map) as saved, at the save's
  clock, with no drift and no scheduled change, and 4.38.1 campaigns changed weather type by themselves within an hour
  of a save; how BMS picks a campaign's type, which update map it takes when loaded past several, and whether SAVE
  WTH alone writes a campaign's `.twx` are open (`docs/WEATHER.md`, "What is still open");
- the kneeboard pages as BMS shows them after Upd Kneeboard in every theater: BMS re-reads them on entering the
  cockpit, and a page written while in 3D shows the next time (checked on KTO's layout only);
- **Q1, what the VIP-TO-TGT ELEV moves.** WDP writes the ingress altitude there (the TM's example `VIP=6,209.4,60211,400`);
  the manuals treat that ELEV as the defined point's elevation, 0 = ground (TrM p.352, p.359; Dash-34 p.423). If BMS
  raises the TD box by it, WDP's VIP ELEV puts the target in the air. Test: VIP mode, ELEV 0 against 2,000, watching
  whether the TD box moves; if it does, a D-row makes the VIP-TO-TGT ELEV 0 or the target's ground;
- **Q2**, whether the HSD draws a point placed in a free slot outside the route (IP STPT at the VRP's free slot),
  and whether its action −1 draws it as a target triangle rather than the IP's square;
- **Q3**, whether BMS reads `Modesel` on a DTC load and comes up in that mode (VIP or VRP).

## The TOSS page, as a state machine

The page is ten sliders and two switches, and every change runs the same chain the original runs. The port keeps
WDP's method names and, more importantly, its **order**: `PullUp()` is run from the height and G sliders and not
from `ProgramFlow()`, so its result persists between changes, and the release-height slider's range is re-derived
from it only when those two move. A recompute-everything design gives different numbers on the second slider you
touch. The first attempt at the comparison failed on every row for two reasons that had nothing to do with the
arithmetic, and both are worth knowing: the harness had left two inputs to whatever the machine's `Setup.ini` held,
and it had not told the page it was running under BMS (`blnVersion`), so WDP filled the Allied Force labels — which
differ. Every input is set explicitly now.

Kept exactly, and each would move the digits if tidied: the constants are **floats** (`NM_TO_FT = 6076.1157f`,
`DTR = 0.01745329f`) widened at the point of use; `Math.Round` is to-even while a `"##0.0"` label is away-from-zero;
`dblReleaseAngleRad` is never assigned, so the horizontal track term is always zero. What WDP got wrong here — the
tangent for the arctangent, the offsets measured from the origin, DEST OA1 filled with the pull-up point, the
swapped VIP frame — is fixed (D1-D4).

**VIP mode** takes the target as steerpoint N and the IP as N−1, in sim feet, with the steerpoint's own altitude as
the elevation — exactly what the app's cartridge holds — and measures the VIP geometry on those feet. WDP printed them
as lat/lon labels and read the geometry back **from those labels**, east first, which is where D1 came from.

## The pages on screen, and typing

- **Fitted whole, never scrolled.** A page is drawn at whatever scale makes all of it fit the room under the Planner's
  shell (the toolbar and the identity strip), centred, with no upper limit: a bigger window is a bigger page, laid out
  at that size rather than stretched (`WdpFormView`). Full window hides the mission header and tab strip and the page
  grows into the room. A child window is drawn at the page's own scale and made smaller only where it would not fit;
  a message box is as big as its text, which wraps and is set smaller rather than scrolled (`WdpDialogHost`).
  `--wdprender` draws every page and four windows on a phone, a tablet and in PC windows of 400 x 800 to 2560 x 1440
  and reports each page's scale (0.34 to 2.03 dp a designer pixel on the DataCard).
- **A phone** zooms with two fingers (`WdpZoom`); one finger stays the page's, so sliders, knobs, lists and typing
  work zoomed; a page always opens fitted, and a button puts it back. During the pinch the page is scaled as a picture
  and laid out again at the new size when the fingers lift, so the type is sharp.
- **Typing** is Windows': every text box, up/down box and typed combo (`TypingField`) keeps its own text and caret
  while it has the keyboard; each key reaches the page's wiring (an up/down box's number when it is committed), and
  the wiring's answer is shown only when it is a change of its own (WDP's validation). A press anywhere else on the
  form leaves the box first; Tab moves on; Enter commits and is reported as `"<box>:enter"` (WDP's KeyDown for
  Return: the DTC pilot box's Accept, Select a Flight's Find, a window's AcceptButton); Escape is a window's Cancel.
  A tap on a box is also its Click (the DataCard's formation, weapon and taxi-time boxes). `--wdptypetest` types into
  every box of every page, DTC tab and child window, key by key, and compares each step with a Windows TextBox.
- **Text fits its box.** A value or caption too long for its field is set smaller, down to `FIT_MIN` (0.6), never
  cropped, and one-line boxes place their text by its ink so the bottoms of g, p and y are not cut. WDP lined up units
  with runs of blanks that match GDI's whole-pixel spaces ("Bingo Fuel      lbs", "  RNG ... FT"); those captions are
  laid out a piece at a time at GDI's positions (`SpacedCaption`), so the unit never slides under its figure.
- **By finger** (`WdpTouch.kt`): a finger's editing never changes a page's layout or a wiring's behaviour. On a touch
  device a box typed in (a text or up/down box, any size) opens **one bar docked on the keyboard** (`WdpEditBar.kt`:
  the box's name, its tip, the value, − and + for a number, **Next** down the column or along the line, Done, ✕; the box
  ringed on the page), and any other small control a big editor (`WdpSheet`: a list, a slider, a grid's rows); both
  commit through the same `onValue`/`"<name>.leave"`/`onClick` names typing and clicking send; a tap in a gap presses the nearest control within 48 dp; a long press on a
  knob is its right-click; a double tap zooms. Only a press whose pointer type is Touch is handled differently: a mouse
  works as in WDP on every device. Paper (`WdpPanelView`) registers nothing.
- **The look is drawing only** (`Palette.modern`, `boxFrame`, `ModernTabStrip`, `WindowFrame`): flat buttons, framed
  boxes with a focus ring, blue ticks, round lamps, the charcoal pages on the app's dark ground (#404040 → #29313B).
  No control moves or changes size, and every colour that means something in WDP is kept: the red and green lamps,
  the DED's black boxes and yellow figures, the DataCard's yellow cells, the attack drawings.
  **Paper is never modern**: the kneeboard card is drawn with Windows' own greys and square boxes.

## The attack pages' map, Save Map and Save to DTC

Pop-up, HADB and TOSS each draw a map on their Profile panel (`picSatView`): WDP crops its own bitmap of the theater
around the target and draws the flight plan, the pre-planned threats and the attack's points on it with GDI+. The
port splits that in two.

- **The drawing is the plan's.** `PopupPlan.mapPicture` is Pop-up's `Draw`, compared mark for mark with the real
  program's by `--wdppagetest popup`. HADB's and TOSS's are the same routine with a different last block, over the
  shared `data/wdp/AttackMap.kt`; threat rings are drawn where they are (D10). WDP's marks stay in the picture, mark
  for mark, for the tests; what the app draws of them is the route and the threats (`MapPicture.underlay`).
- **The attack is the one drawing every map uses** (1.3.8). `AttackDrawing.fromPlot` builds the page's attack
  (`data/mission/AttackDrawing.kt`) and `drawAttackModel` (`ui/components/AttackDraw.kt`) draws it over the route and
  threats: the path the jet flies in the profile's order (Pop-up start → PUP → OA1 → apex → MAP → TGT, HADB VRP → PUP
  → OA1 → TGT, TOSS start → OA1 → OA2 → PUP → TGT), dotted past the target to OA2 on Pop-up and HADB; the target a red
  square; the VIP a blue filled square (the HSD's IP symbol) in VIP mode or the VRP a blue circle in VRP mode, never
  both and no separate IP; the pull-up point a magenta ring; OA1 and OA2 green triangles (the HUD's OA symbol), an OA2
  on OA1 drawn once; the route's IP square drawn as a plain steerpoint under it. The DataCard's map, the Upd
  Kneeboard attack page, the Mission map, the VR map boards and the Planner's Map page draw the same model; a
  cartridge's attack is laid out by `AttackDrawing.fromCartridge` (its mode's OA pair only). The foot's caption names
  the attack: "Pop-up · VIP from STPT 6 · TGT STPT 7".
- **Only the current attack is drawn outside the attack pages** (`AttackFocus`): the attack page last changed or
  applied (its Save to DTC, which also fills the card, the card's profile buttons and Save DTC), never the page last looked at.
  The DataCard keeps its own profile and says in one line when the Planner's latest attack is another. It is kept in
  the device's preferences (`planner_attack_focus`) with the mission it was for (`MissionEpoch`'s mode and flight) and
  taken back at the next start only on that mission (`AttackFocus.restore`), so Upd Kneeboard's attack page survives a
  restart as the pages' settings do; wherever it is cleared (a new mission, Re-read DTC) the kept one goes too. With no
  current attack the Planner's Map page draws the cartridge's as the Mission map does (`fromCartridge` with no profile:
  start → PUP → TGT, the generic caption), never shaped by the DataCard's profile.
- **The picture under it is the app's.** A plan's picture carries its crop in sim feet as well as WDP's pixels, and
  `ui/screens/wdp/WdpAttackMap.kt` cuts the same rectangle out of the app's own theater map and lays the marks over
  it. The ink follows the map as WDP's follows its White Map box. The renderer hosts it through `WdpFormView`'s
  `content`, which a wiring offers by implementing `WdpControlContent`.

**Save Map** is WDP's "Save <page> map" window in its `SavedMaps` folder on `<page>.jpg` (`JPEG|*.jpg`), and the map
written there as a JPEG (`WdpPicture`, the platform's own encoder) — on the BMS PC, from every device, the browser
included (see *WDP's own files* below).

**The caption names the target** along the map's foot: "STPT 15 · EW Site Puryu-gogae 1 JLP-40 Radar P-37" — with a
save's flight the target designated to the seat (else the waypoint's action word), otherwise the cartridge's name for
the point, else the briefing's row. With no target the plan is drawn on black and the caption says why (D54). The
DED Data panel puts each unit after its figure ("TBRG 340.4°", "RNG 43596 FT"): display only, the figures and the
saved offsets are unchanged. TOSS's MAT is drawn disabled, as WDP's designer has it.

**Every ELEV can be typed, and says so.** WDP's ELEVs are outputs; a pilot who wants another figure for the jet had
no way to give it (forum, 2024). On all three pages each ELEV figure of the DED panel and the Coordinates box's target
Elv carry a pencil and a frame — dotted for the page's own figure, solid cyan once typed — the mouse turns to a hand
over them, and the foot of the DED Data panel says what a tap does, what the ELEVs stand on ("Target ground 65 ft,
BMS's terrain · tap to change") and which lines are typed (`ElevMark`, `DedElevHint` in `WdpGround.kt`). A tap or
click opens WDP's small entry window (feet above sea level, 0 = ground level, empty = the page's own figure again);
where a finger works the Planner it opens the finger's big editor instead. What a typed figure changes is what WDP
computes from it, which the comparison on the last Korea save shows figure by figure:

- **A DED line's ELEV** (VRP, VIP, PUP, OA1, OA2) is that line's figure and nothing else: the DED, the nav offset Save
  to DTC writes, the card's delivery block. WDP works nothing out from an ELEV — no range, bearing, release or
  pull-up figure and not the HUD check (`SightDepression` uses the release height, the bomb range and the dive) —
  so a typed OA1 of 5,000 ft moves OA1's line only, and the pull-down altitude beside it stays WDP's.
- **The ground under the target** is WDP's `TargetElv`: on Pop-up the ingress altitude, the pull-down altitude and
  every ELEV but OA2's 0 move with it, exactly as WDP's do when its height map says so (2,877 ft typed under
  steerpoint 3 gives what WDP gives with 2,877 ft of terrain there, figure for figure: VRP and PUP 3,377, OA1 and the
  pull-down 9,625); Type 2's run-in does not move (D64). On HADB and TOSS it moves every ELEV but the 0s (D7).
- A typed figure holds through every slider and switch until the target steerpoint changes; an empty box, or a new
  target, puts the page's own back.

**Save to DTC** hands the page's nav offsets for the selected mode to the cartridge through
`WdpCartridge.saveNavOffsets`, with WDP's profile name (`PopUp`, `HADB`, `TOSS`), and shows the answer as WDP does.
Only the selected reference's four lines are written (D3), and the other reference's are cleared (D88): VIP and VRP
cannot be used together on one attack (Dash-34 p.426). Once the cartridge is saved, the DataCard's Delivery block
becomes this attack (`AttackSelection.saved` → `DataCardWiring.attackSaved`; a failed save leaves the card alone), and
the card's Save DTC takes the same path (`DtcWiring.stageNavOffsets`) and writes byte-identical `[NAV OFFSETS]`.

## How the mission reaches the Planner

WDP reads the campaign and the printed briefing itself. The Planner has two sources, and the identity strip under the
toolbar always says which:

- **A flight of a save**, picked with **Open mission…** and **Pick a flight** (`PlannerMissionState.plan`). The PC reads
  the save (`CampaignArchive`, with WDP's own `BMSUtils.dll` record layouts) and answers `/api/campaign/flight` with
  the flight's route, loadouts, package, support tracks, the save's air defences, ships and bullseye, WDP's intel lists,
  and a `Briefing` made of the save (`origin = "save"`) with the texts BMS's own briefing scripts would print. When
  BMS's printed briefing is for the same flight, its situation, weather, comm ladder and support are joined in
  (`DataCardWiring.cardMission`); otherwise each box only a printed briefing fills says PRINT brings it. The weather
  is the save's own `.twx` where no printed briefing gives it or the `.twx` is newer with other weather (D68).
- **BMS's printed briefing**, the cartridge and BMS's own route (`MissionData.route`, the mission file beside the save
  when it is provably the briefed flight's, else the printed flight's own waypoints in the save that provably holds
  it). **Back to BMS briefing**, in Open mission… and Pick a flight, returns to it.

Either way the Planner reads **BMS's own files** (`MissionLink.bmsFiles`, `/api/mission?source=bms`), never the
snapshot WDP mode serves to the Mission section, or it would plan the last Populate instead of the files.

`WdpMission` carries the mission to every wired page (`WdpWiring.onMission`): the steerpoint table slot by slot
(`slots`, by the pilot's answer to WDP's Precision STPT question, `precision`, D45 — Yes: the cartridge's slot when it
holds a position, else BMS's route, else the save's waypoint at its cell's middle; No: the save's waypoints only; the
printed briefing, never asked: the cartridge's slot, else BMS's route — each with its source, `StptSource` DTC / BMS
route / save), and each attack page takes its target from
its own **TGT STPT** box: the target is that steerpoint, the IP the one before — or the one the pilot picks in the
**IP STPT** box beside it (D87). A new mission or flight
(`WdpMission.key`) sets the three boxes once to `defaultStpt` (a save's first strike steerpoint, else the first
flight-plan target; D40), and the IP back to following the target; after that each page keeps its own. There is no target bar. `choices` stays as the
mission's target list (the DTC page's Select target, the card's lists). The card's Primary and Secondary are
`cardTargets(seat)`: a save's first two strike steerpoints named by the seat's designated target, else the first two
flight-plan targets. A save of another theater than the one BMS is set to is planned without the cartridge's
positions (they are that theater's feet), and the flight picker says so.

The pages live for as long as the app runs (`WdpSession`), so leaving the tab keeps typed work; only a new mission
or flight is a new card. The attack is no longer published to the Mission map on each change: **Populate from
Planner** takes it (`WdpAttackOverlay.current(page, wiring)` / `toSend()`: the page on show, else the card's PopUp,
HADB or TOSS choice, else the attack page last worked on). `WdpPane` hands the Planner (`WdpPlanner`) what the PC
sends; the headless renders hand it a briefing and a cartridge from disk, and `BMSC_WDP_ROUTE` /
`BMSC_WDP_SAVEFLIGHT` stand in for BMS's route and a save's flight.

### The DataCard and the Performance page talk to each other

In WDP both are fields of one window and write into each other; here they are joined by `WdpHandOff`. The card
hands the page the flight plan's second-row altitude (cruise), the take-off spec chosen on the card (pitch, power),
its take-off weather (wind, temperature, QNH; what a new mission's weather does not give goes back to the page's own
values, 0/0, 1013.2 and its starting temperature) and its departure runway; every recalculation on the page puts its
take-off figures on the card — gross weight (red when over the maximum), drag (D38), rotation and refusal (refusal red
when it is not above rotation), MIL power, MIL climb schedule, take-off spec, take-off and block fuel. A flight that
is not an F-16 gets no speeds, as in WDP. `--wdprender` checks each of these on a real mission.

### The Performance page against WDP 3.7.24, figure by figure

A pilot who sets both programs up alike still sees other figures. An independent check on 2026-09-30 (its own
harness and twelve scenarios of its own: five engines, empty to 55,676 lb overweight, −12 to 40 °C, QNH either side of
standard, 0 to 4,450 ft, every cruise radio, the turn calculator) found every difference on identical inputs to be one
of the fixes above, and each equal to WDP's own code with that one fix: 665 labels identical, 91 differing, 0
unexplained. What differs, and why:

- **Take-off factor, lift-off, rotate, refusal**: D28 (pressure altitude from QNH; a low QNH raises the factor), D31
  (the refusal cell 12,000-15,000 ft). On the user's Korea save WDP also plans from **Afyon** (3,310 ft) instead of
  Osan (D60), and weighs the stores without their pylons and racks (D33): 1.851 / 168 / 153 / 178 there, 1.465 / 170 /
  155 / 186 here, and WDP itself gives 1.46 / 187 once it is given Osan.
- **MIL and MAX AB climb, ceilings, optimum Mach**: D31 (WDP's charts are always given an ISA deviation of 0 while
  the page prints the real one; the charts' own correction is +0.5 % of MIL climb distance per °C above standard, 7 % at ISA+14), plus the field and the load above.
- **Empty weight, fuel, drag of a conformal-tank jet**: D66.
- **The inputs the port had wrong until then**, all put right on 2026-09-30: most fields' elevation was 0 (73 of
  Korea's 94 — Samjiyon, 4,450 ft up, gave a take-off factor of 1.38 instead of 1.90; every field of the Balkans,
  EF2000 and Israel; Hellas's ATC files give 0 for Kasteli's 1,180 ft) and is now the ground under the runways in
  BMS's height map, which WDP's own Aegean database matches to a median of 1 ft and its Korea database puts Samjiyon
  28 ft from; the stores weighed and dragged as Korea's in every theater (Hellas's AN/AAQ-13 NAVPOD drags 32, Korea's
  22) and now as the mission theater's, with that theater's variant of the jet, as WDP reads the running theater's;
  and a save's burnt fuel is now taken off the flight's own jet, and its take-off fuel shown "----" once it is under
  way, as WDP's `SetFuel` does.
- **The weather** a save brings is the card's: see the DataCard.

The overweight corner is the charts' own: a jet far over its maximum (55,676 lb) gets a cruise ceiling of 0 and a
service ceiling of 650 ft in both programs, because the charts run off their end; the gross weight is red.

### What the ballistics actually are

Worth knowing before anyone reads more into a number than is there.

- **Drag is by category, not by store.** Low-drag, high-drag or cluster, and that picks one coefficient: 0.165 for
  slicks and cluster munitions, 1.0 for retarded. There is no per-weapon drag table in the program, so two
  different low-drag bombs give the same range. That is the model's resolution.
- **A retarded bomb also falls faster.** At a coefficient of 1.0 the vertical acceleration changes from gravity to
  −20.91505 ft/s². That is a fitted figure standing in for a drogue, not physics, and it is kept exactly.
- **Release height is above the target**, not above the sea.

### Pressure altitude and the lapse rate

`clsMeteo.PressAltitude` subtracted a tenth of the correction whichever side of 29.92 the setting was, so a setting
above standard lowered pressure altitude as one below it did (D28). That is fixed; WDP's original stays in
`Atmosphere.kt` as `wdpPressureAltitudeFt` for the comparison. Its ISA lapse of 2 °C per thousand feet, where the
DataCard uses 1.98, is **kept**: it is Falcon BMS's own ("a dry adiabatic lapse rate of -2°C per 1000 feet", User
Manual 4.38.1 §4.1), so the day's deviation is measured against the atmosphere the jet flies in (D32, withdrawn).
`clsMeteo.Inch`/`Hpa` recursed into each other and were never read; here they are plain conversions.

## Latitude and longitude

Every page that shows a coordinate — TOSS, Pop-up, HADB, the DTC page and its windows, the DataCard, the Coordination
Card, the Map page and the Upd Kneeboard pages — prints the latitude and longitude **Falcon BMS itself gives**, from one
place: `WdpCoords.coordData` for the theater BMS reports, through `PopupCoords.feetToCoordsBoth` (and back, for a
typed position, through `ConvertLatLonToSimXY`). It is **WDP's own grid** — `fclsMain.InitNewTerrain` and
`InitTransverseMercator`, ported bit for bit — built from three figures of the terrain BMS flies the theater on
(`Theater.projection` in `data/index.json`: `Theater.txt`'s size and centre and the length of
`Heightmaps/HeightMap.raw`, from `tools/extractor/src/projection.mjs`):

- metres are feet ÷ **3.28084** (not the 3.27998 of a campaign kilometre);
- the false origin is GeographicLib's forward projection of the centre, less half the theater;
- a point's northing is one heightmap sample more (1,024,000 m ÷ 32,768 = 31.25 m).

On every 1,024 km theater WDP finds a terrain for, that is exactly what WDP prints. Korea TvT, whose `.tdf` names no
terrain, is flown on `Terrdata\korea` and gets KTO's (WDP found none and printed nothing). The conversions keep WDP's
formats (`clsCoordinates`: `N37,05.410`, the label splitters, feet back as text east first), and a page that writes
the hemisphere as a letter writes `S`/`W` rather than `N-`.

**What D26 got wrong, and how it was found.** Up to this correction the port printed with `Theater.txt`'s *projection
string* (PROJ tmerc with its `x_0`/`y_0`, 3.27998 ft per metre — what the maps, towns and ground charts are drawn on)
and called it BMS's own; WDP's grid was said to lie about 200 m off, and on the Falklands about 500 nm. That verdict
came from real-world airfield positions, seven fields within 0.81 nm — a test that cannot tell 200 m apart. BMS's own
figures say the reverse. An ACMI recording BMS 4.38.1 writes holds each object as `T=lon|lat|alt|…|U|V`, U and V the
sim's feet ÷ 3.28084, so a position in feet and BMS's own latitude and longitude for it are on one line; BMS's KTO AIP
prints a "BMS Lat/Lon coord" for every airbase, and the Balkans AIP a "BMS GPS COORD":

| Terrain (theaters) | BMS's own figure | Positions | Projection string (before) | WDP's grid (now) |
|---|---|---|---|---|
| Korea (KTO and the ten add-ons on its terrain, Korea TvT) | 8 ACMI recordings | 76,619 of 42,257 objects | mean 144 m, max 197 m | mean 0.6 m, max 1.3 m |
| Korea | KTO AIP, AD 3.1.1-3.1.2 | 22 airbases and strips | mean 147 m, max 185 m | all within 1.9 m (median 0.0 m) |
| Hellas (Hellas, Hellas WCP, LHTO) | 9 ACMI recordings | 51,513 of 28,454 objects | mean 168 m, max 214 m | mean 0.6 m, max 1.3 m |
| Israel | 3 ACMI recordings | 20,309 of 17,629 objects | mean 184 m, max 243 m | mean 0.7 m, max 1.3 m |
| Balkans (Balkans, EF2000 BTO) | Balkans AIP ("Balkans Navaids List.pdf") | 104 airports | mean 219 m (median 222 m) | 103 within 2 m (median 0.0 m); Taszar 1.27 km from both (a misprint) |
| Falklands (2,048 km) | none | — | what the Planner prints: not verified | WDP's grid counts 1,024 km: every latitude ~9° north of the theater's own centre |

The projection string is never within 2 m of BMS's figure; WDP's grid always is, the rest being the print's own rounding
(a thousandth of a minute is 1.85 m of latitude). The KTO AIP's Daegu and Gimpo rows print their longitude a whole
degree out and are left out. The two figures differ by construction: the feet per metre (3.28084 against 3.27998, 0.026 %
of the distance from the theater's corner), the one-sample northing, and `y_0` printed to six figures.

**The Falklands** keep the projection string. WDP's grid is built for 1,024 km whatever the theater's size, no
recording or AIP of that theater carries a BMS figure, and a grid scaled to 2,048 km, however likely (real airports sit
a median 166 m from BMS's airbases with it and 460 m with the projection string), is not established. A Falklands
recording decides it: `--acmicoords <recording> out.txt falklands`.

**What the maps draw** is not a printed figure and is unchanged: the Mission map's towns and borders (Natural Earth)
are laid on the projection string (`geo.mjs`). At the finest map tile (125 m a pixel) the difference is a pixel or two,
and whether BMS's terrain was itself laid on the projection string or on the grid is not established.

**How it is checked.** `--wdppagetest coords` demands, in five sections: every one of the 19 theaters handed BMS's grid
(the Falklands the projection string), equal to WDP's own wherever WDP reads the same terrain; points through the
Planner's conversions against an independent copy of each (projection.mjs's series set up as the grid or as the
string, inverted numerically); BMS's own figures carried in the test (24 ACMI positions, 12 KTO and 10 Balkans AIP
airports) within 1.9 m, with the projection string's distance beside them; nine real airfields within 3 nm (signs and
axes); and WDP's own grid reproduced bit for bit against the real `InitNewTerrain` (`wdpref page Coords`, which never
points WDP at the install), WDP's prints identical to the Planner's but on the Falklands and Korea TvT (D26).
`--acmicoords <recording> out.txt [theater id]` prints every position of a recording (a `.zip.acmi` as BMS writes it)
through every theater and names the ones within 2 m of BMS's figure; it passes on all twenty recordings at hand.

## The pages are data

Every page of WDP is drawn from its own layout, read out of the program's designer code by
`tools/extractor/src/wdplayout.mjs` — each control's kind, rectangle, text, font and colours, and which panel it sits
in — into `assets/data/wdp/<form>.json`. One renderer, `ui/screens/wdp/WdpForm.kt`, draws all of them. `cntDTC` alone
is 1,878 controls, and placing those by hand would have put a hundred of them a few pixels out.

The pictures are Falcas's too. A control's `BackgroundImage` or `Image` lives in the form's `.resx` as a base64
payload; `tools/extractor/src/wdpimages.mjs` finds each one by its magic number and writes it out **unchanged** — 78
images, 1.8 MB, under `assets/data/wdp/img/`. What the Planner leaves out is listed once, in
`tools/extractor/src/wdpdropped.mjs`, and neither script writes it: the Threats and Munition pages, the windows nothing
opens (WDP's main window, start-up, about, settings, registry-key and user-name windows, the kneeboard windows, the
timer, and the windows of pages the Planner does not have), and the pictures of the removed Mission.ini lamps. What
the Planner **adds** to a page it keeps is listed in `tools/extractor/src/wdpadded.mjs`, which `wdplayout.mjs` applies
after reading the designer (and which patches the shipped layouts in place: `node src/wdpadded.mjs <layout dir>`): the
attack pages' IP STPT box, with TGT STPT moved 50 px left in its row to make room (D87), and Pop-up's and TOSS's IP
STPT at the VRP in the row WDP's Campaign and TE buttons left (D94); its `DROP` list takes out what an earlier run
added and the Planner no longer has (the test builds' Send to DataCard).

Several Windows Forms behaviours had to be reproduced before the pages read right:

- **Colours inherit.** A label with no ForeColor takes its parent's. WDP's dark panels (`#404040`) carry white ink
  that way — 105 of the 153 labels on the TOSS page set no colour of their own.
- **Text is top-aligned and clipped to its box.** Every control clips to its bounds, labels default to TopLeft, and
  the type is fitted to the smaller of the font and the box.
- **Alternative views are stacked.** WDP shows one of several panels at the same rectangle by flipping `Visible`
  at runtime — 237 such switches on the DataCard page. Siblings sharing a rectangle are treated as alternatives and
  the first in designer order is drawn unless the page names another. **One control can hold several of WDP's
  tabs**: `cntDataCard` carries the Briefing view (`pnlBrief` + `pnlWx`), the DataCard (`pnlPage_1`) and the
  Coordination Card (`pnlPage_2`), which is why the Planner lists them as three pages of one form.
- **A label whose text is its own name is a placeholder** the program fills at runtime (`lblHdg3`, `AltnName`).
- **A checkbox's value is its state, not its text**, and text and number boxes are live when a page is wired.
- **A label no taller than its type is not clipped.** WDP's 13 px value labels with a 13 px font get their natural
  height, which is what Windows shows.
- **Tab controls, grids, list boxes, combo boxes** behave as Windows' do; **child windows are WDP's own forms**, opened
  through `WdpDialogs` over the Planner, modal, with their own colours and picture; `MessageBox.Show` is
  `WdpDialogs.message`; what WDP paints into a panel at runtime is `"<panel>.lines"`; **z-order is Windows'** (the
  first control a parent adds is on top).

`WdpWiring.kt` documents every value the renderer reads and every callback it sends.

**`--wdpclicktest <out.txt> [briefing.txt] [cartridge.ini] [page]`** presses everything: every button, box, list,
slider, grid row, on every page and one level into each window a press opens, on a fresh page each time, and says
what each press did — values changed, a window or message opened, **nothing**, or an exception. "Did nothing" is the
line to read. The removed controls must each come out "hidden on the page", and the run ends PASS only when none shows
and no press throws. **`--wdprender <folder> [briefing.txt] [cartridge.ini]`** draws every page and window to a PNG,
headless, checks the DataCard ↔ Performance hand-off, and draws the whole Planner — header, page tabs and page — for all eight pages at a phone's (400 dp), a tablet's (840 dp) and a PC's (1400 dp) width
(`BMSC_WDP_ONLY=widths` for only those). Run both with `APPDATA` pointed at a scratch folder and `BMSC_WDP_THEATER`
naming the theater.

The pages keep WDP's layout and colour cues, drawn in the modern look above; the kneeboard's paper is Windows' own
greys. The app's dark HUD palette is an alternative (`WdpLook.HUD`) for a tablet in a dark cockpit: same geometry,
different light.

## The DTC page: the pilot's cartridge, edited and saved

The DTC page's job is the cartridge itself, so it is ported for **what each button does**. `data/wdp/DtcPage.kt`
keeps WDP's page code where it is the page's behaviour; `ui/screens/wdp/DtcWiring.kt` does everything around it;
the child windows are `DtcDialogs.kt`. The Planner is for 4.38.1 only, so none of WDP's version branches remain.

- **Loading.** The PC sends the cartridge whole (`GET /api/cartridge`, `desktop/.../bridge/CartridgeStore.kt`), on
  every platform through `MissionLink`. It is loaded twice: into the page, and into a copy never touched.
- **Saving writes only what was edited.** The port runs WDP's save (`DtcSave`) over the loaded text twice — from the
  untouched copy and from the page — and the keys that differ are the pilot's edits (`DtcEdits`); only those are sent
  (`POST /api/cartridge/save`) and written, one key at a time, into the file as it is on disk. `[LINK16]`,
  `[MAP_POP]`, `[Radio] ILS_1..4` and every key WDP does not know survive byte for byte. Saving writes the way WDP's
  Save DTC does: at once, with no question, nothing to switch on and no backup copy (the user's decision; the 1.3.8
  test builds' one-time copy into `User/Config/BMS Companion Backup/` and its switch are gone, and a folder they left
  is never read); temporary file and atomic move; errors come back to the page. `--cartridgetest <folder with a copy> out.txt`.
- **After a save the kneeboards are behind.** In EZBoards mode a save that wrote the file answers with **Generate
  now**, the Kneeboards page's GENERATE NOW, when EZBoards is set up on the PC; it never runs by itself. In WDP mode it
  offers nothing of the kind (EZBoards is suspended there; the cockpit kneeboards are Upd Kneeboard's).
- **Clear asks** (D50), and a save that would set a placed point to zero asks once. The lamps and the Save to DTC
  count follow real edits: a press that changed nothing (Rebuild on an untouched list, Apply on an unchanged point,
  Clear All on an empty table) no longer turns them red; Clear All PPTs leaves empty slots empty rather than writing
  code "0" into them; a cleared steerpoint is named "Not set", as BMS names one; Apply on an unchanged Change window
  keeps the exact position (a latitude/longitude round trip moved it); Change PPT with 0,0 leaves the slot empty.
- **Rebuild STPT List** is WDP's: the 24 steerpoints cleared and the flight's plan laid into them — the flight opened
  from a save (each waypoint at its cell's middle, with its action and altitude), else BMS's route beside the save —
  and where a slot already holds its waypoint (BMS wrote the route into the cartridge) BMS's own figures stay, so a
  Rebuild of an untouched route changes nothing. WDP cleared the rest without a word; here the placed points past the
  flight plan (a Recon target, the pilot's own) are named and the pilot asked first. With no flight plan the
  steerpoints go back to what the cartridge held when it was loaded, and the page says so.
- **With no cartridge** the airport block (Select APT, Charts, frequencies) still works; every other editing control
  is drawn disabled, and a press on one says what is missing and how to get it.
- **4.38.1's own data**: the HARM lists are the app's ALIC codes (D12), "BMS default" in the EWS, MFD and HARM Load
  windows is BMS's own `*_Def.ini` (D15), the radio **Default** is the briefing's comm plan with the departure's TACAN
  and ILS, the PPT table is the theater's `Ppt.ini` and Radar/Engage Zone take their radii from the threat reference,
  **Charts** opens the chart window over the selected field (below), preset numbers are bracketed ("Preset [3]
  275.800", so a channel number is never read as part of the frequency), the airport block opens on the flight's
  departure field (the airbase nearest the first placed steerpoint was often a Recon target's, Taetan for a flight out
  of Osan), and ILS frequencies keep 4.38.1's band (D11). Blue and Red stay as WDP's
  named radio lists. Link 16 is shown on the card (A/A TACAN, STNs) and never written.
- **The child windows** are WDP's forms over the app's data: Change STPT/Line/Target/PPT check the position is inside
  the theater; Target Selection lists the mission's targets and steerpoints and the theater's airbases; Select APT lists
  the theater's airports; Change Area lays the mission's planned tanker/AWACS tracks as WDP lays a tanker track: the closed box about the station leg (D85).
- **Per-part Load/Backup** are WDP's `Open_File`/`Save_File`: its window in `Files\<part>` with the part's own type
  and title ("Save EWS backup files", `ews files (*.ews)`), the part written into the file key by key as WDP's
  `SaveCallsign_*` write it, read with the cartridge's own reader (`DtcFiles.backup`, `backupText`). A Load that picks
  no file offers BMS's own defaults (EWS, MFD, HARM) and the three slots per part an earlier version kept in the app's
  settings. MAIN's **Open / Save Callsign.ini File** and the PPT tab's **Browse PPT.ini** and Personal **Save / Load**
  (`.ppi`) are WDP's windows too; a Callsign.ini opened from outside `User\Config` is the page's cartridge from then on
  and Save to DTC writes it there (directly, as WDP does).
- **From mission…** (the app's own, where nine tabs had a Save DTC button of their own): what the app knows of the
  mission into that tab — tracks as lines, the spotted air defences as PPTs of the theater's own table, the flight plan
  into empty steerpoints or any point into a chosen one, targets, the comm plan, a TACAN, an ILS, laser codes, HARM
  tables — as the tab's own editing would, asking before it replaces a placed item. A tanker's TACAN is the channel
  the save gives it (Texaco4 122Y, "set 59Y A/A TR": BMS's briefing prints the 59Y), BMS's usual allocation (92Y for
  the first tanker) only where the save has none. It uses WDP's own windows
  (Change Area, Target Selection, Airport to STPT) and message boxes. The **Map page** (below) is the same edits on a
  map.
- Positions print with BMS's own latitude and longitude (D26), and as feet (`1234567'`) when the theater is not known;
  the Change windows read either.

## The DataCard, the Coordination Card and the Briefing

The three pages are one control, `cntDataCard`, and one wiring (`DataCardWiring.kt`). The card itself — the flight
plan, the package, the fuel ladder, the tanker lists, the weapon boxes — is WDP's own code (`DataCardPlan.kt`,
checked by `--wdppagetest datacard`), with D19-D25 fixed. Everything around it is ported for **what each button
does**, over the app's data, gathered once per mission by `DataCardWiring.prepare` (`DataCardSources.kt`):

- **The airports** — name, TACAN (or the nearest within 6 nm, marked `*`), elevation, runway, ILS, and the tower's
  UHF and VHF, with Ground, Approach and Ops below — come from the app's airport data for the theater. The runway is
  the end most nearly into the briefing's wind, the one the ATIS names. **C** opens the chart window (`fclsChart`,
  also the DTC page's and Performance's **Charts**) on the airport diagram the app draws from BMS's own airfield data,
  as the Taxi page does; each APC is the ramp BMS numbers for one runway end ("APC 09L"), then the field's instrument
  charts; the line at the foot names the chart on show and a tap turns to the next. WDP showed pictures from its own
  Charts folder.
- **The weather.** Mil/Civ and M/SM make the ATIS from the card's take-off weather (`CardWeather`), with WDP's own
  colour-state thresholds and wording, METAR's visibility caps, and the weather's QNH (the save's `.twx`'s: BMS
  4.38.1's printed briefing gives none). **Reload WX** opens WDP's "Load WX FMAP File" window on the BMS PC and makes
  the card's weather from the `.fmap` or `.twx` picked (D55): each field's own cell, the weather list, Force QNH and
  the Performance page's take-off weather. A save's flight opens with the save's own weather, as WDP's File > Open
  means to (`ReadWeather`, `CreateAtis`; WDP itself cannot read a 4.38 `.twx`, D55): `<save>.twx` beside it, read as
  Reload WX reads a file but quietly, again at every Open mission and Pick a flight, and preferred to a printed
  briefing of the same flight only when it was saved after the PRINT with other weather (D68). Mil/Civ and M/SM are kept
  between sessions as WDP keeps them in Setup.ini, and start Civil and metric (1013) as WDP's do when Setup.ini says
  nothing. The ATIS names the transition level WDP's airport table gives (`TRL140` on KTO's terrain, D61; else 180).
- **Support.** For a save's flight, WDP's own tables (`Tankers`, `Awacs`, `JSTAR`, `CampFlight.sideSupport`): every
  tanker, AWACS and JSTARS of the flight's side in the save, each with its vehicle, its window on station (the leg
  from its first tanker or ELINT waypoint to the next), its UHF from the theater's radio map, its LOC and, for a
  tanker, the save's own TACAN turned to the receiver's (123Y → 60Y, what BMS's briefing prints). The two tanker
  rows are the first two on station while the flight is up, and the small button beside each opens WDP's list of
  them to pick another (`btnSetTanker`, `cboTanker`, `btnTanker`): the list comes up already dropped open (WDP shows
  it closed, a second press away; `"<combo>.drop"`), a pick and **Select** fill the row, and No Tanker empties it —
  by mouse or by finger, checked with real presses by `--planneroutcome tanker`. LOC is D63. With a printed briefing alone, the
  support is the Briefing page's list (TACAN, UHF, and the station the briefing words).
- **The cartridge's boxes** — EWS names, bomb profiles, laser codes, ALOW, MSL floor, bingo, offsets — are read from
  the pilot's cartridge; what the pilot types there goes to the DTC page with the card's **Save DTC** (or the toolbar's
  **Save to DTC**), and the "Callsign.ini saved" lamp is red until it has. CONF-LSR comes from a save's
  per-jet laser codes, the Config rows from the seat's stores by their SMS names (D38), ROE from the briefing, the
  bullseye row from the opened save's header, else the sim's or the header beside the briefed flight's mission file.
  **Current Time** is the sim's clock while BMS runs.
- **A save's flight** (Open mission…): Your Task is the flight's role (WDP's MissionTask), TOT falls back to Time on
  Station for a CAP, Squadron comes from the save, and the Mission Objective (WDP leaves it to the pilot) starts with
  the task, the station or target area, the time on station and the package's flights. With no printed briefing for
  that flight, Situation, Intel, ROE and the emergency procedures are the save's own, worded as BMS's briefing words
  them (`CampaignBriefing`); the weather is the save's own `.twx` (above), and the comm ladder says PRINT brings it.
- **Link 16.** For a jet with a Link 16 plan, the flight's A/A TACAN and STNs are printed where WDP put IDM addresses.
- **Files.** Backup/Load DataCard, Save/Load Codewords and Package Timing, Cards Directory, the plan boxes' pictures
  and the Apt Schedule's Save JPG open WDP's own windows on the BMS PC and write WDP's own formats (below). Copies an
  earlier version kept in the app's settings (`CardCopies`) are offered by Cards Directory and by a Load that picks no
  file.
- **The windows** (Loadout, Formation, Names, Mil Codes, Apt Schedule) work over the briefing. **The card stays on our
  own flight** (D86): the package's other flights are greyed with a tip saying how to plan one, and our own callsign
  opens the Performance page's Loadout window (below), whose OK also writes the card's Config rows. Only a card with no
  Performance page beside it (a headless check's) opens the read-only `LoadoutWindow` over the briefing's stores, with
  its weights from the app's Arsenal and aircraft data.
  **Different Flight** is on the page where WDP has it, as well as in the identity strip: **Pick a flight** on the
  save the card is planned from (the one opened, else the one BMS printed the briefing from), which changes the whole
  Planner; with a printed briefing of an unknown save, its package in WDP's window, another flight there pointing to
  Open mission….
- **Target Selection** (`txtDMPI_Pri`/`txtDMPI_Sec`, a tap or a double click, `DataCardTargetWindow.kt`): WDP's
  `fclsTargetSelection` over the save's start file (`/api/campaign/objectives`, `/features`): every objective by type
  (or a name typed in the box), its buildings with their worth (only those worth something unless Show all), a map
  of them; the building picked goes on the card as WDP's `FillPriTarget` puts it — the objective in the target box,
  the building as DMPI, and "N42,03.848 / E127,36.109 - 2877'": the objective's position plus the building's offset,
  and the ground's height from BMS's height map at WDP's cell — on the last save exactly what WDP prints for the same
  building (ZYBS Rwy Sec 19): the same feet, the same height and the same latitude and longitude (D26). WDP also moved the box's strike
  steerpoint onto the building in its cartridge (`SetTargetSTPTs`); the card leaves that to the DTC page.
- **The weapon boxes** are the DTC page's (`cntDTC.FillWeapondata`, which the card copies): a sub-mode the lists do not
  have is CCIP, a fuze NSTL, a release anything but PAIR is SGL; the arming delay reads as the masked "00.00" box
  (`04.00`) and the height of burst as the masked "0000" box (`0500`), as on WDP's card. **Delivery** stays empty,
  **None** at its head, until a profile is chosen with PopUp, HADB or TOSS: WDP's card never names the cartridge's
  own offsets ("Else" is the DTC page's word).
- **The Coordination Card's plan boxes** open with WDP's own templates (holding pattern, target area flow AA/AG by
  role); a click opens WDP's picture window straight away (`DataCards\PlanPic`, below), as WDP's does, and the right
  button (a long press on a touch screen) offers the app's own: a picture file, CW/CCW or AA/AG, an attack map, or Clear.
- **The Coordination Card's package table** is filled for every flight of the package, as WDP fills it from the
  save it opened: each flight's own route comes from the save (`CampFlight.packageRoutes`; with the printed briefing,
  from the save that holds the briefed flight), so its take-off, taxi, holding point and altitude (`STPT n`/FL or
  ` No Hold `/0'), push time and altitude and TOT are the save's (to the second, as WDP and BMS print them); the
  in-flight frequency is the comm ladder's, and where the ladder has none (a tanker's VHF, every other package
  flight) the theater's `RadioMap.dat`, as WDP's `ReadRadio`; the TACANs are WDP's 12Y-16Y. **Package** is the
  package number and the save's file name (`3474 Auto Save`), WDP's `strMission`, which the DataCard prints as its
  Mission too. The bullseye row is the opened save's header, name included (`Bullseye()`: "Bullseye" or "Rose"),
  at the middle of its cell where the sim has it (D62), else the sim's. Callsigns are written as WDP writes them,
  the name, a space and the number (`Texaco 4` where the briefing prints `Texaco4`). The transition level is D61's.

## WDP's own files

WDP opens and saves its files through Windows' Open and Save windows; the Planner opens the same windows on the BMS
PC (`PcFiles`: Windows' own dialog on the PC's window, the PC's folders in a window on a phone, a tablet or a browser)
and reads and writes the same files in the same formats, through the PC, so a file saved by either program opens in
the other (`WdpFiles.kt`).

**Where.** WDP keeps these files in its own program folder. The Planner keeps them in a folder of its own **inside the
BMS install**, `User\BMS Companion Planner` (the PC's place `@planner`), laid out as WDP's folder for the parts the
Planner writes (`PcFileRoutes.SKELETON`): `Files\EWS`, `Files\Harm`, `Files\Harpoon`, `Files\Line`, `Files\MFD`,
`Files\Open`, `Files\PPT` (with `Personal`), `Files\Radio`, `Files\System`, `Files\Target`, `Files\Weapons`,
`SavedMaps`, and `DataCards` with `PlanPic` and the `<mission>\<package>\<callsign>` folders of Backup DataCard. It is
made, all of it, the first time a file window needs any of it (WDP's `CheckForDir`), and the files an earlier 1.3.8 test
build kept in `Documents\BMS Companion Planner` are copied in then, once (a file already there is kept; nothing is moved
or deleted). It is never WDP's own folder: WDP's files stay WDP's (its folder is still offered in the window's places),
and the Planner's are the pilot's with the BMS install they belong to. The DataCards folder is a setting, as WDP's
Settings → DataCard directory (`[Main] DatacardDir`): `@datacards` is the folder the pilot chose
(`BridgeSettings.PlannerDataCardDir`, read and set from any device with `GET`/`POST /api/files/planner`), else
`@planner\DataCards`. The cartridge is not among these: Get DTC File and Open/Save Callsign.ini File open the game's
`User\Config` (`@config`), where BMS keeps it. These files are **not read by Falcon BMS**: they are the pilot's own
presets and copies, loaded into the Planner, whose Save to DTC (the cartridge) and Upd Kneeboard (the cockpit's
kneeboard pages) are what reach the jet. Written as WDP writes them: directly, no backup.

| Button | WDP's window: title, start, types | The file |
|---|---|---|
| Reload WX | "Load WX FMAP File", the campaign's `.twx` folder and name, `FMAP(*.fmap)`, `TWX(*.twx)` | read only (D55) |
| Load DataCard | "Load backup DataCard File", the flight's DataCards folder (else DataCards), `Backup DataCard (*.bdc)` | `.bdc`: `SaveDataCard`'s 542 writes, `CardFile.TABLE` |
| Backup DataCard | the mission-and-package window, then "Backup DataCard" in `DataCards\<mission>\<package>\<callsign>` (made) on `<callsign>` | the same `.bdc`, key by key; WDP's own values the app does not have (the theater's number in WDP's database unless a WDP file gave one, the loadout's weapon ids, the Pop-up page's state, the main map's position) left out, so WDP keeps its own |
| Save/Load Codewords, Package Timing | "Save Codewords" … in DataCards on `Codewords.ini`, `PackageTiming.ini`, `(*.ini)` | `[Codewords]`, `[PackageTiming]` with WDP's key names |
| Cards Directory | File Explorer on the flight's DataCards folder (the PC's window); the folder in the Planner's window elsewhere | a `.bdc` or `.ini` picked there is loaded |
| plan box (Coordination Card), a click | "Open" (Windows' own title: WDP sets none), `DataCards\PlanPic` on `<Box>Plan.jpg` (the reform and holding plans only when that file is there), no file types (every file) | any picture, drawn fitted in the box |
| Apt Schedule: Save JPG | the mission-and-package window, then "Save Airport Schedule" in the flight's DataCards folder on `<field>Schedule.jpg` | the 600-pixel sheet as JPEG (D58) |
| Save Map (Pop-up, HADB, TOSS) | "Save PopUp map" … in `SavedMaps` on `PopUp` …, `JPEG\|*.jpg` | the map as JPEG |
| DTC: Open / Save Callsign.ini File | "Open Callsign.ini File" / "Save Callsign.ini File" in `User\Config` on the pilot, `Callsign DTC File\|*.ini` | the cartridge, written directly (no backup) |
| DataCard: Get DTC File | the same "Open Callsign.ini File" window in `User\Config` (WDP's opened "Open TE.ini File" on a TE's `Mission.ini`, above) | the cartridge, into the DTC page and the card's boxes |
| Settings: Datacard directory, Browse | WDP's `FolderBrowserDialog` on the DataCards folder in use: Windows' folder picker on the PC's window, the folder window's **Select Folder** elsewhere (`PcFiles.folder`) | the setting only (`POST /api/files/planner`), for every device |
| DTC: Load / Backup (11 tabs) | "Open EWS backup files" / "Save EWS backup files" … in `Files\<part>` on the last file, `ews files (*.ews)` … | the part's sections as the cartridge holds them (D59) |
| DTC: Personal Save / Load | "Save Personal PPT files" / "Load Personal PPT files" in `Files\PPT\Personal`, `ppi files (*.ppi)` | `code range name` a line, as `SavePPT` writes it |
| DTC: Browse PPT.ini | "Browse PPT.ini file" in the campaign folder on `ppt`, `ini files (*.ini)` | read as `ReadPPT` reads it |
| Open mission…: Browse… | "Open Campaign or TE", the last save's folder, `all\|*.cam;*.tac;*.trn`, then each kind | opened from its theater's campaign folder, where BMS keeps its companion files |

`--wdpfilestest` checks every kind in-process through the PC's routes: each window's title, start and types, each file
byte for byte against what WDP's `WritePrivateProfileString` writes, files laid out as WDP writes them read back into
WDP's boxes, and Reload WX on the last save (Poor.fmap at Osan's own cell, a version 8 map, the save's own `.twx`).
- **The attack profile and the map.** With Pop-up, HADB or TOSS chosen on the card, the Delivery block prints that
  page's offsets and figures and `picMap` shows its map; with none, the flight's stretch of the theater map with the
  tankers on station drawn as WDP's main map draws them (`DrawMapTankerTracks`: the box 30,000 ft either side of the
  station leg, dark green, blue on the white map, dash-dot, "Copper4/60Y" and its window) and a JSTARS on station as
  its magenta mark. A click on the map turns WDP's white map on and off (`picMap_Click`, kept as Setup.ini keeps it);
  unlike WDP's picture box it can be looked into — the wheel or two fingers zoom about the pointer, a drag pans (and
  is never taken for the click), and **Fit** puts the whole picture back.
- **The plan pictures** on the Coordination Card offer the Pop-up, HADB or TOSS map as it is at that moment.

## The Performance page's Type and Loadout

- **Type stays open with a mission loaded.** WDP disables it once a mission is in (`fclsMain.cs` l.19590); the pilot
  asked to pick the jet. Any F-16 of WDP's list can be planned against the mission; the stores on the page go across
  to the new jet's hardpoints (the same hardpoint where it takes them, as many as it takes there, the rest wherever they
  fit — WDP's TypeChange empties the loadout), and picking the mission's own jet again brings its loadout back. A
  flight of another aircraft heads the list by its own name (D51).
- **The Loadout window is redesigned** (`PerformanceLoadoutWindow.kt`): WDP's frame (aircraft and seats, the jet's
  picture, the weights, Clear All; WDP's Ok and Cancel at the top right are removed for the picker's Cancel and Apply), and inside it a card under each hardpoint (hung from WDP's own number
  label) with the store, count, − / + and ✕. Where WDP's grid was, the **store picker**: a search box and the kinds
  the jet carries; every store it can carry grouped by kind (air-to-air, air-to-ground missiles, guided bombs, bombs,
  rockets, tanks, ECM, pods), one compact row each with its weight, drag index and how many a station takes, in as few
  columns as hold them; **a store, then a station** (the stations that take it lit on the jet, the others faded) or
  **a station, then a store** (only what it takes) hangs a full load, and a row's **+** adds one where it fits;
  **Mirror** on by default (1-9, 2-8, 3-7, 4-6, 5L-5R); Copy to flight; one line saying what is picked — the station
  with − / + and **Clear station**, or the store and the stations that take it — and what happened, refusals included;
  the totals always in sight with **Cancel** and **Apply** (WDP's Cancel and OK) on one bar. **By finger, or where the
  window is drawn too small** (a phone, a tablet), the window has one large **Choose stores** button and the picker
  opens over it at the device's own sizes (`WdpPanelWindow`), rows a finger's height, the stations as a strip of
  buttons at its top. The figures are WDP's WeightsDragsFuel with BMS's pylons and racks (D33); check
  `PerfLoadoutCheck` (own entry point; its `devices` part draws a PC, a tablet both ways and a phone).
- **OK asks WDP's question with a mission loaded** (`fclsLoadout.CloseForm`): "Loadout will not be changed for your
  mission, only on the datacard. Do you want to continue?" — Yes, No, Cancel, titled "Question", as WDP asks it. Yes
  hands the stores to the page and the DataCard's take-off figures; No and Cancel keep the window open on the stores as
  they are (WDP closes its window whatever the answer, its OK carrying `DialogResult.OK`, and the changes go with it);
  with no mission there is no question. A mission is a printed briefing or a save's flight (Open mission…), as WDP asks
  whenever `blnMissionLoaded` is set.
- **The seat's own fuel**: with a save's flight, the page takes that aircraft's starting fuel from the save
  (`fuel_initial[seat]`, as WDP's `SetFuel`), which is also what BMS's loadout screen adds into its gross weight.
- **Charts** opens the same chart window as the card's **C** buttons; the AGC button is hidden while the ground chart
  is on show, as each APC button is while its chart is.

## The Planner's shell and windows

WDP's own shell (`fclsMain`: menus, theater and mission selection, page switching) is not ported; the Planner has one
of its own, the same on every page (`WdpScreen.kt`):

- **Row A**, the toolbar: WDP's page tabs (Pop-up, HADB and TOSS as one **Attack** tab, whose three pages are a slim
  rail beside the page that reopens on the one last used; **ATO Targets** last, below), then **Open mission…**, **Save to DTC**
  (with the count of edits not saved; its menu: Re-read DTC from BMS, Save to DTC and populate), **Populate from
  Planner**, **Upd Kneeboard**, **Steps**, **Guide**, **Options** (WDP's Options menu, below) and **Full window**.
  **Row B**, the identity strip: theater › file or BMS briefing › package › flight › seat, and where the steerpoints
  came from; a press opens the flight picker (with nothing open, the Steps). Then the Mission section's state
  ("Mission: populated 22:51", "not populated yet"; never "changed since": the pilot asked not to be told) and "WDP by
  Falcas", both words rather than buttons.
  On a phone the two fold into one row with a ⋮ menu; held sideways the Planner fills the screen.
- **One place per action** (1.3.8): no action is offered twice in the shell — not in row B, the ⋮ menu, a page or a
  note. Back to BMS briefing is in Open mission… and Pick a flight only; WDP's own cartridge buttons on the card and
  the DTC page are the one exception, kept because pilots look for them there.
- **Steps** (`PlannerSteps.kt`, not in WDP): the evening from BMS to the cockpit in ten lines, beside the page (a sheet
  on a phone), with the step the pilot is on lit from what the app knows and the BMS steps ticked by hand.
- **What replaced what**: File > Open → **Open mission…** (every theater's saves on the PC, from any device, newest
  first, BMS's own files folded away and protected, D41, D42); `fclsSelection` and `fclsSide` → **Pick a flight**
  (D44); Print, Print Preview and Upd Kneeboard → **Upd Kneeboard** in the toolbar (D36, D47; the card's own Upd Kneeboard did the same and is taken off the page, `DataCardWiring.REMOVED`), with WDP's Browse Picture as its **Browse picture…** and "Selected Picture" as each half's **Picture…**; WDP's cartridge buttons are joined by
  **Save to DTC** and its menu (the DTC page's Save DTC stays on its five tabs, the other nine are From mission…; the
  card keeps Get DTC File, Save DTC and the lamp, all through the same save); Different Flight stays on the card and is
  also the identity strip; WDP's menu bar (File, Options, Settings, Help) → the toolbar's **Options** menu
  (`ShellMenu.OPTIONS`, `PlannerShell.OPTIONS`; on a phone under an Options heading in the ⋮ menu): Options' page
  list is the tab strip, **Settings…** is WDP's Settings window (`PlannerSettings`), and **About WDP (Falcas)** — Help >
  About — opens the **Guide**'s last page, the credit; Options > ATO Target List and the flight selection's ATO
  Target List (`fclsAtoTargetList`) → the Planner's **ATO Targets** page, the last tab (`WdpPage.ATO`, a page of the
  app's own like the Map; `AtoTargetListWindow.kt`: `AtoTargetsPage`, `AtoTargetList.open` turns to it;
  `/api/campaign/atotargets`, D67): the same two lists and twelve columns, with the real latitudes and longitudes.
- **Populate from Planner** (the Mission section in WDP mode) takes the flight of the save open in the Planner, the
  cartridge **as saved** and the attack; edits not saved are asked about first. It replaced the Send to Mission button
  of the first 1.3.8 builds, which laid a plan over BMS's briefing: in WDP mode the Mission section is the plan.
- **The Planner needs the PC**: on a phone, a tablet or in a browser that is not linked, it shows a note with Setup
  and the Guide instead of the pages, and the pages come back with the link.
- **The Guide** (`PlannerGuideText.kt`): the evening from BMS to the cockpit (the Steps' ten lines in short, then step
  by step), a card per page, and what to do when something is missing; it opens itself the first time and at the page
  on show afterwards.

## The Map page: what the HSD will show

A page of the app's own before the DTC, where WDP's toolbar has its MAP button (`fclsMain.cs` l.7066: Mission Brief,
DataCard, Coordination Card, MAP, DTC, Performance, PopUp, HADB, TOSS), with no WDP layout behind it (`WdpPage.MAP`,
`native`): the DTC page's
cartridge on the theater map the way the HSD will show it, unsaved edits included — the flight plan (BMS's route
where the cartridge leaves a slot empty, as the jet flies it in 4.38.1), the precision and open steerpoints, lines
L1-L4, the PPTs with type and ring, the attack's nav offsets laid out from their steerpoints as the jet lays them,
the bullseye — beside what the mission knows and the cartridge does not carry: the tanker and AWACS tracks, the
spotted air-defence sites, the flight's fields, an attack not saved yet. A tap offers what an item can become (a line,
a PPT of the theater's own type, a steerpoint, a target, a TACAN or ILS) and does it through the DTC page's own calls,
so it counts in Save to DTC and asks before replacing. **HSD preview** draws it north up on black at the HSD's ranges.

**WDP's MAP tab, brought across** (1.3.8). The page is laid out as WDP's MAP tab is: the map, and beside it one panel of
options in WDP's order — Map (style, label ink, grid, bullseye with Extra lines and Cursor bullseye), Airfields &
navaids (airports, airstrips, Name & data / ICAO labels, VORTAC with Max range), Flight plan (WDP's "Viewing DTC file",
here *what the jet flies* / cartridge / mission file, with its red/green saved lamp; STPT, Numbers, Trk/Dist with its
minimum, PPT, PPT numbers, Lines, Nav offsets, Tanker tracks), Package (the other flights' routes), SAMs & intel (JSTAR
lamp and area, the Types tabs Hostile | Own side, Threats — the mission's: the sites the printed briefing names when
the flight open is the one BMS printed, else the spotted ones whose ring reaches the route, as every map of the mission
draws them — with **All known SAMs** (WDP's own Threats layer: every site the side has seen; off), Search and S Large,
No radar, own-side SAMs and search, ships, Circle fill, Auto PPT, Clear PPT, the Colours legend), Tools (Measure, Save Map, Fit) and Lines (the four lines'
names, Change Area…, Clear Lines). It is drawn in the app's look (`WdpMapOptions.kt`), not extracted from WDP's
designer code; on a tablet the panel is a side sheet and on a phone a full-screen sheet (**Options**). Over the map:
WDP's sunken labels as one readout strip (X/Y, lat/long, the ground from BMS's height map, the variation from BMS's own
`MagVarMap_*.csv`, the bullseye), its khaki info label as a box beside the resting mouse (`hoverFacts`, the same
facts a card starts with), its right-click add menu as the point card, and the measure line with its track (true and
magnetic) and distance (`WdpMapCursor.kt`). The layers are `WdpMapLayers.kt`; the pure rules (WDP's threat, no-radar,
own-side selections, Auto PPT, the orbit box, the graticule, the leg labels) are `PlannerIntel.kt`. The save's
picture is the PC's `/api/campaign/mapintel` (every unit with its side, how it was seen, its radar, whether it moves
or is destroyed; the search radars; who holds each airfield now, from the save's `.obd` objective changes; JSTARS) and
`/api/campaign/magvar`; an older PC answers 404 and the page falls back on the flight's spotted air defences. The
search-radar ring is 25 nm (75 with S Large): WDP's own figure, since BMS publishes no search-radar range and 4.38.1's
`Ppt.ini` has no search type. Ships are drawn with NATO-style symbols (`UnitSymbols.kt`), although WDP 3.7.24 only
wrote its twenty symbol routines and never calls them; the ground-unit layers the 1.3.8 test builds had are gone (the
map was too full and the tablet too slow), and the PC sends no enemy unit or site the side has not seen. By finger the readout follows the last tap
(a crosshair marks it) and a right-click is a tap on empty map, or **A point here instead…** on a card. Every map
edit — the PPT window from a card (**Add as PPT…**, **Change…**, **PPT here…**), Auto PPT, Clear PPT, Clear Lines,
Change Area, an orbit box as a line — goes through `DtcWiring`, so it counts in Save to DTC. Tooltips: `tips/wdpmap.json`.
**It draws in the app's look by default**: the airfields, steerpoints and legs, lines, PPT rings, air defences, tanker
and AWACS boxes and labels are the Mission map's and the VR map board's own drawing (`MissionMapMarks.kt`), so one
mission looks the same on all three; WDP's look — STPT 1 and the IP as squares, tasked points as triangles, airfields
filled with the side holding them, the bullseye's 30 nm rings, the side's tankers dash-dot with their times — is the
option **WDP's look (Falcas)**, off. Its start state differs from WDP's in three more places, for the same reason:
airstrips on, airfield labels Off (ICAO and Name & data remain), Circle fill 20 (WDP's 50). The HSD preview keeps the
HSD's colours. Check `--wdpmaptest` (the intel through the PC's routes, hover and card for every kind of pick, Measure, Auto PPT,
Clear PPT and Clear Lines asking, a mouse resting, a right-click, Save Map, and pictures at a PC's, a tablet's and a
phone's size).

## What is left out on purpose

WDP's **Threats** and **Munition** pages are reference lists; the app already has both, as the Threat Guide and the
Arsenal, built from BMS's own files. WDP's shell (main window, Allied Force mode, its settings, registry and user-name
windows) has no place in a tab of an app that is 4.38.1 only; its theater and mission selection is Open mission… and
Pick a flight. WDP's MAP tab is ported onto the app's own maps (the Map page, above), and its layout is not extracted.
Left out of it: IATA labels (BMS publishes no IATA code; WDP's come from its own `Airports.xml`); Save Bullseye / Set
Bullseye (it writes the `.cam` in place, WDP's BMS build hides it, and the 4.38.1 cartridge holds no bullseye
position); Map Building mode and the 4096 px setting (WDP's own picture making; the grid's fine lines come by zoom);
Save DTC on the page (the shell's Save to DTC; in a campaign WDP's zeroed BMS's route, D46); WDP's **Cheat SAM**
layers (No Spot, On Move, InActive) and their `Setup.ini` gate — no cheating: nothing the flight's side has not seen is
drawn, offered or even sent to a device; and Change Area's named areas from `Areas.xml` (Korea, Aegean and Balkans only) — the
mission's own areas and the orbit boxes are offered instead, as the DTC page already does. Its other layouts and
pictures are not extracted (`wdpdropped.mjs`).

## What the rest of the port involved

Decompiled, the program is about 400,000 lines, most of it not hand-written:

| Part | Lines | What it really is |
|---|---|---|
| `clsGE100`, `clsGE129`, `clsPW200`, `clsPW220`, `clsPW229` | ~90,000 | Engine performance: tables with their interpolation unrolled into thousands of `else if` branches. Translated by `wdpengines.mjs`, not re-derived. |
| `cntDTC`, `cntDataCard` | ~60,000 | Two big forms. Mostly VB `WithEvents` plumbing — about 17 generated lines per control, and there are ~1,900 controls. |
| `fclsMain` | ~19,000 | The shell: menus, theater and mission selection, page switching. Not ported: the Planner's own toolbar and windows do its jobs. |
| `cntTOSS`, `cntPopUp`, `cntHADB`, `cntPerformance` | ~15,000 | **The planning itself.** The part worth the most, and the part checked hardest. |
| `clsLoadDTC`, `clsSaveDTC`, `clsSortData`, `clsCoordinates`, `clsAircraftData` | ~10,000 | Reading and writing BMS's own files, and coordinate conversion. Where the app already does this its own way, the app's way is used. |

## Credit

The About page names Falcas and links to Weapon Delivery Planner. The Planner's identity strip says "WDP by Falcas" under
every page, and the Guide's last page is the credit.
That is not a formality: someone reading a number off a tablet should know whose work produced it.

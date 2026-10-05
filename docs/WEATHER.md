# Falcon BMS weather: the file format, and how BMS Companion writes it

This note is the reference for the **Weather** section: how BMS decides a mission's weather and who reads it when,
what a `.fmap` holds, what is known and what is not, how the app generates weather, and the rules that make
everything the app writes undoable.

## Where BMS keeps weather

**BMS's weather belongs to each mission, not to the theater.** Every campaign, TE and training picks one of BMS's
**three weather models** on its Weather screen (the Weather icon → the **WEATHER** tab → **Weather Model**; in a TE the
icon is in the Mission Schedule's icon column):

- **Probabilistic** (1) and **Deterministic** (2) read no map at all. The whole theater is in one of four weather
  **types** at a time — Sunny, Fair, Poor, Inclement — and each type's wind, visibility, cloud, contrails, temperature
  and pressure come from a table **inside the mission's own `.twx`**. Probabilistic draws a new type every change
  interval from four percentages; Deterministic has a starting type and a schedule of changes. On the development
  install 23 of 24 campaign saves were Deterministic (the KTO 80s one Probabilistic), every stock TE is Deterministic,
  and five trainings (TR_BMS_08, 12, 14, 21, 22) fly a map.
- **Map Model** (3) flies a map: the `.fmap` picked from the list, which is every `.fmap` in the theater's campaign
  folder. The pick loads at once (BMS says MAP LOADED); when the weather is saved, BMS writes **its own copy**,
  `<save>.fmap`, and the mission flies that from then on — so writing the picked map again later does not change a
  mission that already saved it.

"Sunny, Fair, Poor, Inclement" therefore means two things: the four **types** (tables in each `.twx`), and four
**ready-made maps** of the same names in the Map Model list. The files, in `Data/<theater>/Campaign/` (the theater
definition's `campaigndir`):

| File | What it is |
|---|---|
| `<save>.twx`, beside each save | the mission's weather: model, current type, the four types' tables, the change schedule, MAPS AUTO UPDATE. Written with **every campaign save** (in the same millisecond as the `.cam`: a snapshot at the save's clock) and by a TE's **SAVE WTH**, which works only once the TE has been saved and named (a later TE save does not necessarily rewrite it). A 4.38.1 `.cam`/`.tac`/`.trn` carries no weather part of its own. `Auto Save.cam` and `Auto Save.tac` share one `Auto Save.twx`. The app reads it (below) and never writes one |
| `<save>.fmap`, beside a Map Model save | BMS's own copy of the map picked (version 8): what that mission flies |
| `SUNNY.fmap`, `FAIR.fmap`, `POOR.fmap`, `INCLEMENT.fmap` | four **ready-made maps** in the Map Model list, one type in every cell, drifting toward 45° at 20 — not the models, and not where a mission's four types are set. Byte-identical in the 16 theaters that have them; Hellas, Hellas WCP and LHTO have none. The app no longer writes these: it copies them once into the backup folder (below), and **Restore them** puts back only a map its own record says it wrote |
| any other `*.fmap` | BMS lists **every** `.fmap` in the folder under Weather → Map Model (Korea has 15: the four, `Save0-5`, `TR_BMS_*`). A generated map is written as a new one, `BMSC <name>.fmap`, and BMS's own stay as they are |
| `WeatherMapsUpdates/<DHHMM>.fmap` | BMS's **Maps Auto Update** folder (Technical Manual 13.6), used only by a Map Model mission with MAPS AUTO UPDATE on: BMS loads `day*10000 + hour*100 + minute` `.fmap` when the clock reaches it (`30509.fmap` at day 3, 05:09), disregards maps less than 55 minutes apart, and wants the first map in the campaign folder itself. The folder is the theater's, shared by every such mission there and never copied per save. The Korea family ships 741 hourly maps (D1 01:00 to D31 21:00, beside a previews folder), Balkans and EF2000 334, LHTO 380, LKTO 373, LKTO+ 385; Israel and the Falklands only a `Start Maps Auto Update.fmap` in the campaign folder, Hellas and Hellas WCP none. A generated **series** writes here |
| `Weather.b` | BMS's briefing script for the printed briefing's WEATHER block: Take Off, Target Area and Landing, each with situation, wind, visibility, temperature, cloud base and contrails — and no QNH row. Not touched |

Every 4.38.1 theater's grid is 59 x 59 (Korea, Balkans, EF2000 BTO, Falklands, Israel, Korea 2012 x6, Korea TvT, KTO 80s,
LKTO, OFMKTO), but nothing assumes it: a generated map takes its size from the theater's own ready-made map — else any
version 5 map in the campaign folder or its `WeatherMapsUpdates`, else the built-in 59 x 59 (`Fmap.blank`), which is how
Hellas, Hellas WCP and LHTO are offered — and is refused if the two disagree. **The theaters are BMS's own theater
definitions'** (`WeatherStore.places`, through `Theaters`: `theater.lst` + each `.tdf`'s `campaigndir`), in
`theater.lst` order, named as BMS names them and keyed by the app's theater id — so the six Korea 2012 theaters (two
levels down) and LKTO's `Campaign+` are there too, and a new theater needs no code; a theater that cannot be written
(its campaign folder missing from the install, or kept in another theater's) is listed greyed with the reason.
"Korea (the base theater)" is "Korea KTO" now; the old ids (`korea-the-base-theater`, `lkto`) are still accepted, and a
Data folder with no theater definitions (a bare copy, as `--wxtest` uses) is scanned the old way. Each theater keeps its
backup beside its own maps — for Korea 2012, `Data/Add-On Korea 2012/Campaign/<theater>/Campaign/BMS Companion Backup`.

### How a map reaches a mission

Writing a map changes nothing BMS flies until the pilot picks it for that mission and saves the weather. In BMS, with
the campaign or TE loaded (a TE must have been saved once, or BMS says SAVE TE FIRST):

1. Weather → **WEATHER** tab → **Weather Model: Map Model**.
2. Click **`BMSC <name>`**; it loads at once.
3. **MAPS AUTO UPDATE**: on for a series, **off for a single map** — with it on, BMS's own hourly maps replace it
   within the hour.
4. **SAVE WTH** (a TE), then save the TE or the campaign. BMS then writes `<save>.twx` (model 3) and its own copy,
   `<save>.fmap`.

A campaign normally flies BMS's own four-type weather until this is done: on the development install every campaign saved from
Korea's map-model starts came out Deterministic, reading no map. Whether a campaign keeps Map Model through its next
save and Auto Save, and whether SAVE WTH alone writes a campaign's `.twx`, are not known (below), so save the campaign
as well. In multiplayer the host's `.twx` and `.fmap` go to every client: generate and pick the weather on the host.
The Weather tab's Save panel ends with these steps.

### Weather changes by itself

What a briefing or a plan shows is a forecast. A map drifts across the theater by the heading and speed in its
header, wrapping at the edges (a field can go from Fair to Poor in a flight), and with auto update on it is replaced
at each update map's time. A Probabilistic or Deterministic mission changes type: the development install's campaigns
changed type within about an hour of a save (Korea Fair at D1 01:34, Sunny at 02:30; Hellas WCP Fair at 04:21, Sunny at
05:46), which is not the schedule stored in their `.twx` (all Sunny, at one-day steps) — the rule BMS follows in a
campaign is not known. A TE changes at its schedule's times (the stock ones about a day in).

### Who reads the weather, and when

Every reader takes a snapshot, which decides the order: **choose the weather and save it in BMS before PRINT and
before the Planner's Open mission…**.

- **PRINT**: `Weather.b` words the weather BMS has loaded at that moment. In EZBoards mode the Mission section (the
  Briefing page, the Dashboard's WEATHER card, the VR boards) and EZBoards read only that `briefing.txt`.
- **The Planner's card** (DataCard, ATIS, weather list, Force QNH, the Performance page's take-off weather): a file
  Reload WX picked, until another mission; else the save's own `.twx`, read again at every Open mission and Pick a
  flight from the save's own theater, when no printed briefing of the same flight gives weather or when the `.twx` was
  saved after that PRINT with other weather; else the printed briefing, with the `.twx`'s QNH (WDP-PORT.md D68).
- **WDP mode's Populate from Planner**: when the snapshot carries BMS's printed briefing of the same flight, its
  weather, unless the save's `.twx` was saved after that PRINT with other weather (the card's rule); else the save's
  `.twx` read the same way into the snapshot's weather table (`SaveWeather.kt`), with the QNH. The PC notes the
  file's time (`Populated.changed`), but no screen says it changed: Populate again after saving new weather.
- **Upd Kneeboard**: the card's weather as it is when pressed.

A `.twx` becomes weather as the Planner reads it: under Probabilistic or Deterministic, the table of the type the file
is in (a wind direction BMS picks itself is not in the file: VRB, and a calm take-off); under Map Model, the newest
update map named at or before the save's clock when auto update is on, else `<save>.fmap`, at each field's own cell,
with no drift. Either way it is the weather at the save's clock. WDP 3.7.24 cannot read a version 8 `.twx` at all
(WDP-PORT.md D55).

**The `.twx` format** (version 8, 728 bytes, as BMS 4.38 writes it) is laid out in `desktop/.../bridge/Twx.kt`: the
model at byte 212 (1 Probabilistic, 2 Deterministic, 3 Map Model), the current type **counted from 0** (0 Sunny … 3
Inclement; maps and older `.twx` count 1-4), map updates, the four probabilities, the deterministic schedule at 244,
the wind heading model at 292 (1 = BMS picks the direction and the file holds none), then per type one wind speed, the
visibility, cloud layers and contrails, and **one** temperature and **one** pressure (version 7 had three, for night,
dawn and day).

## The `.fmap` format

A 44-byte header, then the body: **one array per property**, each holding one value per grid cell, laid out
field-major rather than cell-major. That is why a hex dump of `SUNNY.fmap` opens with thousands of `01 00 00 00` —
that is the whole weather-type array, not the first cell. Little-endian throughout.

The header and every array are named by **WeatherGen's own writer** (`src/vmt/fmap.cljc` in Tyrant's Virtual Mission
Tools 0.63, Craig Andera, MIT), which BMS has loaded for years. Before that source was read, this note listed the
header's fourth int, its float and two of its altitudes, and arrays 24 and 26, as open or wrongly named.

```
0   int    version            5 (stock and campaign maps; BMS's own Save0-5 and TR_BMS_* are 8, see below)
4   int    cells across       59
8   int    cells down         59
12  int    map move heading   45 stock; 0-356 across the campaign maps
16  float  map move speed     20.0 stock; 11.5-58.9 across the campaign maps. WeatherGen writes knots here
20  int    high stratus, sunny/fair ft        35000 stock (WeatherGen's default 43300)
24  int    high stratus, poor/inclement ft    30000 stock (WeatherGen's default 33000)
28  int[4] contrails, sunny/fair/poor/inclement ft   34000, 28000, 25000, 20000 stock and WeatherGen default
44  the body: 28 arrays of 59 x 59 four-byte values
```

`(389916 - 44) / (59 * 59) = 112` bytes a cell, `112 / 4 = 28` arrays. Cells run **row-major from the north-west
corner** (`index = y * cols + x`, x east, y south): WeatherGen's weather space has its origin at the upper left with y
running south (`coordinates.cljc`), which independently confirms the order the app already used. BMS's own data
supports it too: in its TFR training map (`TR_BMS_08_TFR`) Gunsan's cell falls in the map's only block of southerly
wind, matching the Training Manual's "RWY 18 … wind from the south" for that lesson. Not yet checked in a flight.

| Array | SUNNY | FAIR | POOR | INCLEMENT | What it is |
|---|---|---|---|---|---|
| 0 | 1 | 2 | 3 | 4 | weather type, 1-4 in the order of BMS's four types (a version 8 `.twx` counts them from 0) |
| 1 | 1030 | 1025 | 1010 | 1005 | pressure, millibars (WeatherGen calls it "mmhg" but its `inhg->mmhg` gives mb: 29.92 inHg → 1013) |
| 2 | 28 | 25 | 10 | 8 | temperature, °C |
| 3–12 | 9.26→34.26 | 14.8→39.8 | 27.8→69.5 | 37.0→103.7 | **wind speed**, ten levels per cell, km/h |
| 13–22 | 180→216 | same | same | same | **wind direction**, ten levels per cell, degrees |
| 23 | 5000 | 5000 | 4000 | 3000 | cloud base, feet MSL |
| 24 | 5 | 5 | 5 | 5 | **cloud coverage code**: 0 none, 1 FEW, 5 SCT, 9 BKN, 13 OVC |
| 25 | 3.0 | 3.0 | 3.0 | 3.0 | cumulus **size**, a *float* 0 (congestus) to 5 (humilis) |
| 26 | 0 | 0 | 0 | 0 | **towering cumulus**, 0 or 1 |
| 27 | 60.96 | 48.77 | 21.34 | 7.62 | visibility, kilometres |

The ten wind levels are at **0, 3,000, 6,000, 9,000, 12,000, 18,000, 24,000, 30,000, 40,000 and 50,000 ft**
(WeatherGen's `wind-data`); this note used to say the file does not give them, and the file indeed does not — the
writer that BMS accepts does.

### Cloud coverage is a code, not oktas

Array 24 is not a count of eighths. WeatherGen writes 0 / 1 / 5 / 9 / 13 for none / FEW / SCT / BKN / OVC, and the
files agree: the 741 campaign maps in `WeatherMapsUpdates` hold only 1, 5 and 9, and BMS's own training maps
(`TR_BMS_14_Maverick_Advanced`) add 13 on inclement cells. Oktas written here, clamped to 0–9, would put "overcast"
(8) *below* BMS's broken (9), and real overcast could not be written at all.

The app still **speaks oktas** — the Technical Manual's unit (FEW 1-2, SCT 3-4, BKN 5-7, OVC 8) and what `Wx.cover`
holds — and the PC converts at the file (`WxCover`): oktas to the category's code on the way in, and a code to the
middle of its category on the way out. A value between the codes (a map an older BMS Companion wrote, or the single
6 in `TR_BMS_08_TFR`) reads as the code at or below it, since the codes are four apart.

Two rules from the Technical Manual ("Cloud Coverage") are applied to everything written: a **sunny** cell is clear
whatever the code says, and a **poor or inclement** cell is broken at minimum — BMS's own map editor refuses to
paint FEW or SCT on one, and none of BMS's own maps has one. WeatherGen's model lets those two types range from SCT;
the model's answer is kept (it is checked against WeatherGen), and only the byte BMS reads is lifted to BKN.

### Array 26 is towering cumulus

The app called it "shower". WeatherGen names it towering cumulus, and the data agrees: in the campaign maps 18,416
of its 20,228 set cells are poor weather with broken cloud — towering-cumulus weather, not fair-weather showers. The
Technical Manual describes both a Towering Cumulus and a Shower tab in Weather Commander; the version 5 format has
room for one flag, and it is this one. `Wx.towering` carries it, and `Wx.shower` is kept as its older name so an
older client still reads and writes the flag (the PC reads it into both and writes `towering || shower`).

### Version 8

BMS's own `Save0-5.fmap`, the `TR_BMS_*` training maps and the copy BMS keeps beside a Map Model save (`<save>.fmap`)
are version 8 and 417,764 bytes: the same header, and 30 arrays instead of 28. Arrays 0-26 (type, pressure,
temperature, the wind, cloud base, cover, size, towering) are where version 5 has them; **array 27 is a shower flag
(0/1), the visibility moves to array 28, and array 29 is a fog layer height** — WDP's `ReadFMapDataNew` reads them so
(HasShowerCumulus from version 7, FogEndBelowLayerMapData after it, FogLayerZ from version 8), and the files agree:
array 27 of `Save0.fmap` holds only 1, array 28 kilometres up to 59.98, array 29 the cloud base again.
`Fmap.visibilityKm` takes the visibility from the right array (reading array 27 gave every version 8 cell 0 km;
WDP-PORT.md D57), so the page and the Planner read these maps. Nothing is written from one: every write starts from a
version 5 map, which is what the ready-made maps and WeatherGen both are.

### Units, and the one still argued

Temperature, cloud base, pressure and visibility are the figures BMS itself prints in a briefing, in the same units
(`°C`, `ft MSL`, `mb`, `km`).

**Wind is written in km/h**, and WeatherGen disagrees: it writes its knots unchanged. The evidence for km/h is that
dividing by 1.852 turns BMS's own four stock maps into round knots on every level — sunny 5 kt at the surface rising
to 18.5, fair 8 → 21.5, poor 15 → 37.5, inclement 20 → 56 — and round numbers on all four is not a coincidence. The
campaign maps' strongest surface wind is 42.4 in file units, which is 23 kt as km/h and 42 kt as knots. This is noted,
not followed; the check that would settle it is a flight: write a uniform 20 kt map, fly it, and compare the ATIS or
briefing wind. If the file is knots, one constant (`Fmap.KMH_PER_KT`) changes. **NOT TESTED in the sim.**

The header's move speed is written in knots, as WeatherGen writes it, and its heading is the direction the pattern
moves toward (WeatherGen's movement heading). Neither is checked in the sim. **NOT TESTED.**

## The wind is not ten grids

Everything else in the body is one value per cell, field after field. **The wind is not.** It is a block of
**ten levels per cell, cell after cell**, occupying the span of ten fields:

```
wind speed  value index = 3  * cells + cell * 10 + level
wind dir    value index = 13 * cells + cell * 10 + level
```

Reading it as ten separate grids returns a value that cycles through the ten levels every ten cells. **Every
stock map hides this completely**, because each of their cells holds the same ramp — so the wrong reader gives
the right answer for the whole of `SUNNY.fmap` and scrambles the wind across the theater the moment anything is
written. It was caught by noticing that Korea's `FAIR.fmap` had exactly ten distinct values in "array 3", and
that they were the ten level values; a campaign map confirms it, each cell carrying its own profile veering with
altitude (cell 0: 21° at the surface backing to 323° aloft). WeatherGen's writer lays it out the same way.

`--wxtest` reads the wind back **at five cells spread across the map**, not just the first, and every cell of a
generated map. That is the check that would have caught it, and it is why it is written that way.

## Cloud size is a float

Array 25 is a `float`, not an integer: the campaign maps carry 3.34 and 4.94. Read as an integer it comes back
as 1,077,936,128, which is the bit pattern of `3.0f` and an obvious tell once you have seen it once.

## Generated weather (WeatherGen's model)

The generator is **WeatherGen's model, ported function for function** into shared Kotlin
(`app/.../data/weather/`: `WxNoise.kt` for `math.cljc`, `WxModel.kt` for `model.cljc`, `WxTime.kt` for `time.cljc`,
`WxParams.kt` for the parameters, defaults, random buttons and colours from `ui.cljs`), each file carrying the MIT
notice "Copyright (c) 2017 Craig Andera". It is pure `kotlin.*`, so the phone, the browser and the PC all run it:
the page previews locally and sends only the parameters (`WxGenParams`, a few hundred bytes); the PC runs the same
model to build the file. The grid never crosses the network.

In one paragraph: a checkerboard of highs and lows is sampled through a warp of smooth noise that evolves with time,
giving one number per cell, the **value**. The four types are bands of that value in proportion to their weights,
crossfaded at each edge; pressure is the value scaled into the theater's range; wind runs along the pattern's contours
near a high or low and follows the prevailing wind between them, turning toward it with height; temperature, cloud
and visibility are read off differently scaled copies of the same pattern, so they vary together the way a front
does. Override regions (centre, radius, falloff, strength, optional fade in/out over time) pull any attribute toward
their own value, and a type override pulls the value itself, so the weather around a storm crossfades into it.

**Checked against the program, not against a reading of it.** `tools/wxref/wxref.clj` loads WeatherGen's own
`model.cljc` into Clojure on the JVM and writes fourteen whole grids — seeds 1234, 42 and 4711 at three times; a tuned
set with two overrides (one animated) at three times; two runs of WeatherGen's own `step` — and two six-step
forecasts. `--wxgentest` asks the port for the same and demands the same answers. Every number is handed to the
reference as a double, because WeatherGen runs as ClojureScript where every number is one; on the JVM `(/ 1003 10)`
would otherwise be an exact ratio and the reference would be more exact than the program. Result: **48,748 rows, no
mismatch in any column, largest difference 0** — the port is bit-exact on the JVM (a browser's sine may differ from
the JVM's in the last bit; that is invisible). One grid takes about 40 ms.

**Fixed, and only outside the check.** WeatherGen turns each level of wind aloft toward the prevailing wind, and an
override's wind toward its own, with a straight average of two headings, so a ground wind from 003 and a prevailing
wind from 325 average through 180 rather than through north. In the reference run, seed 1234 at day 1 05:00, cell
31,0 veers `003 035 067 099 131 164 196 228 260 292` up the column — a wind that swings right round the compass. The
app averages the short way round (`003 359 355 351 … 328`); `WxModel.weather(exact = true)` keeps WeatherGen's
arithmetic for the check, and `--wxgentest` reports what the fix changes (7,902 of 48,734 cells in the reference
scenarios, nothing but headings). Two of WeatherGen's Randomize buttons are also corrected (`WxRandom`): its random
temperatures ignore the sorted offsets they draw (`(map #(+ temp-mean-i))`), and its first level of random wind aloft
runs backwards (a "to" of 1–5 kt under a "from" of up to 10). Its pressure colour ramp draws nothing below 28.5 inHg,
which its own default minimum of 28 reaches; the app's carries on at the ends.

### A map, and a series

- **A map** is written as `Campaign/BMSC <name>.fmap`, a new file BMS lists under Map Model beside its own. The file
  is built whole: the version and the grid's two sides come from the theater's ready-made map (else another version 5
  map there or in its `WeatherMapsUpdates`, else the built-in 59 x 59); every array and every header field comes from
  the model (movement, stratus and contrails from `WxGenParams.movement` and `.clouds`).
- **A series** is what WeatherGen's "Save mission weather files" writes: the first map as `BMSC <name>.fmap` (the one
  to pick under Map Model) and one `WeatherMapsUpdates/<DHHMM>.fmap` every step from the start to the end. As in
  WeatherGen, each map is the pattern **at its time** — it evolves, it is not translated — and each header carries the
  movement heading and speed, so BMS drifts each map itself between updates. BMS disregards update maps less than
  **55 minutes** apart (Technical Manual 13.6), so a shorter step is refused; a series is limited to 240 maps.
- **A series and BMS's own update maps share the folder.** A series replaces only BMS's maps of the same names (each
  copied aside first); BMS's own hourly maps before the series, after it, and between steps that are not on the hour
  stay, and keep loading. So the series starts, by default, on the hour, and the Save panel's **Mission clock** row
  starts it at the hour of the mission's own clock (the save open in the Planner, else the printed briefing's save, else
  a time typed); a start or a step off the hour gets a warning, and a 60-minute step keeps a series on BMS's hourly
  names. The panel says how many update maps BMS has of its own in the theater and the times they span.
- In BMS: the steps under *How a map reaches a mission* (Map Model, pick `BMSC <name>`, MAPS AUTO UPDATE on for a series
  and off for one map, SAVE WTH, save the TE or campaign). Update maps in that folder apply to **any** mission flown in
  that theater with MAPS AUTO UPDATE on.

WeatherGen also writes a `.twx`; the app does not. BMS writes the `.twx` itself, with SAVE WTH in a TE and with every
campaign save, and WeatherGen's own template admits "most of the below doesn't work".

## The page

The Weather tab is the generator and nothing else. Above it is one row, the **theater** selector: every theater BMS
defines (one that cannot be written listed greyed, with the reason), opening on the one Falcon BMS is running (marked
"running"); with no PC, the theater the app is set to, so the preview still works. The map under the weather is always
the theater being edited, which need not be the one being flown. The Save panel ends with the steps in BMS (*How a map
reaches a mission*).

BMS's four ready-made maps are never written. (An early development version of this page edited them in place, one
weather per painted area.) The PC still reports, from the disk alone, whether each of the four differs from its
backed-up original, and from the app's own record (`bms-companion-weather.txt`) whether the app is why
(`WxModel.edited`): while one is, one line under the theater row says which and offers **Restore them**, which puts
back each map the app wrote. A map that differs **without** that record — a BMS update that shipped new maps, or
another tool — reads `<map> differs from the copy taken on <date> (a BMS update?)` (`WxModel.differs`) and offers
**Refresh the backup** instead (`/api/weather/refreshbackup`): a new copy of today's map, the earlier copy moved into
`BMS Companion Backup/Older copies/<time>/`, because Restore would put the older map back over BMS's newer one. When
they are BMS's own and match the copy, nothing is shown.

**Each theater keeps its own settings.** The generator's settings used to be one set for every theater, so switching
theater laid the same weather over the new map — WeatherGen's 59 x 59 grid is the same whatever it is laid over, which
made the switch look as if it did nothing. Now each theater opens with its own, kept on the device as they change
(`wx_gen_params@<theater id>`); a theater with none opens with the settings of the map the PC last saved for it, else
the pre-1.3.8 shared settings (taken over once), else a weather of its own (a seed of its own, `seedFor`). The line
beside the theater picker says which. The PC keeps the settings each generated map was made from, in
`BMS Companion Backup/Generated settings/BMSC <name>.json` (BMS never reads them; they go with their map), and **Open**
beside each map under *Written by this app* brings them back (`GET /api/weather/params`).

**Pick several.** Shift- or Ctrl-click region numbers (or cells) on the map, turn on **Select several** on a touch
screen, or tick the boxes in the region list. The panel then changes every picked region at once, and a setting they
disagree on reads "mixed" until it is set. Picked regions drag together by any one centre. Several picked cells are
summed up (types, ranges), and **Cover them with a region** gives them one weather. A Shift/Ctrl/Meta click is taken
before the map sees it, so the map's own tap and double-tap zoom never do; a plain click stays the map's.

## Reading the map

How the map is drawn is set in the **Display** panel and remembered between launches (`WxGenLook`): the cells
coloured by type, pressure or temperature (or not at all) with an opacity slider; an overlay of wind barbs at any of
the ten levels, cloud cover, cloud base, visibility, pressure or temperature; pressure in inHg or mb; and two switches.

**Grid** draws the 59 × 59 cells BMS actually stores, with a heavier line every ten, so a cell can be counted to. It
is off by default, and suppressed once a cell is under three pixels wide, where it would be a grey wash rather than a
grid.

**BMS 2D** draws the theater in the app's light chart style — light land, blue water, dark coastlines — which is
what BMS's own 2D planning map looks like, so weather seen here and the same theater seen in BMS read alike. It is
a style for this map alone (`TheaterMap(style = …)` → `drawMapBase(styleOverride)`): the mission map keeps whatever
the pilot chose under ☰ → Map, because a tool has no business changing a setting the pilot made somewhere else.

The legend over the top-left corner is the key for the chosen colouring — the four weather types in WeatherGen's
colours, or the pressure or temperature ramp — and says what the overlay shows.

## The rules that make it reversible

Reversibility is the promise, so it is the design, not a feature bolted on afterwards. `WeatherStore` holds to these
rules and `--wxtest` checks every one of them on the bytes:

1. **The originals are copied once and never touched again.** The four ready-made maps go into
   `Data/<theater>/Campaign/BMS Companion Backup/`, **inside the BMS install, beside the files they protect** — not
   in `%APPDATA%`. So reinstalling the app, updating it, moving to another PC or coming back in two years all still
   find them. A copy that already exists is never replaced, however many times the weather is changed afterwards —
   except by **Refresh the backup**, which the pilot presses for a map something other than the app changed (a BMS
   update), and which moves the earlier copy into `BMS Companion Backup/Older copies/<time>/` rather than losing it.
   A series copies each of BMS's update maps it is about to write over into `BMS Companion Backup/WeatherMapsUpdates/`
   the same way, **one file at a time, just before its first overwrite** (the whole folder is 290 MB in Korea).
2. **Every file written outside the four ready-made maps is listed before it is written**, in `BMS Companion Backup/written.txt`:
   `added <path>` for a file that was not there (a generated map, or an update map with no BMS original) and
   `replaced <path>` for one of BMS's own. Listing first means even a write that is cut short is known about. A
   generated map never takes the place of a file the program did not write: that is refused, with the reason.
3. **A `README.txt` goes in that folder** saying, in plain English, how to put Falcon BMS back *without this program
   or any other*: copy the four ready-made maps back one level up, copy anything in its `WeatherMapsUpdates` back into
   the update folder, and delete every file `written.txt` lists as added. It also says how BMS flies a generated map
   (Map Model, SAVE WTH or the campaign save). The README is written again whenever the tab lists a theater that has a
   backup folder (once per run, by the same safe write), so an older wording on disk is brought up to date.
   `bms-companion-weather.txt` beside it records which ready-made maps the app itself wrote, with checksums.
4. **A generated map is built whole from its parameters**, and a write of a ready-made map (the API still has one; the
   app no longer calls it) starts from the original, never from the last edit. So writing the same weather twice
   gives the same file and edits never compound.
5. **Nothing is written in place.** New bytes go to a temporary file beside the target and are moved across in one
   step, so a write that fails half way cannot leave a torn weather map where BMS expects one. The move keeps the
   file's own name on disk: Windows would otherwise rename BMS's `Sunny.fmap` to the `SUNNY.fmap` the move asked for.

A sixth thing follows from those: nothing in `WeatherStore` throws. Falcon BMS is often installed where Windows will
not let an ordinary program write, and the reason travels back in `WeatherState.error` for the page to show in red.

Undoing: `restore` puts back the ready-made maps the app's record says it wrote (the page's **Restore them**);
`refreshBackup` takes a new copy of one BMS changed (**Refresh the backup**); `restoreSeries` puts back every update
map from the backup and deletes every added one (**Restore update maps**); `removeGenerated` deletes the `BMSC` maps
(**Remove**). The backup copies themselves are never deleted.

Nothing is written for a theater until **Back up this theater and enable** is pressed for it (in the Save panel),
which takes the copies above. The tab itself is always shown; the switch is per theater and is the backup folder.

## Checking it

```bash
# the port against WeatherGen itself (Clojure 1.11 + VMT 0.63's src on the classpath; see the script's header)
java -cp "clojure-1.11.1.jar;spec.alpha-0.3.218.jar;core.specs.alpha-0.2.62.jar;<vmt>/src" clojure.main tools/wxref/wxref.clj ref.tsv
"BMS Companion.exe" --wxgentest ref.tsv out.txt

# the writer, never against the real install: it refuses a folder outside the temp folder or inside an install
"BMS Companion.exe" --wxtest "%TEMP%\wx\Data" out.txt
```

`--wxtest` hashes the four maps, backs them up, writes a weather nothing like the stock one, reads every value back
(cover as BMS's code 9, towering in array 26), writes the same weather twice to prove edits do not compound, restores,
and insists every file is **byte for byte** what it started as — then does the restore again by hand the way the
README says. Then the generated half: a map of its own checked cell by cell against the model (wind in km/h, cover
codes only BMS's own, the header's movement, stratus and contrails), the same parameters twice giving the same file,
refusal to overwrite a file it did not write or to take a name that is not a plain name, refusal of a step under 55
minutes, and a series laid over day 1 00:00–03:00 — which in a copy of Korea's folder backs up and replaces
`10100`–`10300` and adds `10000` — checked for the backups, the manifest, the map at 02:00 being the model's weather at
02:00, and the weather evolving; then undoing the series and the maps, and insisting the Campaign and update folders
are byte for byte (and name for name) what they were, by the program and again by hand.

Make the copy with the four ready-made maps, any other top-level `.fmap` files and a few update maps, e.g. `10100`–`10400`.

`--wxrender <a folder under temp>` draws the page headless at three widths and four times, and also prints the
"Picking several" and "Switching theater" checks (pictures `wx-multi-phone-panels`, `wx-multi-pc`, `wx-switch-korea-kto`,
`wx-switch-balkans`); with `BMSC_WX_DATA=<a copy of a Data folder under temp>` it also writes a map and a series there
and checks the settings kept beside them.

## What is still open

- **The wind unit, the header's move speed unit and move heading sense** (above): each needs one flight. NOT TESTED.
- **Version 8 maps** are read (the shower flag in array 27, the visibility in 28, the fog layer in 29) and never written.
- **How BMS changes a campaign's weather type.** Saves marked Deterministic with the default schedule (all Sunny, at
  one-day steps) went Fair to Sunny within an hour, and 25 saves were only ever Fair or Sunny; the counter at byte 28
  of the `.twx` is not decoded. To settle: fly a new campaign three or four hours, saving each hour, and log the type.
- **Map Model in a campaign.** Why campaigns started from Korea's map-model starts come out Deterministic; whether a
  campaign switched to Map Model keeps it through the next save and Auto Save, and whether each save rewrites
  `<save>.fmap`; whether SAVE WTH on a campaign's Weather screen writes its `.twx`, or only the campaign save does.
- **BMS's Map Model list**: whether a newly written `BMSC` map shows without leaving the Weather screen or restarting.
- **Update maps**: which one BMS applies when a mission loads with its clock already past several update times, how it
  blends between maps (the manual speaks of interpolation), and whether it honours names off the hour (a 55-minute
  series). To settle: one TE with auto update on, loaded at D1 04:30, its briefing against the maps.
- **The printed briefing's three columns**: whether BMS forecasts type changes or drift for Take Off, Target Area and
  Landing, or repeats the current weather (a Fair save printed Fair in all three).
- **The time of day** in version 8's single temperature and pressure per type: whether BMS applies a night/dawn/day
  curve to them.
- **Reading the campaign's live weather** (`WeatherMapsUpdates`) onto the mission map: cloud base and visibility
  under the route, wind at each steerpoint's altitude. Entirely read-only, and the decode above already covers it.

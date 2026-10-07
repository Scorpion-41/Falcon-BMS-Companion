# Where every piece of BMS Companion's data comes from

One row per thing the app shows: the file in the Falcon BMS install it is read out of, the code that reads it, how
we know the reading is right, and what to look at when a new BMS version lands. `docs/UPDATING.md` is the checklist
to *run*; this is the reference for *why each step exists* and where to look when one of them goes wrong.

> Everything here only reads the BMS install. What the app writes into it — Config, Weather, the pilot's cartridge,
> a TE's own mission file, the cockpit kneeboard pages — and the rules each write follows are in `CLAUDE.md`'s ground
> rules; the files each writer touches are named below all the same, because a new BMS version can move them.

Paths are relative to the BMS install unless said otherwise. `<th>` is a theater's own folder, which
`theaters.mjs` resolves from `Data/TerrData/TheaterDefinition/*.tdf` — a theater may point `objectdir` or
`3ddatadir` at **another** theater's data, and add-on campaign packs usually do. Always go through
`th.objectDir` / `th.data3dDir` rather than assuming `Data/TerrData/Objects`. The PC program does the same at run time
in one place, `desktop/.../bridge/Theaters.kt` (below).

---

## Ground charts — the Taxi page, the Airfields chart, the VR runways board

The richest and least documented of the lot, so it gets the most space.

| What | Where it comes from |
|---|---|
| Which fields exist, their position, ICAO, elevation, runway names | `Data/Campaign/<th>/*.obd` + the objects DB — `airports.mjs` |
| ‣ elevation | the field's ATC file (`TerrData/ATC/<field>.dat`, the `#INFO` line) where it has a non-zero one; otherwise BMS's height map (`NewTerrain/HeightMaps/HeightMap.raw`, the cell `TerrainHeights.kt` reads) under both ends and the middle of every runway rectangle, the median — `elevation.mjs`. Most fields have no ATC file (73 of Korea's 94; every field of the Balkans, EF2000 and Israel) and Hellas's give `0` for fields up to 1,180 ft up, so these were all planned at sea level before. Checked: where the ATC file has a figure the map agrees (Korea 18 of 21 to the foot — Osan 42, Daegu 120 — Pohang 16 against 74 and Muan 46 against 38 the exceptions); Hellas's fields land a median of 1 ft from WDP's own Aegean database (39 matched, all but one within 50 ft; Afyon 3,310 exactly); a ship has none. On a new BMS, re-run `airports.mjs` (or the whole extractor) and compare a few fields with their ATC files |
| The field's own authored layout | `<objectdir>/TerrData/Objects/ObjectiveRelatedData/OCD_nnnnn/` — `airfields.mjs` |
| ‣ runway rectangles | the OCD header records of type **8** |
| ‣ one taxi network per runway end | header type **1**, one per end |
| ‣ taxi points | `PHD_nnnnn.XML` / `PDX_nnnnn.XML`. Point types: 1 runway end, 2 taxi start, 3 taxiway, 8 runway edge, 9 runway crossing, 11 parking for a small aircraft, 12 parking with no size limit, 15 runway entry/crossing lane, 21 a point on the runway centreline where an exit lane meets it (not a hold short, which no point marks; `docs/PARKING.md`) |
| ‣ everything standing on the field | `FED_nnnnn.XML` — each record names a feature class (`FeatureCtIdx` → `ct` → `fcd`) and carries its offset and heading |
| ‣ the asphalt | the 3D models the FED records point at (`GraphicsNormal`) — `pavement.mjs`, below |
| ‣ control towers | FED records whose class BMS names a control tower (`CONTROL_TOWER` in `airfields.mjs`) — position and heading exact; the **shape** from the tower's own 3D model, else the box its `Parent.dat` states — `footprint.mjs`, below |
| ‣ arresting cables | FED records of class "Arrestor System 1/2/4/5" — exact positions, given to the runway each lies across (`assignCables`); no source says which kind of gear |

**Four things about that data decide the whole design.** Each was learned the hard way; do not re-derive them.

1. **The point list is a walk of a tree, not a chain.** A side taxiway is its own run whose first point carries
   `RootIdx` back to the point it leaves. Joining the list in order invents edges that cross the field — ten at
   Gunsan, up to 6,000 ft long. Join each run at its root.
2. **Ramp spot numbers are BMS Ground's: a count in storage order.** Ground's "park 04" (call 517) and
   `ATCBrain::GetParkingPtNbr`, read from the sim's own code (`Bin\x64\Falcon BMS.pdb` ships beside the exe),
   count every type 11/12 point of the network from its first point up to the spot, from 00 — the alert cell
   included. See `numberParking`; the app prints it two-digit (`AfSpot.label`). **After landing, Ground counts in
   the taxi-in network**: the reciprocal end's route, whose line-up is where you roll out (landing on Gunsan 36 →
   runway 18's). `taxiInRoute`/`routeShown` in `TaxiRouting.kt` pick it for every page that shows the way in.
   **−1 in `ParkingPointGroup` is not "ungrouped": it is the alert cell**, the red numbers on BMS's own parking
   charts, which Ground never assigns. `AfSpot.q`.
3. **BMS parks you on the half of the ramp nearest the runway in use**, and numbers the ramp separately for each
   end (Gunsan: 69 spots for 18, 77 for 36, only 12 in the same place). So the spot the jet is sitting on tells you
   which runway the field is working — `routeForPosition`.
4. **Parallel runways share a course**, so a route and a runway end must be matched by `RunwayNumber`, not by
   heading. Without it Osan calls both strips 09L, and 52 fields end up with two routes of the same name.

**How we know it is right.** The theaters ship their own parking charts, with a latitude and longitude per spot:

```bash
cd tools/extractor && node src/apcverify.mjs      # prints "every chart agrees, spot for spot"
```

It checks Araxos 36 (24 spots), Souda 11 (24), Tirana 17 (27) and Skopje 16 (28) against
`tools/extractor/charts/*.txt`, matching each printed spot to ours by position. Souda, Tirana and Skopje print
BMS's count exactly. Araxos 36's chart is a drawing numbered another way (breadth first by `ParkingPointGroup`,
−1 last) and differs from the count at six spots, 12-17, which `KNOWN_CHART_NUMBER` lists; any other difference
fails. The count itself was confirmed on Gunsan against the sim's walk (`--taxirender` prints it: landing on 36,
04 and 28). **If a BMS release changes the layout of any of those four fields, update the .txt tables from the new
charts before trusting a failure.**

Those charts also print a **size letter** per spot, and the same run checks it: S (an encircled number) is the
point's own type 11, L (a boxed number) is type 12, and Q (printed red) is `ParkingPointGroup` −1. All 103 spots
agree on the alert cell and 101 of 103 on the size; the two that do not are Araxos 4 and 5, which the data types
small and the chart boxes, and they are named in `KNOWN_SIZE_MISMATCH` rather than papered over.

Where a shipped chart and the field's own points disagree, the points win — Yenihesir's chart is drawn for a
layout the install no longer has.

### The asphalt, out of the 3D models (`bml.mjs`, `pavement.mjs`)

A field's pavement is **not** in its point data and is **not** photoreal terrain imagery: it is a model, placed
once in the FED list. Osan's is a single object called "RKSO Taxiways"; nearly every field names its own the same
way. It carries the aprons, the dispersals and the turnarounds joining the runway ends — none of which any taxi
route describes.

**The model format is decoded**, so the chart is drawn from BMS's own triangles rather than from a reconstruction:
a header, a mesh table, then one vertex array, with each mesh a plain triangle list. The full layout, the tools
for inspecting a model, and the five checks that say a reading is correct are in
**`tools/extractor/bml-format.md`** — read that before touching any of this.

The one check worth repeating here, because it is what makes the difference between a chart and a mess:
**the ground triangles have to tile.** A real surface covers each patch of ground once, so the sum of its triangle
areas is within a few per cent of the area of their union — Osan 0.99, Anshan 0.99, Cheongju 1.01. A model read
the wrong way makes triangles out of vertices that are not neighbours, and those overlap: Gunsan comes out at
1.62 and drawn it is a fan of wedges across the field. `pavement.mjs` throws away anything above 1.25, so a
field whose pavement cannot be trusted falls back to its taxi network instead of being given a wrong picture.
**1,485 of 1,720 fields** have pavement that passes; the rest keep the network drawn at real width.

The triangles are rasterised on a 3 ft grid, their union traced with `outline.mjs` and simplified at 9 ft, and
what the app stores is a list of closed rings filled even-odd — a few kilobytes a field, exact edges, crisp at any
zoom. The taxiway centre line drawn over it is BMS's own ground network, which is also what the routing walks.

**`xz` ships with git for Windows** at `Program Files/Git/mingw64/bin/xz.exe`; only a shell started from git
has it on the PATH, so the reader looks there itself and **stops the run** if it cannot find it — an earlier
version failed silently and shipped 1,720 charts with no asphalt on them.

### Control towers and arresting cables (`footprint.mjs`, `airfields.mjs`)

**Control towers** are drawn as the shape they have from above. The FED record gives the position and heading
(exact); the outline comes from the tower's own model: `modelTriangles` in `bml.mjs` reads a building's model mesh
by mesh (its meshes change record size — 36, 40, 32 bytes — and may start a few bytes apart, so each is found where
its own record size lands on unit normals, all of the first 24, and the last must end where the file does), every
triangle standing off the ground is projected straight down, and the union is rasterised at 1 ft and traced. The
part reaching 60 % of the model's height (the shaft and the cab) is kept as well. A reading is believed only when its
extent agrees with the model's `Parent.dat` ("Dimensions = radius xmin xmax ymin ymax zmin zmax", x forward, y right,
z down); otherwise that box is drawn — the right size and heading, a rectangle. In the stock data 1,649 of the 2,171
towers placed across the theaters come from their model and 522 from the box (`airfieldrun.mjs` prints the split).
Watchtowers, water and radio towers and "RKSS Old ATC Part n" (pieces of a terminal) are not control towers.

**Arresting cables** are BMS's "Arrestor System" objects (variants 1, 2, 4 and 5; "Arrestor Cable Sign" is the
marker beside one). Each is laid across a runway, its heading across the strip, and goes to the runway it lies
across — within the width plus 60 ft, from 1,500 ft short of one end to 1,500 ft past the other. The distance is
measured along the centre line from each end of the runway rectangle. Coverage: 1,061 cables on 312 fields' runways;
29 lie across no runway BMS models (a closed or unmodelled strip at Tel Nof, Hatzor, Ramat David, Larissa, Nea
Anchialos, Balikesir, Bandirma) and are left off, named in `airfieldrun.mjs`'s report. The distances agree with the
usual placement (most within 1,200-1,800 ft of each threshold). The theaters' AIPs (`Docs/03 KTO Charts/KTO_AIP.pdf`)
carry no arresting gear, and no BMS file says which kind of gear an object is, so the app says "cable".

### Looking at a chart

```bash
cd tools/extractor
node render-debug.mjs korea-kto "Osan AB" C:/temp/osan.png     # pavement, features, runways, centre line
BMSC_ONLY_FIELD="Osan AB" node render-debug.mjs ...            # one field in a second, rather than the theater
BMSC_PAVEMENT_LOG=C:/temp/pave.log node src/airfieldrun.mjs    # what every model was judged to be
```

`render-debug.mjs` prints which reading won and what it scored (`[joined 108] Osan AB: 128 roads …`). **Look at a
chart after any change to `pavement.mjs`.** A number cannot tell you the field looks like an airfield.

### What to check on a new BMS version

- [ ] `node src/airfieldrun.mjs` — rebuilds only the ground charts. Read its two summary lines: the count of
      charts, and the pavement line (how many models were taken, and how many were unreadable or had no vertex
      block). A jump in "unreadable" means the BML header changed.
- [ ] The towers and cables lines of `airfieldrun.mjs`'s summary: a fall in towers "drawn from their model" means
      the building models moved (`modelTriangles`); a rise in cables "on no runway" means the arrestor objects or the
      runway rectangles moved.
- [ ] `node src/apcverify.mjs` — the parking charts, spot for spot.
- [ ] `node render-debug.mjs` on Osan (dense mesh, marker-less model) and Mezze (sparse mesh, marked block).
      Those two between them exercise every path in the extractor.
- [ ] `node bml-rate.mjs korea-kto` — how many models still read. A fall here means the model format moved;
      `bml-why.mjs` says where the reader gives up and `bml-decode.mjs` dumps the bytes.
- [ ] `node bml-tile.mjs korea-kto "Osan AB"` — the tiling ratio must stay near 1.00.

---

## The rest of the app, in brief

| Feature | BMS source | Code | Check |
|---|---|---|---|
| Airfields, navaids, ILS, ATC, radio | `Data/Campaign/<th>/*.obd`, `Stations+Ils`, ATC tables, RadioMap | `airports.mjs`; one set per distinct content (`ap-*`, `rm-*`), **written for every theater**, add-ons included | `node src/geocheck.mjs` (median error under ~1 nm against OurAirports); `node src/plannercheck.mjs` (every set a theater names is shipped) |
| Aircraft, weapons, loadouts | the objects DB, `catalog.mjs` inputs | `db.mjs`, `catalog.mjs` | counts in `index.json` |
| Reference pictures (Encyclopedia, Arsenal, Threats) | the theater's TacRef (`TacRefDB.xml` beside its terrain, else `Data/TerrData`'s): each entry's `PicName`, the file `<artdir>\Art\TacRData\<PicName>.tga`, else `Data\Art\TacRData`'s. An aircraft or store has the picture of the entry whose `ClassTable` is its own class-table row, and nothing else (no family or sibling picture). A theater that borrows another's TacRef (Hellas, LHTO, Hellas WCP, EF2000 BTO, the Korea 2012 six, LKTO, OFMKTO, TvT, KTO 80s) keeps a link only when that row is the same object in the TacRef's own `Objects` (same name), or the object's name is the entry's word for word, or for a store the same designation. A theater's own art that differs from the base theater's under the same name ships as `<name>__<add-on folder>`; a flat-colour placeholder is no picture. BMS's own slips, reviewed by eye, are listed in `reference.mjs` (an entry's `PicName` of another type, a row linked to another type's entry, a type's photo showing another air arm), keyed by name and picture so each lapses when BMS changes it. An aircraft still without a picture may take one of the same type family whose markings were identified as its own nation's (`NATION_PICTURES`, matched on the air arm its name names, `NATION_WORDS`; never across nations); three borrowed store links that differ only in name are explicit (`SAME_STORE` in `catalog.mjs`). The threat guide names its entry in `tools/curated/threat_pictures.json` (reviewed: same type and variant, no other nation's markings), shipped as each threat's `tacref` | `reference.mjs`, `catalog.mjs` (`tacrefFor`), `images.mjs`; `node src/referencerun.mjs` rebuilds only these | the run's counts (aircraft, weapons and entries pictured); `img/tacref` names nothing stale; on a new BMS, look at every picture two aircraft share and at every name `withThreatPictures` warns about |
| Theater maps, tiles | `NewTerrain/HeightMaps/HeightMap.raw` (int16 feet, row 0 = north), `NewTerrain/Photoreal/GlobalColorMap.dds` | `maps.mjs`, `heightmap.mjs` | `--maprender <theater> <folder>`; `MAX_Z` must match `MapBase.kt`. Water is **flat areas**, not "height ≤ 0" — land lies below sea level in several theaters |
| Borders, provinces, towns | Natural Earth 10m GeoJSON in `cache/ne`, projected with `NewTerrain/Theater.txt` | `geo.mjs`, `projection.mjs` | coordinates must be **integers**; one float fails the whole file |
| Instrument charts, plates | `Docs/` PDFs and the theaters' own plates | `charts.mjs` (needs `cache/pdfbox-app.jar`) | page count and orientation, `PageDirs.java` |
| Reference photographs (in place of or beside BMS's pictures) | **not BMS** — Wikimedia Commons files, each chosen by hand in `tools/curated/photos.json` (`pic:<BMS picture>` stands in for that picture everywhere; `aircraft:`/`weapon:`/`enc:<key>` and `threat:<id>` give one entry its own). Rule: the exact type and variant the entry names, and for an air arm's aircraft that air arm's markings (the nation of the BMS picture it replaces); checked by the file's title, description and categories and by eye; no look-alike, no watermark, no collage; replaced only where clearly better. Licences: public domain (U.S. government works included), CC0, CC BY, CC BY-SA, read from the file's `extmetadata` at every fetch; anything else is refused | `photos.mjs` (fetch, licence check, 320:151 crop, ≤ 800 px WebP q80 → `img/tacref/ph-*.webp`; credits → `tools/curated/photo_credits.json` and `data/credits/photos.json`), `applyPhotos` in `referencerun.mjs`/`main.mjs` | `node src/photos.mjs` prints each file's licence, author and size and refuses any other licence; on a new BMS, a `pic:` whose BMS picture changed (another subject or nation) must be looked at again; `applyPhotos` warns about a target no entry has |
| Threats, HOTAS, checklists | the PDF manuals, by hand (a threat's picture: `threat_pictures.json`, above) | `tools/curated/*.json` | diff the `pdftotext` dump between versions |
| Live flight data | `Tools/SharedMem/FlightData.h` | `SharedMemory.kt` | `--selftest`; BMS appends fields at the **end** of each struct |
| Briefing, DTC | `briefing.txt` (CRLF), the DTC files. BMS writes `[STPT]`, `[Radio]`, `[COMMS]` Comm1/2, `[EWS]`, `[MFD]`, `[IFF]`, `[HARM]`, `[LINK16]`; WDP and the Planner add `[NAV OFFSETS]`, `[COMMS]` TACAN/ILS, `[Laser]`, `[ICP]` and the EWS program comments. A PPT is `x, y, z, range in feet, code`, the code being the key of the theater's `Ppt.ini`; a range under 100 ft is a marker (AWACS and tanker are 0.1 ft) | `BmsFiles.kt` (`BriefingParser`, `DtcParser`, `PptTable`) | `--selftest`; `--plantest parse <a copy of a BMS folder> [api] out.txt` |
| Text strings | StringData, with a running BMS | `SharedMemory.kt` | `--dumpstrings out.txt` |
| MFD button legends | the `FalconSharedOsbMemoryArea` area (`OSBData` in `FlightData.h`): 20 legends a side, two lines of 7 characters, plus which one is boxed | `SharedMemory.kt` (`Osb`, `readOsb`), `MfdPanel` | `--osbtest out.txt` with BMS in 3D: the legends printed must be the legends on the glass, which is what checks that the ring runs clockwise from the top left |
| The cockpit **displays** — HUD, PFL, DED, RWR, both MFDs, HMS | BMS's render-to-texture export: the `FalconTexturesSharedMemoryArea` mapping, with `RTT_size` and `RTT_area[7][4]` in FlightData2 saying where each display sits in it. The area is a DDS file in memory: "DDS ", a 124-byte header (height, width, row pitch, 32-bit BGRA), the pixels from byte 128 — read out of `Falcon BMS.exe`'s own code, which creates it at `RowPitch * height + 128` bytes. Needs `g_bExportRTTTextures 1` in the BMS config (the Launcher's Export RTT Textures). The same door `Tools/RTTRemote` goes through | `RttTextures.kt` (`shapeOf`), `RttView.kt` | `--rttselftest` proves the reader against textures it publishes itself in BMS's layout (every pixel, at 1024 x 500 to 2048 x 2048 with padded pitches, and the JPEG a phone gets); `--rtttest` dumps what real BMS is publishing, header included. If a BMS update changes the header, `--rtttest`'s "header:" line shows it |
| Weather | `Data/<theater>/Campaign/*.fmap` — a 59x59 grid, 28 four-byte arrays (30 in version 8), field-major. Also the briefing's own weather block. The theaters are the theater definitions' (every `campaigndir`), so the Korea 2012 six and LKTO's `Campaign+` are found, and Hellas, Hellas WCP and LHTO, which ship no ready-made map, get another version 5 map's grid or the built-in 59 x 59. **A save's weather** is its own `<save>.twx` (version 8 in 4.38: model, current type from 0, the four types' tables) and, under Map Model, `<save>.fmap` or the update map at the save's clock — read by the Planner's card and WDP mode's Populate | `Fmap.kt`, `WeatherStore.kt` (`places`, through `Theaters`); briefing in `BmsFiles.kt`; `Twx.kt`, `PcFileRoutes.weather`, `SaveWeather.kt` | `--wxtest <a copy of a Data folder>`, which checks on the bytes that everything written can be undone; `--wdpfilestest` and `--missiontest` read a save's `.twx`. Format, the unidentified arrays and who reads the weather when: `docs/WEATHER.md` |
| AWACS picture, air defences | Tacview real-time telemetry, port 42674 | `TacviewClient.kt` | `--sidetest <recording> <callsign> <secs> out.txt` |
| Who is allied with whom | the campaign save's `.tea` part, from the same save as the planned tracks | `TeamRelations.kt` | `--teamtest`, `--teamfile out.txt <save>…` |
| Planned tanker/AWACS tracks | the newest `.cam`/`.tac` in the current theater's own `campaigndir` (per-part LZSS), its class table through the `objectdir` and its names from the theater's `Strings.txt` — all through `Theaters` | `MissionArchive.kt`, `PlannedRoutes.kt` (`sourceFor`) | `--trackstest`, `--supporttest`, `--basetest`, `--anytype`, `--camsource out.txt <a copy> [callsign]` |
| Radio call text | **nowhere.** BMS assembles calls from `Data/Sounds/CommFile.xml` fragments and draws the subtitles itself; shared memory, the Tacview stream and the logs have nothing | — | the mission log derives events instead |
| Carrier flight decks | **the ship's own BMS 3D model** (`Falcon4_VCD.xml` → `Falcon4_CT.xml` `GraphicsNormal` → `Objects/Models/<gfx>/Model_0.bml`): outline, sponsons and galleries, islands, ski jump, and the paint the model carries (landing-area lines, foul lines, catapult tracks, deflectors, lifts). The objective's deck points are in the model's frame, so nothing is fitted. What the model does not hold — wires on the Nimitz, flush lifts, catapult lengths on the older ships — is placed from a published figure (NAVAIR, Wikipedia, GlobalSecurity, Naval Technology, Navypedia, Navy Lookout) or marked estimated; each deck's `_src` in `carriers.json` says which | `tools/curated/carriers.json` (`decks`, `classes`), `ships.mjs` | `--deckrender <folder>`: every deck drawn; BMS's ramp spots counted on each outline (all on deck but HMS Ocean's, whose objective carries a Tarawa's data); the jet put back on the deck from a ship at other headings. On a new BMS: check each carrier's `GraphicsNormal` still names the same model, and look at the pictures |
| Config options (the Config page) | `User/Config/Falcon BMS.cfg`, the stock file BMS ships with every option at its default and most lines carrying BMS's own comment | `cfgcatalog.mjs` + `tools/curated/cfgnotes.json` | `node src/cfgcatalog.mjs` prints the count, the groups, and any option left without a description |
| The Planner (Weapon Delivery Planner) pages and child windows | **not BMS** — WDP's own designer code (decompiled) and its forms' pictures | `wdplayout.mjs`, `wdpimages.mjs` → `assets/data/wdp/` | `--wdprender <folder>` draws every page; `--wdpclicktest` presses every control |
| The Planner's latitude/longitude (BMS's own) | three figures of the terrain BMS flies the theater on: `NewTerrain/Theater.txt`'s size and centre latitude/longitude and the byte size of `NewTerrain/Heightmaps/HeightMap.raw` (its sample count), as `projection` in `index.json` (`heightmapBytes` beside the centre); WDP's grid over them (feet ÷ 3.28084, the centre's forward projection less half the theater, one heightmap sample north) is the latitude and longitude BMS writes into its ACMI recordings and prints in its AIPs. What WDP itself reads (its own terrain lookup, which finds none for Korea TvT) is `wdpTerrain`, kept for the comparison | `projection.mjs`, `wdpterrain.mjs`, `WdpCoords.kt` (`coordData`, `bmsGrid`), `PopupCoords.kt` | `--acmicoords <recording> out.txt` against BMS's own ACMI (every position within 2 m on the theaters of its terrain; 20 recordings of Korea, Hellas and Israel pass); `--wdppagetest coords <reference>` carries ACMI and AIP samples and checks WDP's own conversion (`tools/wdpref`, harness page `Coords`); `plannercheck.mjs` demands `heightmapBytes` on every 1,024 km theater. The Falklands (2,048 km) keep the projection string until a recording of theirs is checked |
| A theater's projection string | `NewTerrain/Theater.txt`'s `Projection string` (`+proj=tmerc +lon_0 +k +x_0 +y_0`, WGS84) — the one the maps, towns and ground charts are drawn with, **not** the latitude and longitude BMS gives (140-220 m from it on average, up to 290 m: D26 in `WDP-PORT.md`) — as `projection` in `index.json`, with 3.27998 ft per metre (theater x = north). Korea TvT names no `terraindir` and flies `Terrdata/korea`, as BMS does | `projection.mjs` (`theaterProjection`), `TheaterProjection` in `Models.kt` | `plannercheck.mjs` projects each theater's own centre (`Center latitude/longitude`) and demands the middle of the square: 4 to 22 ft off on the 19 theaters of 4.38.1, which is the file rounding its false northing to 10 m |
| PPT types (the DTC's pre-planned threat list and default radii) | each theater's `Campaign/Ppt.ini` (in the definition's `campaigndir`; the Korea 2012 pack has one per campaign): `<code> <radius ft> <name>`, e.g. `SA2 164055.12 SA-2`, `AWC 0.1 AWACS`. Rows kept in file order, repeats and `---` separators included. The PC reads the same file at run time to name a cartridge's PPTs on every device (the theater BMS is on, else `Data/Campaign`) | `theaters.mjs` (`readPptTable`) → `data/ppt/pp-*.json`, `pptSet` in `index.json`, `Repo.pptTable`; at run time `CartridgeStore.pptIni`, `Bridge.withPptNames` | `plannercheck.mjs` (every theater has one, not empty); 8 distinct tables across the 19 theaters in 4.38.1 (Korea TvT ships Balkans' file word for word) |
| The Planner's readiness, per theater | what the rows above produce | `main.mjs` writes `planner: {ok, missing[]}` per theater and `bmsBuild` (the version resource of `Bin/x64/Falcon BMS.exe`) | `node src/plannercheck.mjs` — **a new theater needs no code change**: re-run the extractor and this must pass. It caught the 1.3.7 extractor shipping only the primary theaters' airport sets (8 of 19 theaters named files that were not there) |
| BMS's cartridge defaults (the Planner's Default buttons) | `User/Config/*_Def.ini` (EWS, HARM, IFF, MFD in 4.38.1), read **by the PC at run time**, not extracted: they belong to the pilot's install | `BmsDefaultsFiles.kt`, `GET /api/cfg/defaults` (read only), `BmsDefaults` in `Models.kt` | `--api /api/cfg/defaults` with `BmsDirOverride` pointed at a **copy** holding `User/Config` |

### The Planner's mission, read on the PC at run time

None of this is extracted: it is the pilot's own install and saves, read by the PC program when a device asks.

| What | Where it comes from | Code | Check |
|---|---|---|---|
| **Theaters** — which exist, and where each keeps its campaign, objects, 3D data and cockpit textures | `Data/TerrData/TheaterDefinition/theater.lst` and each `.tdf` (`name`, `campaigndir`, `objectdir`, `3ddatadir`); the current one is the registry's `curTheater`. Names carry trailing spaces, paths differ in case, and several theaters point at another's folders (Korea 2012's campaigns sit two levels down; LKTO has `Campaign` and `Campaign+`) | `Theaters.kt` | `--theatertest out.txt <a copy of a BMS folder>` |
| **Campaign, TE and training saves** (Open mission…) | the `.cam`/`.tac`/`.trn` files directly in each theater's `campaigndir`: the directory at the end, per-part LZSS; `.cmp` (clock, teams, bullseye, `SaveFile` = the name BMS saved it under, 40 bytes at 2030 unpacked), `.uni` (every unit, version 107-110 in 4.38.1), `.obj` (a start's objectives), `.tea`, `.plt`, `.ver`. Record layouts from WDP's `BMSUtils.dll` | `CampaignArchive.kt`, `CampaignFiles.kt`, `CampaignRoutes.kt` | `--camtest out.txt <a copy> [--any-install]`: every unit and objective part walks to its last byte with the header's count, the links hold, 99.5 % of waypoint targets resolve; on the development install 19 theaters, 517 files, 169 starts, 5,562 flights |
| **Names in a save** | the theater's `Strings.txt` (300 + mission, 350 + waypoint action, 400 + task, 2000 + callsign; the id may be followed by a space rather than a tab: "341 RELOCATE"), the class table (`Falcon4_CT.xml` in the `objectdir`, else `Data/TerrData/Objects`), UCD → VCD for aircraft, WCD for weapons | `Theaters.kt` (`strings`, `classFile`), `CampaignArchive.kt` | `--camdump <save> out.txt [BMS folder] [callsign]` |
| **A save's flight as a briefing** (situation, Station/Target Area, ROE, emergency procedures, intel) | the logic of BMS's own briefing scripts, `Data/Campaign/*.b` (Header.b, Situate.b, RoE.b, Emerganc.b, End.b), ported — they are not read at run time — with every word from the theater's `Strings.txt`; the data from the save's `.tea` (from version 102: stances +22, name +453, motto +473, ground action +673, defensive/offensive air action +692/+720), primary objectives (`.pol`), units and the start's objectives. Distances as BMS makes them: between whole grid cells, km / 1.852, cut to whole miles; the alternate is measured from its landing waypoint's cell. Intel is WDP's own lists (every hostile unit in the save), not BMS's threat analysis, which BMS picks along the route when it prints | `CampaignBriefing.kt` | `--camtest` item 10 compares every text with the printed briefing of the same flight (Korea Auto Save, Cyborg6); `--camdump … --card` shows the Planner's Briefing page for a flight with no printed briefing |
| **The route before 3D** | the mission file BMS writes beside the save it flies, `<campaigndir>/<SaveFile>.ini` (`SaveFile` from the header, not the file's `[MISSION] title`), believed only when its rows match the printed briefing (a Nav point prints its waypoint's route action, from the save) and its points equal the briefed flight's cells + ½ to 2 ft; else the printed flight's waypoints in the save itself, when the save provably holds it. One `<name>.ini` serves every save kind of that name, and BMS rewrites it under whatever save is loaded | `MissionDtcFile.kt` | `--plantest route <a copy> out.txt` |
| **The mission's threats on every map** | the printed briefing's Threat Analysis rows ("SA-19 (2K22) missile launchers 2 nm west of Buk-myeon"), placed in the save BMS flies: the battalion BMS words exactly so (nearest city or town, whole cells, as `CampaignBriefing.Places` words it), else the nearest unit of that system within 5 nm of the described point, else the point; without a printed briefing of the flight, the save's sites spotted by the team that controls the flight's whose ring reaches within 3 nm of the route. Rings: the theater's `Ppt.ini`, else the threat reference. An unspotted enemy site never leaves the PC; the Tacview feed's SAMs pass only at a known site | `MissionPicture.kt`, `BriefedThreats.kt`, `MissionGrounds.kt`, `KnownSams.kt` | `--missiontest` part 10, `--mappicturerender <a copy> <out>` |
| **A TE's own mission file** (written by Save to DTC with a TE open: the pilot's own, or one BMS ships, as in WDP) | `<campaigndir>/<TE name>.ini`, the `[STPT]` keys; some TEs have none (then none is made). A file renamed in Explorer keeps its old `SaveFile`, and is not written | `CartridgeStore.kt` (`saveTe`, `CampaignStarts`) | `--tesavetest <a copy of a Data/Campaign> out.txt` |
| **The cockpit kneeboard pages** (Upd Kneeboard) | `<3ddatadir>/KoreaObj/7982.dds`-`7997.dds` (and a `KoreaObj_HiRes` twin where there is one), the left half page n of the left knee, the right half the right knee's. Their layout depends on who wrote them last (BMS ships DXT5 with 12 levels on KTO, DXT1 with 12 on Balkans, one level on Hellas; EZBoards writes DXT1 without mips, html_brief 32-bit BGRA). BMS's shipped copies, for **Put BMS's page back**: KTO `Docs/07 Kneeboard Templates/F-16/DDS_Backup`, `Add-On Hellas/Docs/03 3d Kneeboard Backup`, `Add-On Balkans/Docs/04 3d Kneeboards Backup/01 F-16`. EZBoards' pages: its `CONFIG_USER.BAT` `SET KNEEBOARD[F16_<n><L\|R>]` lines | `KneeboardPrint.kt`, `Dds.kt` | `--ddstest out.txt [<fixture>]` (the codec: every layout read and written), `--kbprinttest <a copy> out.txt` (section 1: the shipped copies found) |

### The config catalogue on a new BMS version

The list is **read out of BMS's own file**, not written by hand, so a new version is a re-run rather than an
editing job: `node src/cfgcatalog.mjs` (or the whole extractor) rebuilds `data/cfg/options.json` with whatever
options that version has, at that version's defaults, described in that version's own words.

What is written by hand in `tools/curated/cfgnotes.json` is only what BMS does not say:

- `groups` — a regex per group, matched against the option name, first hit wins. An unmatched option lands in
  "Other", which is the signal that a group is missing.
- `options` — a description for the ~50 lines BMS leaves uncommented, and a `kind` or `group` override.
- `choices` — the named values for options that take one of a set (`0 = off, 1 = …`).
- `extra` — options BMS **reads but does not list** in the stock file. Those are the only ones a new version can
  silently drop; the rest look after themselves.

After a re-run, check the printed line "no description yet: …" is absent — that is the list of options this
version added — and that the group counts have not collapsed into "Other".

---

### The Planner's mission on a new BMS version

- [ ] `--theatertest out.txt <a copy>` — a new theater, or a moved campaign folder, shows here first.
- [ ] `--camsource out.txt <a copy>` — per theater, the save, class table and names the planned tracks now use.
- [ ] `--camtest out.txt <a copy> --any-install` — every [2]-[8] line must still pass. A new save format shows as a
      walk that goes inexact (the `.ver` part says which version; 107-110 in 4.38.1): compare with WDP's newest
      `BMSUtils.dll` and add the version branch in `CampaignArchive` (`core`/`flight`/`pkg`/`squadron`/`waypoint`/
      `walkObjectives`). Print a briefing and save the campaign on the same flight first, so item 10 compares the
      save's texts with a fresh printed briefing; if a text moved, re-read the `.b` scripts and the `Strings.txt` ids above.
- [ ] `--plantest route <a copy> out.txt` — `Auto Save.ini` still equals the briefed flight's cells + ½, and the
      header's `SaveFile` still names it.
- [ ] `--ddstest` on a copy holding the new pages (a new texture layout shows as an unreadable or unwritable line),
      and `--kbprinttest` section 1 (the shipped copies' folders still found).

## Rules that hold everywhere

- **Verify against something BMS itself produces**, not against a number that looks plausible: the shipped parking
  charts, the ground AI's own network, the known airfield positions, the struct sizes. Every hard bug in this
  repo's history was caught by one of those and would not have been caught by inspection.
- **A silent failure is worse than a loud one.** Anything that can produce empty output — a missing tool, a format
  that changed — must stop the run or print a count, never quietly ship a diagram with nothing on it.
- **Look at the output.** Renderers are checked in (`render-debug.mjs`, `--maprender`) for exactly this reason.

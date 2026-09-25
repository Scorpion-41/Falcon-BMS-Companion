# Where every piece of BMS Companion's data comes from

One row per thing the app shows: the file in the Falcon BMS install it is read out of, the code that reads it, how
we know the reading is right, and what to look at when a new BMS version lands. `docs/UPDATING.md` is the checklist
to *run*; this is the reference for *why each step exists* and where to look when one of them goes wrong.

> The BMS install is read-only. Everything here only reads it.

Paths are relative to the BMS install unless said otherwise. `<th>` is a theater's own folder, which
`theaters.mjs` resolves from `Data/TerrData/TheaterDefinition/*.tdf` — a theater may point `objectdir` or
`3ddatadir` at **another** theater's data, and add-on campaign packs usually do. Always go through
`th.objectDir` / `th.data3dDir` rather than assuming `Data/TerrData/Objects`.

---

## Ground charts — the Taxi page, the Airfields chart, the VR runways board

The richest and least documented of the lot, so it gets the most space.

| What | Where it comes from |
|---|---|
| Which fields exist, their position, ICAO, elevation, runway names | `Data/Campaign/<th>/*.obd` + the objects DB — `airports.mjs` |
| The field's own authored layout | `<objectdir>/TerrData/Objects/ObjectiveRelatedData/OCD_nnnnn/` — `airfields.mjs` |
| ‣ runway rectangles | the OCD header records of type **8** |
| ‣ one taxi network per runway end | header type **1**, one per end |
| ‣ taxi points | `PHD_nnnnn.XML` / `PDX_nnnnn.XML`. Point types: 1 runway end, 2 taxi start, 3 taxiway, 8 runway edge, 9 runway crossing, 11 sized parking, 12 parking, 15 on-runway, 21 hold short |
| ‣ everything standing on the field | `FED_nnnnn.XML` — each record names a feature class (`FeatureCtIdx` → `ct` → `fcd`) and carries its offset and heading |
| ‣ the asphalt | the 3D models the FED records point at (`GraphicsNormal`) — `pavement.mjs`, below |

**Four things about that data decide the whole design.** Each was learned the hard way; do not re-derive them.

1. **The point list is a walk of a tree, not a chain.** A side taxiway is its own run whose first point carries
   `RootIdx` back to the point it leaves. Joining the list in order invents edges that cross the field — ten at
   Gunsan, up to 6,000 ft long. Join each run at its root.
2. **Ramp spot numbers are not storage order.** BMS walks the tree breadth first and, among the runs leaving one
   parent, orders them by `ParkingPointGroup` with **−1 (ungrouped) last**. See `numberParking`.
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
`tools/extractor/charts/*.txt`. Plain storage order gets Araxos wrong; a breadth-first walk that ignores the
groups gets Tirana and Skopje wrong. **If a BMS release changes the layout of any of those four fields, update
the .txt tables from the new charts before trusting a failure.**

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
| Airfields, navaids, ILS, ATC, radio | `Data/Campaign/<th>/*.obd`, `Stations+Ils`, ATC tables, RadioMap | `airports.mjs` | `node src/geocheck.mjs` (median error under ~1 nm against OurAirports) |
| Aircraft, weapons, loadouts | the objects DB, `catalog.mjs` inputs | `db.mjs`, `catalog.mjs` | counts in `index.json` |
| Theater maps, tiles | `NewTerrain/HeightMaps/HeightMap.raw` (int16 feet, row 0 = north), `NewTerrain/Photoreal/GlobalColorMap.dds` | `maps.mjs`, `heightmap.mjs` | `--maprender <theater> <folder>`; `MAX_Z` must match `MapBase.kt`. Water is **flat areas**, not "height ≤ 0" — land lies below sea level in several theaters |
| Borders, provinces, towns | Natural Earth 10m GeoJSON in `cache/ne`, projected with `NewTerrain/Theater.txt` | `geo.mjs`, `projection.mjs` | coordinates must be **integers**; one float fails the whole file |
| Instrument charts, plates | `Docs/` PDFs and the theaters' own plates | `charts.mjs` (needs `cache/pdfbox-app.jar`) | page count and orientation, `PageDirs.java` |
| Threats, HOTAS, checklists | the PDF manuals, by hand | `tools/curated/*.json` | diff the `pdftotext` dump between versions |
| Live flight data | `Tools/SharedMem/FlightData.h` | `SharedMemory.kt` | `--selftest`; BMS appends fields at the **end** of each struct |
| Briefing, DTC | `briefing.txt` (CRLF), the DTC files | `BmsFiles.kt` | `--selftest` |
| Text strings | StringData, with a running BMS | `SharedMemory.kt` | `--dumpstrings out.txt` |
| AWACS picture, air defences | Tacview real-time telemetry, port 42674 | `TacviewClient.kt` | `--sidetest <recording> <callsign> <secs> out.txt` |
| Who is allied with whom | the campaign save's `.tea` part | `TeamRelations.kt` | `--teamtest`, `--teamfile out.txt <save>…` |
| Planned tanker/AWACS tracks | the `.cam`/`.tac` campaign save (per-part LZSS) | `MissionArchive.kt`, `PlannedRoutes.kt` | `--trackstest`, `--supporttest`, `--basetest`, `--anytype` |
| Radio call text | **nowhere.** BMS assembles calls from `Data/Sounds/CommFile.xml` fragments and draws the subtitles itself; shared memory, the Tacview stream and the logs have nothing | — | the mission log derives events instead |
| Carrier flight decks | **not BMS** — published dimensions of each class (deck length, width to port and starboard, angled-deck angle, landing-area length, ski-jump angle, island). BMS supplies only where the ship is and which way it lies | `tools/curated/carriers.json`, `ships.mjs` | look at one: a deck should read as a carrier from above. No ramp is drawn — a ship turns, and its spot numbers turn with it |
| Config options (the Config page) | `User/Config/Falcon BMS.cfg`, the stock file BMS ships with every option at its default and most lines carrying BMS's own comment | `cfgcatalog.mjs` + `tools/curated/cfgnotes.json` | `node src/cfgcatalog.mjs` prints the count, the groups, and any option left without a description |

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

## Rules that hold everywhere

- **Verify against something BMS itself produces**, not against a number that looks plausible: the shipped parking
  charts, the ground AI's own network, the known airfield positions, the struct sizes. Every hard bug in this
  repo's history was caught by one of those and would not have been caught by inspection.
- **A silent failure is worse than a loud one.** Anything that can produce empty output — a missing tool, a format
  that changed — must stop the run or print a count, never quietly ship a diagram with nothing on it.
- **Look at the output.** Renderers are checked in (`render-debug.mjs`, `--maprender`) for exactly this reason.

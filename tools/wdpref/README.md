# wdpref — what the real Weapon Delivery Planner answers

A small harness that loads Falcas's `WeaponDeliveryPlanner.exe` and asks it, by reflection, for the numbers it
computes over a grid of inputs. The Kotlin port is then checked against that CSV rather than against somebody's
reading of the decompiled source (`--wdpporttest`). See `docs/WDP-PORT.md`.

It ships no part of WDP and writes nothing into its folder.

```bash
dotnet build                     # x86 and net48, because WDP is
# run it from WDP's own folder so its dependencies resolve:
wdpref.exe WeaponDeliveryPlanner.exe wdpref.csv
```

A page's reference is `wdpref page <Name> WeaponDeliveryPlanner.exe <out.tsv>` (`<Name>.cs` here). `Coords` also
reads a Falcon BMS install, only reading it (`BMS_ROOT=<the BMS folder>`, else the registry's): it has WDP set up
every theater's map projection and writes the coordinates it prints, for `--wdppagetest coords`.

`PerfCase` is the Performance page end to end: it reads the cases `--wdppagetest performance <x>.cases.tsv` writes
(`<x>.cases.tsv.in`) and answers them in `<x>.cases.tsv`. Run it from a folder of its own with a copy of a
Setup.ini beside it (WDP reads Setup.ini beside the running program), never from WDP's folder; it resolves WDP's
assemblies from the folder of the `.exe` it is given.

**A mission of your own.** `Popup`, `Hadb` and `TossCase` also take their sequences from a file: `POPUP_CASES`,
`HADB_CASES`, `TOSS_CASES=<file>`, one sequence a line (`op;op;…`, the ops each file lists). With them a page can be
put on a real flight the way `fclsMain` puts it there — `flight=`/`fwp=` (the save's grid cells), `te=`/`camp=` (the
tables `CreateFlightplan` fills), `prec=`, `src=Both`, `camptecall` — and driven as a pilot drives it. `kto=<folder>`
loads a theater's new terrain as `fclsMain.InitNewTerrain` does (the folder holds `NewTerrain\Theater.txt` and
`NewTerrain\Heightmaps\HeightMap.raw`; a sparse copy with BMS's heights at the points you need is enough), so the
latitude and longitude and the Pop-up page's ground under the target are WDP's own; `POPUP_HEIGHTMAP=<file>` replaces
the random terrain the Pop-up reference is otherwise made on. `TossCase` needs `TOSS_PICTURES=<WDP's Pictures folder>`.

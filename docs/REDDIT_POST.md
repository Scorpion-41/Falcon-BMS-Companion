# [Release] Falcon BMS Companion: live mission data, AWACS picture, EZBoards and a full offline reference on your Android tablet

I got tired of alt-tabbing out of BMS to check a frequency, find the alternate's ILS, re-read the loadout or run EZBoards. So I built an Android companion app for phone and tablet with one goal: **never alt-tab again.**

It has two parts:
- **Mission section (live).** A small Windows program, **BMS Companion Bridge**, reads your current mission and streams the raw data to the tablet over your home network. The tablet draws everything natively (no screen mirroring, no image streaming).
- **Offline reference.** Aircraft, loadouts, Threat Guide, airfields and charts, HOTAS, checklists and a bullseye trainer, all extracted from the game files. This part works with no PC at all.

Free, no ads, no accounts. The bridge only talks on your local network.

## 📸 Screenshots
*(Upload these from `docs/screenshots/` as a Reddit image gallery, in this order, with these captions.)*

| # | File | Caption |
|---|---|---|
| 1 | `mission-map.jpg` | Live map on a tablet: flight plan, SAM rings, AWACS picture (a missile in flight too) and the hostile picture list |
| 2 | `mission-map-threat.jpg` | Tap anything: bullseye position, BRAA, "you are inside this ring" |
| 3 | `mission-flight.jpg` | Flight data as a clean table, with the RWR scope and a DED replica |
| 4 | `mission-briefing.jpg` | Briefing: TOT, home plate and alternate with TACAN/ILS, flight plan with bullseye |
| 5 | `mission-loadout.jpg` | Loadout per jet (tap a store to open its Arsenal page), package and support |
| 6 | `mission-comms.jpg` | Comm ladder with your tuned frequency highlighted, plus DTC presets |
| 7 | `mission-ezboards.jpg` | Generate EZBoards kneeboards from the tablet, with the kneeboard content shown natively |
| 8 | `airfield-charts.jpg` | One tap from the briefing to the airfield page and its charts |
| 9 | `phone-mission-map.jpg` | The same on a phone |
| 10 | `home.jpg` | Home: live mission plus the offline reference |
| 11 | `threat-guide.jpg` | Threat Guide with envelopes, RWR symbols and HARM codes |
| 12 | `hotas.jpg` | Real HOTAS illustrations: tap a switch to see what it does |
| 13 | `arsenal.jpg` | 323 flyable aircraft with station-by-station loadouts |
| 14 | `bullseye-trainer.jpg` | Bullseye trainer mini-game |
| 15 | `bridge-setup-guide.jpg` | The PC bridge's setup guide checks your BMS config for you |

*Screenshots use the bridge's demo mode (a synthetic SEAD mission in Hellas).*

---

## 🛰️ Mission section (with the PC bridge)

### Map
- **Your jet** on the theater map with the **flight plan**, **target steerpoints**, **PPT threat rings**, markpoints and **bullseye rings**.
- The **AWACS picture**: friendly and hostile aircraft, helicopters, ships and **missiles in flight**, each with speed vectors, callsigns and altitude. It comes from BMS's built-in Tacview real-time telemetry, and Tacview itself is not required.
- **Tap anything** to get **BRAA from you**, **bullseye position** and **aspect** (hot/flank/beam/drag). Tap the map to get the bullseye call for any point.
- Home plate and alternate are highlighted. Tap them for the **airfield page and charts** (runways, ILS, TACAN, tower).
- Follow mode and layer toggles. On a tablet you get a side panel with ownship data, a **"Picture" list** of hostiles sorted by range, and your steerpoints.

### Flight
- A **clean table** you can read at a glance: ALT, KIAS/Mach, heading, GS, **fuel vs bingo**, chaff/flares, your **bullseye position**, VVI, G, TACAN, UHF, Zulu.
- An **ALR-56M-style RWR scope** with launch and lock warnings, and a readable emitter list ("SA-5, 6 o'clock, LOCK").
- A **DED replica**.

### Briefing
- Mission, package, **T/O, push and TOT**.
- **Departure, recovery and alternate** with TACAN, tower, ILS and runways. One tap opens the charts.
- **Flight plan** with TOS, altitude and bullseye bearing/range for every steerpoint. Tap one to see it on the map.
- **DTC targets**, the package with roster, and **loadout per jet** (tap a store to open its Arsenal page).
- **Threats linked to the Threat Guide** (tap "SA-5" to see its envelope and RWR symbol), plus support (AWACS/tanker), weather, ROE and emergency procedures.

### Comms
- The comm ladder, grouped, with **the frequency you have tuned highlighted**.
- DTC UHF/VHF presets, IFF and Link 16.

### Boards: EZBoards from the tablet
- Press PRINT in BMS, then tap **Generate kneeboards** on the tablet. EZBoards runs **hidden** on the PC and you get a ✅ success or ❌ error with the log. You can also let the bridge run it **automatically every time you PRINT**.
- The kneeboard content is also shown on the tablet: package with TACANs, comm ladder, steerpoints with **min fuel**, targets and weather.

### Setup
- **Auto-discovery** (tap "Find bridge") or manual IP.
- A built-in guide covers the BMS settings you need (briefing to file, DTC save, the Tacview real-time cfg line), the firewall, and troubleshooting.
- **Demo mode** in the bridge (off by default) lets you try every screen without BMS running.

---

## 📚 Offline reference (no PC needed)

- **Arsenal:** 323 flyable aircraft types from KTO and every add-on theater, with per-theater variants, specs, **station-by-station loadouts with rack capacity**, a weapon × station matrix, and 518 stores.
- **Threat Guide:** SAMs, radars, AAA, MANPADS, fighters, AAMs and ships, each with RWR symbols, HARM/ALIC codes, ranges, tactics and **engagement-envelope diagrams**. Full HARM & RWR tables are included.
- **Airfields:** **519 unique airfields** in the 5 theaters that have their own map (KTO, Balkans, Hellas, Israel, Falklands). Runways, ILS per runway end, TACAN, all frequencies, ATC patterns, minima, nearest diverts, navaids and **1,386 airport/parking charts**. There is also a zoomable theater map with a search box for ICAO, TACAN, navaid or town.
- **Cockpit:**
  - **Real HOTAS illustrations** for the F-16C/D and F-15C (from the Dash-34/Dash-1). Tap a switch to see what it does in each master mode.
  - Checklists with tick-off (F-16CM, F-16CJ, F-15C).
  - 590 brevity words, CAS/9-line, ATC, tanker and AWACS calls.
  - Calculators.
- **Bullseye Trainer:** 5 game modes (plot the AWACS call, call your bullseye, BRAA from bullseye, clock drills, real theater map) and 3 difficulty levels. Scoring is forgiving for finger taps.
- **Global search** across everything (`SA-10`, `RKTN`, `75X`, `108.7`, `BANDIT`), plus favorites.
- The UI is dark and cockpit-friendly. **Tablet layouts** have a navigation rail and multi-column pages; phones get a normal bottom bar. It runs fullscreen.

---

## ⚙️ Setup in 2 minutes
1. Run `BMSCompanionBridge.exe` on the BMS PC. It's a single file with no install. On first launch a **setup guide** checks every step for you live (BMS found, firewall, app connected, briefing settings, DTC, AWACS feed, EZBoards/.NET 8), with buttons to fix things.
2. On the tablet, go to Mission → Setup → **Find bridge**.
3. In the BMS Launcher, Config → General → Briefing: ✅ *Briefing Output to File*, ❌ *HTML Briefings*. In the mission, press **PRINT** on the Briefing screen and **SAVE** the DTC.
4. For the AWACS picture, add `set g_bTacviewRealTime 1` to `Falcon BMS User.cfg` and turn on ACMI recording in 3D.

## 🔧 How it works / how it was built
I built it with **Claude Code** (Anthropic's AI coding agent) against my own BMS 4.38 install. Everything is read from the game, never retyped:

- **Live data:** the bridge (C# / .NET 8) reads **BMS shared memory**: FlightData, FlightData2 and the StringData area for steerpoints, PPTs, theater, aircraft and the tanker/AWACS/airbase names. It parses the **briefing.txt** from PRINT and your **DTC .ini**, and connects to BMS's **Tacview real-time telemetry** server for other aircraft. A tiny built-in HTTP server sends compact JSON, and the app polls it 4× per second.
- **EZBoards:** the bridge launches `EZBOARDS.BAT` with no console window, checks its exit code and `SUCCESS.` line, and reads the kneeboard tables with EZBoards' own `xbrief`, so the tablet shows the same data as the kneeboard.
- **Reference data:** a Node.js extractor reads the objects database (`Falcon4_*.xml`), `BmsRack.dat`, `TacRefDB`, CampObjData, Stations+Ils, ATC files, runway geometry and the terrain heightmaps, and renders the theater maps. The Threat Guide, HOTAS, checklists and comms were transcribed from the BMS manuals.
- **App:** Kotlin + Jetpack Compose (Material 3). Reference data ships inside the APK.
- **Open source:** the repo has a step-by-step checklist for updating to the next BMS version.

## ⬇️ Downloads
- **BMS-Companion.apk** (~64 MB, airport charts included)
- **BMSCompanionBridge.exe** (Windows, self-contained)

*(GitHub link here)*

## Notes
- Targets **BMS 4.38** and the add-on theaters I have installed. The extractor and bridge are built to be updated when a new version drops.
- The AWACS picture shows whatever the BMS telemetry stream contains. There is a "Hostiles" toggle if you prefer friendlies only.
- Unofficial fan project, **not affiliated with Benchmark Sims**. Game data, docs, pictures and charts belong to BMS and the add-on theater teams, and EZBoards is by Logic. Huge thanks to all of them.

Feedback and bug reports are welcome, especially from multiplayer and other theaters.

# Falcon BMS Companion

A companion for **Falcon BMS 4.38** on **Windows PCs, Android phones and tablets, and iPhone, iPad or any browser**. The goal is simple: **never alt-tab out of the sim.**

- **Live Mission section:** the moving map with the AWACS picture, ownship data, RWR, DED, the briefing, loadout, comm plan and steerpoints, **tankers & support** (TACAN, UHF, position), one-tap **EZBoards** kneeboards, a **customizable Dashboard** and an **AWACS/GCI** page for a human controller.
- **Maps in four styles** (relief, satellite, dark, chart) with sharp detail when zoomed in, **country and province borders**, and **towns**, all lined up with the campaign grid.
- **Offline reference:** aircraft and loadouts, the Threat Guide, HARM/RWR, airfields with charts, HOTAS, checklists, comms and a bullseye trainer. All of it is extracted from the game files.
- **Media:** your BMS screenshots on any device: browse, view, share, download and delete.
- **One PC program:** BMS Companion for Windows reads Falcon BMS, serves your phones, tablets, browsers and other PCs, and is the full app too, in one window: a light **server page**, or the **full app** (borderless full screen with F11). On a laptop it runs as a **client** of the BMS PC.
- **Real browser version:** iPhone, iPad, Mac, Chromebook… open the address and the whole app runs **in the browser itself** (WebAssembly), as smooth as the native apps. Nothing to install.

> Unofficial fan project, not affiliated with Benchmark Sims. See [Credits & legal](#credits--legal).

| Dashboard (PC) | AWACS / GCI | Phone browser |
|---|---|---|
| ![Dashboard](docs/screenshots/pc-dashboard.jpg) | ![AWACS](docs/screenshots/awacs.jpg) | ![Phone browser](docs/screenshots/browser-phone-mission.jpg) |
| **Server page on the BMS PC** | **Full screen on a second monitor** | **Briefing** |
| ![Server page](docs/screenshots/pc-server.jpg) | ![Full screen](docs/screenshots/pc-fullscreen.jpg) | ![Briefing](docs/screenshots/mission-briefing.jpg) |
| **Live map & AWACS picture (tablet)** | **EZBoards from the tablet** | **Airfield charts** |
| ![Mission map](docs/screenshots/mission-map.jpg) | ![EZBoards](docs/screenshots/mission-ezboards.jpg) | ![Charts](docs/screenshots/airfield-charts.jpg) |

More in [docs/screenshots](docs/screenshots). What changed in each version: [CHANGELOG.md](CHANGELOG.md).

---

## Contents

- [Which files do I need?](#which-files-do-i-need)
- [Features](#features)
- [Download & install](#download--install)
- [Quick setup](#quick-setup) (full guide in [docs/SETUP.md](docs/SETUP.md))
- [How it works](#how-it-works)
- [Repository layout](#repository-layout)
- [Building](#building)
- [Updating for a new BMS version](#updating-for-a-new-bms-version)
- [Credits & legal](#credits--legal)

## Which files do I need?

Every release has three files. Falcon BMS runs on a Windows PC, so **the BMS PC always gets BMS Companion for Windows**; it reads the sim and serves every other device. What else you download depends on where you want to see the companion:

| Your setup | Download for the BMS PC | Download for the other device | How to connect |
|---|---|---|---|
| **One PC only** (second monitor, or next to a windowed sim) | `BMS-Companion-PC.msi` | — | Press **Open the full app** on the server page. **F11** for borderless full screen on the second monitor. |
| **PC + Android phone or tablet** | `BMS-Companion-PC.msi` | `BMS-Companion.apk` (install on the phone/tablet) | Android app: Mission → Setup → **Find BMS PC**. |
| **PC + iPhone or iPad** | `BMS-Companion-PC.msi` (keep **Browser access** on) | nothing to download | Scan the QR code on the server page with the camera, or open the address in Safari (iOS/iPadOS **18.2 or newer**). **Share → Add to Home Screen** for an app icon. |
| **PC + any web browser** (Mac, Linux, Chromebook, a TV browser, a friend's tablet) | `BMS-Companion-PC.msi` (keep **Browser access** on) | nothing to download | Open the address shown on the server page in a current Chrome, Edge, Firefox or Safari. |
| **2 PCs** (BMS PC + a laptop or second PC with the full app) | `BMS-Companion-PC.msi` | `BMS-Companion-PC.msi` on the laptop too | Laptop: **Open the full app** → Mission → Setup → **On another PC** → **Find BMS PC**. |
| **2 PCs, the second one only in a browser** | `BMS-Companion-PC.msi` | nothing to download | Open the address shown on the server page in the second PC's browser. |
| **Everything at once** (e.g. tablet + phone + iPad + laptop) | `BMS-Companion-PC.msi` | the file for each device, as above | All devices can be connected at the same time. |
| **Reference only, no PC** (aircraft, threats, airfields and charts on the go) | — | `BMS-Companion.apk` | Nothing to connect; the reference works offline. |

- **`.msi` or `.zip`?** Both are the same Windows program. The **MSI** installs it (Start menu, desktop shortcut, updates in place). The **zip** is portable: unzip anywhere and run `BMS Companion.exe`. Pick one.
- **The browser version needs the PC:** iPhone, iPad and browsers load the app from BMS Companion on the BMS PC, over your home network. There's no App Store app.
- **Coming from 1.2?** `BMSCompanionBridge.exe` is no longer used. Exit it (tray icon → Exit), delete it and install `BMS-Companion-PC.msi`; it uses the same port and keeps your settings. Android: install the new APK over the old one.

## Features

### Mission (live)
| Tab | What you get |
|---|---|
| **Dashboard** | Your own page built from cards: live map, ownship, RWR, DED, picture, fuel (endurance, time to bingo, fuel to get home), time & TOT countdowns, bullseye, threat rings, steerpoints, mission, airbases, comm ladder, radio presets, tankers & support, kneeboards. **Edit** to add, move, resize (S/M/L) and remove cards, or pick a preset (Pilot, Cockpit, Navigator, Pre-flight). Small cards stack beside a wide map. Phones and wide screens keep their own layouts. |
| **Map** | Theater map with your jet, the flight plan, target steerpoints, PPT threat rings, markpoints, bullseye rings, and the mission's home/alternate fields. It also shows the **AWACS picture**: friendly and hostile air, helicopters, ships and missiles, each with a speed vector, callsign and altitude; **tankers** (green, with a boom) and **AWACS/JSTARS** (purple, with a rotodome) stand out. If BMS isn't streaming yet, a reminder tells you to press **F** (ACMI recording) in the cockpit. Tap anything to get BRAA, bullseye position and aspect, or to open the airfield and its charts. Follow mode and layer toggles are included, plus the **Map** menu (style, borders, provinces, towns; see [Maps](#maps)). |
| **AWACS** | For a **human AWACS/GCI** in multiplayer: the whole air picture with smooth motion, trails, speed vectors (where each aircraft will be in 1, 2 or 3 min), hostile group circles, radar-lock lines and labelled bullseye rings. **Picture** panel with a ready-to-read picture call, hostile groups and friendlies (altitude, speed, fuel). **Contact** details with bullseye and BRAA calls. **Control** a flight: its threats with BRAA, aspect, closure and time to merge, BRAA and intercept **vector** calls, nearest field and fuel to reach it. **Measure** A→B (contacts or map points): bearing/range, closure, intercept heading, time and fuel. **Calc**: time and fuel for a distance, bullseye→BRAA converter, time to merge. **Layers**: filters, altitude band, group radius, commit range. **Alerts** for merges, commit range, radar spikes, missiles and low fuel. |
| **Flight** | An easy-to-read table: altitude, KIAS/Mach, heading, GS, fuel vs bingo, chaff/flares, bullseye position, VVI, G, TACAN (the channel you tuned, plus UFC and AUX COMM), UHF and Zulu time. There is also an ALR-56M-style **RWR scope** (launch and lock warnings), a **DED** replica and the hostile **picture** list (nearest threats first, ejected crews last). |
| **Briefing** | Mission, package and TOT; departure, recovery and alternate airbases (TACAN, tower, ILS, runways, one tap to charts); the flight plan with TOS and bullseye; DTC targets; the package and roster; **loadout** per jet (tap a store to open the Arsenal page); threats linked to the Threat Guide; **tankers & support**; weather; ROE; emergency procedures. |
| **Comms** | The comm ladder, grouped, with the tuned UHF frequency highlighted. **Tankers & support**. DTC UHF/VHF presets, IFF and Link 16. |
| **Boards** | **Generate kneeboards** with EZBoards from any device. The console runs hidden on the PC and you get success or error feedback plus the log. The kneeboard content (package, comm ladder, steerpoints with min fuel, targets, weather) is shown natively. |
| **Setup** | Connection, and step-by-step guides for every kind of device, BMS configuration and troubleshooting. |

**Tankers & support** (Dashboard card, Briefing and Comms): every tanker, AWACS, JSTARS and FAC of the mission in one table, like a support board: role, callsign and aircraft, **TACAN** (with the channel to set to tie on), **UHF** with its preset, **location** from bullseye and altitude when airborne, every **comm channel** the briefing gives for it (e.g. AWACS Check-In ch 5 and Tactical ch 6, Tanker ch 13; preset numbers also matched against your DTC), and the briefing notes. Your own tanker/AWACS is marked. Sources: the printed briefing (support list, package, comm ladder), the theater's radio plan (in BMS a callsign always has the same frequency), the AWACS feed, and BMS's tanker TACAN channels (first tanker 92Y, then 126Y, 125Y…, marked with * when not printed in the briefing).

### Maps
Every map (Mission map, Dashboard map, AWACS, Airfields) has **+ / −** zoom buttons (besides pinch, double-tap and the mouse wheel) and a **Map** menu:
- **Styles:** **Relief** (shaded terrain from the BMS heightmap), **Satellite** (the sim's own photoreal ground texture), **Dark** (muted, for busy AWACS pictures) and **Chart** (light, paper-like). All styles line up exactly: switching never moves anything.
- **Zoom levels:** a quick overview, then sharper tiles as you zoom in, up to 8192 px across a theater (about 125 m per pixel on the 1024 km theaters). Everything is included in the apps; nothing to download.
- **Landmarks** (on/off): **country borders** as a clear line with the country names, **provinces/governorates** as faint dashed lines with their names, and **towns** as a faint dashed circle with the name (cities, towns and villages appear as you zoom in; labels never overlap).
- **Towns → Mission** (the default): only the towns that matter for your mission: those the briefing names, the nearest town to each steerpoint, target, threat and airbase, and the larger towns along the route. The **target town** (e.g. "destroy the S-60 AAA above X", or the town at your target steerpoint) gets a solid amber ring with a target mark and a bold name.
- Borders and names come from Natural Earth and are projected with each theater's own map projection (`NewTerrain/Theater.txt`), checked against real airport positions (median error under 0.4 nm).

### Media (screenshots)
The screenshots you take in Falcon BMS (`User\Pictures`, or `g_sPicturesDirectory`), on any device: a gallery grouped by day, a full-screen viewer with zoom and previous/next, multi-select, and **delete** (moved to the Recycle Bin on the BMS PC, so it can be undone). It is the last section of the navigation.
- **Android:** Share (any app) and **Download to this device** (Pictures/BMS Companion).
- **Browsers (iPhone, iPad…):** **Download to this device** and **Share** (the system share sheet), one or several at once.
- **PC client (laptop):** **Download to this PC** (Downloads\BMS Companion), copy to the clipboard, save a copy.
- **On the BMS PC:** copy to the clipboard, save a copy, open the screenshot folder.

### Offline reference (no PC needed)
- **Arsenal:** 323 flyable aircraft types (KTO + every add-on), per-theater variants, specs, station-by-station loadouts with rack capacity, and 518 stores.
- **Threat Guide:** SAM, AAA, radars, MANPADS, aircraft, AAMs and ships, with RWR symbols, HARM/ALIC codes, engagement envelopes and a range chart. HARM & RWR tables are included.
- **Airfields:** 519 unique airfields across the 5 theaters with their own map (KTO, Balkans, Hellas, Israel, Falklands). Runways, ILS, TACAN, frequencies, ATC patterns, nearest diverts, navaids and 1,386 charts. Also a searchable, zoomable theater map in every map style, with borders and towns.
- **Cockpit:** real HOTAS illustrations for the F-16C/D and F-15C (tap a switch to see what it does), checklists with check-off, comms/brevity, calculators.
- **Bullseye Trainer:** 5 game modes and 3 difficulty levels.
- **Global search** and favorites. The UI is dark and adapts to phone and tablet (a navigation rail and split panes on tablets and PCs).

### BMS Companion for Windows
- **One program, one window, two faces**, remembered between runs:
  - **Server page** (first start): a single light page for a PC that serves your devices. Live status (Falcon BMS, AWACS feed, briefing, connected devices), **Connect your devices** with the browser address and a QR code, the **setup checklist** with live ✓/✕ checks and a fix button for each step, Falcon BMS settings, recent activity and start-up options. The full app isn't loaded, so it uses little memory.
  - **Full app**: every section, with data from Falcon BMS on this PC, or from the BMS PC on the network (**On another PC**, for a laptop client). A big **Open the full app** button on the server page, and a **Server** button in the app (bottom of the navigation rail and the Mission header) switch in one click.
- **Borderless full screen**: **F11** or the full-screen button fills the monitor the window is on (Esc or F11 to leave). Keep window on top with the pin button.
- **Only one copy runs**: opening it again brings the running window forward, also from the tray.
- **Tray icon**: open, switch between server page and full app, browser access on/off, exit. Closing the window keeps it serving devices in the tray (optional), and it can start with Windows.
- **Falcon BMS settings in the app:** BMS, EZBoards and screenshot folders, EZBoards on PRINT, Tacview stream on/off with host, port and password, demo mode, network port, firewall rules (one prompt), edit `Falcon BMS User.cfg`.
- PC extras: mouse-wheel zoom on maps and charts, UI zoom with **Ctrl +/−/0**, dark title bar, remembered window size and position.

## Download & install

Get the files from the **[latest release](https://github.com/Scorpion-41/Falcon-BMS-Companion/releases/latest)** (which ones you need: [Which files do I need?](#which-files-do-i-need)):

| File | What |
|---|---|
| `BMS-Companion-PC.msi` | **BMS Companion for Windows**: reads Falcon BMS, serves your devices and browsers, and runs the full app (per-user install with Start menu and desktop shortcuts, includes its own Java runtime and the browser version; ~280 MB). |
| `BMS-Companion-PC.zip` | The same, portable: unzip anywhere and run `BMS Companion.exe` (~280 MB). |
| `BMS-Companion.apk` | Android app, including the airport charts and all map styles and zoom levels (~180 MB). |

Install the APK on the phone or tablet (allow "install unknown apps"), or use `adb install -r BMS-Companion.apk`. iPhone and iPad need nothing: see [Quick setup](#quick-setup).

The installer isn't code-signed, so Windows SmartScreen may ask you to confirm (**More info → Run anyway**). Coming from version 1.2? The separate `BMSCompanionBridge.exe` is no longer needed: close and delete it (BMS Companion uses the same port).

## Quick setup

1. **BMS PC:** install `BMS-Companion-PC.msi` and start it. It opens on the **server page**. When Windows asks, allow private networks (or press **Allow through Windows Firewall**). Flying with the app on this PC? Press **Open the full app**.
2. **Falcon BMS:** in the Launcher, **Config → General → Briefing**: tick *Briefing Output to File*, untick *HTML Briefings*. In the mission Briefing screen press **PRINT**, and **Save** the DTC.
3. **AWACS picture:** add these to `User\Config\Falcon BMS User.cfg` (the checklist has an edit button), then turn on ACMI recording in 3D:
   ```
   set g_bTacviewRealTime 1
   set g_bTacviewAcmi 1
   ```
4. **Devices:**
   - **Android:** Mission → Setup → **Find BMS PC** (same Wi-Fi/LAN).
   - **iPhone/iPad/browser:** scan the QR code on the server page, or open the address it shows (e.g. `http://192.168.1.20:47474`). On iPhone/iPad, **Share → Add to Home Screen** for a full-screen icon.
   - **Laptop/second PC (client):** install BMS Companion, **Open the full app**, choose **On another PC** in Mission → Setup, then **Find BMS PC**.
5. **EZBoards** ships with BMS in `Tools\EZBoards` and is found automatically. It needs the .NET 8 runtime.

No BMS at hand? Turn on **Demo mode** in the Falcon BMS settings to try every Mission screen with a synthetic mission.

The full guide is in [docs/SETUP.md](docs/SETUP.md). The network API is described in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## How it works

```
Falcon BMS (shared memory, briefing.txt, DTC .ini, Tacview RT stream, EZBoards, screenshots)
   │ read-only
   ▼
BMS Companion for Windows ──┬─ its own window: server page or full app (reads BMS directly)
   HTTP 47474, UDP 47475    ├─ Android app, client PCs: mission API (/api/…), discovery on UDP 47475
                            └─ browsers: the web app (/) runs on the device, data from /api and /assets
```

| Data | Source on the PC |
|---|---|
| Ownship, RWR, DED, steerpoints, PPTs, bullseye, theater, aircraft, TACAN | BMS shared memory: `FalconSharedMemoryArea`, `…Area2`, `…AreaString` (layout from `Tools\SharedMem\FlightData.h`) |
| Other aircraft (AWACS picture) with speed, fuel and radar locks | BMS's built-in Tacview real-time telemetry server (`g_bTacviewRealTime`, port 42674) |
| Briefing, package, loadout, comm ladder, weather | `User\Briefings\briefing.txt` (the PRINT button; honours `g_sBriefingsDirectory`) |
| Steerpoint coordinates before 3D, targets, PPTs, radio presets | `User\Config\<callsign>.ini` (DTC Save) |
| Kneeboard tables | EZBoards `bin\xbrief.exe` (read-only, output to memory) |
| Screenshots (Media) | `User\Pictures` (or `g_sPicturesDirectory`); thumbnails and previews are made on the PC |
| BMS folder, pilot callsign, theater | Registry `HKLM\SOFTWARE\WOW6432Node\Benchmark Sims\Falcon BMS 4.xx` (newest version found) |

BMS Companion only reads BMS files, with two exceptions you trigger yourself: it runs EZBoards (which then writes the kneeboard textures as it always does), and it moves screenshots you delete in Media to the Recycle Bin.

The Android app, the PC program and the browser version are built from the same Kotlin/Compose code, so every device shows the same screens. The browser version is that code compiled to WebAssembly: it runs on the phone or tablet like a native app, and the PC only sends it data.

## Repository layout

```
app/                     Android app (Kotlin, Jetpack Compose, Material 3)
  src/main/assets/       extracted game data (JSON/WebP), airport charts, maps/<theater>/<style> tiles and data/geo landmarks, generated by tools/extractor
  src/main/java/com/bmscompanion/app/
    data/                models + asset repository
    data/mission/        mission client (HTTP polling, UDP discovery) and API models
    ui/screens/          reference screens, Media (screenshots)
    ui/screens/mission/  Mission section: tabs (MissionTabs), Dashboard, Map, AWACS (+ AwacsTools), Flight, Briefing, Comms, Boards, Setup
desktop/                 BMS Companion for Windows (Kotlin, Compose for Desktop). Compiles app/src/main/java as-is, plus:
  src/main/kotlin/overrides/   PC versions of the Android-only files (Repo, MissionLink, map/chart zoom, Mission screen & setup)
  src/main/kotlin/shims/       tiny stand-ins for the Android APIs the shared screens call (Uri.encode, Bitmap, screen width)
  src/main/kotlin/com/bmscompanion/desktop/
    Main.kt                    the window (server page or full app, full screen), tray, single instance
    PcConfig.kt, PcServer.kt   mode and services; the HTTP server (mission API, web app, bundled data)
    bridge/                    reads Falcon BMS: shared memory, briefing/DTC parsers, Tacview client, EZBoards, screenshots, demo
    ui/                        server page and settings cards
web/                     browser version (Kotlin/Wasm, Compose for Web). Compiles app/src/main/java as-is, plus:
  src/wasmJsMain/kotlin/overrides/   browser versions of Repo (data over HTTP, settings in the browser) and MissionLink
  src/wasmJsMain/kotlin/shims/       stand-ins for the JVM and Android APIs the shared screens call
pc/publish-desktop.ps1   builds BMS Companion for Windows (dist/BMS-Companion-PC.msi and .zip, the browser version inside)
tools/extractor/         Node.js extractor: BMS install -> app assets (read-only)
tools/curated/           data transcribed from the BMS manuals (threats, HOTAS, checklists, comms, HARM/RWR)
docs/                    SETUP, PROTOCOL, UPDATING, screenshots
CHANGELOG.md             what changed in each version
CLAUDE.md                orientation notes for AI-assisted maintenance (Claude Code)
```

## Building

**Requirements:** JDK 17 (with jpackage, for the PC installer), Android SDK (API 35), Node 18+ (extractor only). The browser version's build downloads its own Node.js, Yarn and Binaryen into the Gradle cache.

```bash
# Android app (debug on a connected device / release APKs)
./gradlew installDebug
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk

# BMS Companion for Windows (the browser version is built and packed in automatically)
./gradlew :desktop:run                                            # run from source
powershell -ExecutionPolicy Bypass -File pc/publish-desktop.ps1  # dist/BMS-Companion-PC.msi + .zip

# Browser version on its own
./gradlew :web:wasmJsBrowserDistribution   # web/build/dist/wasmJs/productionExecutable

# Regenerate reference data from a BMS install (read-only)
cd tools/extractor && npm install
BMS_ROOT="D:/Falcon BMS 4.38" node src/main.mjs   # aircraft, weapons, airports, theaters, images
BMS_ROOT="D:/Falcon BMS 4.38" node src/charts.mjs # airport charts
BMS_ROOT="D:/Falcon BMS 4.38" node src/maps.mjs   # map styles and tile levels (uses texconv from EZBoards; ~30 min)
BMS_ROOT="D:/Falcon BMS 4.38" node src/geo.mjs    # borders, provinces and labels (Natural Earth files in tools/extractor/cache/ne)
```

Release APKs are signed with the debug key so anyone can build and side-load them. Use your own keystore if you publish to a store.

The PC installer is built with jpackage (WiX is downloaded automatically by the Compose Gradle plugin).

PC program switches: `--tray` (start in the tray, used by "Start with Windows"), `--route <route>` or env `BMSC_ROUTE=mission` (open a screen directly). Development: env `BMSC_DEMO=1` (demo mission for this run), `BMSC_PORT=<port>` (another network port for this run). Developer checks: `--selftest out.txt` (shared memory struct sizes and parser output), `--dumpstrings out.txt` (StringData from a running BMS), `--eztest <EZBoards copy> out.txt` (runs EZBoards like the app button), `--api /api/info,/api/mission out.txt` (API responses from BMS on this PC), `--maprender <theater> <folder> [xFt,yFt]` (every map style at three zoom levels as PNGs, to check landmarks and alignment). The browser version accepts `?route=mission` in its address.

## Updating for a new BMS version

See **[docs/UPDATING.md](docs/UPDATING.md)**. It is a checklist written so it can be handed straight to Claude Code: re-run the extractor, diff `FlightData.h`, compare a fresh `briefing.txt`, check the add-on theaters, bump versions.

## Credits & legal

- Unofficial fan project, **not affiliated with Benchmark Sims**. Falcon BMS, its data, documentation, TacRef pictures, HOTAS illustrations and airport charts belong to Benchmark Sims and the respective add-on theater teams. This repository contains data extracted from the game install for personal reference use.
- **EZBoards** is by "Logic" and ships with BMS. This project only launches it (and the extractor uses its bundled Microsoft `texconv` to decode the BMS ground texture).
- Borders, provinces and country/region names: **[Natural Earth](https://www.naturalearthdata.com)** 1:10m data, public domain.
- Tacview real-time telemetry is a protocol by Raia Software, implemented natively by BMS.
- App, PC program and browser version code: built with Claude Code.

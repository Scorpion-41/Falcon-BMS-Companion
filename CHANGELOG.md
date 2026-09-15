# Changelog

## 1.3 (BMS 4.38) — September 2026

The biggest update so far. **BMS Companion for Windows** is now one program that reads Falcon BMS, serves all your devices and is the full app itself. **iPhone, iPad and any browser** get the whole app running in the browser. New in every version: a **customizable Dashboard**, an **AWACS/GCI page** for a human controller, **maps in four styles** with sharp zoom levels, **country borders, provinces and towns**, a **mission towns view** that highlights the target town, a **Tankers & support** board with TACAN, frequencies and channels, and **Media** for your BMS screenshots. The separate `BMSCompanionBridge.exe` is gone.

### Which files do I download?

| Your setup | BMS PC | Other device |
|---|---|---|
| One PC only (second monitor) | `BMS-Companion-PC.msi` | — |
| PC + Android phone/tablet | `BMS-Companion-PC.msi` | `BMS-Companion.apk` |
| PC + iPhone/iPad | `BMS-Companion-PC.msi` | nothing: scan the QR code on the PC (iOS 18.2+) |
| PC + any web browser (Mac, Linux, Chromebook…) | `BMS-Companion-PC.msi` | nothing: open the address shown on the PC |
| 2 PCs (BMS PC + laptop with the full app) | `BMS-Companion-PC.msi` | `BMS-Companion-PC.msi` (use **On another PC**) |
| Reference only, no PC | — | `BMS-Companion.apk` |

`BMS-Companion-PC.zip` is the same Windows program as the MSI, portable (unzip and run `BMS Companion.exe`). Pick one of the two.

### New: BMS Companion for Windows (replaces the bridge)
- **The whole app on Windows**, with the same screens and features as the Android app, in a resizable window that switches to tablet layouts when wide.
- **Reads Falcon BMS itself**: shared memory, the printed briefing, the DTC, BMS's Tacview real-time stream, EZBoards and screenshots. It **replaces `BMSCompanionBridge.exe`**: same port (47474), same settings, same API, so Android devices still on 1.2 keep working.
- **One window, two faces**, remembered between runs:
  - **Server page** (first start): one light page for a PC that serves your devices. Status tiles (Falcon BMS, AWACS feed, briefing, connected devices); **Connect your devices** with the browser address and a **QR code**, plus Android and laptop instructions, the browser access switch and a firewall button; the **setup checklist** with live ✓/✕ checks and a fix button per step (BMS found, firewall, briefing export settings, DTC, AWACS feed configuration, EZBoards and .NET 8); Falcon BMS settings; recent activity; start-up options. The full app isn't loaded here, so memory use stays low.
  - **Full app**: every section. A big **Open the full app** button on the server page; a **Server** button in the app (bottom of the navigation rail and in the Mission header) switches back.
- **Client mode** for a laptop or second PC: the full app (not a web page) with the live mission from the BMS PC (**On another PC** in Mission → Setup, or **Use this PC as a client** on the server page). A client also passes the data on to browsers near it.
- **Borderless full screen** with **F11** or the full-screen button: fills the monitor the window is on and keeps your place; **F11** or **Esc** to leave. Made for a second screen next to the sim.
- **Only one copy runs**: starting it again brings the running window forward, also from the tray.
- **Tray icon**: Open, Server page, Full app, Browser access on/off, Exit. Close to tray keeps serving devices; **start with Windows** (in the tray) is optional.
- **Falcon BMS settings in the app**: BMS, EZBoards and screenshot folders, generate EZBoards automatically on PRINT, Tacview stream on/off with host, port and password, demo mode, network port, **Edit Falcon BMS User.cfg**, open the screenshot folder.
- **Firewall rules in one administrator prompt**, limited to the local subnet (TCP 47474 and UDP 47475; old bridge rules are replaced).
- PC extras: mouse-wheel zoom on maps and charts, keep window on top (pin), UI zoom with **Ctrl + / Ctrl − / Ctrl 0**, dark title bar, remembered window size and position.
- One installer with its own Java runtime and the browser version inside. BMS Companion itself needs no .NET (EZBoards still needs the .NET 8 runtime).

### New: browser version (iPhone, iPad, any browser)
- Open the address shown on the PC, or scan its QR code, and **the whole app runs in the browser itself** (compiled to WebAssembly). Scrolling, zooming, typing and the phone keyboard are local and smooth: it is not a video stream of the PC.
- Every section: Mission (Dashboard, Map, AWACS, Flight, Briefing, Comms, Boards), Media and the full offline reference with the airport charts.
- **Share → Add to Home Screen** on iPhone/iPad (or Android Chrome) gives a full-screen app icon. `?route=mission` in the address opens the Mission section directly.
- Each browser keeps its own settings (dashboard layout, map layers, favorites, theater).
- Media in the browser: **Download to this device** and **Share** (the system share sheet).
- Served by BMS Companion for Windows on the same port as everything else. Needs Safari / iOS 18.2 or newer, or a current Chrome, Edge or Firefox.

### New: Mission → Dashboard
- Build your own page from cards: **live map, ownship, RWR, DED, AWACS picture, fuel** (endurance, time to bingo, fuel to get home), **time & TOT** countdowns, **bullseye**, **threat rings**, **steerpoints**, **mission**, **airbases**, **comm ladder**, **radio presets**, **tankers & support**, **kneeboards**.
- **Edit** to add, move, resize (S / M / L) and remove cards, or start from a preset: **Pilot**, **Cockpit**, **Navigator**, **Pre-flight**.
- Small cards stack beside a wide map. Phones and wide screens keep separate layouts.

### New: Mission → AWACS (for a human AWACS / GCI)
- The whole air picture with smooth motion, trails, **speed vectors** that show where each aircraft will be in **1, 2 or 3 minutes**, hostile group circles, radar-lock lines and labelled bullseye rings.
- **Picture**: a ready-to-read picture call, hostile groups, friendlies with altitude, speed and fuel.
- **Contact**: details with bullseye and BRAA calls for anything you tap.
- **Control**: pick the flight you control to get its threats with BRAA, aspect, closure and time to merge, BRAA and intercept **vector** calls, the nearest field and the fuel to reach it.
- **Measure** A→B between contacts or map points: bearing/range, closure, intercept heading, time and fuel.
- **Calc**: time and fuel for a distance, bullseye → BRAA converter, time to merge.
- **Layers**: filters, altitude band, vector length, group radius, commit range.
- **Alerts** for merges, commit range, radar spikes, missiles and low fuel.
- Other aircraft now carry indicated airspeed, Mach, fuel and radar locks when BMS sends them, and ejected crews show as crews.

### New: maps
Every map (Mission map, Dashboard map, AWACS, Airfields) has a new **Map ▾** menu and **+ / −** zoom buttons (besides pinch, double-tap and the mouse wheel; the buttons make zooming easy in browsers).
- **Four map styles**, all lined up exactly with each other and with the campaign grid, so switching never moves anything:
  - **Relief**: shaded terrain rendered from the BMS heightmap (land below sea level such as the Jordan valley stays land; only real water is blue);
  - **Satellite**: the sim's own photoreal ground texture;
  - **Dark**: muted land and sea, easiest to read under a busy AWACS picture;
  - **Chart**: light and paper-like, like an aviation chart.
- **Sharp when zoomed in**: an overview first, then 512 px tiles as you zoom, up to 8192 px across a theater (was one 1024 px image). Included in every download, nothing extra to install.
- **Landmarks**, each switchable:
  - **Country borders** as a clear line with a dark casing, disputed lines dashed, and large faint country names;
  - **Provinces and governorates** as faint dashed lines with small italic names;
  - **Towns** as a faint, see-through area with a dashed circle and the name. **Towns** options: **Mission** (default), **Cities**, **Cities and towns**, **All places**, **Off**. Smaller places appear as you zoom in, and labels never overlap.
- **Towns → Mission** (default): only the towns that matter for your mission, so the briefing and the map make sense together. A town counts when the briefing names it (target area, situation, support notes, comm ladder), when it is the nearest town to a steerpoint, target, threat or mission airbase, or when a city or town sits right beside your route. Without a mission it shows cities.
- **The target town stands out**: the town in the target area or task (e.g. "destroy the S-60 AAA above X"), or the town at your target steerpoint, gets a solid amber ring with a target mark and its name in bold capitals.
- **Tankers and AWACS on the maps**: tankers in green with a refuelling boom, AWACS and JSTARS in purple with a rotodome, labelled **TANKER** / **AWACS**, on the Map, Dashboard and AWACS pages.
- **Press F reminder**: in 3D with no air picture arriving, the Map and AWACS pages remind you to press **F** in the cockpit (ACMI recording). BMS only streams the Tacview picture while it records.
- Borders and names come from Natural Earth (public domain) and are projected with each theater's own map projection; checked against real airport positions (median error under 0.4 nm) for Korea, Balkans, Hellas, Israel and the Falklands.

### New: Tankers & support
- One board, like a support table, with every **tanker, AWACS, JSTARS and FAC** of the mission: role, callsign and aircraft type.
- **TACAN** with the channel to **set to tie on** (63 apart). Taken from the briefing when printed there, otherwise BMS's default tanker channels from the -34 manual (first tanker 92Y, then 126Y, 125Y…, marked *; AWACS "Vector to tanker" confirms it in flight).
- **UHF** with its preset channel, and a **COMMS** line with every channel the briefing gives for that callsign, for example AWACS **Check-In 342.275 ch 5** and **Tactical 399.125 ch 6**, **Tanker 325.475 ch 13**. Preset numbers are also matched against your DTC presets. Without a ladder entry, the frequency comes from the theater's radio plan (in BMS a callsign always has the same frequency).
- **Location** from bullseye and altitude when the aircraft is airborne on the AWACS feed; **YOURS** marks your assigned tanker/AWACS; **AIRBORNE** when it's in the air. Briefing notes are shown too.
- Shown as a **Dashboard card** (also in the Pre-flight preset) and on the **Briefing** and **Comms** tabs.

### New: Media (BMS screenshots)
- The screenshots you take in BMS (`User\Pictures` or `g_sPicturesDirectory`), on every device, in a gallery grouped by day. It is the last section of the navigation.
- Full-screen viewer with zoom and previous/next; multi-select.
- **Delete** moves them to the Recycle Bin on the BMS PC, so it can be undone.
- **Share** and **download to the device**: Android to Pictures/BMS Companion, a laptop client to Downloads\BMS Companion, browsers to the browser's downloads (with the system share sheet where supported). On the BMS PC: copy to the clipboard, save a copy, open the folder.

### Also new
- **Setup guides** for every kind of device (same PC, Android, iPhone/iPad/browser, laptop client), the BMS configuration and troubleshooting.
- **Demo mode** now includes a tanker, so every Mission screen can be tried without BMS.

### Fixes and changes
- **TACAN** on the Flight tab showed the airbase TACAN from the DTC instead of the channel tuned in the aircraft. It now shows the tuned air-to-air or Y-band channel, with both the UFC and AUX COMM channels listed.
- The Mission tabs are now Dashboard, Map, AWACS, Flight, Briefing, Comms, Boards and Setup.
- Airfields map: the **Towns** switch is replaced by the **Map** menu (styles, borders, provinces, towns).
- The Android APK is larger (about 180 MB, was 64 MB) because it carries every map style and zoom level. The Windows download is about 280 MB with its own Java runtime.
- Wording: "Find bridge" is now **Find BMS PC**, and texts refer to BMS Companion on the PC instead of the bridge.

### Removed
- **`BMSCompanionBridge.exe`** and its setup window: BMS Companion for Windows reads Falcon BMS and serves your devices itself.

### Upgrading from 1.2
- **PC**: exit `BMSCompanionBridge.exe` (tray icon → Exit) and delete it. Install `BMS-Companion-PC.msi` (or unzip the portable zip). Your bridge settings (folders, Tacview, EZBoards) are kept. Press **Allow through Windows Firewall** once more.
- **Android**: install the new APK over the old one; settings and favorites stay.
- **iPhone / iPad / browsers**: nothing to install; open the address shown on the PC's server page.
- The installer isn't code-signed yet, so Windows SmartScreen may ask to confirm (**More info → Run anyway**).

### For developers
- API v1 unchanged; additions: `live.tacanUfc`/`tacanAux`, contact `ias`/`mach`/`fuelLb`/`locked`, kind `crew`, `info.media`, `/api/media…` endpoints, and the browser version's `/assets` routes (maps, tiles, `data/geo`). See `docs/PROTOCOL.md`.
- The C# bridge was ported to Kotlin (`desktop/.../bridge/`); its output was compared field by field with the C# version on demo data and on a real BMS install.
- New `web/` module (Kotlin/Wasm, Compose for Web) compiled from the same `app/src/main/java`.
- Maps: `tools/extractor/src/maps.mjs` renders `app/src/main/assets/maps/<theater>/<style>.webp` and `<style>/<z>/<row>_<col>.webp` tiles (z2–z4) from `NewTerrain/HeightMaps/HeightMap.raw` (water = flat areas, see `heightmap.mjs`) and `NewTerrain/Photoreal/GlobalColorMap.dds`; `geo.mjs` writes `data/geo/<theater>.json` (Natural Earth borders, provinces, labels) projected with `NewTerrain/Theater.txt`. Drawing and the mission towns logic are in `app/.../ui/components/MapBase.kt`.
- Developer checks moved to the PC program: `--selftest`, `--dumpstrings`, `--eztest`, `--api`, and the new `--maprender`. Env `BMSC_DEMO=1`, `BMSC_PORT`, `BMSC_ROUTE`.

## 1.2 (BMS 4.38) — September 2026
- AWACS picture list: **Hostiles** toggle; nearest hostiles and threats first, ejected crews last.
- App 1.2 (versionCode 3), bridge 1.2.0.

## 1.0 (BMS 4.38)
- First release: Android app (live Mission section with map, AWACS picture, flight data, RWR, DED, briefing, comms, EZBoards; offline reference) and the Windows bridge.

# Changelog

## 1.3.4 (BMS 4.38) — September 2026

**Files:** `BMS-Companion-PC.msi` (or the portable `.zip`) for the PC that runs Falcon BMS, `BMS-Companion.apk` for Android. iPhone, iPad and any other browser need nothing installed — they open the PC's address. Installing over 1.3.3 keeps everything you have set up.

### The map knows where the threats and the tankers are

- **Air defences, with the reach of each system drawn round them.** The systems your briefing names are matched against the app's own threat reference, so an SA-6 draws its 13 nm and a Shilka its mile and a half. Hostile red, your own side blue, one marker per site rather than one per launcher. Tap one for its range, BRAA and bullseye. What the campaign has not briefed you on stays off the map.
- **The tanker and AWACS tracks the campaign actually planned.** BMS publishes no flight plan for anyone else's aircraft, so BMS Companion reads the mission it is flying and draws the leg the tanker holds on as a twelve-mile corridor, with the transit thin behind it. Only the ones airborne while you are; several if there are several; the one assigned to your flight drawn boldly and labelled with its TACAN ("Texaco1 · TCN 92Y"). The file is only read, and only believed when it also contains your own flight plan.
- **One switch for all of it.** **SAMs** covers the briefed sites and your own DTC threat rings together.

### The Mission section, tidied

- **Six pages instead of nine, and nothing lost.** The **Flight** page is gone — ownship, RWR, DED and the picture were each already a Dashboard card, so it was a second copy of a screen you had arranged yourself. The cards stay, and the new **In flight** layout lays them out the way that page did, in one tap.
- **One Kneeboards page** for everything you print or pin in the headset: the BMS kneeboards, the VR boards and the HTML Briefing pages, which used to be a page, a page and a card on Briefing. It opens by saying what to do in BMS — print the briefing, save the DTC — states whether this mission's kneeboards exist and whether they were made automatically, and keeps **Generate now** underneath as the fallback it is. HTML Briefing starts folded away and stays however you leave it.
- **The AWACS page is off until you ask for it**, in Setup → Mission pages.
- **Your Dashboard is untouched**: every card, every layout, exactly as you left it.

### What this PC needs, checked for you

The first run of a new version says whether the optional runtimes are there — **.NET 8** for EZBoards, **WebView2** and **OpenKneeboard** for the VR boards — and each missing one has a button to its maker's own page. Nothing is installed on your behalf.

### Fixed

- **"Generate kneeboards" said it had failed when it had worked** — a run was judged by a line the stock `EZBOARDS.BAT` prints, so anyone who had rewritten that file got an error every time while their boards were written perfectly well. The exit code decides now.
- **Browsing for the EZBoards folder threw an exception.** The dialog no longer goes anywhere near the Windows shell.
- **Planned tracks sat a quarter of a mile out.** A campaign waypoint is a grid *cell* and the aircraft flies to the middle of it; the corner was being used. Measured against 381 known airfields, the error is now zero.

### Every build from here on

Installable straight from **About → check for updates**, and **keeps what you have set up**: Dashboard cards and layouts, favourites, VR boards, folded sections and the BMS, EZBoards, HTML Briefing and screenshot folders all carry across.

## 1.3.3 (BMS 4.38) — September 2026

Everything since 1.3.1. The headline is **VR kneeboards**: BMS Companion now serves numbered boards that OpenKneeboard shows on your knee in the cockpit, with your own controller buttons turning the pages. Alongside that: **instrument charts** for Korea and the Falklands, the **kneeboard exported by BMS's HTML Briefing tool** shown inside the app and in VR, **updating from inside the app**, **ACMI clean-up**, and a long list of fixes — the Android 9/10 crash, the folder picker that closed the PC app, and every installer problem reported since 1.3.

**Files:** `BMS-Companion-PC.msi` (or the portable `.zip`) for the PC that runs Falcon BMS, `BMS-Companion.apk` for Android. iPhone, iPad and any other browser need nothing installed — they open the PC's address. The APK and the PC package are larger this time: the instrument charts are bundled so they work with no connection and no BMS installed.

---

### New: VR kneeboards through OpenKneeboard

A **board** is one page of BMS Companion served at its own address — `http://<your-pc>:47474/kneeboard/1`, `/kneeboard/2` and so on — showing exactly one thing: the live map, the briefing, the flight plan, the comm ladder, your airfield plates, the hostile picture. You add each address to [OpenKneeboard](https://openkneeboard.com) as a *Web Dashboard* tab, and OpenKneeboard puts it on your knee in the headset.

**OpenKneeboard is required for the in-cockpit part, and it is free.** It also works **outside VR** — it can draw the same kneeboard into the flat 2D window — so this is worth setting up even if you do not fly in a headset.

**How to set it up**

1. On the BMS PC, open BMS Companion → **Mission → VR boards**.
2. **Add a board** for each thing you want on your knee, and choose what it shows from the drop-down. Each row hands you its address; **Copy address** puts it on the clipboard, **Preview** opens it in a window shaped like the board so you can see it before the headset goes on.
3. In OpenKneeboard: **Settings → Tabs → Add a tab → Web Dashboard**, paste the address, and give the tab a name. The name is filled in for you — the page arrives titled `BMS KB 1 (Live map)`, `BMS KB 4 (Briefing)` and so on, so a stack of tabs is readable at a glance.
4. One tab per board. Repeat for each row.
5. In OpenKneeboard: **Settings → Input → your controller → Bindings**, and bind **Next page**, **Previous page**, **Next tab** and **Previous tab**. **Toggle visibility** and **Recentre** are worth having too.

**Why there are no buttons on a board.** OpenKneeboard supports graphics tablets and nothing else in game — its own documentation says "Mice are not supported in-game" — so a board covered in controls would be a board you cannot use. Everything a finger would have done is decided beforehand on the PC, and the only interaction in the cockpit is your own **next page / previous page** binding. BMS Companion publishes each board's sheets to OpenKneeboard as pages, so that binding walks through them with nothing to aim at.

- **The pages come round again.** OpenKneeboard stops at the last page it was handed; turn past the last sheet on a BMS Companion board and the first one comes back, in either direction, for as long as you keep pressing.
- **On a map board, next page is the zoom.** A map is one sheet, so the page binding steps through five zooms instead — the whole theater down to close enough to read a taxiway, and round again.
- **A small panel names what you turned to** for a couple of seconds after every press — the chart, the section, or the zoom step ("Zoom · Wide") — then fades. In a headset it is the only confirmation that the button did what you meant.
- **What a board can show:** live map (with its own style, zoom, layers and towns), briefing, flight plan, comms ladder, tankers & AWACS, mission threats, HARM/ALIC codes, airfield plates, instrument charts, hostile picture, and the HTML Briefing kneeboard.
- **They follow the mission.** The chart boards show the field *this* mission takes off from, the threat board has a page for each system the briefing names, the briefing board is this briefing. Print a new briefing and the boards fill themselves in; **Rebuild pages** forces it.
- **Set once, for every board:** day or night ink, and the print size (Small / Normal / Large / Very large). A board is drawn at its full resolution whichever you pick.

**How the boards read.** The page is set like a kneeboard, not like a web page: the sheet's name is large in the board's accent ink, section headings carry a bar of that ink, and the figures you reach for — TACAN channel, UHF, VHF, bullseye — sit in raised boxes, found by shape rather than by reading. Tables are ruled, with every other row shaded. Every page keeps a margin from the tablet's frame, and the page counter is a faint number in the corner instead of a strip taken off the board.

### New: the kneeboard from BMS's HTML Briefing tool, in the app and in VR

Falcon BMS ships UOAF's **HTML Briefing** tool in `Tools\html_brief_win`. It is a separate program: you run it yourself and press its own export button, and it writes a kneeboard as PDF (or PNG) pages. BMS Companion does not replace it and does not automate it — **it reads what that tool last exported and shows those pages**:

- **Mission → Briefing → HTML Briefing generated kneeboard**: the exported pages as cards; tap one to open it in the chart viewer, with zoom, arrows, swipe and Esc.
- **As a VR board**: choose *HTML Briefing kneeboards* for any board and its pages are one OpenKneeboard tab away, turned with your page binding.
- **Run HTML Briefing** on that same card starts the tool on the BMS PC — a shortcut, nothing more, because the tool has no headless export. You still press export in its window.
- The card says when BMS has printed a newer briefing than the export, so you are never reading last flight's pages by accident.
- **If you do not use that tool, ignore the card.** Nothing else depends on it.

**Setup:** BMS Companion finds the tool in your BMS install automatically. If you keep it somewhere else, set **Settings → Falcon BMS settings → HTML Briefing folder**. Nothing in that folder is ever written or changed.

### New: instrument charts (Korea and the Falklands)

The approach, departure and arrival plates those theaters ship as PDFs are now in the app, beside the BMS ground plates: **Instrument charts** on any airfield page, and an **Instrument charts** VR board that follows your departure field.

- **244 charts, 988 pages**, rendered at 150 dpi so they stay sharp when you zoom.
- **Every chart is named for what it is** — `09L · ILS or LOC/DME`, `09L · RNAV (GPS)`, `DRAGGIN departure` — with the runway first, so one runway's charts sit together.
- **145 blank pages are dropped, and 176 sideways pages are turned upright** for you, decided from the printed table itself rather than guessed.
- **The arrows carry on into the next chart, and round**: an airfield's eight approach plates read straight through without closing anything.
- **Only Korea and the Falklands ship instrument charts.** Other theaters have none — that is BMS, not the app — and their airfield pages show the BMS ground and parking plates as before.
- **This is why the downloads are bigger.** The charts are bundled rather than fetched, so they work on a tablet with no network and on a PC with no BMS installed.

### New: updating from inside the app

**About** (at the bottom of the Home screen) checks for a newer version on Android, on the PC and in the browser.

- **What's new** shows the release notes of *every* version between yours and the newest.
- **Download and install** shows how much has arrived of how much, the transfer rate and the time left (`131 MB of 277 MB · 6.4 MB/s · 2 min 8 s left`). The Windows app hands the installer to Windows; the Android app hands the APK to Android, which asks once for permission to install apps.
- **Nothing is installed without being checked** against the checksum the release publishes; a download that does not match is thrown away.
- **An interrupted download is not a wasted one** — the part already fetched is kept and verified on the next attempt.

### New: clearing ACMI recordings

Flying with the AWACS picture on means BMS is recording, and `User\Acmi` grows by a file a flight. **Settings → Falcon BMS settings** now shows how many recordings there are and how much room they take, with **Clear (to Recycle Bin)** beside it — to the Recycle Bin, so a tape you wanted back is a right-click away. BMS's own `.vhs` training tapes are never touched.

### New: the kneeboard layout, and the board as an object

Open the browser version at `/kneeboard` and the page is laid out for a kneeboard: the content gets the whole board, a small **☰** opens the sections, the page's own options sit behind **⋯**, and both fade when the mouse stops.

- **Printed, not displayed**: warm paper, black ink, hairline rules, serif headings, and one muted family of inks so nothing on the board is brighter than anything else in a lit cockpit.
- **A day/night button**, remembered between flights.
- **☰ → Print size** and **☰ → Board shape** (5:8 kneeboard, 3:4, tall, square, landscape). OpenKneeboard reshapes the tab to the shape the page asks for.
- **A keyboard on the page** when you tap a search field, so you can search with the mouse while wearing a helmet.
- The **Dashboard** on a board is laid out for the board: the cards take the lines they need and the map takes all the rest.

### New: smaller things

- **A-Z strip** down the side of the Airfields and Encyclopedia lists: tap or drag it to jump.
- **About**: what BMS Companion is, the version you are running, the repository, the releases page — and **Credits** naming Benchmark Sims and the Falcon BMS team, EZBoards, OpenKneeboard and Tacview.
- **Chart viewer**: big page buttons, arrow keys and page up/down on a PC, swipe on a tablet (only while the page is fitted, so panning a zoomed plate never turns it), a turn button for a sideways page, **+ / − / fit** buttons, and **Esc** to close. The wheel now zooms in small glided steps.
- **The Windows installer** has its own banner and welcome artwork.

---

### Fixed

**Crashes**

- **Android 9 and 10 crashed** opening Mission or an airfield (`NoSuchMethodError … LinkedHashSet.reversed()`). The app is built against Android 15, where Java 21's sequenced collections exist; Kotlin bound a method older Android does not have. The call is gone, and every release APK is now scanned for this class of mistake before it ships. Thanks to the pilot who sent the crash log.
- **The PC app closed when you pressed Browse** for the EZBoards folder — and for the BMS and screenshots folders. Windows' folder chooser was walking the shell for icons and the "This PC" list, which fails on plenty of ordinary machines (OneDrive, mapped or removable drives). It no longer goes near the shell, it opens in a folder you already have set, and if it fails anyway it fails quietly — every one of those folders can also be typed into Settings. Thanks to the two pilots who reported it.
- **"You cannot access the NavBackStackEntry's ViewModels…"** while clicking through Home and the sections. Home is now reached with a single navigation and sections switch without a cross-fade, which is where that error came through.

**Installing and updating**

- **"You do not have sufficient privileges to complete this installation for all users of the machine."** The installer was built to install into your own profile and then told Windows to register it for the whole machine — a contradiction Windows answers by refusing, and elevating does not help. It is built for the machine throughout now: **double-clicking raises the ordinary Windows consent prompt by itself**, and it installs into Program Files like any other program.
- **"Another version of this product is already installed."** Every build of one version carried the same product code; each installer now carries its own.
- **The same version installed twice**, leaving two entries in Installed apps. The installer now replaces whatever is there, same version or not, and the old copy is closed and removed before the new one lands.
- **An update wiped what you had set up.** An earlier build cleared `%APPDATA%\BMS Companion` while installing. It now clears only what belongs to the copy it is replacing — and this version also sweeps up after older ones on its first run (cached installers, stale lock files, the pre-1.3 bridge's own folders). **Your Dashboard layouts, VR boards, and BMS, EZBoards, HTML Briefing and screenshot folders survive this and every future update.**
- **One desktop shortcut, not one per install.**
- **The browser version would not update.** Every build of one version tagged its files the same way, so a browser went on running the old app however often you reloaded. The tag now follows the files themselves, and the first run of a new version throws away what an older one cached (your settings stay).
- **The address your devices use stays put.** Right after an update the copy being replaced can still be holding the network port for a few seconds; BMS Companion now waits for its own port and says so, instead of failing and inviting you to change it — which would have broken every address you had written down, every OpenKneeboard tab and every bookmark.

**VR boards**

- **Next page did nothing.** Three separate faults: the page API was asked for before the experimental feature that provides it was enabled; page identifiers were generated with a call that does not exist on a plain-HTTP LAN address; and a board published a placeholder count before the PC had said what it was. All fixed and tested against OpenKneeboard 1.12.10 — paging, wrap-around and the map zoom all work with a bound controller button.
- **A board lost its pages when it published twice** (a count that grew as the mission arrived, or a kind changed on the PC): OpenKneeboard answers a second "enable this feature" with an error. It is asked for once per page now, and a publish that fails is retried rather than given up on for the flight.
- **Page counts were wrong** — a briefing with three sections still offered twelve pages, and the counter walked off the end ("9 / 5") into blank sheets. Boards publish the number of sheets they actually have.
- **Boards were mostly empty.** The briefing put each section on a page of its own, and tables broke after a fixed number of rows whatever the size of the page. Every board of print now measures what it is holding and breaks where the sheet runs out: the briefing is one or two pages instead of six, the ALIC table is one sheet, and the flight plan and comm ladder fill the page they are on.
- **The map is all map.** Panning towards the edge of the theater used to slide the map off the page and leave a band of bare board; and the strip along the foot that carried the page title is gone.
- **Every board asked you to press PRINT** over a briefing you had already printed: nothing was polled unless a screen asked for it, and the boards never asked.
- **The corners of a board were filled, not empty**, so the rounded tablet sat in a square of page colour.

**Everywhere else**

- **A search box you could not click into** after closing a chart or the bullseye page.
- **Search bars were different heights** from one section to the next.
- **The section rail is centred**, instead of starting in the very corner of the screen.
- **Towns appeared twice on the map** where a city is several campaign objectives of the same name.
- **Something to send when it goes wrong**: failures now go to `error.log` next to the settings, with **Open the error log** under Falcon BMS settings.

---

### Upgrading

Install over what you have — the installer closes the running copy, removes the old version and keeps your settings. On Android, install the new APK over the old one. Browsers pick the new version up on the next load.

If you added OpenKneeboard tabs before this version, they keep the names you gave them; rename them in OpenKneeboard, or remove and add them again to get the new `BMS KB n (…)` names.

## 1.3.1 (BMS 4.38) — September 2026

Fixes and a much smaller memory footprint, on top of 1.3.

### Fixes
- **"Use this PC as a client" on the server page did nothing but show an error.** It now switches to the full app and opens Mission → Setup with **On another PC** selected, ready for **Find BMS PC**.
- **"Allow through Windows Firewall" did nothing.** The rules are now added properly (one administrator prompt), the button reports progress and the result, and the checklist turns green. If Windows refuses, the button says so and names the ports to open by hand (TCP 47474, UDP 47475).
- **Towns appearing twice on the map**: a city is often several campaign objectives with the same name, which put the same label on the map two or three times. Objectives of one name closer than 25 nm are now one town. Same names further apart are kept: they are different towns (Greece really does have several Pyrgos).

### Less memory
- **PC**: about **270 MB on the server page and 340–380 MB in the full app**, where 1.3 used 450 MB and more the longer you browsed. The Java heap is capped and hands memory back to Windows, the image cache is a quarter of its old size, the map keeps only the tiles it needs, and screenshot previews and the browser's gzip cache are bounded.
- **Android**: the image cache is now sized from the memory the device gives the app (at most 40 MB instead of a fixed 96 MB), the map keeps fewer tiles, and cached images are released when Android asks for memory back.
- **Old tablets and phones**: on devices with little memory the maps load **half-size tiles** automatically: slightly softer, about a third less memory in total (measured 289 MB → 215 MB on a test tablet).
- **Browser**: the same smaller caches.

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

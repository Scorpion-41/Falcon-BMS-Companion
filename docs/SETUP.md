# Setup guide

BMS Companion comes as:

- **BMS Companion for Windows** (`BMS-Companion-PC.msi`, or the portable `BMS-Companion-PC.zip`): one program that reads Falcon BMS, serves your phones, tablets, browsers and other PCs, and is the full app too. It also runs as a **client** on a laptop next to you.
- **BMS Companion for Android** (`BMS-Companion.apk`): the same app for phones and tablets.
- **The browser version**: served by BMS Companion for Windows to iPhone, iPad and any browser. Nothing to install on the device.

The live Mission section needs BMS Companion running on the PC that runs Falcon BMS. It reads BMS locally and sends plain data (never screen captures) to every device, so it costs nothing in frame rate.

```
                                    ┌─ its own window: server page or full app
 Falcon BMS ─┐                      ├─ Android phones/tablets ─┐
 briefing, DTC, Tacview RT, ─> BMS  ├─ laptops / second PCs ───┼─ LAN: TCP 47474, UDP 47475 (discovery)
 EZBoards, screenshots   Companion  └─ browsers (the app runs ─┘
                                       in the browser)
```

## Which files do I need?

| You fly on a PC and want the companion on… | Install on the BMS PC | Install on the device |
|---|---|---|
| **the same PC** (second monitor, windowed sim) | BMS Companion for Windows, use the **full app** | — |
| **an Android phone or tablet** | BMS Companion for Windows | BMS-Companion.apk |
| **an iPhone or iPad** | BMS Companion for Windows (**Browser access** on) | nothing (Safari, iOS 18.2 or newer) |
| **any other device with a browser** (Mac, Linux, Chromebook, another tablet) | BMS Companion for Windows (**Browser access** on) | nothing (a current Chrome, Edge, Firefox or Safari) |
| **a laptop or second PC, as a full app (client)** | BMS Companion for Windows | BMS Companion for Windows (full app, **On another PC**) |

Any mix works at the same time: for example the app full screen on a second monitor, an Android tablet on the kneeboard and an iPad for a friend acting as AWACS.

The offline reference (aircraft, threats, airfields and charts, cockpit, trainer) works on every device without the PC.

## 1. Install BMS Companion on the BMS PC

1. Download `BMS-Companion-PC.msi` from the GitHub Releases page and install it, or unzip `BMS-Companion-PC.zip` anywhere and run `BMS Companion.exe`. It includes its own Java runtime and the browser version; nothing else to install. The installer isn't code-signed, so Windows SmartScreen may ask: **More info → Run anyway**. Installing asks for consent once, the way installers do — double-click it, there is nothing to right-click. Prefer no prompt at all? Use the zip: it needs no installing.
2. The first start opens the **server page** (below). It already reads Falcon BMS and serves your devices.
   - Flying with the app on this PC too? Press **Open the full app**. The window becomes the full app; its **Server** button (bottom of the navigation rail, or the Mission header) turns it back.
   - BMS Companion remembers which one you used last and starts that way.
3. Windows may ask whether BMS Companion may use networks. Allow **private networks**, or press **Allow through Windows Firewall** (one administrator prompt; adds rules for TCP 47474 and UDP 47475, limited to your local subnet). If only this PC uses BMS Companion, you can skip this.
4. Configure Falcon BMS as in [section 5](#5-configure-falcon-bms). The setup checklist on the server page shows what is still missing.

**Already have a copy installed?** Just run the new installer (or **About → Download → Install** in the app). It closes BMS Companion for you, removes the old version and installs the new one. Everything you have set up carries over: the settings in `%APPDATA%\BMS Companion`, the Dashboard layouts, favourites, VR boards and folders.

Upgrading from version 1.2? The separate **BMSCompanionBridge.exe** is not needed any more: close it (tray icon → Exit) and delete it. BMS Companion uses the same port and settings.

### The server page
One light page for a PC that serves your devices (the full app isn't loaded, so it uses little memory):

| Part | What it does |
|---|---|
| **Status tiles** | Falcon BMS (running, in 3D, theater), AWACS feed, briefing, connected devices |
| **Open the full app** | Turns the window into the full app (and back with **Server**) |
| **Connect your devices** | The browser address with a QR code, what Android devices and laptops need, **Browser access** on/off, the firewall button, connected devices |
| **Setup checklist** | Live ✓/✕ checks with a fix button for each step: BMS found, network, briefing export, DTC, live data, AWACS feed, EZBoards (and the .NET 8 runtime) |
| **Falcon BMS settings** | EZBoards folder and auto-generate on PRINT, Tacview stream on/off with host, port and password, BMS folder and screenshots folder (only if not found), network port, **Edit Falcon BMS User.cfg**, **Open screenshots folder** |
| **Is Falcon BMS on another PC?** | Turns this PC into a client (full app with data from the BMS PC) |
| **Activity** | Devices seen in the last two minutes, and the recent log |
| **Start-up and closing** | Keep running in the tray when the window closes; start with Windows (in the tray) |

The same settings are in the full app's **Setup** section.

**One window, one copy:** opening BMS Companion again (Start menu, desktop icon) brings the running window to the front, also when it is hidden in the tray. The tray icon's menu has **Open BMS Companion**, **Server page**, **Full app**, **Browser access** and **Exit**.

**Full screen:** in the full app press **F11** (or the full-screen button in the Mission header or at the bottom of the navigation rail) for a borderless window that fills its monitor, handy on a second screen next to the sim. **F11** or **Esc** returns to the normal window. The **pin** keeps the window on top.


## 2. Android phones and tablets

1. Install `BMS-Companion.apk` (allow "install unknown apps"), or `adb install -r BMS-Companion.apk`.
2. Put the device on the **same network** as the BMS PC. Guest Wi-Fi networks usually isolate devices and will not work.
3. In the app open the **Setup** section and tap **Find BMS PC**. One PC found means it connects immediately.
4. If nothing is found (some routers block broadcasts), enter the address shown on the BMS PC's server page and port **47474**, then **Connect**.

The header dot turns green (**LINKED**). The app polls only while the Mission screen is open, and keeps the screen awake while it is.

## 3. iPhone, iPad and other browsers

1. On the BMS PC, keep **Browser access** on (it is on by default) and press **Allow through Windows Firewall** once.
2. On the device, scan the **QR code** on the server page with the camera, or open the address shown (for example `http://192.168.1.20:47474`). Same network as the PC.
3. On iPhone/iPad, **Share → Add to Home Screen** gives a full-screen app icon. On Android Chrome: menu → **Add to Home screen**.

How it works: the page loads the **whole app into the browser** (WebAssembly, about 16 MB the first time, then cached). It runs on the device like a native app: scrolling, zooming and typing are local, and the keyboard opens when you tap a text field. BMS Companion on the PC only sends the mission data and the bundled BMS data. Notes:
- It needs **Safari 18.2 / iOS 18.2** or newer, or a current Chrome, Edge or Firefox. Older browsers show a message instead of the app.
- Each browser keeps its own settings (dashboard layout, map layers, favorites, theater).
- Add `?route=mission` to the address to open the Mission section directly (for a home-screen bookmark).

## 4. Laptop or second PC as a client

1. On the BMS PC, run BMS Companion (server page or full app) and allow it through the firewall ([section 1](#1-install-bms-companion-on-the-bms-pc)).
2. On the laptop (or second PC), install BMS Companion. On its server page press **Use this PC as a client** (or open the full app and choose **On another PC** in the **Setup** section).
3. **Find BMS PC**, or enter the BMS PC's address (shown on its server page) and port 47474 → **Connect**.

The client runs the complete app (all tabs, Dashboard, AWACS, reference) in its own window, not a web page; only the mission data comes from the BMS PC. With browser access on, it also passes the app on to browsers near it.

## 5. Configure Falcon BMS

### Briefing export (briefing, package, loadout, comms, weather)
In the **BMS Launcher → CONFIG → General → Briefing / Debriefing**:

| Option | Setting |
|---|---|
| Briefing Output to File | **ON** (enables the PRINT button) |
| HTML Briefings | **OFF** (BMS Companion and EZBoards read the text file) |
| Append New Briefings | optional (the newest briefing in the file is used) |

The cfg equivalents are `set g_nPrintToFile 1` and `set g_bBriefHTML 0`. If you use `g_sBriefingsDirectory`, BMS Companion follows it once BMS is running.

Then, for every mission: finish planning, open the **Briefing** tab and press **PRINT** (top right). The app updates within about a second.

### DTC (targets, PPTs, lines, presets)
In the **DTC** page set your target steerpoints, PPTs and comm plan, then press **SAVE**. This writes `User\Config\<callsign>.ini`. Your route before 3D needs nothing extra: after PRINT the app takes it from the mission file BMS writes beside the save (or from the save itself), once it matches your briefing.

### Cockpit displays (live MFDs)
The MFDs card shows BMS's own display export. In the **Falcon BMS Launcher**, on its main page, set **Export RTT Textures** to **Enable** and launch BMS. The Launcher writes this at the end of `Falcon BMS User.cfg` (and `VR.cfg`) every time it starts BMS, so it wins over any line above it; only when you start BMS without the Launcher's overrides does the glass offer a **SWITCH ON** button of its own (through the Config section's backed-up path). The glass says what it is still waiting for, and **Setup → Cockpit displays (MFDs)** has the guide, the live-picture switch and the frame rate.

### Live flight data
Nothing to do. BMS always publishes shared memory, and data appears once you are in 3D.

### AWACS picture (other aircraft)
BMS has a built-in **Tacview real-time telemetry** server. The Tacview program itself is not needed. Add to `User\Config\Falcon BMS User.cfg` (the checklist's **Edit Falcon BMS User.cfg** opens it):

```
set g_bTacviewRealTime 1     // start the real-time telemetry server
set g_bTacviewAcmi 1         // Tacview ACMI recording (default 1)
// optional:
// set g_nTacviewPort 42674
// set g_sTacviewPassword mypass   (enter the same password in the Falcon BMS settings)
```

- The stream only runs while **ACMI recording is on**. Start it in 3D with the recording key (default **F**) or enable recording in the Launcher. The Map and AWACS pages show a reminder when you are in 3D and no picture arrives.
- Multiplayer: the host decides with `g_bMPTacviewRtAllowedByServer` (default 1).
- The full picture can feel like cheating when you fly. Options: turn the Tacview stream off in the settings, use the **Hostiles** switch on the map or Picture card, or leave the full picture to a human AWACS on the **AWACS** tab.

### EZBoards (in-cockpit kneeboards)
- EZBoards is a separate tool by Logic, available from the Falcon BMS forum. BMS Companion finds it in `Tools\EZBoards`
  (or pick its folder in the settings). It needs the **.NET 8 runtime** (Console Apps).
- BMS Companion finds it in the BMS folder automatically. If you keep it elsewhere, set the **EZBoards folder** in the settings.
- Workflow: plan → save DTC → PRINT → **Mission → Kneeboards → GENERATE NOW** (or the Kneeboards card on the Dashboard). With **Generate kneeboards automatically when the briefing is printed**, PRINT alone is enough.
- EZBoards runs in **EZBoards mode**, the default. In **WDP mode** (the switch at the top of the Mission section, or in Setup) it is paused — GENERATE NOW is greyed out and nothing runs at PRINT — and the cockpit kneeboards come from the Planner's **Upd Kneeboard**. Your EZBoards setting is kept. `docs/KNEEBOARDS.md` has the order for each mode.
- To see kneeboards in the cockpit, the 3D pilot model must be on (Setup → Graphics → Pilot Model).
- EZBoards' own settings (which pages, which kneeboard slots) are in its `CONFIG_USER.BAT`.

### HTML Briefing (the exported kneeboard)
- UOAF's **HTML Briefing** tool is a separate download, not part of Falcon BMS. It turns a briefing into kneeboard pages of its own.
- BMS Companion looks for it in `Tools\html_brief_win` in the BMS folder; if you keep it elsewhere, set the **HTML Briefing folder** in Falcon BMS settings.
- Workflow: PRINT the briefing in BMS → export in the HTML Briefing window (**Run HTML Briefing** on the Kneeboards page opens it) → the pages appear on the **Kneeboards** page, on every device, and can be given a **VR board** of their own (kind: *Exported kneeboard*).
- In **WDP mode** Run HTML Briefing is greyed out and the PC refuses it: its export writes cockpit pages 1-3, over the Planner's. The pages it already exported are still shown.
- The card says when BMS has printed a newer briefing than the export. Nothing in that tool's folder is written or changed.

### ACMI recordings
- Flying with the AWACS picture on means BMS is recording to `User\Acmi`, which grows by a file a flight. **Falcon BMS settings** shows the count and the size, and **Clear (to Recycle Bin)** empties it. BMS's own `.vhs` training tapes are left alone.

## 6. Using the Mission section

The **EZBoards | WDP** switch at the top of the section (also in **Setup**) decides where everything in it comes from: **EZBoards mode** (the default) is BMS's printed briefing, filled when you press PRINT; **WDP mode** is the Planner, filled when you press **Populate from Planner**. The choice is kept on the PC, so every device shows the same. The Planner tab works in WDP mode only; in EZBoards mode it is greyed out. See the README's *Two modes*.

| Tab | What for |
|---|---|
| **Dashboard** | Your own pages, five of them on small numbered buttons: **Customize Dashboard** to add, move, resize (S/M/L) and remove cards (live map, ownship, RWR, DED, **MFDs**, picture, fuel, time & TOT, bullseye, threat rings, steerpoints, mission, airbases, comm ladder, weather, presets, tankers & support, kneeboards), or pick a preset (Pilot, Cockpit, Navigator, Pre-flight, In flight). Phones and wide screens keep separate layouts. |
| **Map** | Full live map with layers, selection details and side panels, **+ / −** zoom buttons: the mission picture (route, tanker and AWACS tracks, your PPTs and the threats your briefing names, your airfields) and the air picture; tankers (green) and AWACS (purple) have their own symbols. The **Map** menu chooses the style (Relief, Satellite, Dark, Chart) and the landmarks (country borders, provinces, towns); it applies to every map and is remembered. Towns → **Mission** (default) keeps only the towns of your mission and highlights the target town. |
| **AWACS** | For a human AWACS/GCI: the whole air picture with trails, speed vectors, group circles, radar locks and bullseye rings. Panels: **Picture** (picture call, hostile groups, friendlies with fuel), **Contact** (details, bullseye and BRAA calls), **Control** (pick the flight you control: threats with BRAA, aspect, closure and merge time, BRAA and intercept vector calls, nearest field and fuel), **Measure** (A→B bearing/range, closure, intercept, fuel), **Calc** (time and fuel for a distance, bullseye→BRAA, time to merge), **Layers** (filters, altitude band, vector length, group radius, commit range). Alerts flag merges, commit range, radar spikes, missiles and low fuel. |
| **Briefing, Comms, Kneeboards** | The briefing (BMS's printed one, or in WDP mode the flight populated from the Planner); comm ladder and presets; EZBoards, Upd Kneeboard's place in the order, the VR boards and the HTML Briefing pages. Briefing and Comms include **Tankers & support**: TACAN (with the tie-on channel), UHF, every comm channel from the ladder with its preset, and bullseye position of tankers, AWACS and JSTARS. A TACAN marked * is BMS's default channel (first tanker 92Y, then 126Y, 125Y…); AWACS "Vector to tanker" confirms it in flight. |
| **Taxi** | The ground chart of your field: runway, spot, the taxi route and clearance; **Taxi in** after landing. |
| **Weather** | Generate weather maps the way WeatherGen does and save them beside BMS's own (pick them in BMS under Map Model). |
| **Planner** | Weapon Delivery Planner for Falcon BMS 4.38.1 (WDP mode). Open your save, plan, **Save to DTC**, **Populate from Planner**, **Upd Kneeboard**; **Steps** and the **Guide** walk through it ([section 7](#7-wdp-mode-and-the-planner)). |

**Setup** is a section of its own in the navigation rail: connection and guides, the mode switch, the Mission pages shown (AWACS, Config), the MFD guide; on the PC also where Falcon BMS runs, the setup checklist, devices, settings and the **Graphics** card.

## 7. WDP mode and the Planner

1. Switch the Mission section to **WDP** (the switch at its top, or in Setup). The Planner tab unlocks; EZBoards and Run HTML Briefing pause.
2. On a phone, tablet or browser, link the device to the PC first ([sections 2-4](#2-android-phones-and-tablets)): the Planner works through the PC, where your saves, cartridge and cockpit pages are. On the BMS PC's own window it works straight away.
3. Follow the **Steps** on the Planner's toolbar: in BMS pick your flight, SAVE the DTC and the campaign or TE, PRINT; in the Planner **Open mission…**, plan, **Save to DTC**; in BMS **LOAD** and SAVE the DTC; **Populate from Planner**; **Upd Kneeboard**; fly, and LOAD on the DTE page.
4. The **Guide** (toolbar) has the long version and opens by itself the first time.

The Planner writes your cartridge (`User\Config\<callsign>.ini`) and the cockpit kneeboard pages directly when you press Save to DTC and Upd Kneeboard, as WDP does; its own files go into `User\BMS Companion Planner` inside your BMS folder. At each new mission and each switch of mode, the PC takes what the Planner saved for an earlier flight back out of the cartridge by itself. `docs/DATA-STORES.md` has the detail.

## 8. VR: the app as a kneeboard

BMS Companion does not draw inside the headset itself — that needs a native OpenXR layer. Use it with **[OpenKneeboard](https://openkneeboard.com)**, which already does the VR part: it puts the board on your knee, drags, resizes and rotates it, toggles it with your own key, and lets the mouse move in and out of the board.

1. Turn **Browser access** on in BMS Companion (server page).
2. In OpenKneeboard: **Settings → Tabs → Add a tab → Web Dashboard**.
3. Address: `http://<bms-pc-address>:47474/kneeboard` — the address from the server page with `/kneeboard` on the end. On the BMS PC itself, `http://127.0.0.1:47474/kneeboard`.
4. Size and place the board in OpenKneeboard; its own binding shows and hides it.

**Using it in the headset.** There is no pointer on a kneeboard in VR: OpenKneeboard supports graphics tablets (Wacom, Huion) and nothing else — "Mice are not supported in-game", as its FAQ puts it. So BMS Companion publishes its sections to OpenKneeboard as **pages**: bind a button to *next page* and *previous page* (HOTAS, keyboard, StreamDeck) and that button walks through Mission, Reference and Cockpit without anything to aim at. With a graphics tablet you can point at the board as well.

**Print size.** The board is drawn at a headset-friendly resolution, and **☰ → Print size** decides how large that resolution is used: Small print, Normal, Large, Very large. Remembered per board.

OpenKneeboard gives a web tab a landscape page and offers no way to reproportion it in its settings — the page has to ask, and this one does. It asks for an upright board (5:8, the shape of a real kneeboard and of OpenKneeboard's own), and **☰ → Board shape** on the board offers 3:4, tall, square and landscape instead; the choice is remembered. A one-off shape can be had with `?size=WIDTHxHEIGHT`, e.g. `/kneeboard?size=1200x1600`.

`/kneeboard` is what makes the board layout. It lays the page out for a board: the content gets all of it, a small **☰** in the corner opens the sections and the Mission tabs, each page’s own options sit behind **⋯** beside it, and both fade away when the mouse stops. The map fills the board. Left out are the things you would not use in the cockpit: the AWACS/GCI page, the Boards tab (the briefing has the same tables), the setup guides, Home and Media.

Everything else is there: Dashboard, map, flight data, briefing, comms, the charts and the reference sections.

The board layout is the browser version only — the PC window and the Android app keep their normal one.

The board is drawn as paper rather than as a screen — warm off-white, black ink, no saturated colour — and the button next to the ☰ switches between daylight and a dimmed night page. That choice is remembered.

**Trying it without a headset:** open `http://127.0.0.1:47474/kneeboard` in any browser and make the window small (about 800 x 800), or press F12 and use the browser's device toolbar at 1024 x 1024. The ☰ and ⋯ fade a couple of seconds after the mouse stops; move it to bring them back.

**Numbered VR boards.** For a board that always shows one thing — the live map, Live taxi, a ground chart, the briefing, comms, the HARM table, instrument charts, the exported HTML Briefing — set it up on the BMS PC under **Mission → Kneeboards → VR boards** and give OpenKneeboard one Web Dashboard tab per board: `http://<bms-pc-address>:47474/kneeboard/1`, `/2`, … Their pages turn with the same *next page* / *previous page* binding (on the map and Live taxi boards, the pages are zoom steps).

## 9. Media (screenshots)

The **Media** section (last in the navigation) shows the screenshots you take in Falcon BMS (PrtScr, or your screenshot key). BMS Companion reads them from `User\Pictures`, or the folder set with `g_sPicturesDirectory`; if you keep them elsewhere, set **Screenshots folder** in the settings. New screenshots appear within a few seconds.

- **Browse:** grouped by day. Tap a picture to open it; zoom with pinch, scroll or double-tap; ‹ › for the previous and next one.
- **Select:** long-press a picture (or **Select**) to pick several, then **Download** or **Delete** them together.
- **Delete:** screenshots are moved to the **Recycle Bin on the BMS PC**, so you can restore them there.
- **Share and download**, depending on the device:

| Device | Actions |
|---|---|
| Android | **Share** (system share sheet), **Download to this device** (Pictures/BMS Companion; the app's own Pictures folder before Android 10) |
| iPhone, iPad, any browser | **Download to this device**, **Share** (the system share sheet, where the browser supports it) |
| PC client (laptop) | **Download to this PC** (Downloads\BMS Companion), **Copy image**, **Save a copy…** |
| The BMS PC itself | **Copy image**, **Save a copy…**, **Open the screenshot folder** |

## Troubleshooting

| Symptom | Fix |
|---|---|
| "timed out" / NO LINK | Is BMS Companion running on the BMS PC? Firewall allowed? Same network, not guest Wi-Fi? Try the address manually. |
| "connection refused" | Nothing listens on the port: BMS Companion is closed on the BMS PC, or the network port was changed in its settings. |
| "Port 47474 is in use" on the server page | Another program uses the port, often an old **BMSCompanionBridge.exe** from version 1.2: exit it from its tray icon. Or choose another port in the settings (and on the devices). |
| Browser shows "too old" | Update iOS/Safari to 18.2 or newer, or use a current Chrome, Edge or Firefox. |
| Browser page doesn't load | Browser access off, wrong address or port, or the firewall button not pressed. |
| Briefing tab empty | PRINT not pressed, HTML briefings still on, or the briefing folder is redirected (start BMS so its real path can be read). |
| Steerpoints not on the map before 3D | PRINT the briefing after saving the campaign or TE: the route comes from the mission file BMS writes beside the save, or from the save, once it matches the briefing. In 3D, positions come from shared memory. |
| MFD glass says the export is off | Falcon BMS Launcher, main page: **Export RTT Textures → Enable**, then restart BMS. |
| Planner greyed out with a lock | The Mission section is in EZBoards mode: switch it to **WDP**. |
| GENERATE NOW or Run HTML Briefing greyed out | WDP mode: the Planner's **Upd Kneeboard** makes the cockpit pages there. Switch to EZBoards mode to use EZBoards. |
| The Planner says the device is not linked | Link it in **Setup** (Find BMS PC); the Planner works through the PC. |
| No AWACS feed | `g_bTacviewRealTime 1`, ACMI recording running, Tacview stream on with the right port/password in the settings, MP host allows it. |
| EZBoards fails | Open the log on the Kneeboards page. Usual causes: .NET 8 runtime missing, briefing not printed, EZBoards folder wrong. |
| Wrong map | The app uses the theater BMS reports. Add-on theaters that reuse a base map show that map. |
| Map too busy | **Map** menu: set Towns to **Mission** (only the mission's towns, target town highlighted), **Cities** or **Off**, or turn off provinces. The **Dark** style keeps the AWACS picture easiest to read. |
| Satellite map shows clouds or seams | That is the sim's own ground texture, as BMS ships it. Use Relief or Chart. |
| Text too small or too large on the PC | Ctrl + / Ctrl − / Ctrl 0 (remembered). |
| Nothing happens when starting BMS Companion | It is already running: look for its tray icon (it brings the window forward; if the window stays hidden, use the tray menu). |

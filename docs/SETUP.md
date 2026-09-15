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

1. Download `BMS-Companion-PC.msi` from the GitHub Releases page and install it, or unzip `BMS-Companion-PC.zip` anywhere and run `BMS Companion.exe`. It includes its own Java runtime and the browser version; nothing else to install. The installer isn't code-signed, so Windows SmartScreen may ask: **More info → Run anyway**.
2. The first start opens the **server page** (below). It already reads Falcon BMS and serves your devices.
   - Flying with the app on this PC too? Press **Open the full app**. The window becomes the full app; its **Server** button (bottom of the navigation rail, or the Mission header) turns it back.
   - BMS Companion remembers which one you used last and starts that way.
3. Windows may ask whether BMS Companion may use networks. Allow **private networks**, or press **Allow through Windows Firewall** (one administrator prompt; adds rules for TCP 47474 and UDP 47475, limited to your local subnet). If only this PC uses BMS Companion, you can skip this.
4. Configure Falcon BMS as in [section 5](#5-configure-falcon-bms). The setup checklist on the server page shows what is still missing.

Upgrading from version 1.2? The separate **BMSCompanionBridge.exe** is not needed any more: close it (tray icon → Exit) and delete it. BMS Companion uses the same port and settings.

### The server page
One light page for a PC that serves your devices (the full app isn't loaded, so it uses little memory):

| Part | What it does |
|---|---|
| **Status tiles** | Falcon BMS (running, in 3D, theater), AWACS feed, briefing, connected devices |
| **Open the full app** | Turns the window into the full app (and back with **Server**) |
| **Connect your devices** | The browser address with a QR code, what Android devices and laptops need, **Browser access** on/off, the firewall button, connected devices |
| **Setup checklist** | Live ✓/✕ checks with a fix button for each step: BMS found, network, briefing export, DTC, live data, AWACS feed, EZBoards (and the .NET 8 runtime) |
| **Falcon BMS settings** | EZBoards folder and auto-generate on PRINT, Tacview stream on/off with host, port and password, demo mode, BMS folder and screenshots folder (only if not found), network port, **Edit Falcon BMS User.cfg**, **Open screenshots folder** |
| **Is Falcon BMS on another PC?** | Turns this PC into a client (full app with data from the BMS PC) |
| **Activity** | Devices seen in the last two minutes, and the recent log |
| **Start-up and closing** | Keep running in the tray when the window closes; start with Windows (in the tray) |

The same settings are in **Mission → Setup** of the full app.

**One window, one copy:** opening BMS Companion again (Start menu, desktop icon) brings the running window to the front, also when it is hidden in the tray. The tray icon's menu has **Open BMS Companion**, **Server page**, **Full app**, **Browser access** and **Exit**.

**Full screen:** in the full app press **F11** (or the full-screen button in the Mission header or at the bottom of the navigation rail) for a borderless window that fills its monitor, handy on a second screen next to the sim. **F11** or **Esc** returns to the normal window. The **pin** keeps the window on top.

**Try it without BMS:** turn on **Demo mode** in the Falcon BMS settings. You get a synthetic SEAD mission in Hellas with moving traffic, a friendly CAP and a hostile pair for the AWACS page.

## 2. Android phones and tablets

1. Install `BMS-Companion.apk` (allow "install unknown apps"), or `adb install -r BMS-Companion.apk`.
2. Put the device on the **same network** as the BMS PC. Guest Wi-Fi networks usually isolate devices and will not work.
3. In the app open **Mission → Setup → Find BMS PC**. One PC found means it connects immediately.
4. If nothing is found (some routers block broadcasts), enter the address shown on the BMS PC's server page and port **47474**, then **Connect**.

The header dot turns green (**LINKED**). The app polls only while the Mission screen is open, and the **☀ button** keeps the screen awake.

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
2. On the laptop (or second PC), install BMS Companion. On its server page press **Use this PC as a client** (or open the full app and choose **On another PC** in **Mission → Setup**).
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

### DTC (steerpoint positions before 3D, targets, PPTs, presets)
In the **DTC** page set your target steerpoints, PPTs and comm plan, then press **SAVE**. This writes `User\Config\<callsign>.ini`.

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
- EZBoards (by Logic) ships with BMS 4.38 in `Tools\EZBoards` and needs the **.NET 8 runtime** (Console Apps).
- BMS Companion finds it in the BMS folder automatically. If you keep it elsewhere, set the **EZBoards folder** in the settings.
- Workflow: plan → save DTC → PRINT → **Boards → Generate kneeboards** (or the Kneeboards card on the Dashboard). With **Generate kneeboards automatically when the briefing is printed**, PRINT alone is enough.
- To see kneeboards in the cockpit, the 3D pilot model must be on (Setup → Graphics → Pilot Model).
- EZBoards' own settings (which pages, which kneeboard slots) are in its `CONFIG_USER.BAT`.

## 6. Using the Mission section

| Tab | What for |
|---|---|
| **Dashboard** | Your own page: **Edit** to add, move, resize (S/M/L) and remove cards (live map, ownship, RWR, DED, picture, fuel, time & TOT, bullseye, threat rings, steerpoints, mission, airbases, comm ladder, presets, tankers & support, kneeboards), or pick a preset (Pilot, Cockpit, Navigator, Pre-flight). Phones and wide screens keep separate layouts. |
| **Map** | Full live map with layers, selection details and side panels, **+ / −** zoom buttons; tankers (green) and AWACS (purple) have their own symbols. The **Map** menu chooses the style (Relief, Satellite, Dark, Chart) and the landmarks (country borders, provinces, towns); it applies to every map and is remembered. Towns → **Mission** (default) keeps only the towns of your mission and highlights the target town. |
| **AWACS** | For a human AWACS/GCI: the whole air picture with trails, speed vectors, group circles, radar locks and bullseye rings. Panels: **Picture** (picture call, hostile groups, friendlies with fuel), **Contact** (details, bullseye and BRAA calls), **Control** (pick the flight you control: threats with BRAA, aspect, closure and merge time, BRAA and intercept vector calls, nearest field and fuel), **Measure** (A→B bearing/range, closure, intercept, fuel), **Calc** (time and fuel for a distance, bullseye→BRAA, time to merge), **Layers** (filters, altitude band, vector length, group radius, commit range). Alerts flag merges, commit range, radar spikes, missiles and low fuel. |
| **Flight, Briefing, Comms, Boards** | Flight data with RWR and DED; the printed briefing; comm ladder and presets; EZBoards. Briefing and Comms include **Tankers & support**: TACAN (with the tie-on channel), UHF, every comm channel from the ladder with its preset, and bullseye position of tankers, AWACS and JSTARS. A TACAN marked * is BMS's default channel (first tanker 92Y, then 126Y, 125Y…); AWACS "Vector to tanker" confirms it in flight. |
| **Setup** | Connection and guides. On the PC: where Falcon BMS runs, the setup checklist, devices and settings. |

## 7. Media (screenshots)

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
| Steerpoints not on the map before 3D | Save the DTC. In 3D, positions come from shared memory. |
| No AWACS feed | `g_bTacviewRealTime 1`, ACMI recording running, Tacview stream on with the right port/password in the settings, MP host allows it. |
| EZBoards fails | Open the log on the Boards tab. Usual causes: .NET 8 runtime missing, briefing not printed, EZBoards folder wrong. |
| Wrong map | The app uses the theater BMS reports. Add-on theaters that reuse a base map show that map. |
| Map too busy | **Map** menu: set Towns to **Mission** (only the mission's towns, target town highlighted), **Cities** or **Off**, or turn off provinces. The **Dark** style keeps the AWACS picture easiest to read. |
| Satellite map shows clouds or seams | That is the sim's own ground texture, as BMS ships it. Use Relief or Chart. |
| Demo data after testing | Turn off Demo mode in the settings. |
| Text too small or too large on the PC | Ctrl + / Ctrl − / Ctrl 0 (remembered). |
| Nothing happens when starting BMS Companion | It is already running: look for its tray icon (it brings the window forward; if the window stays hidden, use the tray menu). |

# Which kneeboard is which, and in what order to make them

Several different things are called "kneeboard" around Falcon BMS, and only one of them is the one you pull up inside
the cockpit. **Three tools can write that one**, onto the same page files, and each of them takes a *snapshot* of the
mission when it runs, so the order you do things in decides whether your kneeboard shows this flight or the last one.

Which of the three you use depends on the Mission section's mode — the **EZBoards | WDP** switch at the top of the
Mission section (and in Setup):

- **EZBoards mode** (the default): the Mission section follows BMS's printed briefing, and EZBoards makes the cockpit
  kneeboard.
- **WDP mode**: the Mission section follows the Planner (Weapon Delivery Planner), and the Planner's **Upd
  Kneeboard** makes it. EZBoards is paused: GENERATE NOW is greyed out ("WDP mode: EZBoards is paused so it cannot
  overwrite the Planner's pages. Make the cockpit boards with Planner → Upd Kneeboard.", with **Open the Planner**
  beside it), nothing runs at PRINT, and the PC refuses a press from any device (409), while your EZBoards setting is
  kept for when you switch back. **Run HTML Briefing** is greyed out the same way, because html_brief's export writes
  pages 1-3 over the Planner's; the pages it already exported are still shown, on every device and the `EXPORTED` VR
  board.

Everything else — the VR boards, Map, Taxi, Briefing, Comms, Weather — works the same in both modes; only who writes
the cockpit pages differs.

## What they are

| What | Where you see it | What makes it | What it reads |
|---|---|---|---|
| **The cockpit kneeboard** | in the pit, on your knee | **three tools**, below | — |
| ‣ EZBoards | the pages its `CONFIG_USER.BAT` claims (`SET KNEEBOARD[F16_<n><L\|R>]`), page 1 on a stock setup | EZBoards (by Logic, a separate download from the Falcon BMS forum), at PRINT or with **GENERATE NOW**, **in EZBoards mode** | the **printed briefing** and your **cartridge** (`xbrief.exe` is handed both, `BRIEFING_TXT` and `CALLSIGN_INI`, in EZBoards' own `CONFIG.BAT`) |
| ‣ Upd Kneeboard | the pages you choose; by default the **Mission set**, your DataCard on page 1 and Coordination Card on page 2 (EZBoards is paused in WDP mode, so its pages are the Planner's); **Browse picture…** puts a picture of your own on any half | the Planner, **in WDP mode** | what the Planner has open: the save's flight, the DTC page as it is (saved or not), the attack page; a picture is a `.jpg`, `.png`, `.bmp` or `.dds` on the BMS PC, read again at each print and drawn by the PC from the file |
| ‣ HTML Briefing | pages 1-3, when it exports | UOAF's HTML Briefing tool (a separate download; BMS Companion looks for it in `Tools\html_brief_win`), **in EZBoards mode** (BMS Companion does not start it in WDP mode) | the **campaign save** (`.cam`) and your **cartridge** — *not* the printed briefing |
| **HTML Briefing pages** | BMS Companion → Kneeboards, the chart viewer, and the `EXPORTED` VR board | the same tool's export | as above |
| **BMS Companion VR boards** | OpenKneeboard, in the headset | this app, as web pages | live data, and the Mission section's mission (so they follow the mode) |
| **BMS Companion pages** | phone, tablet, browser, PC | this app | the same |

**How the cockpit pages work.** Falcon BMS shows the F-16's kneeboard from sixteen texture files, `7982.dds` to
`7997.dds`, in the `KoreaObj` folder of the theater's 3D data (several theaters share one folder). The left half of each
file is the left knee's page *n*, the right half the right knee's. BMS reads them **as you enter the cockpit**: a page
written while you are in it shows the next time you enter. Whichever tool wrote a page last is what you see. Upd
Kneeboard's window shows who made each page now (BMS, EZBoards, HTML Briefing, WDP or this app), worked out from the
file itself, and **Put BMS's page back** restores the page BMS ships.

**None of them keeps a backup of a page** — the pages are made again at will. Upd Kneeboard writes a page beside
itself first and then moves it into place, keeps the file's own format, and leaves the half you did not print exactly
as it was. It never creates a page file that does not exist.

**Which pages Upd Kneeboard uses.** Its window opens on the **Mission set**, laid for the mode the Mission section is
in. In **WDP mode** — where the Planner is used — EZBoards does not run at PRINT, so the Mission set takes the first
pages: the DataCard on page 1 (both knees) and the Coordination Card on page 2. (In EZBoards mode it would leave EZBoards
the pages its `CONFIG_USER.BAT` claims and start at the first pair it does not, pages 2 and 3 on a stock setup.) A
Mission set you have not changed is laid again when the mode changes, and the window says so; a plan you changed by
hand is kept. **The plan is kept on the device between launches**, as WDP keeps its kneeboard choices: a picture page
you set once is still planned the next evening.

**Pictures of your own** (WDP's Browse Picture): **Browse picture…** opens the Open picture window on the BMS PC
(Windows' own on the PC, the PC's folders in a window on a phone or in a browser), then shows the picture as it will
fill a half page, with a page number, **← Insert left**, **Insert right →** and Cancel. A half's own menu has
**Picture…** for the same; a half that had a picture before keeps it when you give it another kind, and its
**Picture…** opens the chooser on that picture again. The picture is stretched over the whole half, as WDP stretches
it, and **the PC draws it from the file itself** at the page's full size (1024 × 2048 on a stock page), so a picture
made at that size keeps every pixel. It is read again when you print: a file moved or unreadable by then leaves its
half as it is, and the window says why. Two things differ from WDP on purpose: the picture covers the whole half
(WDP leaves a rim of one or two pixels of the old page), and what is transparent in it prints white (WDP lets the old
page show through).

What is read: `.jpg`, `.png` and `.bmp`, and a `.dds` in DXT1-DXT5 or any uncompressed layout (32-, 24- and 16-bit
colour, grey, alpha). A CMYK JPEG (the kind printing programs save) is refused with a sentence — save it as an RGB
JPEG or a PNG — and so are a `.dds` with a DirectX 10 header (BC7 and the rest), ATI1/ATI2 (BC4/BC5) or
floating-point pixels. A picture of more than 32 million pixels is read at a fraction of its size.

## The order that matters

**Weather comes first.** If you want weather of your own, choose it *and save it in BMS* before anything below reads
it. BMS flies a weather map the moment you pick it, but PRINT words the weather loaded at that moment, and the
Planner, **Populate from Planner** and **Upd Kneeboard** read the weather file BMS writes when it saves the mission
(`<save>.twx` beside the save, and under Map Model its own copy of the map, `<save>.fmap`). The weather step, in
either mode:

- **BMS Companion → Mission → Weather** (optional): pick the mission's theater, then **Save map** for one weather or
  **Save series** for weather that changes (start it at the mission's own day and hour with **Mission clock**). In
  multiplayer, on the host's PC.
- **In BMS**, with the campaign or TE loaded (a TE must have been saved once, or BMS says SAVE TE FIRST): **Weather**
  → **WEATHER** tab → **Weather Model: Map Model** → click **`BMSC <name>`** → **MAPS AUTO UPDATE** on for a series, off
  for a single map (with it on, BMS's own hourly maps take over within the hour) → **SAVE WTH** in a TE, then save the
  TE; in a campaign, save the campaign. Until then a campaign flies BMS's own four-type weather and reads no map.
  Saving the same map again on the Weather tab does not change a mission that already saved it: pick it and save
  again.

### In EZBoards mode

1. **Your own weather**, if you want it, chosen and saved in BMS as above.
2. **Plan the mission in BMS** and press **SAVE** in the DTC window. This writes `User\Config\<callsign>.ini`, and
   everything below reads it.
3. **Press PRINT on the BMS briefing screen.** This writes `briefing.txt`, which is what the Mission section reads
   (its WEATHER block is BMS's forecast from the weather now loaded), and, if kneeboards-on-PRINT is on, it also runs
   EZBoards. Do this after steps 1 and 2, or the boards carry the old weather or the old cartridge.
4. **GENERATE NOW** on Mission → Kneeboards if you saved the DTC again after PRINT, so the kneeboard carries the
   cartridge as it now is.
5. **Export in html_brief**, if you use it — it reads the save and the DTC, so it comes after every save.
6. **Commit and enter the cockpit.** BMS Companion's own pages and VR boards follow the jet from here and need nothing
   pressed.

If you change the weather after PRINT, save it in BMS and PRINT again (and GENERATE NOW): until then the briefing,
EZBoards' pages and the Mission section show the old weather while BMS flies the new.

**Coming from a mission planned in WDP mode?** BMS keeps lines, PPTs and nav offsets in the cartridge until something
overwrites them, and EZBoards and html_brief print what is there. The PC takes out what the Planner saved by itself:
everything at the switch to EZBoards mode, and what it saved for another flight at the PRINT of a new one (nothing to
press; docs/DATA-STORES.md, "Starting the next mission"). That happens at PRINT, so if the DTC was open in BMS
meanwhile, **LOAD** it before you SAVE it again, and GENERATE NOW after, so the kneeboards carry only this mission's.

### In WDP mode

1. **Your own weather**, if you want it, chosen and saved in BMS as above.
2. **In BMS**: pick your flight and seat, set the loadout, **SAVE** in the DTC window, **SAVE** the campaign or TE (a
   campaign save writes its weather file again; that is fine).
3. **PRINT** in BMS (recommended): BMS's own forecast for take-off, target and landing, and the comm ladder. The
   Planner's card uses it when it is for the same flight, unless the save's weather file was saved later with other
   weather. BMS 4.38.1's briefing prints no QNH; the card takes it from the save's weather file.
4. **In the Planner**: **Open mission…**, pick your flight, plan, and **Save to DTC**. The card's weather list says
   where its weather comes from and as of when. Whatever the Planner saved into the cartridge for an earlier flight
   is taken out by itself when you open this one.
5. **In BMS**: open the DTC window again, press **LOAD**, then **SAVE**, so BMS's copy of your cartridge is the
   Planner's (without LOAD, FLY saves BMS's old copy over the Planner's).
6. **Populate from Planner** — the Mission section, and every device and VR board, shows your flight, with the save's
   weather.
7. **Upd Kneeboard** — your cards on the cockpit kneeboard.
8. **Commit and enter the cockpit**, and **LOAD** on the DTE page.

The Planner's **Steps** (on its toolbar) keeps these steps beside the page, with the one you are on lit.

html_brief is not started from BMS Companion in WDP mode (its button is greyed out and the PC refuses it): by default
it writes pages 1-3, over the Planner's Mission set (pages 1 and 2). If you start it yourself, set it to other pages
in its own settings first; its pages already exported stay readable on the Kneeboards page and the `EXPORTED` board.

If you change the weather after you opened the mission: save it in BMS (SAVE WTH in a TE, the campaign save in a
campaign), PRINT again if you use the briefing, then **Open mission…** the same flight again — the card reads the
save's weather file afresh — and press **Upd Kneeboard** and **Populate from Planner** again. The plan is the weather as saved, at the save's clock:
BMS may still change it before take-off (a campaign's weather type changes by itself as the clock runs, a TE's at its
set times, and a map drifts across the theater).

## Settings, and the one that is not a conflict

- EZBoards and BMS Companion's Briefing pages both need the **plain text** briefing:
  `set g_nPrintToFile 1` and `set g_bBriefHTML 0`. That is what **Setup → step 2** asks for.
- **html_brief does not need `g_bBriefHTML`.** It reads the campaign save directly rather than BMS's briefing
  output, so turning HTML briefings on gains it nothing and costs you the text briefing the others need.
  Leave `g_bBriefHTML 0`.
- Which pages EZBoards writes is set by the `SET KNEEBOARD[...]` lines in its own `CONFIG_USER.BAT`, not by any BMS
  setting. BMS Companion only reads that file, to know which pages to leave to EZBoards in EZBoards mode (in WDP mode
  EZBoards is paused, and the Planner starts at page 1).
- To see a kneeboard in the cockpit the 3D pilot model must be on (Alt+C, then P). Ctrl+Insert turns the left
  kneeboard's page, Ctrl+PgUp the right one's.

## Picking one

- **"I want a kneeboard in the cockpit, on my knee."** In EZBoards mode, EZBoards: set its folder in BMS Companion's
  Setup, leave kneeboards-on-PRINT on, and press PRINT after the DTC save. In WDP mode, the Planner's Upd
  Kneeboard. You can do either from any device.
- **"I fly in VR."** BMS Companion's VR boards through OpenKneeboard: they are live, they follow the jet, and a bound
  button turns the pages. Add the html_brief pages as one more board if you like its layout.
- **"I fly with a tablet beside me."** BMS Companion's own pages. Nothing to generate, nothing to go stale.
- **"I want a PDF to print."** html_brief, last.

Using them together is fine, as long as they are given different pages: EZBoards and the Planner work in different
modes (in EZBoards mode the Planner's Mission set avoids the pages EZBoards claims; in WDP mode EZBoards is paused and
the Planner takes page 1 on), and html_brief's pages are chosen in its own settings (BMS Companion starts it only in
EZBoards mode).

The page files are shared by both modes, since there is one cockpit. **A switch of mode cleans up after the other
mode** (1.3.8): switching to EZBoards mode gives every half Upd Kneeboard made for an earlier flight Falcon BMS's own
page back; switching to WDP mode does the same to every half EZBoards or html_brief made for an earlier flight. Who
made a half is read from the file itself (BMS Companion's tag, EZBoards' `IMAGEMAGICK`, html_brief's 32-bit layout),
and "earlier" means the file was written before the current flight began (its PRINT, or when it was opened in the
Planner): pages made for the flight you are about to fly stay, whichever mode you switch to. BMS's own page comes
from the copies BMS ships (Korea KTO `Docs\07 Kneeboard Templates\F-16\DDS_Backup`, Hellas, the Balkans) — the whole
file when the other half is BMS's too, else only that half drawn into the file — so a theater without them keeps its
pages (the server page's recent activity says "left: no BMS original to put back"; nothing is shown on the devices).
No page file is ever created, each keeps its format, and the `KoreaObj_HiRes` twin is left as it is. Pages are not
undone (they are BMS's own originals); make them again with EZBoards or Upd Kneeboard. A new mission within one mode
leaves the pages alone: the mode's own tool makes them again for it. Where every other kind of mission data lives, what each mode reads and what else a
switch resets is in [DATA-STORES.md](DATA-STORES.md#what-a-switch-resets).

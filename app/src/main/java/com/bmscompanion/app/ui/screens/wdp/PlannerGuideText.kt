package com.bmscompanion.app.ui.screens.wdp

/**
 * The Planner guide's text, for a pilot who has never used Weapon Delivery Planner: the evening from Falcon BMS to
 * the cockpit in nine steps, one card per Planner page, and what to do when something is missing. Drawn by
 * [PlannerGuideWindow]; each [GuideSection] is one page of the guide.
 *
 * It is static text, compiled in, so it can be read with no link to the PC (R3-PLAN A18). The steps follow Falcon BMS
 * 4.38.1's own manuals and WDP's forum thread; the page cards describe the Planner as built. Button names are the
 * ones on screen: **Open mission…**, **Save to DTC** (its menu: *Re-read DTC from BMS*, *Save to DTC and populate*),
 * **Populate from Planner**, **Upd Kneeboard**, **Steps**, **Guide**, **Full window**, **Back to BMS briefing**, the
 * **ATO Targets** tab; and the
 * Mission section's switch, **EZBoards | WDP** (the Planner works in WDP mode only).
 * Change them here when they change there — `--planneroutcome guide` fails on the old names.
 *
 * Markup inside a text: `**bold**` for what is on screen or what matters, and backquotes for a file or a path.
 */
internal object PlannerGuideText {

    val sections: List<GuideSection> = listOf(

        // ------------------------------------------------------------------------------------------------ start

        GuideSection(
            id = "start", group = START, title = "The Planner, from Falcon BMS to the cockpit", short = "Start here",
            aliases = listOf("home", "intro", "overview", "short", "shortversion"),
            blocks = listOf(
                p(
                    "The **Planner** is Weapon Delivery Planner (WDP), Falcas's mission planner, made for Falcon BMS " +
                        "4.38.1 and built into BMS Companion. You open the mission you saved in BMS, pick your flight, " +
                        "and plan: the data card, your cartridge (steerpoints, targets, threats, lines, countermeasures, " +
                        "radios, MFD pages, HARM tables, laser codes), your take-off performance and your attack " +
                        "(the **Attack** tab: Pop-up, HADB or TOSS). Then you put the plan where you need it:",
                ),
                bullets(
                    i("**Save to DTC** writes it into BMS's data cartridge, the file your jet loads."),
                    i(
                        "**Populate from Planner** fills BMS Companion's own Mission section with your flight, on every " +
                            "device: the Dashboard, Map, Briefing, Comms, Taxi and the VR kneeboards.",
                    ),
                    i("**Upd Kneeboard** puts your card on the kneeboard strapped to your leg in the 3D cockpit."),
                ),
                note("Nothing reaches BMS or the rest of the app until you press one of those three buttons."),
                sub("WDP mode"),
                p(
                    "The Planner works in **WDP mode**. A switch at the top of the Mission section (and in Setup) says " +
                        "where the Mission section comes from, on every device at once:",
                ),
                bullets(
                    i("**EZBoards mode** (the default): BMS's printed briefing, filled when you press PRINT in BMS. The Planner is greyed out."),
                    i(
                        "**WDP mode**: the Planner, filled from a save and your cartridge when you press **Populate from " +
                            "Planner**. EZBoards' own kneeboards are paused; your cockpit kneeboards come from **Upd Kneeboard**.",
                    ),
                ),
                p(
                    "Switching is instant, asks nothing and shows nothing. It **clears every leftover of the Planner's** " +
                        "— the lines, PPTs, steerpoints and nav offsets it saved in your cartridge, whatever flight they " +
                        "were for — and the other mode's cockpit kneeboard pages from an earlier flight (BMS's own page " +
                        "goes back). Anything BMS or you changed since the Planner saved it stays. LOAD the DTC in BMS " +
                        "afterwards. Switching to EZBoards mode opens the Mission section on its Briefing.",
                ),
                p(
                    "Every map shows the same mission by default — the Mission map, the VR map board and the Planner's " +
                        "Map page: the route, the tanker and AWACS boxes, the mission's air defences (those the briefing " +
                        "names, else the ones your side has spotted along your route — never one it has not), the PPTs, " +
                        "the airfields and the bullseye — after PRINT in EZBoards " +
                        "mode, and from the flight you open in WDP mode, where the Map page also lets you add, move and " +
                        "take out what goes into the DTC.",
                ),
                sub("Two ways to start"),
                bullets(
                    i(
                        "**From a save, as WDP does** (what Populate from Planner takes). Press **Open mission…**, pick " +
                            "your campaign or TE save and your flight in it.",
                    ),
                    i(
                        "**From BMS's printed briefing.** Press PRINT in BMS and the Planner fills its pages from the " +
                            "briefing and your cartridge. **Back to BMS briefing** returns to it from a save. To fill the " +
                            "Mission section, open the save.",
                    ),
                ),
                p("The strip under the buttons always says which one you are planning from, and which flight and seat."),
                sub("The short version"),
                p(
                    "The same ten lines are the Planner's **Steps** (the toolbar's Steps: beside the page on a PC or a tablet " +
                        "held sideways, a sheet on a phone), with the step you are on lit. Optional, before step 1: weather of " +
                        "your own (made on **Mission → Weather**, or BMS's own settings) is picked and saved in BMS first, " +
                        "before anything reads it (**Weather first**).",
                ),
                steps(
                    i("**In BMS:** open the campaign or TE (stop the clock), pick your flight and seat, set the loadout."),
                    i("**In BMS:** open the DTC, set what you want, **SAVE**. Then save the campaign or TE."),
                    i("**In BMS:** **PRINT** the briefing."),
                    i("**Planner:** **Open mission…** → pick the save (newest on top, grouped by theater), your flight and seat."),
                    i("**Planner:** plan on the pages you need: DataCard, DTC, the Attack tab, Map."),
                    i("**Planner:** **Save to DTC**."),
                    i(
                        "**In BMS:** open the DTC again → **LOAD** → **SAVE**. Check that your threats and lines appear on BMS's map.",
                        why = "without LOAD, FLY saves BMS's old copy of the cartridge over the Planner's.",
                    ),
                    i("**Planner:** **Populate from Planner**: the app's Mission section and the VR boards show your flight on every device."),
                    i("**Planner:** **Upd Kneeboard**: your DataCard on page 1 and the Coordination Card on page 2 of the cockpit kneeboard (EZBoards is paused in WDP mode)."),
                    i(
                        "**In BMS:** **FLY**. In the jet, from a cold ramp start, load the cartridge on the MFD **DTE** page " +
                            "with **LOAD**; from a taxi or runway start BMS has already loaded it.",
                    ),
                ),
                p("The next pages go through the same steps one at a time, with the why of each."),
                go(GuideGo.Section("weather"), "Weather first"),
                go(GuideGo.Section("step1"), "Step by step"),
                go(GuideGo.OpenMission, "Open mission…"),
                sub("The Planner's buttons"),
                table(
                    listOf("Button (tap for its step)", "What it does"),
                    row("Open mission…", "Opens a campaign or TE save from the PC and picks your flight in it.", "step3"),
                    row(
                        "Save to DTC",
                        "Writes your plan into BMS's cartridge. The number on it is how many edits are not saved yet. Its " +
                            "menu has **Re-read DTC from BMS** and **Save to DTC and populate**. WDP's own **Get DTC File** " +
                            "and **Save DTC** are on the DataCard page too, as in WDP.",
                        "step5",
                    ),
                    row(
                        "Populate from Planner",
                        "Fills the app's Mission section, on every device, from your flight and your cartridge as saved. " +
                            "Writes nothing into BMS.",
                        "step5",
                    ),
                    row("Upd Kneeboard", "Puts your card on the cockpit kneeboard.", "step7"),
                    row(
                        "Steps",
                        "The ten steps above, beside the page (a sheet on a phone). The step you are on is lit, and so is its " +
                            "toolbar button; you tick the steps done in BMS. A press on a Planner step lights its button on the " +
                            "toolbar; where the toolbar has no room for it (a phone), the step does it.",
                    ),
                    row("Guide", "This guide. It opens at the page you are on."),
                    row(
                        "Options",
                        "WDP's **Options** menu (the gear; on a phone, under Options in the ⋮ menu): **Settings…** and " +
                            "**About WDP**.",
                    ),
                    row(
                        "ATO Targets",
                        "The last page tab: WDP's ATO Target List, the targets your side's flights are tasked to attack in " +
                            "the open save.",
                        "ato",
                    ),
                    row(
                        "Settings…",
                        "WDP's Settings, as the Planner has them. **Show tooltips** explains a control when the mouse rests " +
                            "on it, or on a long press by finger; **Auto load last mission on startup** opens the flight you " +
                            "planned last when the Planner first opens; **Reload from BMS** reads the save and your cartridge " +
                            "again. It also shows the Planner's own folder, User\\BMS Companion Planner inside Falcon BMS, and " +
                            "its DataCards folder, which **Browse…** changes.",
                    ),
                    row("Full window", "Gives the Planner the whole window; press it again to bring the app back."),
                ),
            ),
        ),

        GuideSection(
            id = "first", group = START, title = "Before your first mission (once)", short = "Before your first mission",
            aliases = listOf("setup", "once", "before"),
            blocks = listOf(
                bullets(
                    i(
                        "**BMS Companion runs on the PC where Falcon BMS is installed.** Whatever device you plan on, the " +
                            "Planner reads your mission files and writes your cartridge and kneeboards through that PC.",
                    ),
                    i(
                        "**BMS must print the briefing as a text file.** Setup, step 2, checks this for you. The app's " +
                            "Briefing pages and EZBoards both read that file.",
                    ),
                    i("**Switch the Mission section to WDP mode** (the switch at its top, or in Setup): the Planner works only there."),
                    i("**If you use EZBoards,** set its folder in Setup and leave “kneeboards on PRINT” on. It runs in EZBoards mode; WDP mode pauses it."),
                    i(
                        "**To see a kneeboard in the cockpit, the 3D pilot model must be on.** It is on by default; in the " +
                            "cockpit, **Alt+C then P** switches it on and off.",
                    ),
                ),
            ),
        ),

        // ------------------------------------------------------------------------------------------------ the steps

        GuideSection(
            id = "weather", group = STEPS, title = "Weather first (optional): choose it and save it in Falcon BMS",
            short = "Weather first (optional)", aliases = listOf("wx", "twx", "fmap", "savewth", "mapmodel", "weathertab"),
            blocks = listOf(
                p(
                    "Skip this to fly the weather the mission already has. For weather of your own, choose it **and save it " +
                        "in BMS** before you PRINT and before **Open mission…**. Each tool takes the weather as it is when it " +
                        "reads: BMS flies a map the moment you pick it, but the Planner, **Populate from Planner** and " +
                        "**Upd Kneeboard** read the weather file BMS writes when it saves the mission (`<save>.twx`, beside " +
                        "the save), and PRINT words the weather loaded at that moment.",
                ),
                steps(
                    i(
                        "**In BMS Companion, Mission → Weather** (optional): pick the mission's theater, then **Save map** for " +
                            "one weather or **Save series** for weather that changes (start it at the mission's own day and " +
                            "hour: **Mission clock**). In multiplayer, do this on the host's PC.",
                        why = "the host's weather files are the ones BMS sends to everyone.",
                    ),
                    i(
                        "**In BMS**, with the campaign or TE open: the **Weather** icon, the **WEATHER** tab, **Weather Model: " +
                            "Map Model**, then click **BMSC <name>** (BMS says MAP LOADED). Or set BMS's own weather there instead.",
                        why = "a map is flown only under Map Model. A campaign normally flies BMS's own four-type weather and " +
                            "reads no map until you switch it.",
                    ),
                    i(
                        "**MAPS AUTO UPDATE**: on for a series, off for a single map.",
                        why = "with it on, BMS's own hourly weather maps take over within the hour.",
                    ),
                    i(
                        "**Save the weather:** in a TE, **SAVE WTH** (a TE must have been saved once, or BMS says SAVE TE " +
                            "FIRST), then save the TE; in a campaign, save the campaign.",
                        why = "this writes the save's weather file, and for a map BMS's own copy of it (`<save>.fmap`): what " +
                            "the Planner reads.",
                    ),
                ),
                bullets(
                    i(
                        "**Saving the same map again on Mission → Weather does not change a mission that already saved it**: " +
                            "BMS flies its own copy. Pick the map and save again.",
                    ),
                    i(
                        "**Changed the weather after you opened the mission?** Save it in BMS, PRINT again if you use the " +
                            "briefing, then **Open mission…** the same flight again: the card reads the save's weather file " +
                            "afresh. Then **Upd Kneeboard** and **Populate from Planner** again.",
                    ),
                    i(
                        "**The plan is the weather as saved**, at the save's clock. BMS may change it before take-off: a " +
                            "campaign changes weather type by itself as the clock runs, a TE at its set times, and a map drifts " +
                            "across the theater.",
                    ),
                    i(
                        "In **EZBoards mode**, PRINT again after the weather is saved (then **Generate now** if kneeboards are " +
                            "not made at PRINT): the briefing, EZBoards' pages and the Mission section all come from that PRINT.",
                    ),
                ),
            ),
        ),

        GuideSection(
            id = "step1", group = STEPS, title = "Step 1. In Falcon BMS: your flight, your seat, your loadout",
            short = "1. Flight, seat, loadout", aliases = listOf("flightseat", "loadout"),
            blocks = listOf(
                steps(
                    i("Open the campaign or the Tactical Engagement (TE)."),
                    i(
                        "**Stop the clock:** click the arrow next to the time and pick **Stop** (in multiplayer the host " +
                            "picks **HALT**).",
                        why = "the clock keeps running while you plan, and a ramp start can run out of time before you are in the jet.",
                    ),
                    i(
                        "**Pick your flight in the FRAG list, then click your seat in it.**",
                        why = "BMS gives your seat its own IFF codes and Link 16 numbers, and in a campaign, choosing the " +
                            "mission clears the target steerpoints, threats and lines from your cartridge. So pick the " +
                            "flight and seat first, and do not change them after you have planned.",
                    ),
                    i(
                        "**Set the loadout** (fuel, stores). If you carry laser-guided bombs, set the bomb's laser code " +
                            "here and press **SET CODE**. The laser code on the Planner's DTC page must be the same.",
                    ),
                    i(
                        "Note your **package number** and **flight callsign**. The briefing prints both at the top " +
                            "(“Package #” and the flight line). The Planner's flight picker opens on the flight BMS " +
                            "briefed, but it helps to know it.",
                    ),
                ),
            ),
        ),

        GuideSection(
            id = "step2", group = STEPS, title = "Step 2. In Falcon BMS: SAVE the cartridge, SAVE the mission, PRINT",
            short = "2. SAVE, SAVE, PRINT", aliases = listOf("save2", "printbriefing"),
            blocks = listOf(
                steps(
                    i(
                        "**Open the DTC window** (the icon on the right-hand side of BMS's map) and press **SAVE**.",
                        why = "your seat's IFF codes and comm plan are only written to the cartridge when BMS saves it, " +
                            "and the Planner reads that file.",
                    ),
                    i(
                        "**SAVE the campaign or TE** (the SAVE button at the top of the screen; give it a name you will recognise).",
                        why = "**Open mission…** reads your flight, route, times and loadout from this save, and its weather " +
                            "from the weather file BMS keeps beside it. Anything you change in BMS after saving is not in " +
                            "it, so save again after a change.",
                    ),
                    i(
                        "**Press PRINT** on the briefing.",
                        why = "the Planner's card takes what only the printed briefing has, the comm ladder and BMS's own " +
                            "weather forecast for take-off, target and landing, when it is for the same flight.",
                    ),
                ),
                note(
                    "**PRINT is optional when you plan from a save.** The Planner and Populate from Planner read your " +
                        "save, and make the situation, intel, rules of engagement and emergency procedures from it, and " +
                        "the weather from the save's own weather file; without a printed briefing only the comm ladder " +
                        "is missing from the Planner's card. In WDP mode PRINT does not change the Mission section and " +
                        "EZBoards makes no kneeboards.",
                ),
            ),
        ),

        GuideSection(
            id = "step3", group = STEPS, title = "Step 3. Open your mission in the Planner", short = "3. Open mission…",
            aliases = listOf("open", "openmission", "browser", "files", "campaign", "flight", "picker", "flightpicker"),
            blocks = listOf(
                steps(
                    i(
                        "Press **Open mission…**. The list shows the mission files of every theater installed on the PC, " +
                            "grouped by theater, **newest first** (the later of when the file was saved and when it was " +
                            "copied in, so a TE a friend sent you is on top too). The theater BMS is set to comes first. " +
                            "A switch sorts by modified, created or name instead; the search box finds a file by name; " +
                            "**Recent** holds the last five you opened on this device.",
                        sub = listOf(
                            "**Campaign saves** end in **.cam**. BMS names its own saves “Save-Day …” or “Auto Save”; yours have the name you typed.",
                            "**Tactical Engagements** end in **.tac**, trainings in **.trn**.",
                            "**BMS's own files** are folded away under **Show BMS's own missions**: the TE_BMS and " +
                                "TR_BMS missions, which open and save like your own, as in WDP, and, under **Campaign " +
                                "starts and templates**, the starts (Save0, Save1 …, Te_New and Instant), which hold no " +
                                "mission for you and are the only files that cannot be opened.",
                        ),
                    ),
                    i("Pick your save. The newest one is normally the one you just made. **Browse…** is WDP's own Open window, on the PC's folders."),
                    i(
                        "**Pick your flight and your seat** (the **Pick a flight** window). It lists each side's packages " +
                            "and flights and opens on the flight BMS briefed. **Find package** finds a package by its " +
                            "number; **Expand all** and **Collapse all** open and close the tree; **ATO Target List** " +
                            "lists every package's targets. Pick Lead, Wing, Element lead or Element wing, then " +
                            "**Plan this flight** (**‹ Files** goes back to the list).",
                    ),
                    i(
                        "The Planner fills its pages from the save and from your cartridge. The strip under the buttons " +
                            "shows the mission, package, flight and seat you are planning; tap it to plan another flight.",
                    ),
                ),
                p(
                    "**Where each steerpoint comes from: WDP's question.** **Plan this flight** asks, as WDP does, " +
                        "“Did you save Precision STPT in the DTC for THIS flight in BMS?”: always for a campaign, and " +
                        "for a TE only when its mission file (the .ini beside it) is there. **No** is the default (Enter): " +
                        "every steerpoint comes from the mission file, the save's own position, which is only to the " +
                        "nearest map cell (about half a mile), and your cartridge's positions are not used. **Yes** takes " +
                        "your cartridge's position for each steerpoint (precise: BMS's Recon, or your own edit), and the " +
                        "mission file's where your cartridge's slot is empty; the strip names those (“STPT 8 empty in " +
                        "your DTC”). Say Yes only when you saved the DTC in BMS for this very flight. The flight picker " +
                        "shows what each answer gives before you press, the strip under the buttons says which you " +
                        "chose, and on each attack page the tip of **TGT STPT** and **IP STPT** says where that steerpoint came from.",
                ),
                p("**Back to BMS briefing** (in **Open mission…** and **Pick a flight**, beside what is planned) puts the Planner back on the printed briefing."),
                go(GuideGo.OpenMission, "Open mission…"),
            ),
        ),

        GuideSection(
            id = "step4", group = STEPS, title = "Step 4. Plan", short = "4. Plan", aliases = listOf("plan", "pages", "clear"),
            blocks = listOf(
                p("Use the pages you need, in any order. They share one mission: a change on one page shows on the others. Tap a page's name for its own card."),
                table(
                    listOf("Page (tap for its card)", "What it is for"),
                    row(
                        "Briefing",
                        "Your mission, package, callsign, airbase, aircraft, task, take-off time and TOT, the situation " +
                            "and threats, and the weather. Write your objective and notes here.",
                        "briefing",
                    ),
                    row(
                        "DataCard",
                        "The card you fly with: the route with times, headings, distances, speeds and minimum fuel; the " +
                            "airfields with their frequencies and ATIS; your flight and package; the tankers and AWACS; " +
                            "your targets and delivery.",
                        "datacard",
                    ),
                    row("Coordination Card", "The package's timing, taxi and take-off times, your route in latitude and longitude, codewords.", "coordination"),
                    row(
                        "Map",
                        "Your cartridge on the theater map as the HSD will show it, beside the tanker and AWACS tracks, the " +
                            "known air defences and your airfields. Tap one to add it to the DTC as a line, a PPT or a steerpoint.",
                        "map",
                    ),
                    row(
                        "DTC",
                        "Your cartridge, tab by tab: steerpoints 1-24 and 81-99, weapon targets, lines (four lines of " +
                            "six points), pre-planned threats (15 of them; steerpoints 56-70 in the jet), EWS " +
                            "countermeasure programs 1-6, radios (20 UHF and 20 VHF presets, TACAN, ILS), MFD pages for " +
                            "each master mode, HARM tables, IFF, systems and laser codes, and nav offsets.",
                        "dtc",
                    ),
                    row(
                        "Performance",
                        "Take-off, refusal, climb and cruise figures and a turn calculator for your departure field, " +
                            "aircraft, loadout and weather. Its take-off figures also go on the DataCard.",
                        "performance",
                    ),
                    row(
                        "Attack (Pop-up, HADB, TOSS)",
                        "The three attack planners, under one **Attack** tab: pick Pop-up, HADB or TOSS on the slim rail " +
                            "beside the page (the tab opens on the one you used last). Pick the target steerpoint (TGT STPT), " +
                            "set up your attack, and read the numbers you type into the jet (VRP or VIP, pull-up point, " +
                            "offset aimpoints). The map shows the attack.",
                        "attack",
                    ),
                    row("ATO Targets", "What your side's other flights are tasked to attack in the save, and when. For information only.", "ato"),
                ),
                sub("Worth knowing"),
                bullets(
                    i(
                        "**Each attack page picks its own target** with **TGT STPT**, and in VIP mode its IP with **IP STPT** beside " +
                            "it (the steerpoint before the target until you pick another). A new mission or flight sets " +
                            "all three pages once to your first strike steerpoint (or the flight plan's first target); " +
                            "after that each page keeps what you set. The page's **Save to DTC** also puts the attack on the card. " +
                            "The attack page you last changed is the one the maps, the kneeboard and Populate show.",
                    ),
                    i(
                        "**Clear** on the DTC page's steerpoint tabs asks before it wipes steerpoints that have a position, " +
                            "such as the target steerpoints you made with BMS's Recon, and **Save to DTC** warns once " +
                            "before it would set a positioned steerpoint to 0. Say no unless you mean it.",
                    ),
                    i("The attack pages put their offsets into the DTC page's **nav offsets**; **Save to DTC** writes them."),
                    i("Leaving the Planner for another part of the app loses nothing. Your plan stays until you open another mission."),
                ),
                go(GuideGo.Page(WdpPage.DTC.name), "Go to the DTC page"),
            ),
        ),

        GuideSection(
            id = "step5", group = STEPS, title = "Step 5. Save to DTC, and Populate from Planner", short = "5. Save to DTC, Populate",
            aliases = listOf("save", "savetodtc", "populate", "populatefromplanner", "send", "sendtomission", "cartridge", "te"),
            blocks = listOf(
                p(
                    "**Save to DTC** writes your plan into BMS's data cartridge, `User\\Config\\<your callsign>.ini` on " +
                        "the PC. The number on the button is how many edits are not saved yet.",
                ),
                bullets(
                    i(
                        "There is **one cartridge per pilot** (named after the callsign in your BMS logbook), not one per " +
                            "flight. Whatever you saved last is what the jet loads, whichever flight you then fly.",
                    ),
                    i("**It writes at once, as WDP does**: no question first and no copy of the file kept."),
                    i("It writes only the settings you changed, onto the file as BMS last left it, so anything BMS changed meanwhile stays."),
                    i(
                        "For a **Tactical Engagement** opened with **Open mission…**, it also updates the TE's own `.ini` " +
                            "beside the `.tac` (your own TE, or a TE or training that ships with Falcon BMS, as in WDP). " +
                            "A copy renamed in Windows and a TE whose name a later campaign save shares (“Auto Save”) " +
                            "are not written, and the answer says why. A TE with no `.ini` of its own is flown on your " +
                            "cartridge alone, and none is made for it.",
                        why = "BMS loads that file on top of your cartridge every time the TE is opened.",
                    ),
                    i(
                        "Its menu has **Re-read DTC from BMS**, for when BMS has saved the cartridge again (it asks first " +
                            "if you have edits not saved), and **Save to DTC and populate**, both in one press.",
                    ),
                ),
                go(GuideGo.Section("savedtc"), "What Save to DTC writes"),
                p(
                    "**Populate from Planner** fills BMS Companion's own Mission section from the flight open in the " +
                        "Planner, your cartridge **as saved** and your attack: steerpoints, targets, threat rings, lines " +
                        "and the attack on the **Map**, the **Briefing**, radio presets on **Comms**, the tanker and AWACS " +
                        "tracks, the **Taxi** page, the Dashboard and the VR kneeboards, on every device. It writes nothing " +
                        "into BMS.",
                ),
                bullets(
                    i(
                        "It happens only when you press it, so the Mission section never changes under you. When you " +
                            "change your save or your cartridge afterwards, press it again to bring the change in.",
                    ),
                    i(
                        "Edits not saved to the DTC do not travel. With some waiting it asks first: **Save to DTC and " +
                            "populate**, **Populate without them** or **Cancel**.",
                    ),
                    i(
                        "It takes a **save's** flight (**Open mission…**). While the Planner plans from BMS's printed " +
                            "briefing it offers Open mission…, or the flight populated last again.",
                    ),
                    i("The Mission section keeps what was populated through a restart of the PC program, and while you switch to EZBoards mode and back — unless the flight current by then is another one (BMS printed another flight, or the Planner has another open), when the switch back clears it. Opening another flight in the Planner clears it at once: a new mission."),
                    i("It works in WDP mode, the only mode the Planner opens in. The Kneeboards page and the Mission section's empty pages have the same button."),
                ),
                go(GuideGo.Populate, "Populate from Planner"),
            ),
        ),

        GuideSection(
            id = "step6", group = STEPS, title = "Step 6. Back in Falcon BMS: LOAD the cartridge", short = "6. LOAD in BMS",
            aliases = listOf("load", "loadinbms"),
            blocks = listOf(
                steps(
                    i("**Open the DTC window and press LOAD.** Wait for “Loaded OK”. If a tab looks empty, press LOAD again."),
                    i("**Look at BMS's map:** your pre-planned threats (rings) and your lines should now be on it."),
                    i("**Do not press SAVE in BMS's DTC window before this LOAD, and do not pick another flight or seat after it.**"),
                ),
                why(
                    "BMS keeps its own copy of your cartridge in memory and writes that copy to the file when you press " +
                        "TAKEOFF. Unless you LOAD first, BMS's copy, without your Planner changes, replaces the file you " +
                        "just saved. **LOAD** is what brings the Planner's changes into BMS.",
                ),
            ),
        ),

        GuideSection(
            id = "step7", group = STEPS, title = "Step 7. Kneeboards", short = "7. Kneeboards",
            aliases = listOf("print", "printtokneeboard", "kneeboard", "kneeboards", "ezboards"),
            blocks = listOf(
                p("Three things can put pages on your in-cockpit kneeboard, and they write the same page files. You can use them together."),
                bullets(
                    i(
                        "**EZBoards** runs when you PRINT in **EZBoards mode** (or press **Generate now** on the " +
                            "Kneeboards page). **In WDP mode it is paused**, so it cannot overwrite the Planner's pages: " +
                            "GENERATE NOW is greyed out (with **Open the Planner** beside it) and nothing runs on PRINT, " +
                            "so the cockpit's kneeboards are the Planner's **Upd Kneeboard**. Your EZBoards setting is " +
                            "kept for when you switch back.",
                    ),
                    i(
                        "EZBoards reads the briefing and your cartridge as they were when it ran, so in EZBoards mode " +
                            "**after a new cartridge, generate again**, or its pages show " +
                            "the old cartridge.",
                    ),
                    i("**Upd Kneeboard** in the Planner puts your DataCard, and any other pages you choose, on the kneeboard, and **Browse picture…** a picture of your own."),
                    i(
                        "The **HTML briefing** (html_brief, Mission → Kneeboards) writes pages 1 to 3 when it exports. " +
                            "**In WDP mode** its **Run HTML Briefing** button is greyed out too, for the same reason; " +
                            "the pages it already exported can still be read.",
                    ),
                ),
                p(
                    "**In WDP mode Upd Kneeboard starts at page 1** (**Mission set**): the DataCard on page 1 and the " +
                        "Coordination Card on page 2, of both knees. EZBoards is paused in WDP mode, so the pages it " +
                        "writes in EZBoards mode are yours. (In EZBoards mode the Mission set leaves EZBoards its pages " +
                        "and starts at the first pair it does not use, pages 2 and 3 on a stock install.) Whichever wrote " +
                        "a page last is what you see, so print after html_brief if you use it. The window shows who wrote " +
                        "each page last.",
                ),
                p(
                    "**Browse picture…** puts a picture of your own on a page, as WDP's Browse Picture does: pick a " +
                        "`.jpg`, `.png`, `.bmp` or `.dds` on the BMS PC, choose the page, then **← Insert left** or " +
                        "**Insert right →**. The picture is stretched to fill that half page, drawn by the PC from the " +
                        "file itself at the page's full size. A half's own menu has **Picture…** for the same. The " +
                        "picture is read again when you print, so a file you have since moved leaves that half as it " +
                        "is, and the window says so. What you choose, pictures included, is remembered between launches.",
                ),
                p("Make kneeboards **before you press TAKEOFF**: BMS picks them up as you enter the 3D world."),
                p(
                    "A kneeboard page is simply replaced (no backup is kept): you can make it again whenever you like. " +
                        "**Put BMS's page back** restores BMS's own page where BMS ships one.",
                ),
                go(GuideGo.Print, "Upd Kneeboard…"),
            ),
        ),

        GuideSection(
            id = "step8", group = STEPS, title = "Step 8. TAKEOFF", short = "8. TAKEOFF", aliases = listOf("takeoff", "fly"),
            blocks = listOf(
                p(
                    "Press **TAKEOFF**. BMS saves your cartridge as it commits you to 3D; after the LOAD in step 6, what " +
                        "it saves is your plan.",
                ),
            ),
        ),

        GuideSection(
            id = "step9", group = STEPS, title = "Step 9. In the cockpit", short = "9. In the cockpit",
            aliases = listOf("cockpit", "jet", "dte"),
            blocks = listOf(
                sub("Load the cartridge into the jet"),
                bullets(
                    i(
                        "**Ramp start (cold jet):** BMS does not load it for you. In the start-up checklist, after the " +
                            "**C&I knob to UFC**, bring up the **DTE** page on an MFD (MENU, then **DTE**, OSB 8) and " +
                            "press **LOAD** (OSB 3). Each label on the page lights up briefly as that part loads. Do it " +
                            "**before** you set up the radios, or the cartridge's presets replace what you set.",
                    ),
                    i("**Taxi or runway start:** BMS loads the cartridge automatically."),
                ),
                p(
                    "**Check it:** the DED shows your steerpoints and radio presets; the HSD shows your pre-planned " +
                        "threats and lines; your attack's VRP or VIP and offsets are on the DED's offset pages.",
                ),
                sub("The kneeboard"),
                p("Look down at your legs (pilot model on: **Alt+C, then P**). Each knee has 16 pages."),
                bullets(
                    i("**Ctrl+Insert** turns the left kneeboard's page, **Ctrl+PgUp** the right one's."),
                    i("You can also click (left or right) or scroll on the kneeboard itself."),
                    i("The “page back” commands have no keys by default; give them keys in your key file if you want them."),
                ),
            ),
        ),

        // ------------------------------------------------------------------------------------------------ the pages

        GuideSection(
            id = "briefing", group = PAGES, title = "The Briefing page", short = "Briefing",
            blocks = listOf(
                p(
                    "**What it is for:** your mission at a glance: mission, package, callsign, airbase, aircraft, task, " +
                        "take-off time and TOT, the situation, the threats and the weather. **Mission Objective** and " +
                        "**Notes** are yours to type.",
                ),
                sub("Where it comes from"),
                bullets(
                    i(
                        "The printed briefing, or, with a save open, the save. When BMS printed a briefing for that same " +
                            "flight, its texts are used. Otherwise the situation, the mission overview, the rules of " +
                            "engagement and the emergency procedures are made from the save, worded the way BMS's own " +
                            "briefing words them (a TE shows its author's mission text), and the comm ladder stays empty " +
                            "until you PRINT in BMS: only the printed briefing has it.",
                    ),
                    i("The situation is cut into WDP's five lines of up to 80 characters."),
                    i(
                        "**Intel** lists the briefing's ground threats, or, from a save, the enemy's air defences, " +
                            "fighters, bombers and helicopters in it (the whole theater, as WDP lists them).",
                    ),
                    i(
                        "On the right: the weather list and the two ATIS lines of the DataCard. The list first says where " +
                            "its weather comes from: the save's own weather file, as saved at the save's clock (BMS may " +
                            "change it before take-off), or BMS's printed briefing of the same flight, whichever is newer. " +
                            "A file picked with **Reload WX** stays until you open another mission.",
                    ),
                ),
                p("The buttons down the left side are the same on the three card pages; the DataCard's card lists them."),
                go(GuideGo.Page(WdpPage.BRIEFING.name), "Go to the Briefing page"),
            ),
        ),

        GuideSection(
            id = "datacard", group = PAGES, title = "The DataCard", short = "DataCard", aliases = listOf("card"),
            blocks = listOf(
                p("**What it is for:** the card you fly with, on two sheets. Its arithmetic is WDP's, checked against the real program."),
                sub("Sheet 1"),
                bullets(
                    i(
                        "**ATIS**: two lines made from the take-off weather (the save's own weather file, or BMS's printed " +
                            "briefing of the same flight) for your take-off time, in the military colour code or civil " +
                            "(**Mil/Civ**). With a file's wind, the runway is the one into it unless you picked one.",
                    ),
                    i(
                        "**DEP / ARR / ALTN**: each field's TACAN, elevation, the runway into the wind, ILS, tower UHF and " +
                            "VHF, and the ground, approach and ops frequencies. Tap a field's name to pick another, its " +
                            "runway for the runway list, **C** for its charts.",
                    ),
                    i("**Flight** and **Package**: names, IDM or Link 16 numbers, A-A TACAN; each flight's aircraft, task and frequencies."),
                    i(
                        "The card always plans **your own flight**: press its callsign in the package rows for its **Loadout**. " +
                            "The package's other flights are greyed: \"" + DataCardWiring.OTHER_FLIGHT_TIP.replace("\n", " ") + "\"",
                    ),
                    i("**Flight plan** (24 rows): action, time over steerpoint, heading, distance, speed (Mach or ground speed), altitude, minimum fuel and formation."),
                ),
                sub("Sheet 2"),
                bullets(
                    i("**T/O and loadout**: gross weight, drag, rotation and refusal speeds, MIL power and climb, take-off spec and fuel, from the Performance page."),
                    i(
                        "**ALOW, MSL, BINGO, EWS, bomb profiles and laser codes**: from your cartridge. What you type here " +
                            "goes into the DTC page and is written by **Save to DTC**.",
                    ),
                    i("**TGT Primary / Secondary**: the first and second strike steerpoints (with a save open, the ones your seat is given), with latitude and longitude."),
                    i(
                        "**DMPI**: tap the box for WDP's **Target Selection**: every objective of your save by type (or type part " +
                            "of a name), its buildings and what each is worth, and a map of them. **Apply** (or a double tap on a " +
                            "building) fills the target, the DMPI and its position with the ground's height.",
                    ),
                    i("**Delivery**: tap the type (PopUp, HADB, TOSS or None) for that attack page's offsets and figures; **T** steps the ranges between feet, nm and km."),
                    i(
                        "**Support**: the tankers, AWACS, JSTARS and FAC with their TACAN, UHF, time on station and where they " +
                            "are (bearing and range from the bullseye). With a save open it is every one of your side in the save; " +
                            "the small button beside Tanker 1 or Tanker 2 lists the tankers on station while you fly, to pick another.",
                    ),
                    i(
                        "**The map**: your route and the tankers' tracks, or the chosen attack's map. A tap switches to the white " +
                            "map; the wheel or two fingers zoom, a drag pans, **Fit** shows it whole again.",
                    ),
                ),
                sub("How the figures are worked out"),
                bullets(
                    i("Each leg's heading and distance from the steerpoints' positions; the time over each steerpoint from the briefing, to the second."),
                    i("Speed from each leg's distance and time, rounded to 5 kt; Mach at the row's altitude."),
                    i(
                        "**Minimum fuel** is worked backwards from the last steerpoint: a 1,000 lb reserve (or what you type " +
                            "in the last row), plus 20 lb a mile below 5,000 ft, 15 lb up to 25,000 ft and 10 lb above, " +
                            "rounded up to the 100 lb. A figure you type in a row works the rows above it again.",
                    ),
                ),
                sub("The buttons down the left side (all three card pages)"),
                table(
                    listOf("Button", "What it does"),
                    row(
                        "Reload WX",
                        "WDP's **Load WX FMAP File** window on the BMS PC: pick a weather map (**.fmap**) or your campaign's " +
                            "weather file (**.twx**, beside the save). The ATIS, the weather list, Force QNH and the Performance " +
                            "page's take-off weather are made from it, at each field's own place on the map; the card says " +
                            "which file its weather comes from, and keeps it until you open another mission. **Populate from " +
                            "Planner** takes the save's own weather file, not a file picked here, and not the printed " +
                            "briefing's wind direction.",
                    ),
                    row("Mil/Civ, M / SM", "The ATIS in the military colour code or civil; visibility in metres or statute miles. Both are remembered; the first start is Civil and metric, as in WDP."),
                    row("Different Flight", "**Pick a flight** on the save your card is planned from, to plan another flight."),
                    row("view Mil codes", "WDP's colour-code chart."),
                    row(
                        "Get DTC File",
                        "The DTC page's **Open Callsign.ini File**, in the game's User\\Config on your own cartridge (WDP's " +
                            "button opened a TE's mission .ini instead): the file you pick is the DTC page's cartridge, and the " +
                            "card takes its boxes afresh (EWS names, bomb profiles, laser codes, ALOW, MSL floor, bingo).",
                    ),
                    row(
                        "Save DTC",
                        "Hands what you changed on the card to the DTC page and saves the cartridge in User\\Config, as the " +
                            "toolbar's **Save to DTC** does. The **Callsign.ini saved** lamp under it is green when nothing waits " +
                            "for the cartridge, red when something does.",
                    ),
                    row(
                        "Load / Save DataCard, Cards Directory",
                        "WDP's own **.bdc** file, kept on the BMS PC in the Planner's folder inside Falcon BMS: " +
                            "User\\BMS Companion Planner\\DataCards\\<mission>\\<package>\\<callsign> (or the DataCards folder " +
                            "you chose). WDP opens the same file. The ticks say which flight-plan columns Load brings back. " +
                            "**Cards Directory** opens the folder (File Explorer on the PC); cards an earlier version kept on " +
                            "this device are offered there.",
                    ),
                    row("Current Time", "Your save's campaign clock, as WDP shows it (day, time). With the printed briefing, the clock of the save that holds your flight; without one, the sim's time of day while BMS runs."),
                    row("Apt Schedule", "Every flight of your save departing from your field, by time: aircraft, callsign, squadron, package and mission, yours underlined. **Save JPG** saves it as a picture beside your DataCards."),
                ),
                p(
                    "The toolbar does the cartridge from every page too: **Save to DTC**, and **Re-read DTC from BMS** in its " +
                        "menu. To plan another flight of the package, tap the strip under the buttons. WDP's own **Upd " +
                        "Kneeboard** is not on the card: the toolbar's is the one.",
                ),
                go(GuideGo.Page(WdpPage.DATACARD.name), "Go to the DataCard"),
            ),
        ),

        GuideSection(
            id = "coordination", group = PAGES, title = "The Coordination Card", short = "Coordination Card",
            aliases = listOf("coord", "coordinationcard"),
            blocks = listOf(
                p("**What it is for:** the package's timing on one sheet, for the brief and the flight."),
                bullets(
                    i(
                        "The package table: each flight's callsign, aircraft, task, taxi, take-off, hold, inflight " +
                            "frequency, A-A TACAN, transit altitude, push and TOT.",
                    ),
                    i("Your route with each steerpoint's latitude and longitude; tap the title for bearing and distance from take-off."),
                    i("The runway in use, STD QNH, the transit level and the briefing's QNH (**Force QNH**)."),
                    i("Four plan pictures: a tap opens a picture file on the PC, as in WDP; a right click or a long press offers WDP's templates or an attack page's map as it is. And 35 codeword boxes."),
                    i("**Taxi time** = take-off time less the taxi minutes (the 4-10 minute spinner)."),
                    i("**Save / Load Package Timing** and **Save / Load Codewords** are WDP's `PackageTiming.ini` and `Codewords.ini`, in the Planner's DataCards folder on the BMS PC."),
                ),
                go(GuideGo.Page(WdpPage.COORDINATION.name), "Go to the Coordination Card"),
            ),
        ),

        GuideSection(
            id = "map", group = PAGES, title = "The Map page", short = "Map", aliases = listOf("mappage", "hsd"),
            blocks = listOf(
                p(
                    "**What it is for:** your cartridge on the theater map, the way the HSD will show it, and what the mission " +
                        "knows that the cartridge does not carry yet. Anything marked \"not in DTC\" is not in your cartridge yet. " +
                        "It is WDP's MAP tab on the app's own maps: the map on the left, WDP's options beside it (**Options** on a " +
                        "tablet or phone).",
                ),
                bullets(
                    i("**Options**, in WDP's order: the map's style and label ink, the lat/long grid and the bullseye's rings; every airfield in the colour of the side holding it, and the VORTACs; your flight plan (**Viewing** what the jet flies, the cartridge or the mission file) with **Trk/Dist**; your package's other flights; the save's intel — the mission's threats (with **All known SAMs**, every one your side has spotted) by type, search radars, no radar, your own side's SAMs, JSTARS and ships. Nothing your side has not seen is ever shown."),
                    i("The strip at the top left says where the pointer is: X/Y, latitude and longitude, the ground, the variation and, with Cursor bullseye, bullseye. Rest the mouse on anything for its facts; by finger the strip follows your tap."),
                    i("**Tap** a tanker's track, a threat, a station or a field: its card says what it is and offers **Add as line**, **Add as PPT**, **Add as PPT…** (WDP's PPT window), **Add as STPT**, **Add to targets**, a TACAN, an ILS or **Charts…**. A tanker's or AWACS's **Add as line** lays its track as WDP does: the box 30,000 ft either side of the leg it holds on, closed, as the map draws it; one of the side's outside your package offers **Orbit box as line**."),
                    i("**Tap an empty spot** (or right-click anywhere) to add a steerpoint, a PPT or a point to a line there."),
                    i("A DTC item's card offers **Move** (then tap where it goes), **Change…** and **Take out**."),
                    i("**Auto PPT** fills PPT 56-70 with the threats nearest your route; **Clear PPT** empties them; **Change Area…** and **Clear Lines** are WDP's line tools. Each asks before it replaces anything."),
                    i("**Measure** gives the track and distance between two points; **Save Map** saves the view as a JPEG on the BMS PC; **Fit** goes back to your flight."),
                    i("**HSD preview** shows it on black, north up, centred on a steerpoint you choose, at the HSD's ranges."),
                    i("Every change is the DTC page's own: it counts in **Save to DTC**, and nothing reaches BMS until you save."),
                ),
                go(GuideGo.Page(WdpPage.MAP.name), "Go to the Map page"),
            ),
        ),

        GuideSection(
            id = "dtc", group = PAGES, title = "The DTC page", short = "DTC", aliases = listOf("dtcpage"),
            blocks = listOf(
                p(
                    "**What it is for:** your cartridge, tab by tab, the file BMS loads into the jet: everything the " +
                        "attack pages and the card do not set. Nothing is written until you press **Save to DTC**.",
                ),
                table(
                    listOf("Tab", "What it edits"),
                    row(
                        "MAIN",
                        "The cartridge's file and callsign, whether it is loaded, and what the last save did. **Open Callsign.ini " +
                            "File** opens any cartridge on the PC (one outside User\\Config is saved where it is); **Save Callsign.ini " +
                            "File** writes the page into the file you pick, directly, and that file is the page's from then on.",
                    ),
                    row(
                        "STPT, Open 1, Open 2",
                        "Steerpoints 1-24, 81-89 and 90-99: position, elevation, action. **Change** opens the Change STPT " +
                            "window; **Rebuild STPT List** lays your flight's plan into the steerpoints, as WDP does, and " +
                            "asks before it clears a point that is not in the plan (a Recon target, your own).",
                    ),
                    row("Targets", "The 100 weapon targets, for Spice bombs (not the target steerpoints). Double-tap a row to pick a target on the map."),
                    row("Lines", "Four lines of six points. **Change Area** lays a line along a tanker's or AWACS's planned track."),
                    row("PPT", "15 pre-planned threats (steerpoints 56-70 in the jet), from the theater's own threat table, with ring sizes from the app's threat reference."),
                    row("IFF", "Modes and codes."),
                    row("EWS", "Countermeasure programs 1-6, bingo and CMDS. **Load** with no file picked offers **BMS default**, BMS's own defaults."),
                    row("MFD", "The MFD pages for each master mode."),
                    row(
                        "RADIO/NAV",
                        "20 UHF and 20 VHF presets with comments, TACAN and ILS. **Default** fills them from the " +
                            "briefing's comm plan; **Select APT** picks a field's frequencies, and **Charts** shows its " +
                            "airport diagram, its parking chart for each runway end and its instrument charts. Preset " +
                            "numbers are in brackets (“Preset [3]”), so they are never read as part of a frequency.",
                    ),
                    row("NAV OFFSETS", "VIP and VRP with their pull-up points and offset aimpoints; the attack pages fill the mode they plan."),
                    row("SYSTEMS", "HUD, views, Master Arm, radar altimeter, lighting, ALOW and bingo, laser codes."),
                    row("WEAPONS", "AIM-9, AIM-120, Maverick and the bomb profiles."),
                    row("HARM", "The three HARM tables, from the app's list of 40 codes."),
                ),
                sub("Worth knowing"),
                bullets(
                    i("**Clear** asks before it wipes steerpoints that have a position."),
                    i(
                        "**Load / Backup** on each tab are WDP's kinds of file, kept on the BMS PC in the Planner's folder inside " +
                            "Falcon BMS, laid out as WDP's (`User\\BMS Companion Planner\\Files\\EWS\\*.ews`, `…\\Files\\PPT\\*.pth` …); " +
                            "WDP opens them too. Pick no file and Load offers BMS's own defaults (EWS, MFD, HARM) and what an " +
                            "earlier version kept on this device. The PPT tab's **Browse** and **Personal** files are WDP's as well.",
                    ),
                    i(
                        "Falcon BMS reads none of those files: they are your own presets and copies, loaded into the Planner. What " +
                            "reaches the jet is the cartridge in User\\Config (**Save to DTC**, and **Save DTC** on the tabs that have it) " +
                            "and the cockpit's kneeboard pages (**Upd Kneeboard**).",
                    ),
                    i("The laser code must equal the bomb's code set on BMS's loadout screen (SET CODE)."),
                    i(
                        "**From mission…** (on STPT, Targets, Lines, PPT, Open 1, Open 2, RADIO/NAV, SYSTEMS and HARM) puts what the " +
                            "app knows of the mission into that tab: tanker and AWACS tracks as lines, known air defences as PPTs, " +
                            "the flight plan, targets, the comm plan, a TACAN or an ILS, the laser codes. It asks before it replaces " +
                            "anything you placed, and it counts in **Save to DTC** like any other edit.",
                    ),
                ),
                go(GuideGo.Page(WdpPage.DTC.name), "Go to the DTC page"),
            ),
        ),

        GuideSection(
            id = "performance", group = PAGES, title = "The Performance page", short = "Performance", aliases = listOf("perf"),
            blocks = listOf(
                p(
                    "**What it is for:** Falcas's F-16 performance page: take-off (factor, rotation, lift-off, refusal), " +
                        "MAX AB and MIL climbs (schedule, distance, fuel, time), cruise (optimum Mach and altitude, " +
                        "ceilings) and a turn calculator. Use it before take-off, to know your speeds and whether the " +
                        "runway is long enough; its take-off figures also fill the DataCard.",
                ),
                sub("Where it comes from"),
                bullets(
                    i("**Airport and runway**: your departure field and the runway end into the take-off wind, the one the DataCard's ATIS names. **Select APT** picks another; **Charts** shows the field's charts."),
                    i(
                        "**Temperature, wind and QNH**: the DataCard's take-off weather, from the save's own weather file (its " +
                            "**.twx**) or BMS's printed briefing of the same flight. It is the weather as saved, at the save's " +
                            "clock, and BMS may change it before take-off. When BMS picks the wind's direction itself the file " +
                            "has none, and the take-off is planned without a head or tail wind. BMS 4.38.1's briefing prints " +
                            "no QNH, so the QNH is the save's file's; with neither it is 29.92 (1013), or what you type. " +
                            "What a new mission's weather does not give goes back to the page's own values.",
                    ),
                    i(
                        "**Aircraft and loadout**: the briefing's aircraft and stores, with the pylons and racks BMS hangs " +
                            "them from. **Type** plans another F-16, with your stores carried across (pick the mission's " +
                            "own jet to get its loadout back); a flight of another aircraft shows its own weights and " +
                            "stores and no F-16 figures. **Set** opens the Loadout window: a card under each hardpoint " +
                            "with − / + / ✕, and every store the jet can carry in a list grouped by kind, with a search box. " +
                            "Pick a store, then a station (the stations that take it light up), or a station, then a store; " +
                            "**Mirror** hangs the same on the other side. The totals stay in sight with **Cancel** and " +
                            "**Apply**, and Apply also fills the DataCard's Config rows. On a phone or tablet, **Choose " +
                            "stores** opens the list full size.",
                    ),
                ),
                sub("How it is worked out"),
                bullets(
                    i("Gross weight = empty weight + take-off fuel + stores, red above the maximum. Drag = 1 + each store's drag index."),
                    i("Take-off, climb and cruise come from WDP's charts for your F-16's engine, at the field's pressure altitude and temperature."),
                    i("Refusal speed is red when it is not above rotation speed: the runway is too short to stop from rotation."),
                    i("Turn radius = (TAS × 1.69)² ÷ (G × 32.2) ft."),
                ),
                sub("Why WDP 3.7.24 shows other figures on the same save"),
                p(
                    "The arithmetic is WDP's own: given the same inputs, every take-off figure is identical in both programs. " +
                        "The figures differ because the inputs differ, and on Falcon BMS 4.38.1 WDP's are wrong:",
                ),
                bullets(
                    i("**The field.** WDP's airport table is not 4.38.1's: it cannot find a save's departure field and plans from its first row (Afyon, 3,310 ft). Here it is your field, at the height BMS gives it."),
                    i("**Pylons and racks.** WDP looks for them where 4.38.1 no longer keeps them, so its weight and drag leave them out."),
                    i("**Your jet and fuel.** Here: the theater's own store figures, conformal tanks when the jet has them, and the fuel your jet really has (\"----\" once it is airborne)."),
                    i(
                        "**The weather.** WDP cannot read BMS 4.38's weather file: its ATIS is blank and it plans with its own " +
                            "settings-file temperature, no wind and 29.92. Here it is the mission's weather as saved (or as " +
                            "BMS printed it), and BMS may change it before take-off.",
                    ),
                    i("**WDP's own slips.** Its climb and fuel charts ignore the ISA deviation it prints, one engine's MAX AB schedule never applies between drag 101 and 200, and its pressure altitude and turn-calculator temperature are worked out wrongly. Each is fixed here and listed in the port's record."),
                ),
                go(GuideGo.Page(WdpPage.PERFORMANCE.name), "Go to the Performance page"),
            ),
        ),

        GuideSection(
            id = "attack", group = PAGES, title = "What the three attack pages share", short = "The attack pages",
            aliases = listOf("attackpages", "tgtstpt", "attacks"),
            blocks = listOf(
                p(
                    "Pop-up, HADB and TOSS give the figures you type into the jet's DED (a **VRP** or **VIP**, the " +
                        "**pull-up point** and two **offset aimpoints**) and a map of the attack. Their arithmetic is " +
                        "Falcas's WDP, checked against his program. They share one **Attack** tab: the rail beside the page " +
                        "picks Pop-up, HADB or TOSS, each keeps its own settings, and the tab opens on the one you used last.",
                ),
                bullets(
                    i(
                        "**TGT STPT** picks the target: that steerpoint is the target. A new mission or flight sets it " +
                            "once to your first strike steerpoint; after that each page keeps its own. Steerpoints 1 to 25.",
                    ),
                    i(
                        "**IP STPT** beside it is the IP the VIP lines are measured from (VIP only; hidden for VRP and on " +
                            "HADB, and shown while VIP is blocked so you can pick one): the steerpoint before the " +
                            "target, following it, until you pick another (any steerpoint with a position; the arrows " +
                            "skip the empty ones). A new mission or flight follows the target again.",
                    ),
                    i("**Reference VRP or VIP**: VRP lays the points from the target and needs no IP, VIP from the IP. HADB plans from a VRP only."),
                    i(
                        "**IP STPT at the VRP** (Pop-up and TOSS, in VRP mode) puts a steerpoint on the VRP and plans the " +
                            "attack as VIP from it. **Finish the Input Panel and check the attack on the map (Profile) " +
                            "first**: the steerpoint stays where it is placed, so changing the attack angle, distances or " +
                            "any other input afterwards moves the attack but not the steerpoint. Plan in VRP freely, then " +
                            "press it. One question says where the VRP is and what the slot holds now — your cartridge's " +
                            "point, or your route's (moving a route point puts your route through the VRP); **Create IP STPT** " +
                            "places it. The steerpoint is the DTC page's own edit: Save to DTC writes it, then LOAD the DTC " +
                            "in BMS after the mission loads.",
                    ),
                    i(
                        "The page then says **IP STPT n created at the VRP**; if you change the Input Panel afterwards it " +
                            "warns that the steerpoint is **no longer at the VRP** — press **IP STPT at the VRP** again to " +
                            "move it (the button stays in VIP mode for that). To delete it: the DTC page, **STPT** tab, " +
                            "**Change** on its row, then **Clear** and **Apply**.",
                    ),
                    i("**The mode knob** (Selections, Profile, DED Data): tap to step round, or tap a caption to go straight there."),
                    i(
                        "**ELEV**: 0 means ground level wherever the point is; any other height is above sea level (the " +
                            "target's elevation added). Tap an ELEV to type your own.",
                    ),
                    i(
                        "WDP's Campaign and TE buttons are gone: the Planner reads one set of steerpoints.",
                    ),
                    i("**The map**: the attack drawn over the app's theater map around the target — the target a red square, the VIP a blue square or the VRP a blue circle, the pull-up point magenta, OA1 and OA2 green triangles; **Zoom**, **Show PPT**; **Save Map** saves it as a JPEG in WDP's SavedMaps folder on the BMS PC."),
                    i(
                        "**Save to DTC** writes the page's four offset lines (VIP or VRP, pull-up, OA1, OA2) into the nav " +
                            "offsets; the other mode's four are cleared (VIP and VRP cannot be used together). Once the " +
                            "cartridge is saved it also fills the DataCard's Delivery section with this attack (profile and " +
                            "every field), so the card and the cartridge agree; a save that fails leaves the card as it was. " +
                            "The card then follows the page, and its own **Save DTC** writes the same offsets.",
                    ),
                    i(
                        "**One attack**: the attack page you last changed or saved (Save to DTC) is the " +
                            "Planner's attack. Every map draws it the same way — the Map page, the Upd Kneeboard attack page " +
                            "and the next Populate — and only it. The DataCard keeps its own and says so when the latest is another.",
                    ),
                    i("**Populate from Planner** puts the attack on the Mission map with your flight. It never goes there by itself."),
                ),
                table(
                    listOf("Page (tap for its card)", "Use it for"),
                    row("Pop-up", "Run in low, pull up, roll in and dive: dive deliveries against a point target.", "popup"),
                    row("HADB", "A dive from height after a turn at the VRP.", "hadb"),
                    row("TOSS", "Run in low, pull up and release in the climb: loft deliveries.", "toss"),
                ),
            ),
        ),

        GuideSection(
            id = "popup", group = PAGES, title = "Pop-up", short = "Pop-up", aliases = listOf("popupattack"),
            blocks = listOf(
                p(
                    "**What it is for:** run in low, pull up at the pull-up point (PUP), roll in and dive at the target: " +
                        "dive deliveries (CCIP or DTOS) against a point target.",
                ),
                p(
                    "**You set:** ingress altitude (100-500 ft), dive angle (10-45°), speed (300-550 KCAS), release " +
                        "height (2,000-8,000 ft), tracking time (1-5 s), G (2-6), turn left or right, attack heading, " +
                        "VRP-to-PUP distance (1-5 nm), profile type 1 or 2, a low- or high-drag bomb, and VRP or VIP.",
                ),
                sub("How it is worked out"),
                bullets(
                    i("The bomb's range and time of fall at the release speed and height."),
                    i("Distance on the map from release to the target = bomb range + ground speed × tracking time."),
                    i("The climb is the dive angle + 5° (dives up to 15°) or + 10°; the pull heading is the attack heading turned by twice the climb."),
                    i("The PUP and the VRP are laid on the turn at your speed and G."),
                    i("**Target visible in the HUD** says whether the target is inside the HUD as you roll in."),
                ),
                go(GuideGo.Page(WdpPage.POPUP.name), "Go to Pop-up"),
            ),
        ),

        GuideSection(
            id = "hadb", group = PAGES, title = "HADB (High Altitude Dive Bomb)", short = "HADB", aliases = listOf("highaltitudedivebomb"),
            blocks = listOf(
                p("**What it is for:** a dive from height after a turn at the VRP: medium- and high-altitude dive deliveries (CCIP or CCRP). HADB plans from a VRP only."),
                p(
                    "**You set:** ingress speed (300-550 KCAS), G (2-6), turn, VRP-to-PUP (1-5 nm), dive angle " +
                        "(10-45°), release speed (300-550 KCAS), release height (3,000-15,000 ft), tracking time " +
                        "(1-10 s), angle off (0-90°), attack heading and the bomb's drag. **Extra Info** shows TAS, the " +
                        "map distance, the bomb range, the aim-off, the turn radius and the slant range.",
                ),
                sub("How it is worked out"),
                bullets(
                    i("Map distance = bomb range + the ground covered while tracking; the tracking point's altitude = release height + the height lost while tracking."),
                    i("The PUP is the map distance plus one turn radius out from the target; the VRP is laid from it with the angle off and the VRP-to-PUP distance."),
                    i("OA1 is at the release point (the bomb range back along the attack), OA2 at the aim-off point on the ground."),
                ),
                go(GuideGo.Page(WdpPage.HADB.name), "Go to HADB"),
            ),
        ),

        GuideSection(
            id = "toss", group = PAGES, title = "TOSS (low-altitude toss)", short = "TOSS", aliases = listOf("loft", "lat"),
            blocks = listOf(
                p("**What it is for:** run in low, pull up at the PUP and release in the climb: loft deliveries (LAT, CCRP)."),
                p(
                    "**You set:** ingress speed and height, G, turn, OA2-to-PUP (1-5 nm), release angle (0-45°), release " +
                        "speed and height, angle off, attack heading, and VRP or VIP.",
                ),
                sub("How it is worked out"),
                bullets(
                    i("The pull-up to the release angle at your speed and G sets how high you can release (the release-height slider's range)."),
                    i("The bomb's range for the loft; the PUP is that range plus the pull-up's ground distance, on the reciprocal of the attack heading."),
                    i("OA2 lies the OA2-to-PUP distance beyond the PUP; OA1 and the VRP are laid from the turn radius and the angle off."),
                    i("In VIP mode every point is measured from the IP."),
                    i("The HUD check says whether the target stays in the HUD at release."),
                ),
                go(GuideGo.Page(WdpPage.TOSS.name), "Go to TOSS"),
            ),
        ),

        GuideSection(
            id = "ato", group = PAGES, title = "The ATO Targets page", short = "ATO Targets",
            aliases = listOf("atotargets", "atotargetlist", "targetlist"),
            blocks = listOf(
                p(
                    "**What it is for:** WDP's **ATO Target List**, as the Planner's last tab: the targets your side's " +
                        "flights are tasked to attack in the open save, so you see at a glance what is being hit, by whom " +
                        "and when. For information only: nothing on it changes your plan.",
                ),
                bullets(
                    i(
                        "**Which save:** the one the Planner plans from (**Open mission…**), else the one BMS printed its " +
                            "briefing from. It is read again each time you show the page, as BMS writes a save over the same name.",
                    ),
                    i(
                        "Two lists, **Units** (battalions, brigades, ships) and **Objectives** (airbases, bridges, factories), " +
                            "each with WDP's columns: Nr, TARGET, TOT, Lat, Long, Package, Flight, Aircraft, Type, Squadron, " +
                            "Airbase and TakeOff. Press a heading to sort by it, again to reverse.",
                    ),
                    i("A flight already airborne by the save's clock is shaded steel blue; a TOT that has passed is red."),
                    i("On a phone one list shows at a time; a wide list scrolls sideways with Nr and TARGET kept in place."),
                    i(
                        "WDP's flight selection window (the DataCard's, when the save is not known) has an **ATO Target List** " +
                            "button: it turns to this page. **Pick a flight**'s own **ATO Target List** lists the packages " +
                            "and their targets beside its tree instead.",
                    ),
                ),
                go(GuideGo.Page(WdpPage.ATO.name), "Go to ATO Targets"),
            ),
        ),

        GuideSection(
            id = "savedtc", group = PAGES, title = "What Save to DTC writes", short = "What Save to DTC writes",
            aliases = listOf("savedetails", "backup", "restore", "navoffsets"),
            blocks = listOf(
                steps(
                    i(
                        "The Planner compares your plan with the cartridge as it was loaded. The settings that differ are " +
                            "your edits, and only those are sent to the PC.",
                    ),
                    i(
                        "The PC writes them one at a time onto the file as it is on disk now, so BMS's own changes to " +
                            "other settings survive, and settings the Planner does not know stay exactly as they were.",
                    ),
                    i(
                        "It writes straight away, as WDP's Save DTC does: nothing to switch on and no copy kept.",
                    ),
                    i("The file is written beside the cartridge first and then moved into place, so BMS never sees half a file."),
                ),
                p(
                    "**Nav offsets:** an attack page writes the four lines of the mode it planned and the mode itself " +
                        "(`vrp` or `vip`), with WDP's profile name; the other mode's four keep what the cartridge holds.",
                ),
                p(
                    "**In a TE** opened with **Open mission…**, the target steerpoints, lines, threats and weapon targets " +
                        "also go into the TE's own `.ini`, in place and with no copy, as WDP does (your own TE, or one " +
                        "that ships with Falcon BMS). A renamed copy and a TE whose name a later campaign save shares " +
                        "are not written; a TE with no `.ini` gets none.",
                ),
                p(
                    "**The next mission:** Falcon BMS keeps lines, PPTs, targets and nav offsets in the cartridge until " +
                        "something overwrites them. The PC notes which flight every save was for, and when a new mission " +
                        "begins (a PRINT of another flight in EZBoards mode, another flight opened or populated in WDP mode) " +
                        "it **clears by itself**, silently, everything the Planner saved for any other flight, wherever " +
                        "the cartridge still holds it, and the last attack's nav offsets; anything BMS or you changed " +
                        "since stays (a switch of mode clears " +
                        "all of the Planner's, this flight's too). LOAD the DTC in BMS afterwards. The attack pages and the " +
                        "card's delivery start afresh too (TGT STPT on the new mission's first strike steerpoint).",
                ),
                go(GuideGo.Page(WdpPage.DTC.name), "Go to the DTC page"),
            ),
        ),

        // ------------------------------------------------------------------------------------------------ more

        GuideSection(
            id = "remote", group = MORE, title = "Planning on a phone, tablet or in a browser", short = "Phone, tablet, browser",
            aliases = listOf("phone", "tablet", "notlinked", "link", "linked", "offline", "remote"),
            blocks = listOf(
                p("The Planner works on every device, **through the PC**."),
                bullets(
                    i(
                        "**BMS Companion must be running on the PC, and your device must be linked to it.** The Android app " +
                            "finds the PC by itself; in a browser, open `http://<PC address>:47474/`.",
                    ),
                    i(
                        "**When the device is not linked, the Planner shows a note saying so instead of its pages**, with " +
                            "Setup and this guide. Your work is kept, and comes back with the link.",
                    ),
                    i(
                        "**Open mission…** lists the mission files **on the PC**, grouped by theater, newest first, exactly " +
                            "as on the PC. The files never leave the PC.",
                    ),
                    i(
                        "**Save to DTC**, **Populate from Planner** and **Upd Kneeboard** work **on the PC**, " +
                            "whichever device you press them on.",
                    ),
                    i("Steps 1, 2, 6 and 8 happen in Falcon BMS itself, on the PC."),
                    i("This guide can be read without the link."),
                ),
                sub("By finger"),
                bullets(
                    i(
                        "**Tap a box** to type in it: one bar opens on top of the keyboard with the box's name, its value, " +
                            "− and + for a number, **Next** (the next box down or along) and **Done**. The page stays its size.",
                    ),
                    i("**Tap a list or a slider** for a big picker; a grid's rows open at a finger's height."),
                    i("**Double-tap the page** to zoom in where you tapped, and again (or **Fit**) to see it whole."),
                    i("**Turn a phone sideways** for a bigger page."),
                    i("**Long-press a knob** to turn it back."),
                ),
            ),
        ),

        GuideSection(
            id = "multiplayer", group = MORE, title = "Multiplayer", short = "Multiplayer", aliases = listOf("mp", "host"),
            blocks = listOf(
                bullets(
                    i("**Everyone plans for themselves.** Each pilot's cartridge lives on their own PC; a change you make reaches nobody else."),
                    i(
                        "**The host runs the mission, but the Planner needs the mission file on your PC:** for a TE, a copy " +
                            "of the `.tac` in the theater's Campaign folder (with its `.ini`, if the mission maker shared " +
                            "one); for a campaign, a save made on your PC.",
                    ),
                    i("Once the host has stopped the clock (HALT), you can plan at leisure."),
                ),
            ),
        ),

        GuideSection(
            id = "missing", group = MORE, title = "If something is missing", short = "If something is missing",
            aliases = listOf("help", "trouble", "troubleshooting", "problems", "undo"),
            blocks = listOf(
                table(
                    listOf("What you see (tap for the step)", "Why, and what to do"),
                    row(
                        "The Planner shows a “not linked” note",
                        "The device cannot reach the PC program. Start BMS Companion on the PC, then check Setup on this device.",
                        "remote",
                    ),
                    row(
                        "My save is not in the list",
                        "Did you press SAVE in BMS (step 2)? Look under your theater's name (only theaters installed on the " +
                            "PC are listed), and press Refresh. BMS's own missions are folded away under **Show BMS's own missions**.",
                        "step2",
                    ),
                    row(
                        "A save is greyed out",
                        "It is a campaign start, which holds no mission for you. Start the campaign in BMS, SAVE, then open your save.",
                        "step3",
                    ),
                    row(
                        "My flight is not in the package",
                        "The save was made before your flight existed or before you changed it. Save again in BMS, then " +
                            "**Open mission…** again.",
                    ),
                    row(
                        "The card's weather is not the weather I chose",
                        "BMS had not saved it when the Planner read the mission. In BMS pick it (Weather → Map Model) and " +
                            "save it (**SAVE WTH** in a TE, the campaign save in a campaign), then **Open mission…** the same " +
                            "flight again. If the card says the weather file belongs to another save, save this mission's " +
                            "weather again in BMS. If it names an update map (\"map 10100.fmap\"), **MAPS AUTO UPDATE** is on: " +
                            "BMS's own hourly map is in force at the save's clock. Turn it off for a single map, or start your " +
                            "series at the mission's clock (**Mission clock** on the Weather tab), and save again.",
                        "weather",
                    ),
                    row(
                        "The Planner shows old IFF codes or frequencies",
                        "BMS had not saved the cartridge since you took your seat. Press SAVE in BMS's DTC window, then " +
                            "**Re-read DTC from BMS** (in Save to DTC's menu).",
                    ),
                    row(
                        "Save to DTC will not write a TE",
                        "The answer says why: a copy renamed in Windows (open it in BMS, SAVE it under the name you want " +
                            "and open that), a TE whose name a later campaign save shares (“Auto Save”), or a TE with no " +
                            "`.ini` of its own, which BMS flies on your cartridge alone.",
                        "step5",
                    ),
                    row(
                        "My threats or lines are not in the jet",
                        "LOAD was not pressed in BMS's DTC window before TAKEOFF (step 6), or another flight or seat was " +
                            "picked after it. On a ramp start, check that you pressed LOAD on the DTE page.",
                        "step6",
                    ),
                    row(
                        "Target steerpoints I made with Recon are gone",
                        "**Clear** wipes them (it asks first). Answering **No** to the “Precision STPT” question (here, " +
                            "as in WDP) plans the flight without them: open the flight again and answer **Yes**. If they " +
                            "are gone from the DTC too, make them again with Recon and SAVE in BMS.",
                    ),
                    row("My laser-guided bombs do not guide", "The laser code on the DTC page must equal the bomb's code set on BMS's loadout screen (SET CODE)."),
                    row("The radios are not on the planned frequencies", "The cartridge was loaded after you set the radios. Load it first, as the checklist says.", "step9"),
                    row(
                        "The kneeboard shows the old card",
                        "It was written after you entered the 3D world (make kneeboards before TAKEOFF), or another tool " +
                            "wrote the same page after you. The Upd Kneeboard window shows who wrote each page last. Check that the " +
                            "pilot model is on (Alt+C, then P).",
                        "step7",
                    ),
                    row(
                        "The EZBoards pages show an old mission",
                        "In WDP mode EZBoards is paused: your cockpit kneeboards are **Upd Kneeboard**'s. In EZBoards " +
                            "mode they were made before the cartridge last changed: press **Generate now** on the Kneeboards page.",
                        "step7",
                    ),
                    row(
                        "The Planner is greyed out",
                        "The Mission section is in EZBoards mode. Switch to **WDP** at the top of the Mission section, or in Setup.",
                        "start",
                    ),
                    row(
                        "The app's Map and Briefing do not show my plan",
                        "Press **Populate from Planner**: the Mission section is filled only when you press it, and " +
                            "again after you change the plan.",
                        "step5",
                    ),
                    row(
                        "I want to undo the Planner's last save",
                        "Save to DTC writes the cartridge directly, as WDP does, and keeps no copy. Put the settings back " +
                            "on the DTC page and save again, or set them in BMS's DTC window and SAVE there.",
                    ),
                ),
            ),
        ),

        GuideSection(
            id = "words", group = MORE, title = "Words used here", short = "Words used here", aliases = listOf("glossary", "terms"),
            blocks = listOf(
                table(
                    listOf("Word", "Meaning"),
                    row("Cartridge (DTC)", "The data transfer cartridge, `User\\Config\\<callsign>.ini`: everything the jet loads before flight."),
                    row("DTE page", "The MFD page that loads the cartridge into the jet."),
                    row("PPT", "Pre-planned threat: a threat ring you place before flight (steerpoints 56-70 in the jet)."),
                    row("Package, flight", "The group of flights on one mission, and your own two- or four-ship in it."),
                    row("TE", "Tactical Engagement: a single mission (`.tac`), as opposed to a campaign (`.cam`)."),
                    row("Stock start", "A campaign starting file that ships with BMS: a template, not your mission."),
                    row("TGT STPT, IP STPT", "The attack pages' target steerpoint, and the IP the VIP is laid from (the steerpoint before the target unless you pick another; VIP only)."),
                    row("VRP, VIP", "Visual reference point (offsets laid from the target) and visual initial point (laid from the IP)."),
                    row("PUP, OA1, OA2", "The pull-up point, and the two offset aimpoints."),
                ),
            ),
        ),

        GuideSection(
            id = "credit", group = MORE, title = "About Weapon Delivery Planner", short = "About WDP",
            aliases = listOf("about", "falcas", "wdp", "wdpbyfalcas"),
            blocks = listOf(
                p(
                    "**Weapon Delivery Planner** is Falcas's program, used by Falcon pilots for many years. The Planner is " +
                        "a port of it for Falcon BMS 4.38.1: the pages, the attack, ballistics and performance planning " +
                        "and the latitude and longitude are his work, checked against his program's own answers (the " +
                        "latitude and longitude against BMS's own ACMI recordings too: they agree to a metre; the " +
                        "Falklands, which no recording has checked yet, print the theater's own projection). The airports, runways, radios, " +
                        "charts, theaters, aircraft and weapons are Falcon BMS's own data, read by BMS Companion.",
                ),
                p("Where the port differs from WDP, it fixes one of WDP's bugs; each fix is numbered and documented with its evidence."),
                p(
                    "Not in this Planner: WDP's Threats and Munition pages (the app's Threat Guide and Arsenal do that " +
                        "job), its own map window (the Planner's Map page is BMS Companion's), paper printing and the " +
                        "F-15C's kneeboard pages.",
                ),
            ),
        ),
    )
}

private const val START = "Start"
private const val STEPS = "Step by step"
private const val PAGES = "The pages"
private const val MORE = "When you need it"

/** One page of the guide. [id] and [aliases] are what [PlannerGuide.indexOf] matches (a page's name is its card's id). */
internal class GuideSection(
    val id: String,
    val group: String,
    val title: String,
    /** the line in the contents */
    val short: String,
    val aliases: List<String> = emptyList(),
    val blocks: List<GuideBlock>,
)

/** A numbered step or a bullet: its text, why it matters (set in under it), and points under it. */
internal class GuideItem(val text: String, val why: String? = null, val sub: List<String> = emptyList())

/** A row of a two-column table; [link] makes its first cell open that section. */
internal class GuideRow(val term: String, val text: String, val link: String? = null)

internal sealed class GuideBlock {
    class Para(val text: String) : GuideBlock()
    /** a small heading inside a section */
    class Sub(val text: String) : GuideBlock()
    class Steps(val items: List<GuideItem>) : GuideBlock()
    class Bullets(val items: List<GuideItem>) : GuideBlock()
    /** "Why:" — the reason for a step, which is what a new pilot skips */
    class Why(val text: String) : GuideBlock()
    /** the one thing not to miss */
    class Note(val text: String) : GuideBlock()
    class Table(val head: List<String>, val rows: List<GuideRow>) : GuideBlock()
    /** a "go there" button */
    class Go(val to: GuideGo, val label: String) : GuideBlock()
}

/** Where a "go there" button goes. [key] names it for the checks (`planner/Guide/Go/<key>`). */
internal sealed class GuideGo(val key: String) {
    /** the Open mission… window */
    object OpenMission : GuideGo("OpenMission")
    /** Populate from Planner (the guide closes, and the press asks what it asks) */
    object Populate : GuideGo("Populate")
    /** the Upd Kneeboard window */
    object Print : GuideGo("Print")
    /** a Planner page ([WdpPage] name); the guide closes */
    class Page(val page: String) : GuideGo("Page.$page")
    /** another section of the guide */
    class Section(val id: String) : GuideGo("Section.$id")
}

private fun p(text: String) = GuideBlock.Para(text)
private fun sub(text: String) = GuideBlock.Sub(text)
private fun why(text: String) = GuideBlock.Why(text)
private fun note(text: String) = GuideBlock.Note(text)
private fun steps(vararg items: GuideItem) = GuideBlock.Steps(items.toList())
private fun bullets(vararg items: GuideItem) = GuideBlock.Bullets(items.toList())
private fun i(text: String, why: String? = null, sub: List<String> = emptyList()) = GuideItem(text, why, sub)
private fun table(head: List<String>, vararg rows: GuideRow) = GuideBlock.Table(head, rows.toList())
private fun row(term: String, text: String, link: String? = null) = GuideRow(term, text, link)
private fun go(to: GuideGo, label: String) = GuideBlock.Go(to, label)

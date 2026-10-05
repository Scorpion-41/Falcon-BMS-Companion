package com.bmscompanion.app.ui.screens.wdp

/**
 * The pilot's data cartridge, as every Planner page reaches it.
 *
 * In WDP the DTC page holds the cartridge and the other pages go through it: an attack page's **Save to DTC** hands
 * its offset aim points to the DTC page (`cntDTC.Profiles`) and the main form writes the file; the DataCard's
 * **Get DTC File** and **Save DTC** do the same from the other side. Here too the DTC page owns the cartridge — it
 * loads it, holds the edits and writes it the reversible way the app writes anything into Falcon BMS — and sets
 * the hooks below when it is created; the other pages call them. A hook that is not set yet means the DTC page has
 * not been opened on this device, and a caller says so rather than doing nothing.
 */
object WdpCartridge {
    /** One NAV OFFSETS entry as WDP's attack pages make it: steerpoint, true bearing, range in feet, elevation in feet. */
    data class NavOffset(val stpt: Int, val bearing: Float, val rangeFt: Int, val elevFt: Int)

    /**
     * An attack page's Save to DTC: [profile] is WDP's (`"PopUp"`, `"HADB"`, `"TOSS"`), [offsets] keyed as WDP keys
     * them (`VIP`, `VIPPUP`, `VRP`, `VRPPUP`, `OA1_1`, `OA2_1`, `OA1_2`, `OA2_2`), [navModeSel] the page's
     * reference. Returns what to tell the pilot — the message WDP shows, or why it could not be saved. [then] runs once
     * the save has written the cartridge (the page's DataCard fill, 1.3.8), never when it fails.
     */
    var saveNavOffsets: (suspend (profile: String, offsets: Map<String, NavOffset>, navModeSel: Int, then: (() -> Unit)?) -> String)? = null

    /**
     * The toolbar's Re-read DTC from BMS: re-read the cartridge from Falcon BMS. Returns what to tell the pilot. When
     * the DTC page holds edits not saved yet it asks first (save, read anyway, or cancel) instead of dropping them.
     */
    var reload: (suspend () -> String)? = null

    /**
     * The DataCard's **Get DTC File**: the DTC page's Open Callsign.ini File — a window in the game's `User\Config` on
     * the pilot's own file (WDP's button opened a TE's mission `.ini`) — and the file picked becomes the cartridge. Returns the cartridge's text as the DTC page
     * holds it then, or null when no file was picked or none could be read (the DTC page has said why).
     */
    var open: (suspend () -> String?)? = null

    /** The DataCard's Save DTC: write the cartridge as the DTC page holds it. Returns what to tell the pilot. */
    var save: (suspend () -> String)? = null

    /**
     * For the DataCard's "Callsign.ini saved" lamp (WDP's `CheckCallsignSaved`): how many settings the DTC page would
     * change on its next save, or null while it has no cartridge loaded (the lamp is off). Read in composition.
     */
    var pending: (() -> Int?)? = null

    /**
     * One of the card's two bomb profiles as it fills them (WDP's `strSubMode1`, `strFuse1`, `strSglPair1`,
     * `strArmDelay1`, `strBurstAlt1`, `strRelAngle1`, `strRipple1`, `strSpacing1`). The three lists take the DTC page's
     * own item text (submode "CCRP", fuze "NSTL", "SGL"/"PAIR"); the arm delay is in seconds. Null leaves a setting as
     * the cartridge has it.
     */
    data class BombProfile(
        val submode: String? = null,
        val fuze: String? = null,
        val sglPair: String? = null,
        val armDelaySec: Double? = null,
        val burstAltFt: Int? = null,
        val releaseAngleDeg: Int? = null,
        val pulses: Int? = null,
        val spacingFt: Int? = null,
    )

    /**
     * What the DataCard puts into the cartridge, as WDP's card does through the DTC page (`cntDataCard` writing
     * `CampICP`, `CampEWS.Program[n].Comment`, `CampLaser`, `CampAGB.Profile1/2` and `cntDTC.strProfile`): the ALOW
     * and the MSL floor (`[ICP] Alow AGL` / `Alow MSL`), bingo (`Bingo_Fuel`), the six EWS program names, the TGP and
     * LST laser codes (`[Laser] LaserTGP` / `LaserLST`), the two bomb profiles (`[FCC_AGB]`) and which attack page's
     * offset aim points the NAV OFFSETS carry ("None", "PopUp", "HADB", "TOSS"). Null leaves a setting alone.
     */
    data class CardEntries(
        val alowAglFt: Int? = null,
        val mslFloorFt: Int? = null,
        val bingoLbs: Int? = null,
        /** programs 1–6; a null name leaves that program's */
        val ewsProgramNames: List<String?>? = null,
        val laserTgp: Int? = null,
        val laserLst: Int? = null,
        val profile1: BombProfile? = null,
        val profile2: BombProfile? = null,
        val attackProfile: String? = null,
        /**
         * The card's own attack (1.3.8, B11): the four nav-offset lines of the mode in use as the card shows them
         * (`DataCardPlan.popUpNavOffsets` …, which an attack page's Save to DTC and the card's profile buttons fill), keyed as
         * [saveNavOffsets] keys them, and the mode (1 VIP, 2 VRP). With them the card's Save DTC writes what the card
         * shows, through the same path as the attack page's Save to DTC; without, the profile's table as the DTC page has it.
         */
        val attackOffsets: Map<String, NavOffset>? = null,
        val attackModesel: Int? = null,
    )

    /**
     * The card's profile buttons (1.3.8): an attack's nav offsets onto the DTC page **without saving**, by the same path as an
     * attack page's Save to DTC (`DtcWiring.stageNavOffsets`; the other mode's lines cleared). Returns null when they
     * are staged, else why not (no cartridge loaded yet).
     */
    var stageNavOffsets: ((profile: String, offsets: Map<String, NavOffset>, navModeSel: Int) -> String?)? = null

    /**
     * The DataCard's entries into the cartridge, through the DTC page: each goes into its box on that page and through
     * the box's own checks (a laser code is kept to 1111–2888), so the DTC page shows it and its Save writes it with
     * the pilot's other edits — only the keys that changed, written the way WDP writes them. [save] true saves at once
     * (asking first only when the save would zero placed points). Returns what to tell the pilot.
     */
    var writeCardEntries: (suspend (entries: CardEntries, save: Boolean) -> String)? = null

    /**
     * Set by the DataCard: its entries for the cartridge, handed over as the toolbar's Save to DTC hands them
     * (`DataCardWiring.entriesToHand`), or null when it has none. The DTC tabs' own Save DTC takes them too, so any Save
     * DTC writes what was typed on the card, as WDP's did (its card wrote straight into the DTC page's model).
     */
    var cardEntries: (() -> CardEntries?)? = null

    /**
     * Set by the DTC page when a save has just written the cartridge and EZBoards is set up on the PC: what the
     * Kneeboards page's GENERATE NOW does. EZBoards reads the cartridge too, and the app runs it by itself only on
     * PRINT, which comes before a Planner save — so the cockpit kneeboards are one save behind until it runs again.
     * Taken by [answer]; never run without the pilot pressing the button.
     */
    var generateBoards: (() -> Unit)? = null

    /** A save's answer in WDP's message box, offering to make the kneeboards again when the save wrote the file. */
    fun answer(title: String, text: String) {
        val generate = generateBoards
        generateBoards = null
        if (generate == null) { WdpDialogs.message(title, text); return }
        WdpDialogs.message(
            title,
            "$text\n\nEZBoards reads the cartridge too: cockpit kneeboards it made before this save (at PRINT, or from " +
                "GENERATE NOW) show the cartridge as it was then. Generate them again now? (The same as GENERATE NOW " +
                "on the Kneeboards page.)",
            listOf("Generate now", "Not now"),
        ) { answer ->
            if (answer != "Generate now") return@message
            generate()
            WdpDialogs.message(
                "Generate now",
                "EZBoards is making the kneeboards on the PC from the briefing and the cartridge as they are now. " +
                    "The Kneeboards page says when they are done.",
            )
        }
    }
}

package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.BmsDefaultsFile
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.Threat
import com.bmscompanion.app.data.wdp.DtcLoad
import com.bmscompanion.app.data.wdp.DtcPage
import com.bmscompanion.app.data.wdp.DtcSave

/**
 * The DTC page's **Load** and **Backup** buttons, and WDP's ready-made settings.
 *
 * WDP keeps a part of the cartridge — the EWS programs, the radio presets, the target list — as a small file in its
 * own folder (`Files\EWS\*.ews`, `Files\Radio\*.rad` …), and every one of those files is simply that part of the
 * cartridge in the cartridge's own format: an `[EWS]` section, a `[Radio]` section. So is each of its presets
 * (`Blue.rad`, `Ramp.sts`). The page's Load and Backup open WDP's own windows for them on the BMS PC ([backup]: the
 * folder, the file type and the window's title WDP's `Open_File` and `Save_File` give each part), write the part
 * into the file key by key as WDP's `SaveCallsign_*` do, and read it with the cartridge's own reader into the page.
 * Copies an earlier version kept in the app's settings (three slots per part) stay loadable. The SYSTEMS tab's three
 * presets are WDP's files, word for word. The PPT tables are not WDP's any more: the theater's own `Ppt.ini` and the
 * app's threat reference ([zoneTable]), and a part's "BMS default" is Falcon BMS's own `*_Def.ini` ([defaultsFile]).
 */
internal object DtcFiles {

    /** One part of the cartridge a copy can be kept of: which sections, and within `[STPT]` which keys. */
    enum class Part(val id: String, val label: String, val sections: List<String>, val keys: Regex? = null) {
        TARGET("target", "target list", listOf("STPT"), Regex("wpntarget_\\d+", RegexOption.IGNORE_CASE)),
        LINE("line", "lines", listOf("STPT"), Regex("lineSTPT_\\d+", RegexOption.IGNORE_CASE)),
        PPT("ppt", "PPTs", listOf("STPT"), Regex("ppt_\\d+", RegexOption.IGNORE_CASE)),
        OPEN("open", "Open 1 steerpoints", listOf("STPT"), Regex("target_8[0-8]", RegexOption.IGNORE_CASE)),
        HARPOON("harpoon", "Open 2 steerpoints", listOf("STPT"), Regex("target_(89|9[0-8])", RegexOption.IGNORE_CASE)),
        EWS("ews", "EWS programs", listOf("EWS")),
        MFD("mfd", "MFD pages", listOf("MFD", "Bullseye")),
        RADIO("rad", "radio presets", listOf("Radio", "COMMS")),
        // WDP's SaveSystems writes the HARM section too (and its LoadSystems reads it back); its own presets add the
        // sensor power and the lighting, which LoadSystems reads
        SYSTEMS("sys", "systems settings", listOf("Hud", "ICP", "Weapons", "Cockpit View", "OTW", "HARM", "Laser", "SNSR_PWR", "INT_LIGHTING")),
        WEAPONS("wpn", "weapons settings", listOf("FCC_AIM", "FCC_AGM", "FCC_AGB")),
        HARM("harm", "HARM tables", listOf("HARM")),
    }

    const val SLOTS = 3

    /**
     * A part's backup file as WDP's `Save_File` and `Open_File` handle it: its folder under WDP's `Files`, its type,
     * the `Filter`, the two windows' titles, and the section header WDP's `CheckXHeader` adds when a file lacks it.
     */
    class Backup(val kind: String, val folder: String, val ext: String, val filter: String, val saveTitle: String, val openTitle: String, val header: String?)

    fun backup(part: Part): Backup = when (part) {
        Part.PPT -> Backup("ppt", "PPT", "pth", "pth files (*.pth)|*.pth", "Save PPT backup files", "Open PPT backup files", "[STPT]")
        // WDP's Open window for lines starts in a folder named "True" or "False" (it assigns a comparison to its
        // InitialDirectory): it opens in Files\Line here, where its Save writes them
        Part.LINE -> Backup("line", "Line", "lns", "lns files (*.lns)|*.lns", "Save Line backup files", "Open Line backup files", "[STPT]")
        Part.TARGET -> Backup("target", "Target", "tgt", "tgt files (*.tgt)|*.tgt", "Save target backup files", "Open Target backup files", "[STPT]")
        Part.OPEN -> Backup("open", "Open", "opn", "opn files (*.opn)|*.opn", "Save Open stpt backup files", "Open Open STPT backup files", "[STPT]")
        Part.HARPOON -> Backup("harpoon", "Harpoon", "hpn", "hpn files (*.hpn)|*.hpn", "Save harpoon backup files", "Open Harpoon backup files", "[STPT]")
        Part.EWS -> Backup("ews", "EWS", "ews", "ews files (*.ews)|*.ews", "Save EWS backup files", "Open EWS backup files", "[EWS]")
        Part.MFD -> Backup("mfd", "MFD", "mfd", "mfd files (*.mfd)|*.mfd", "Save MFD backup files", "Open MFD backup files", "[MFD]")
        Part.RADIO -> Backup("rad", "Radio", "rad", "rad files (*.rad)|*.rad", "Save Radio backup files", "Open Radio backup files", "[Radio]")
        Part.SYSTEMS -> Backup("sys", "System", "sts", "sts files (*.sts)|*.sts", "Save System backup files", "Open System backup files", null)
        Part.WEAPONS -> Backup("wpn", "Weapons", "wpn", "wpn files (*.wpn)|*.wpn", "Save Weapon backup files", "Open Weapon backup files", null)
        Part.HARM -> Backup("harm", "Harm", "hrm", "Harm files (*.hrm)|*.hrm", "Save Harm backup files", "Open Harm backup files", null)
    }

    /**
     * [part] written onto a backup file's text [existing] ("" for a new file) as WDP's `SaveCallsign_*` write it: key
     * by key with `WritePrivateProfileString`, so a file saved over keeps whatever else it holds.
     */
    fun backupText(page: DtcPage, part: Part, existing: String): String = WdpFiles.iniWrite(existing, WdpFiles.entriesOf(extract(page, part)))

    private fun key(part: Part, slot: Int) = "wdp_dtc_${part.id}_$slot"

    /** What is kept in [slot] of [part]: its one-line description and its text, or null. */
    fun kept(part: Part, slot: Int): Pair<String, String>? {
        val text = runCatching { Repo.getString(key(part, slot)) }.getOrNull() ?: return null
        val about = runCatching { Repo.getString(key(part, slot) + "_about") }.getOrNull() ?: "a copy"
        return about to text
    }

    fun keep(part: Part, slot: Int, text: String, about: String) {
        Repo.putString(key(part, slot), text)
        Repo.putString(key(part, slot) + "_about", about)
    }

    /**
     * That part of the page, as the cartridge would hold it: the page's model written out by WDP's own save into an
     * empty text, and only [part]'s sections and keys kept.
     */
    fun extract(page: DtcPage, part: Part): String {
        val all = DtcSave.saveCallsign("", page.m) { page.applyHarm() }.text
        return only(all, part)
    }

    /** The lines of [text] that belong to [part], section headers included, in CR LF as BMS writes them. */
    fun only(text: String, part: Part): String {
        val out = StringBuilder()
        var inPart = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val s = line.trim()
            if (s.startsWith("[")) {
                val name = s.substring(1).substringBefore(']').trim()
                inPart = part.sections.any { it.equals(name, true) }
                if (inPart) out.append(line).append("\r\n")
                continue
            }
            if (!inPart || s.isEmpty()) continue
            val keyName = line.substringBefore('=').trim()
            if (part.keys == null || part.keys.matches(keyName)) out.append(line).append("\r\n")
        }
        return out.toString()
    }

    /** [text] (a part in the cartridge's format) read into the page with the cartridge's own reader. */
    fun load(page: DtcPage, part: Part, text: String, pptNames: List<Pair<String, String>>) {
        val m = page.m
        val c = page.coords
        when (part) {
            Part.TARGET -> DtcLoad.wpnTarget(text, m, c)
            Part.LINE -> DtcLoad.lines(text, m, c)
            Part.PPT -> DtcLoad.ppt(text, m, pptNames, c)
            Part.OPEN -> DtcLoad.open(text, m, c)
            Part.HARPOON -> DtcLoad.harpoon(text, m, c)
            Part.EWS -> { DtcLoad.ews(text, m); page.fillEWS() }
            Part.MFD -> { DtcLoad.mfd(text, m); DtcLoad.bullseye(text, m); page.fillMFD() }
            Part.RADIO -> { DtcLoad.radio(text, m); DtcLoad.comm(text, m); page.fillRadiodata() }
            Part.SYSTEMS -> {
                DtcLoad.hud(text, m); DtcLoad.icp(text, m); DtcLoad.weapons(text, m); DtcLoad.view(text, m)
                DtcLoad.otw(text, m); DtcLoad.laser(text, m); DtcLoad.snsr(text, m); DtcLoad.light(text, m)
                page.fillSystemdata()
                // WDP's LoadSystems reads the HARM tables too (GetCallsignHarm), when the file has them
                if (text.contains("[HARM]", ignoreCase = true)) { DtcLoad.harm(text, m); page.fillHarmData() }
            }
            Part.WEAPONS -> { DtcLoad.aim(text, m); DtcLoad.agm(text, m); DtcLoad.agb(text, m); page.fillWeapondata() }
            Part.HARM -> { DtcLoad.harm(text, m); page.fillHarmData() }
        }
        // the point tables print through the page's coordinate labels, all refilled at once
        if (part.sections == listOf("STPT")) page.getCallsignCoords()
        page.changed()
    }

    // ---------------------------------------------------------------- WDP's own settings, as it ships them

    /** `Files\System\Ramp.sts`, `Taxi.sts`, `TakeOff.sts`: the SYSTEMS tab's three buttons. */
    val SYSTEM_PRESETS: Map<String, String> = mapOf(
        "Ramp" to sts(ded = 1, symWheel = 500, masterArm = 0, ralt = 0, dedLight = 0),
        "Taxi" to sts(ded = 0, symWheel = 1000, masterArm = 0, ralt = 1, dedLight = 2),
        "TakeOff" to sts(ded = 0, symWheel = 1000, masterArm = 1, ralt = 2, dedLight = 2),
    )

    private fun sts(ded: Int, symWheel: Int, masterArm: Int, ralt: Int, dedLight: Int) = listOf(
        "[Hud]", "Color=-16711936", "Scales=1", "Brightness=1", "FPM=0", "DED=$ded", "Velocity=0", "Alt=1", "SymWheelPos=$symWheel",
        "[ICP]", "MasterMode=0", "Alow AGL=100.000000", "Alow MSL=10000", "Alow TFAdv=400", "Manual Wingspan=35.000000", "Bingo_Fuel=1500.000000",
        "[Weapons]", "MasterArm=$masterArm",
        "[Cockpit View]", "WideView=0",
        "[OTW]", "Mode=3",
        "[Laser]", "LaserST=8",
        "[SNSR_PWR]", "RALT=$ralt",
        "[INT_LIGHTING]", "DED=$dedLight",
    ).joinToString("\r\n", postfix = "\r\n")

    // ---------------------------------------------------------------- the PPT tables, from the app's data

    /**
     * WDP's Radar Zone / Engage Zone tables (its `RadarZone.ini` / `EngageZone.ini`, hand-made lists of WDP's own),
     * made from Falcon BMS's and the app's data instead: [theater] is the theater's own PPT types (code, name, range
     * in feet — BMS's `Campaign/Ppt.ini`), and each type's range becomes the threat reference's radar lock range
     * ([radar]) or longest engagement range where the reference lists that system; a type it does not list (AAA,
     * ManPads, a ship's missile) keeps the theater's own range. The codes stay BMS's, so a PPT made from either table
     * is one BMS knows.
     */
    fun zoneTable(theater: List<Triple<String, String, Double>>, threats: List<Threat>, radar: Boolean): List<Triple<String, String, Double>> =
        theater.map { (code, name, range) ->
            val nm = threatFor(name, threats, radar)
            Triple(code, name, if (nm != null) nm * NM_TO_FT else range)
        }

    private const val NM_TO_FT = 6076.1157

    /** A range in nm, from the first threat whose name or alias is [pptName]'s system ("SA-10" is "SA-10B Grumble"). */
    private fun threatFor(pptName: String, threats: List<Threat>, radar: Boolean): Double? {
        val want = norm(pptName.substringBefore('(').trim())
        if (want.isEmpty()) return null
        for (t in threats) {
            val keys = listOf(t.name.substringBefore(' '), t.name) + t.aliases
            val hit = keys.any { k ->
                val n = norm(k)
                // "sa10" names "sa10b" (the variant letter), never "sa2" "sa20a"
                n == want || (n.length == want.length + 1 && n.startsWith(want) && n.last().isLetter())
            }
            if (!hit) continue
            val v = t.numbers[if (radar) "radarLockRangeNm" else "maxRangeNm"]
            if (v != null && v > 0.0) return v
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- BMS's own defaults

    /** The `User/Config` file Falcon BMS keeps [part]'s defaults in, or null where it keeps none. */
    fun defaultsFile(part: Part): String? = when (part) {
        Part.EWS -> "EWS_Def.ini"
        Part.MFD -> "MFD_Def.ini"
        Part.HARM -> "HARM_Def.ini"
        else -> null
    }

    /** A `*_Def.ini` as the PC sends it, back in the cartridge's own format for the cartridge's readers. */
    fun iniOf(f: BmsDefaultsFile): String {
        val out = StringBuilder()
        for (sec in f.sections.ifEmpty { f.values.map { it.section }.distinct() }) {
            out.append('[').append(sec).append("]\r\n")
            for (v in f.values) if (v.section.equals(sec, ignoreCase = true)) out.append(v.key).append('=').append(v.value).append("\r\n")
        }
        return out.toString()
    }

    /** A `ppt.ini` (one type a line: code, range in feet, name) as the page's PPT table. */
    fun pptTable(text: String?): List<Triple<String, String, Double>> =
        text.orEmpty().split('\n').mapNotNull { raw ->
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 3) return@mapNotNull null
            val range = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            Triple(parts[0], parts.drop(2).joinToString(" "), range)
        }

    /** The PPT table written back as a `ppt.ini`. */
    fun pptText(table: List<Triple<String, String, Double>>): String =
        table.joinToString("") { (code, name, range) -> "$code ${plain(range)} $name\r\n" }

    /** A range as .NET's `Double.ToString` writes it into WDP's `.ppi`: plain digits, never an exponent. */
    private fun plain(d: Double): String {
        val s = d.toString()
        if (!s.contains('E') && !s.contains('e')) return s
        return if (kotlin.math.abs(d) >= 1.0) kotlin.math.round(d).toLong().toString() else "0"
    }
}

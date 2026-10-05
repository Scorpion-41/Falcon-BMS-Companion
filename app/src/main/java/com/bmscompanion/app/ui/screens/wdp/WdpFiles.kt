package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.FileFilter
import com.bmscompanion.app.data.PcFiles
import com.bmscompanion.app.data.PickedFile
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.app.data.wdp.DtcIni

/**
 * Weapon Delivery Planner's own files, as its Open and Save windows handle them: the folders WDP opens them in, the
 * last file of each kind it remembers, and its `.ini` writing. The windows themselves are [PcFiles] (Windows' own
 * dialog on the BMS PC's window, the PC's folders in a window everywhere else), and every file is the **BMS PC's**,
 * read and written by that PC.
 *
 * **Where.** WDP keeps its files in its own folder (`ProgramPath`): `Files\EWS`, `Files\PPT` … for the DTC page's
 * backups, `SavedMaps` for the attack maps, and `DataCards` (or the folder its settings name) for the DataCard's
 * backups, codewords, package timing and plan pictures. The Planner keeps them in a folder of its own **inside the
 * BMS install**, laid out the same way: the PC's place `@planner` is `<BMS>\User\BMS Companion Planner` (never WDP's
 * folder, whose files stay WDP's), and `@datacards` its `DataCards`, or the folder the pilot chose instead
 * (`/api/files/planner`, WDP's Settings → DataCard directory). The cartridge itself stays where BMS keeps it, in
 * `User\Config` (`@config`). WDP makes a folder before it opens a window in it (`CheckForDir`); so does [folder], and
 * the first time the Planner's folder is made the PC lays out WDP's folders in it.
 *
 * **What.** Every file is in WDP's own format, written the way WDP writes it — `WritePrivateProfileString` one key at
 * a time onto the file as it is ([iniWrite]: a key replaced, a new one added, every other line kept) — and read the
 * way WDP reads it (`GetPrivateProfileString`, [DtcIni]), so a file saved by either program opens in the other.
 */
internal object WdpFiles {
    /** WDP's DataCards folder (`DataCardPath`). */
    const val DATACARDS = "@datacards"
    /** WDP's `DataCards\PlanPic`, where its plan pictures are. */
    const val PLAN_PICS = "@datacards\\PlanPic"
    /** WDP's `SavedMaps`, where the attack pages' Save Map writes. */
    const val SAVED_MAPS = "@planner\\SavedMaps"

    /** WDP's `ProgramPath\Files\<sub>` ("EWS", "PPT\Personal" …). */
    fun files(sub: String) = "@planner\\Files\\$sub"

    // ---------------------------------------------------------------- what WDP remembers (its Setup.ini's last files)

    /** The last file of a kind the pilot opened or saved (WDP's `strEWS_file` …), for the window's file name. */
    fun last(kind: String): String? = runCatching { Repo.getString("wdp_file_$kind") }.getOrNull()?.takeIf { it.isNotBlank() }

    fun remember(kind: String, file: PickedFile) { runCatching { Repo.putString("wdp_file_$kind", file.path) } }

    /** "Viper.ews": the name of [path] without its folder (WDP's `Path.GetFileName`), "" for none. */
    fun nameOf(path: String?): String = path?.substringAfterLast('\\')?.substringAfterLast('/').orEmpty()

    // ---------------------------------------------------------------- the PC's disks

    /** WDP's `CheckForDir` / `Directory.CreateDirectory`: makes [path] on the PC. The PC's sentence when it could not. */
    suspend fun folder(path: String): String? =
        runCatching { MissionLink.filesMkdir(path) }.getOrElse { return it.message ?: "The PC could not be reached." }.error

    /**
     * WDP's Settings → Datacard directory, **Browse** (its `FolderBrowserDialog`): a folder window on the BMS PC, opened
     * on the DataCards folder in use, and the folder chosen becomes the DataCards folder for every device
     * (`POST /api/files/planner`, [MissionLink.filesSetDataCards]). Answers the DataCards folder now in use, or null when
     * nothing changed (cancelled, or the PC's reason shown in a message). [PlannerSettings.chooseDataCards] is this.
     */
    suspend fun chooseDataCards(): String? {
        folder(DATACARDS)
        val f = PcFiles.folder("Datacard directory", DATACARDS) ?: return null
        val a = runCatching { MissionLink.filesSetDataCards(f.path) }.getOrNull()
        val now = a?.value
        if (now == null || a?.error != null) {
            WdpDialogs.message("Datacard directory", "The DataCards folder was not changed: ${a?.error ?: "the PC could not be reached"}")
            return null
        }
        return now.dataCards
    }

    /** True when [path] is a folder on the PC (WDP's `Directory.Exists`). */
    suspend fun isFolder(path: String): Boolean = runCatching { MissionLink.filesStat(path).value?.dir == true }.getOrDefault(false)

    /** The file's text, or null after saying why in a message titled [title]. */
    suspend fun readText(f: PickedFile, title: String): String? {
        val a = f.readText()
        val t = a.value
        if (t == null || a.error != null) {
            WdpDialogs.message(title, "${f.name} could not be read: ${a.error ?: "the PC sent nothing"}")
            return null
        }
        return t
    }

    /**
     * What a Save writes onto: the file's text when it is there, "" when it is not (a new file), or null after saying
     * why in a message — WDP's `INIWrite` keeps whatever else the file holds.
     */
    suspend fun existingText(f: PickedFile, title: String): String? {
        val a = f.readText()
        if (a.value != null && a.error == null) return a.value
        if (a.status == 404) return ""
        WdpDialogs.message(title, "${f.name} could not be read before saving: ${a.error ?: "the PC sent nothing"}")
        return null
    }

    /** Writes [text] as the file (8-bit, as WDP writes an `.ini`); false after saying why in a message. */
    suspend fun writeText(f: PickedFile, text: String, title: String): Boolean {
        val a = f.writeText(text)
        if (a.error != null) {
            WdpDialogs.message(title, "${f.name} was not saved: ${a.error}")
            return false
        }
        return true
    }

    /** Writes [bytes] as the file; false after saying why in a message. */
    suspend fun writeBytes(f: PickedFile, bytes: ByteArray, title: String): Boolean {
        val a = f.write(bytes)
        if (a.error != null) {
            WdpDialogs.message(title, "${f.name} was not saved: ${a.error}")
            return false
        }
        return true
    }

    // ---------------------------------------------------------------- the windows

    /** WDP's `OpenFileDialog` in one of its folders (made first, as WDP's `CheckForDir` does). */
    suspend fun open(title: String, folder: String, filter: String, fileName: String? = null, makeFolder: Boolean = true): PickedFile? {
        if (makeFolder) folder(folder)
        return PcFiles.open(title, folder, FileFilter.parse(filter), fileName)
    }

    /** WDP's `SaveFileDialog` in one of its folders (made first). */
    suspend fun save(title: String, folder: String, filter: String, name: String, defaultExt: String? = null, makeFolder: Boolean = true): PickedFile? {
        if (makeFolder) folder(folder)
        return PcFiles.save(title, folder, name, FileFilter.parse(filter), defaultExt = defaultExt)
    }

    // ---------------------------------------------------------------- WDP's .ini writing

    /** `clsIni.INIWrite` once per entry, in order, onto [text]: what WDP leaves in a file it writes key by key. */
    fun iniWrite(text: String, entries: List<Triple<String, String, String>>): String =
        entries.fold(text) { t, (section, key, value) -> DtcIni.write(t, section, key, value) }

    /**
     * The `key=value` lines of an `.ini` text as (section, key, value), in order: a part of the cartridge (the DTC
     * page's backups) laid onto a file with [iniWrite], key by key, as WDP's save writes it.
     */
    fun entriesOf(text: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        var section: String? = null
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val s = line.trim()
            if (s.startsWith("[")) { section = s.substring(1).substringBefore(']').trim(); continue }
            if (section == null || s.isEmpty() || s.startsWith(";")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            out += Triple(section, line.substring(0, eq).trim(), line.substring(eq + 1))
        }
        return out
    }

    /**
     * WDP's `CheckXHeader` before it reads a backup file: a file that has no `[header]` line is read as if it began
     * with one. (WDP also rewrites the file that way; here only the reading does.)
     */
    fun withHeader(text: String, header: String): String =
        if (text.split('\n').any { it.trimEnd('\r') == header }) text else header + "\r\n" + text

    // ---------------------------------------------------------------- the Coordination Card's two files

    /** `SaveCodeWords` / `LoadCodeWords`: `[Codewords]` key → the box it is written from and read into. */
    val CODEWORD_KEYS: List<Pair<String, String>> = listOf(
        "AsFragged" to "txtAsFragged", "OnStation" to "txtOnStation", "OffStation" to "txtOffStation", "Pushing" to "txtPushing",
        "ReqRolex" to "txtReqRolex", "Rolex" to "txtRolex", "MissionAbort" to "txtMissionAbort", "PackageAbort" to "txtPackageAbort",
        "MissionSucces" to "txtMissionSucc", "MissionUnSucces" to "txtMissionUnSucc", "BombJett" to "txtBombJett", "Amr" to "txtAMR",
        "WfZoneActive" to "txtWfZoneActive", "WfZoneCancelled" to "txtWfZoneCancelled", "AttPriTgt" to "txtAttPriTgt",
        "AttSecTgt" to "txtAttSecTgt", "AttSucc" to "txtAttSucc", "AttUnSucc" to "txtAttUnSucc", "TgtObs" to "txtTgtObs",
        "Reattack" to "txtReattack", "LastOfTgt" to "txtLastOfTgt", "Rtb" to "txtRtb", "Spoofing" to "txtSpoofing",
        "ChatterMarkPri" to "txtChatterMarkPri", "ChatterMarkSec" to "txtChatterMarkSec", "ChatterMarkTer" to "txtChatterMarkTer",
        "ChatterMarkHq" to "txtChatterMarkHq", "LameDuck" to "txtLameDuck", "WoundedBird" to "txtWoundedBird", "Bailout" to "txtBailout",
        "EscortRoll" to "txtEscortRoll", "OpenName1" to "txtExtra1_1", "OpenCode1" to "txtExtra1_2", "OpenName2" to "txtExtra2_1",
        "OpenCode2" to "txtExtra2_2",
    )

    /** `SavePackageTiming` / `LoadPackageTiming`: `[PackageTiming]` key → box. */
    val TIMING_KEYS: List<Pair<String, String>> = (0..9).map { "Ramrod_$it" to "txtRamrod_$it" } + listOf(
        "Balt" to "txtBalt", "Bhead" to "txtBhead", "Bnum" to "txtBnum", "Bfuel" to "txtBfuel", "RollCall" to "txtRollCall",
        "EobUpdate" to "txtEobUpdate",
    ) + listOf("TakeOffToPush", "TotWindow", "ReAttackWindow", "LastOut").flatMap { n ->
        listOf("", "Callsign", "Pri", "Sec", "Ter", "Hq").map { (n + it) to "txt$n$it" }
    }
}

/**
 * A backup DataCard (`.bdc`), in Weapon Delivery Planner's own format: an `.ini` that `SaveDataCard` writes key by key
 * and `LoadDataCard`, `LoadCommCard` and `LoadBriefingCard` read back, one page each.
 *
 * [TABLE] is `SaveDataCard`'s 542 writes in its own order — section, key, and what is written: a box's text (its
 * control's name) or one of WDP's own values (`=…`). The file therefore has WDP's sections in WDP's order, and a file
 * WDP wrote reads here through the same table: each key goes back into the box it was written from, as WDP's loads put
 * it back (their reads are the same keys, section and key compared without regard to case).
 */
internal object CardFile {
    class Entry(val section: String, val key: String, val source: String) {
        /** the box this key is written from and read into, or null for one of WDP's own values */
        val box: String? get() = source.takeUnless { it.startsWith("=") }
    }

    val TABLE: List<Entry> by lazy {
        TABLE_TEXT.trim().split('\n').map { l -> l.trim().split('|').let { Entry(it[0], it[1], it[2]) } }
    }

    /**
     * The card as WDP's `SaveDataCard` writes it onto [existing] (the file's text, "" for a new one). [box] gives a
     * box's text as the card shows it; [value] one of WDP's own values (`=theater`, `=totime1` …), or null to leave
     * that key out — what the app does not know, WDP's load then keeps as it was rather than taking a made-up figure.
     */
    fun write(existing: String, box: (String) -> String, value: (String) -> String?): String {
        val entries = TABLE.mapNotNull { e ->
            val v = e.box?.let(box) ?: value(e.source) ?: return@mapNotNull null
            Triple(e.section, e.key, v.replace("\r", " ").replace("\n", " "))
        }
        return WdpFiles.iniWrite(existing, entries)
    }

    /** Every box the file holds a key for, with what WDP's load reads for it ("" for a key the file does not have). */
    fun boxes(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in TABLE) {
            val b = e.box ?: continue
            out[b] = DtcIni.read(text, e.section, e.key)
        }
        return out
    }

    /** One of WDP's own values as the file holds it. */
    fun value(text: String, section: String, key: String): String = DtcIni.read(text, section, key)

    private const val TABLE_TEXT = """
Mission|Theater|=theater
Mission|Campaign|=campaign
Mission|Name|lblMission
Mission|PackageNr|=package
Weather|Line1|lblAtis1
Weather|Line2|lblAtis2
Airport|Dep|lblDepName
Airport|DepRwy|lblDepRWY
Airport|Arr|lblArrName
Airport|ArrRwy|lblArrRWY
Airport|Altn|lblAltnName
Airport|AltnRwy|lblAltnRWY
Flight|Pilot1|lblLead
Flight|Pilot2|lblWing1
Flight|Pilot3|lblElement
Flight|Pilot4|lblWing4
Flight|Lead_IDM|txtLead_IDM
Flight|Wing1_IDM|txtWing1_IDM
Flight|Element_IDM|txtElement_IDM
Flight|Wing4_IDM|txtWing4_IDM
Flight|Lead_TCN|txtLead_TCN
Flight|Wing1_TCN|txtWing1_TCN
Flight|Element_TCN|txtElement_TCN
Flight|Wing4_TCN|txtWing4_TCN
Flight|Lead_TO|txtLead_TO
Flight|Wing1_TO|txtWing1_TO
Flight|Element_TO|txtElement_TO
Flight|Wing4_TO|txtWing4_TO
Flight|Lead_LND|txtLead_Lnd
Flight|Wing1_LND|txtWing1_Lnd
Flight|Element_LND|txtElement_Lnd
Flight|Wing4_LND|txtWing4_Lnd
Flight|Lead_Note|txtLead_Lsr
Flight|Wing1_Note|txtWing1_Lsr
Flight|Element_Note|txtElement_lsr
Flight|Wing4_Note|txtWing4_Lsr
Package|CallSign1|rbnCallsign1
Package|CallSign2|rbnCallsign2
Package|CallSign3|rbnCallsign3
Package|CallSign4|rbnCallsign4
Package|CallSign5|rbnCallsign5
Package|SelFlight|=selflight
Package|AC1|lblAC1
Package|AC2|lblAC2
Package|AC3|lblAC3
Package|AC4|lblAC4
Package|AC5|lblAC5
Package|UHF1|txtC1_UHF
Package|UHF2|txtC2_UHF
Package|UHF3|txtC3_UHF
Package|UHF4|txtC4_UHF
Package|UHF5|txtC5_UHF
Package|VHF1|txtC1_VHF
Package|VHF2|txtC2_VHF
Package|VHF3|txtC3_VHF
Package|VHF4|txtC4_VHF
Package|VHF5|txtC5_VHF
Package|IDM1|txtIDM_1
Package|IDM2|txtIDM_2
Package|IDM3|txtIDM_3
Package|IDM4|txtIDM_4
Package|IDM5|txtIDM_5
Package|TCN1|txtTCN_1
Package|TCN2|txtTCN_2
Package|TCN3|txtTCN_3
Package|TCN4|txtTCN_4
Package|TCN5|txtTCN_5
Package|Task1|lblP_Task1
Package|Task2|lblP_Task2
Package|Task3|lblP_Task3
Package|Task4|lblP_Task4
Package|Task5|lblP_Task5
Package|ToTime1|=takeofftime1
Package|ToTime2|=takeofftime2
Package|ToTime3|=takeofftime3
Package|ToTime4|=takeofftime4
Package|ToTime5|=takeofftime5
Package|PushTime1|=pushtime1
Package|PushTime2|=pushtime2
Package|PushTime3|=pushtime3
Package|PushTime4|=pushtime4
Package|PushTime5|=pushtime5
Package|PushAlt1|=pushalt1
Package|PushAlt2|=pushalt2
Package|PushAlt3|=pushalt3
Package|PushAlt4|=pushalt4
Package|PushAlt5|=pushalt5
Package|TOT1|=targettime1
Package|TOT2|=targettime2
Package|TOT3|=targettime3
Package|TOT4|=targettime4
Package|TOT5|=targettime5
Flightplan|Swing|=swingflpn
Flightplan|Action1|lblAction1
Flightplan|Action2|lblAction2
Flightplan|Action3|lblAction3
Flightplan|Action4|lblAction4
Flightplan|Action5|lblAction5
Flightplan|Action6|lblAction6
Flightplan|Action7|lblAction7
Flightplan|Action8|lblAction8
Flightplan|Action9|lblAction9
Flightplan|Action10|lblAction10
Flightplan|Action11|lblAction11
Flightplan|Action12|lblAction12
Flightplan|Action13|lblAction13
Flightplan|Action14|lblAction14
Flightplan|Action15|lblAction15
Flightplan|Action16|lblAction16
Flightplan|Action17|lblAction17
Flightplan|Action18|lblAction18
Flightplan|Action19|lblAction19
Flightplan|Action20|lblAction20
Flightplan|Action21|lblAction21
Flightplan|Action22|lblAction22
Flightplan|Action23|lblAction23
Flightplan|Action24|lblAction24
Flightplan|TOS1|lblTOS1
Flightplan|TOS2|lblTOS2
Flightplan|TOS3|lblTOS3
Flightplan|TOS4|lblTOS4
Flightplan|TOS5|lblTOS5
Flightplan|TOS6|lblTOS6
Flightplan|TOS7|lblTOS7
Flightplan|TOS8|lblTOS8
Flightplan|TOS9|lblTOS9
Flightplan|TOS10|lblTOS10
Flightplan|TOS11|lblTOS11
Flightplan|TOS12|lblTOS12
Flightplan|TOS13|lblTOS13
Flightplan|TOS14|lblTOS14
Flightplan|TOS15|lblTOS15
Flightplan|TOS16|lblTOS16
Flightplan|TOS17|lblTOS17
Flightplan|TOS18|lblTOS18
Flightplan|TOS19|lblTOS19
Flightplan|TOS20|lblTOS20
Flightplan|TOS21|lblTOS21
Flightplan|TOS22|lblTOS22
Flightplan|TOS23|lblTOS23
Flightplan|TOS24|lblTOS24
Flightplan|HDG1|lblHdg1
Flightplan|HDG2|lblHdg2
Flightplan|HDG3|lblHdg3
Flightplan|HDG4|lblHdg4
Flightplan|HDG5|lblHdg5
Flightplan|HDG6|lblHdg6
Flightplan|HDG7|lblHdg7
Flightplan|HDG8|lblHdg8
Flightplan|HDG9|lblHdg9
Flightplan|HDG10|lblHdg10
Flightplan|HDG11|lblHdg11
Flightplan|HDG12|lblHdg12
Flightplan|HDG13|lblHdg13
Flightplan|HDG14|lblHdg14
Flightplan|HDG15|lblHdg15
Flightplan|HDG16|lblHdg16
Flightplan|HDG17|lblHdg17
Flightplan|HDG18|lblHdg18
Flightplan|HDG19|lblHdg19
Flightplan|HDG20|lblHdg20
Flightplan|HDG21|lblHdg21
Flightplan|HDG22|lblHdg22
Flightplan|HDG23|lblHdg23
Flightplan|HDG24|lblHdg24
Flightplan|Dist1|lblDist1
Flightplan|Dist2|lblDist2
Flightplan|Dist3|lblDist3
Flightplan|Dist4|lblDist4
Flightplan|Dist5|lblDist5
Flightplan|Dist6|lblDist6
Flightplan|Dist7|lblDist7
Flightplan|Dist8|lblDist8
Flightplan|Dist9|lblDist9
Flightplan|Dist10|lblDist10
Flightplan|Dist11|lblDist11
Flightplan|Dist12|lblDist12
Flightplan|Dist13|lblDist13
Flightplan|Dist14|lblDist14
Flightplan|Dist15|lblDist15
Flightplan|Dist16|lblDist16
Flightplan|Dist17|lblDist17
Flightplan|Dist18|lblDist18
Flightplan|Dist19|lblDist19
Flightplan|Dist20|lblDist20
Flightplan|Dist21|lblDist21
Flightplan|Dist22|lblDist22
Flightplan|Dist23|lblDist23
Flightplan|Dist24|lblDist24
Flightplan|ALT1|lblAlt1
Flightplan|ALT2|lblAlt2
Flightplan|ALT3|lblAlt3
Flightplan|ALT4|lblAlt4
Flightplan|ALT5|lblAlt5
Flightplan|ALT6|lblAlt6
Flightplan|ALT7|lblAlt7
Flightplan|ALT8|lblAlt8
Flightplan|ALT9|lblAlt9
Flightplan|ALT10|lblAlt10
Flightplan|ALT11|lblAlt11
Flightplan|ALT12|lblAlt12
Flightplan|ALT13|lblAlt13
Flightplan|ALT14|lblAlt14
Flightplan|ALT15|lblAlt15
Flightplan|ALT16|lblAlt16
Flightplan|ALT17|lblAlt17
Flightplan|ALT18|lblAlt18
Flightplan|ALT19|lblAlt19
Flightplan|ALT20|lblAlt20
Flightplan|ALT21|lblAlt21
Flightplan|ALT22|lblAlt22
Flightplan|ALT23|lblAlt23
Flightplan|ALT24|lblAlt24
Flightplan|Kias1|txtKias1
Flightplan|Kias2|txtKias2
Flightplan|Kias3|txtKias3
Flightplan|Kias4|txtKias4
Flightplan|Kias5|txtKias5
Flightplan|Kias6|txtKias6
Flightplan|Kias7|txtKias7
Flightplan|Kias8|txtKias8
Flightplan|Kias9|txtKias9
Flightplan|Kias10|txtKias10
Flightplan|Kias11|txtKias11
Flightplan|Kias12|txtKias12
Flightplan|Kias13|txtKias13
Flightplan|Kias14|txtKias14
Flightplan|Kias15|txtKias15
Flightplan|Kias16|txtKias16
Flightplan|Kias17|txtKias17
Flightplan|Kias18|txtKias18
Flightplan|Kias19|txtKias19
Flightplan|Kias20|txtKias20
Flightplan|Kias21|txtKias21
Flightplan|Kias22|txtKias22
Flightplan|Kias23|txtKias23
Flightplan|Kias24|txtKias24
Flightplan|Fuel1|txtFuel1
Flightplan|Fuel2|txtFuel2
Flightplan|Fuel3|txtFuel3
Flightplan|Fuel4|txtFuel4
Flightplan|Fuel5|txtFuel5
Flightplan|Fuel6|txtFuel6
Flightplan|Fuel7|txtFuel7
Flightplan|Fuel8|txtFuel8
Flightplan|Fuel9|txtFuel9
Flightplan|Fuel10|txtFuel10
Flightplan|Fuel11|txtFuel11
Flightplan|Fuel12|txtFuel12
Flightplan|Fuel13|txtFuel13
Flightplan|Fuel14|txtFuel14
Flightplan|Fuel15|txtFuel15
Flightplan|Fuel16|txtFuel16
Flightplan|Fuel17|txtFuel17
Flightplan|Fuel18|txtFuel18
Flightplan|Fuel19|txtFuel19
Flightplan|Fuel20|txtFuel20
Flightplan|Fuel21|txtFuel21
Flightplan|Fuel22|txtFuel22
Flightplan|Fuel23|txtFuel23
Flightplan|Fuel24|txtFuel24
Flightplan|Formation1|txtFormation1
Flightplan|Formation2|txtFormation2
Flightplan|Formation3|txtFormation3
Flightplan|Formation4|txtFormation4
Flightplan|Formation5|txtFormation5
Flightplan|Formation6|txtFormation6
Flightplan|Formation7|txtFormation7
Flightplan|Formation8|txtFormation8
Flightplan|Formation9|txtFormation9
Flightplan|Formation10|txtFormation10
Flightplan|Formation11|txtFormation11
Flightplan|Formation12|txtFormation12
Flightplan|Formation13|txtFormation13
Flightplan|Formation14|txtFormation14
Flightplan|Formation15|txtFormation15
Flightplan|Formation16|txtFormation16
Flightplan|Formation17|txtFormation17
Flightplan|Formation18|txtFormation18
Flightplan|Formation19|txtFormation19
Flightplan|Formation20|txtFormation20
Flightplan|Formation21|txtFormation21
Flightplan|Formation22|txtFormation22
Flightplan|Formation23|txtFormation23
Flightplan|Formation24|txtFormation24
Loadout|Station1|=station1
Loadout|Station2|=station2
Loadout|Station3|=station3
Loadout|Station4|=station4
Loadout|Station5|=station5
Loadout|Station6|=station6
Loadout|Station7|=station7
Loadout|Station8|=station8
Loadout|Station9|=station9
Loadout|Station10|=station10
Loadout|Station11|=station11
Loadout|Station12|=station12
Loadout|Station13|=station13
Loadout|Station14|=station14
Loadout|Station15|=station15
Loadout|Station16|=station16
Loadout|Count1|=count1
Loadout|Count2|=count2
Loadout|Count3|=count3
Loadout|Count4|=count4
Loadout|Count5|=count5
Loadout|Count6|=count6
Loadout|Count7|=count7
Loadout|Count8|=count8
Loadout|Count9|=count9
Loadout|Count10|=count10
Loadout|Count11|=count11
Loadout|Count12|=count12
Loadout|Count13|=count13
Loadout|Count14|=count14
Loadout|Count15|=count15
Loadout|Count16|=count16
Loadout|ALOW|txtALOW
Loadout|MSL|txtMSL
Loadout|SetFuel|txtSetFuel
Loadout|Joker|txtJoker
Loadout|Bingo|txtBingo
Loadout|Pitch|=pitch
Loadout|Power|=power
TGT|Primary|txtTGT_Pri
TGT|Pri_DMPI|txtDMPI_Pri
TGT|Pri_LatLon|txtLatLong_Pri
TGT|Secondary|txtTGT_Sec
TGT|Sec_DMPI|txtDMPI_Sec
TGT|Sec_LatLon|txtLatLong_Sec
Attack|Type|=attacktype
Attack|Bomb|=attackbomb
Attack|Ref|=attackref
Attack|IngAlt|lblIngrHgt
Attack|DA|lblDA
Attack|Spd|=attackspd
Attack|RelHgt|lblRelHgt
Attack|TrackTime|=attacktracktime
Attack|G|=attackg
Attack|Turn|lblTurn
Attack|Hdg|lblAttHed
Attack|VrpToPup|=attackvrptopup
Attack|Stpt|=attackstpt
Attack|Zoom|=attackzoom
EWS|Ews1|txtEws1
EWS|Ews2|txtEws2
EWS|Ews3|txtEws3
EWS|Ews4|txtEws4
EWS|Ews5|txtEws5
EWS|Ews6|txtEws6
Weapon|Attack|txtWpn_AttackType
Weapon|WpnMode1|txtWpn_SubMode1
Weapon|WpnArmDly1|txtWpn_ArmDly1
Weapon|WpnBurstAlt1|txtWpn_BA1
Weapon|WpnFuse1|txtWpn_Fuse1
Weapon|WpnSGLPAIR1|txtWpn_SGLPAIR1
Weapon|WpnRipple1|txtWpn_Ripple1
Weapon|WpnSpace1|txtWpn_Space1
Weapon|WpnRelAngle1|txtWpn_RelAngle1
Weapon|WpnMode2|txtWpn_SubMode2
Weapon|WpnArmDly2|txtWpn_ArmDly2
Weapon|WpnBurstAlt2|txtWpn_BA2
Weapon|WpnFuse2|txtWpn_Fuse2
Weapon|WpnSGLPAIR2|txtWpn_SGLPAIR2
Weapon|WpnRipple2|txtWpn_Ripple2
Weapon|WpnSpace2|txtWpn_Space2
Weapon|WpnRelAngle2|txtWpn_RelAngle2
Support|Tanker1|txtTanker1
Support|Tanker1_TCN|txtTanker1_TCN
Support|Tanker1_UHF|txtTanker1_UHF
Support|Tanker1_Loc|txtTanker1_Loc
Support|Tanker1_Notes|txtTanker1_Notes
Support|Tanker2|txtTanker2
Support|Tanker2_TCN|txtTanker2_TCN
Support|Tanker2_UHF|txtTanker2_UHF
Support|Tanker2_Loc|txtTanker2_Loc
Support|Tanker2_Notes|txtTanker2_Notes
Support|AWACS|txtAWACS
Support|AWACS_TCN|txtAWACS_TCN
Support|AWACS_UHF|txtAWACS_UHF
Support|AWACS_Loc|txtAWACS_Loc
Support|AWACS_Notes|txtAWACS_Notes
Support|JSTAR|txtJSTAR
Support|JSTAR_TCN|txtJSTAR_TCN
Support|JSTAR_UHF|txtJSTAR_UHF
Support|JSTAR_Loc|txtJSTAR_Loc
Support|JSTAR_Notes|txtJSTAR_Notes
Support|FAC|txtFAC
Support|FAC_TCN|txtFAC_TCN
Support|FAC_UHF|txtFAC_UHF
Support|FAC_Loc|txtFAC_Loc
Support|FAC_Notes|txtFAC_Notes
Class|Class1|txtClass1
Class|Class2|txtClass2
Class|Class3|txtClass3
Class|Class4|txtClass4
Class|Mtr1|txtMtr1
Class|Mtr2|txtMtr2
Class|Mtr3|txtMtr3
Class|Mtr4|txtMtr4
Class|Fr1|txtFr1
Class|Fr2|txtFr2
Class|Fr3|txtFr3
Class|Fr4|txtFr4
Class|Mar1|txtMar1
Class|Mar2|txtMar2
Class|Mar3|txtMar3
Class|Mar4|txtMar4
Class|Dr1|txtDr1
Class|Dr2|txtDr2
Class|Dr3|txtDr3
Class|Dr4|txtDr4
Class|Dor1|txtDor1
Class|Dor2|txtDor2
Class|Dor3|txtDor3
Class|Dor4|txtDor4
Roe|Roe1|txtRoe1
Roe|Roe2|txtRoe2
Roe|Roe3|txtRoe3
Roe|Roe4|txtRoe4
Roe|Roe5|txtRoe5
Extra|Extra1|txtExtra1
Extra|Extra2|txtExtra2
Extra|Extra3|txtExtra3
Extra|Extra4|txtExtra4
Extra|Extra5|txtExtra5
Map|LocX|=maplocx
Map|LocY|=maplocy
Map|Zoom|=mapzoom
Map|White|=mapwhite
CommPackage|HoldPt1|txtCommHoldPt1
CommPackage|HoldPt2|txtCommHoldPt2
CommPackage|HoldPt3|txtCommHoldPt3
CommPackage|HoldPt4|txtCommHoldPt4
CommPackage|HoldPt5|txtCommHoldPt5
CommPackage|HoldAlt1|txtCommHoldAlt1
CommPackage|HoldAlt2|txtCommHoldAlt2
CommPackage|HoldAlt3|txtCommHoldAlt3
CommPackage|HoldAlt4|txtCommHoldAlt4
CommPackage|HoldAlt5|txtCommHoldAlt5
CommPackage|TransAlt1|txtCommTransAlt1
CommPackage|TransAlt2|txtCommTransAlt2
CommPackage|TransAlt3|txtCommTransAlt3
CommPackage|TransAlt4|txtCommTransAlt4
CommPackage|TransAlt5|txtCommTransAlt5
Taxi|Rwy1|txtRwy1
Taxi|Rwy2|txtRwy2
Taxi|Time1|lblRwyTaxiTime1
Taxi|Time2|lblRwyTaxiTime2
Pressure|StdQnh|txtStdQnh
Pressure|ForceQnh|txtForceQnh
Pressure|TL|txtTransitLvl
Pressure|MSAA|txtMSAA
RouteStpt|Swing|=swing
RouteStpt|RouteStpt1|=route1
RouteStpt|RouteStpt2|=route2
RouteStpt|RouteStpt3|=route3
RouteStpt|RouteStpt4|=route4
RouteStpt|RouteStpt5|=route5
RouteStpt|RouteStpt6|=route6
RouteStpt|RouteStpt7|=route7
RouteStpt|RouteStpt8|=route8
RouteStpt|RouteStpt9|=route9
Bullseye|Name|txtBullseye
Bullseye|North|lblCommBullLat
Bullseye|East|lblCommBullLon
Speed|Med|txtSpdMedLvl
Speed|Low|txtSpdLowLvl
PackageTiming|Ramrod_0|txtRamrod_0
PackageTiming|Ramrod_1|txtRamrod_1
PackageTiming|Ramrod_2|txtRamrod_2
PackageTiming|Ramrod_3|txtRamrod_3
PackageTiming|Ramrod_4|txtRamrod_4
PackageTiming|Ramrod_5|txtRamrod_5
PackageTiming|Ramrod_6|txtRamrod_6
PackageTiming|Ramrod_7|txtRamrod_7
PackageTiming|Ramrod_8|txtRamrod_8
PackageTiming|Ramrod_9|txtRamrod_9
PackageTiming|Balt|txtBalt
PackageTiming|Bhead|txtBhead
PackageTiming|Bnum|txtBnum
PackageTiming|Bfuel|txtBfuel
PackageTiming|RollCall|txtRollCall
PackageTiming|EobUpdate|txtEobUpdate
PackageTiming|TakeOffToPush|txtTakeOffToPush
PackageTiming|TakeOffToPushCallsign|txtTakeOffToPushCallsign
PackageTiming|TakeOffToPushPri|txtTakeOffToPushPri
PackageTiming|TakeOffToPushSec|txtTakeOffToPushSec
PackageTiming|TakeOffToPushTer|txtTakeOffToPushTer
PackageTiming|TakeOffToPushHq|txtTakeOffToPushHq
PackageTiming|TotWindow|txtTotWindow
PackageTiming|TotWindowCallsign|txtTotWindowCallsign
PackageTiming|TotWindowPri|txtTotWindowPri
PackageTiming|TotWindowSec|txtTotWindowSec
PackageTiming|TotWindowTer|txtTotWindowTer
PackageTiming|TotWindowHq|txtTotWindowHq
PackageTiming|ReAttackWindow|txtReAttackWindow
PackageTiming|ReAttackWindowCallsign|txtReAttackWindowCallsign
PackageTiming|ReAttackWindowPri|txtReAttackWindowPri
PackageTiming|ReAttackWindowSec|txtReAttackWindowSec
PackageTiming|ReAttackWindowTer|txtReAttackWindowTer
PackageTiming|ReAttackWindowHq|txtReAttackWindowHq
PackageTiming|LastOut|txtLastOut
PackageTiming|LastOutCallsign|txtLastOutCallsign
PackageTiming|LastOutPri|txtLastOutPri
PackageTiming|LastOutSec|txtLastOutSec
PackageTiming|LastOutTer|txtLastOutTer
PackageTiming|LastOutHq|txtLastOutHq
Codes|AsFragged|txtAsFragged
Codes|OnStation|txtOnStation
Codes|OffStation|txtOffStation
Codes|Pushing|txtPushing
Codes|ReqRolex|txtReqRolex
Codes|Rolex|txtRolex
Codes|MissionAbort|txtMissionAbort
Codes|PackageAbort|txtPackageAbort
Codes|MissionSucc|txtMissionSucc
Codes|MissionUnSucc|txtMissionUnSucc
Codes|BombJett|txtBombJett
Codes|AMR|txtAMR
Codes|WfZoneActive|txtWfZoneActive
Codes|WfZoneCancelled|txtWfZoneCancelled
Codes|AttPriTgt|txtAttPriTgt
Codes|AttSecTgt|txtAttSecTgt
Codes|AttSucc|txtAttSucc
Codes|AttUnSucc|txtAttUnSucc
Codes|TgtObs|txtTgtObs
Codes|Reattack|txtReattack
Codes|LastOfTgt|txtLastOfTgt
Codes|Rtb|txtRtb
Codes|Spoofing|txtSpoofing
Codes|ChatterMarkPri|txtChatterMarkPri
Codes|ChatterMarkSec|txtChatterMarkSec
Codes|ChatterMarkTer|txtChatterMarkTer
Codes|ChatterMarkHq|txtChatterMarkHq
Codes|LameDuck|txtLameDuck
Codes|WoundedBird|txtWoundedBird
Codes|Bailout|txtBailout
Codes|EscortRoll|txtEscortRoll
Codes|Extra11|txtExtra1_1
Codes|Extra12|txtExtra1_2
Codes|Extra21|txtExtra2_1
"""
}

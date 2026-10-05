package com.bmscompanion.app.data.wdp

import kotlin.math.abs

/**
 * The pilot's data cartridge (`User/Config/UserConfig<callsign>.ini`) as Weapon Delivery Planner holds it, and the
 * two things WDP does with it: **reading** it into that model exactly as `clsLoadDTC.LoadCallsign` does, and
 * **writing** the model back into the file's text exactly as `fclsMain.SaveCallsign_DTC` does through
 * `clsSaveDTC`. Falcas's code, ported.
 *
 * Both are pure: text in, model out; model and text in, text out. The file itself — where it is, backing it up,
 * writing it safely — is the caller's business (see the DTC integration notes). Every call WDP makes to Windows'
 * profile API goes through [DtcIni], which reproduces what Windows does to the file.
 *
 * The model mirrors `fclsMain`'s `tblCamp*` tables and `Camp*` structures field for field, sizes included (25
 * steerpoints of which the file holds 24, 101 weapon targets of which 100, …), because what WDP writes back is the
 * whole table and what it leaves alone is whatever was there before. A load does not start from a clean model
 * either: like WDP's it overwrites what the file holds and keeps the rest (the HARM tables, the second OA2 offset),
 * so the same [DtcModel] is meant to live as long as the page does.
 *
 * ### Quirks kept (each moves what is read or written)
 * - **A short steerpoint line stops the steerpoint table.** Each field is taken with `FirstWordCsv`; a missing
 *   field is `""`, and `Conversions.ToDouble("")` (the "both zero" test) throws, which ends `GetCallsignSTPT` there
 *   with its error bit set — the steerpoints before it are loaded, the rest stay cleared. The same holds for the
 *   Open and Harpoon tables. A field that *begins* with a comma is taken whole (`FirstComma` - 1 is 0 and the code
 *   treats "no comma" and "comma first" alike).
 * - **A steerpoint's target name carries over**: the name is declared outside the loop, so a steerpoint whose line
 *   is missing takes the name of the one before it.
 * - **A non-numeric UHF or VHF preset ends the radio read**: the "not numeric" message box is built with its
 *   arguments in the wrong order and `Conversions.ToInteger("Loading Callsign.ini")` throws before anything shows,
 *   so the presets after it keep their defaults and no comment is read.
 * - **Default UHF comment 5 lands in slot 1** and default VHF comment 7 in slot 1 (`UHFcomment[1] = "UHF Preset 5"`),
 *   so slot 5 and slot 7 stay empty, and the VHF defaults carry the key name ("VHF_COMMENT_1=VHF Preset 1").
 * - **An A-G MFD left page of 255** is kept as 255 where every other page takes 0 (its "else" repeats the
 *   conversion), and anything above a byte throws there.
 * - **HARM values that are not numbers are skipped**, not zeroed: the previous cartridge's stay.
 * - **The last of the Cockpit View and OTW sections decides the Views box**: both set it, OTW last.
 *
 * ### Quirks put right
 * The page writes back what it reads (see [DtcEdits]), so three of WDP's reading slips, each of which moved or lost
 * the pilot's data on a round trip, are corrected rather than kept: the Open 2 table is read from `target_89`…`98`
 * where it is written (WDP read one key late); the steerpoint and Open readers clear their own target names, not
 * the weapon targets' (which WDP left "Not set"); and the second `OA2-n` goes into OA2_2 (WDP assigned OA1_2 twice).
 *
 * ### Falcon BMS 4.38.1 only
 * WDP read every cartridge back to Falcon 4.0 and branched on the version at a dozen places; the Planner is for
 * 4.38.1, so each of those branches reads the way 4.38.1 writes (a numeric `Modesel` is one more than the page's, as
 * since 4.36 build 25688). Three readings are corrected for 4.38.1's data:
 * - **ILS frequencies are not raised to 109.00** (D11). WDP forced anything below 109.00 up to it, but 4.38.1 puts
 *   localizers on 108.10–111.95 in every theater (Daegu 108.70, Gimpo 14R 108.30, Nevatim 08R 108.50, Cigli 108.15
 *   in the app's airport data), so a cartridge holding 10870 loaded as 109.00 and was saved back as 10900: the jet
 *   tuned the wrong localizer. A value inside that band is kept as it is; outside it, it goes to the nearer edge.
 * - **4.38.1's ILS presets** (`[Radio] ILS_1`…`ILS_4` with their comments, which BMS writes itself: `ILS_1=11130` at
 *   Osan) are read, and never written: WDP knows nothing of them, so they survive a save byte for byte. When the
 *   cartridge has no `[COMMS] ILS Frequency` of its own, the ILS box starts from `ILS_1` rather than WDP's 109.00.
 * - **An IFF time event's criteria of -1** (every event in BMS's own `IFF_Def.ini`: "not used") is read as -1.
 *   WDP's `ToULong` threw on it and ended the whole IFF read there, so the position events were never loaded.
 */
class DtcModel {
    /**
     * `fclsMain.Build` / `MinorPart`: which BMS the campaign is from. WDP's reader and writer branched on both; the
     * Planner is for Falcon BMS 4.38.1 only and reads neither (they stay so that a caller setting them compiles).
     */
    var build = 0
    var minorPart = 0

    val stpt = Array(25) { DtcStpt() }
    val ppt = Array(15) { DtcPpt() }
    val line = Array(24) { DtcLine() }
    val tgt = Array(101) { DtcTgt() }
    val open = Array(9) { DtcStpt() }
    val hpn = Array(10) { DtcStpt() }
    val ews = DtcEws()
    val mfd = DtcMfdMode()
    var bullseyeInfoOnMfd = false
    val radio = DtcRadio()
    val comm = DtcComm()
    val nav = DtcNavOffsets()
    val hud = DtcHud()
    val icp = DtcIcp()
    val iff = DtcIff()
    var wideView = 0
    var otwMode = 0
    var masterArm = 0
    val harm = DtcHarm()
    val laser = DtcLaser()
    val aim = DtcAim()
    val agm = DtcAgm()
    val agb1 = DtcBombProfile()
    val agb2 = DtcBombProfile()
    var ralt = 0
    var dedLight = 0

    /** The page's "include" boxes, which the reader ticks and the writer obeys (`chbCmdsIncl` … `chbIntLghtIncl`). */
    var cmdsIncl = false
    var hudIncl = false
    var viewsIncl = false
    var masterArmIncl = false
    var snsrPowerIncl = false
    var intLghtIncl = false

    /**
     * The nav offsets the attack pages hand over (`fclsMain.PopUpNavOffsets`, `HADBNavOffsets`, `TOSSNavOffsets`):
     * what the DTC page's Pop-Up / HADB / TOSS buttons copy into the cartridge's own.
     */
    val popUpNav = DtcNavOffsets()
    val hadbNav = DtcNavOffsets()
    val tossNav = DtcNavOffsets()

    /**
     * WDP's reader ticks the page's include boxes itself (`cntDTC.chbCmdsIncl.CheckState = …`), which raises the
     * page's handlers; the page listens here (0 CMDS, 1 HUD, 2 views, 3 master arm, 4 sensor power, 5 lighting).
     */
    var onIncl: ((Int, Boolean) -> Unit)? = null

    /** `fclsMain.IFF_TIME_EVENTS` / `IFF_POS_EVENTS`. */
    val iffTimeEvents = 12
    val iffPosEvents = 2
}

/** `Camp_STPT` (also the Open and Harpoon tables). Track, distance and the half-way point are the page's. */
class DtcStpt {
    var nr = 0
    var falconX = 0f
    var falconY = 0f
    var falconZ = 0f
    var north: String? = null
    var east: String? = null
    var action = 0
    var track = 0.0
    var distance = 0.0
    var halfWayX = 0.0
    var halfWayY = 0.0
    var target: String? = null
}

class DtcTgt {
    var falconX = 0f
    var falconY = 0f
    var falconZ = 0f
    var north: String? = null
    var east: String? = null
    var action = 0
    var target: String? = null
}

class DtcPpt {
    var nr = 0
    var stptNr = 0
    var name: String? = null
    var falconX = 0f
    var falconY = 0f
    var falconZ = 0f
    var falconRng = 0f
    var code: String? = null
    var north: String? = null
    var east: String? = null
}

class DtcLine {
    var nr = 0
    var falconX = 0f
    var falconY = 0f
    var falconZ = 0f
    var north: String? = null
    var east: String? = null
}

class DtcEwsProgram {
    var chaffBQ = 0
    var chaffBI = 0
    var chaffSQ = 0
    var chaffSI = 0
    var flareBQ = 0
    var flareBI = 0
    var flareSQ = 0
    var flareSI = 0
    var comment: String? = null
    fun copy() = DtcEwsProgram().also {
        it.chaffBQ = chaffBQ; it.chaffBI = chaffBI; it.chaffSQ = chaffSQ; it.chaffSI = chaffSI
        it.flareBQ = flareBQ; it.flareBI = flareBI; it.flareSQ = flareSQ; it.flareSI = flareSI; it.comment = comment
    }
}

class DtcEws {
    var reqjam = false
    var reqctr = false
    var bingo = false
    var fdbk = false
    var flareBingo = 0
    var chaffBingo = 0
    /** Null until something sizes it, as in a fresh `fclsMain`. */
    var program: Array<DtcEwsProgram>? = null
    var modeSelection = 0
    var numberSelection = 0
}

class DtcMfd {
    var left = 0
    var center = 0
    var right = 0
    var csel = 0
    fun copy() = DtcMfd().also { it.left = left; it.center = center; it.right = right; it.csel = csel }
}

class DtcMfdMode {
    var aG: Array<DtcMfd>? = null
    var aA: Array<DtcMfd>? = null
    var nav: Array<DtcMfd>? = null
    var msl: Array<DtcMfd>? = null
    var dgf: Array<DtcMfd>? = null
    var sJ: Array<DtcMfd>? = null
}

class DtcRadio {
    var uhf: IntArray? = null
    var vhf: IntArray? = null
    var uhfComment: Array<String?>? = null
    var vhfComment: Array<String?>? = null
    /**
     * 4.38.1's ILS presets, `[Radio] ILS_1`…`ILS_4` (index 1–4, hundredths of a MHz as BMS writes them) and their
     * comments: read for the page, never written, so the file keeps them exactly as BMS wrote them.
     */
    val ils = IntArray(5)
    val ilsComment = arrayOfNulls<String>(5)
}

class DtcComm {
    var comm1 = 0
    var comm2 = 0
    var tacanChannel = 0
    var ilsFrequency = 0
    var ilsCrs = 0
    var tacanBand = 0
    var tacanDomain = 0
    var comm1Comment: String? = null
    var comm2Comment: String? = null
}

class DtcOffset {
    var stpt = 0
    var bearing = 0f
    var range = 0
    var elv = 0
}

class DtcNavOffsets {
    var modesel = 0
    val vip = DtcOffset()
    val vipPup = DtcOffset()
    val vrp = DtcOffset()
    val vrpPup = DtcOffset()
    val oa1_1 = DtcOffset()
    val oa2_1 = DtcOffset()
    val oa1_2 = DtcOffset()
    val oa2_2 = DtcOffset()
}

class DtcHud {
    var color = 0
    var scales = 0
    var brightness = 0
    var fpm = 0
    var ded = 0
    var velocity = 0
    var alt = 0
    var symWheelPos = 0
}

class DtcIcp {
    var masterMode = 0
    var alowAgl = 0f
    var alowMsl = 0
    var alowTfAdv = 0
    var manualWingspan = 0f
    var bingoFuel = 0f
}

class DtcIffTime {
    var mode1Code = 0
    var mode3aCode: Short = 0
    var mode4Key = 0
    /** A .NET `ulong`, kept in a Long's bits. */
    var timeCriteria = 0L
}

class DtcIffPos {
    var mode1: Short = 0
    var mode2: Short = 0
    var mode3a: Short = 0
    var mode4: Short = 0
    var modeC: Short = 0
    var modeS: Short = 0
    var wayPoint = 0
    var direction = 0
}

class DtcIff {
    var mode1On = 0
    var mode2On = 0
    var mode3aOn = 0
    var mode4On = 0
    var modeCOn = 0
    var modeSOn = 0
    var mode1Code: Short = 0
    var mode2Code: Short = 0
    var mode3aCode: Short = 0
    var mode4Key: Short = 0
    var autoChange = 0
    var timeSettings: Array<DtcIffTime>? = null
    var posSettings: Array<DtcIffPos>? = null
}

class DtcHarmThreats {
    var threat0 = 0
    var threat1 = 0
    var threat2 = 0
    var threat3 = 0
    var threat4 = 0
    fun copy() = DtcHarmThreats().also { it.threat0 = threat0; it.threat1 = threat1; it.threat2 = threat2; it.threat3 = threat3; it.threat4 = threat4 }
}

class DtcHarm {
    var table: Array<DtcHarmThreats>? = null
    var mode = 0
    var subMode = 0
    var ter = 0
}

class DtcLaser {
    var laserSt = 0
    var laserCode: Short = 0
    var lstCode: Short = 0
}

class DtcAim {
    var spotScan = 0
    var tdBp = 0
    var targetSize = 0
}

class DtcAgm {
    var mavAutoPwr = 0
    var mavAutoPwrDir = 0
    var mavAutoPwrWpt = 0
}

class DtcBombProfile {
    var submode = 0
    var fuze = 0
    var sglPair = 0
    var releaseSpacing = 0
    var releasePulse = 0
    var releaseAngle = 0
    var c1Ad1 = 0f
    var c1Ad2 = 0f
    var c2Ad = 0f
    var c2Ba = 0
}

/** The .NET and VB runtime behaviour the cartridge's text goes through (the conversions are the DataCard port's). */
internal object DtcVb {
    fun isNumeric(s: String?) = DataCardNet.isNumeric(s)
    fun toDouble(s: String?) = DataCardNet.toDouble(s)
    fun toSingle(s: String?) = DataCardNet.toSingle(s)
    fun toInteger(s: String?) = DataCardNet.toInteger(s)
    fun toShort(s: String?) = DataCardNet.toShort(s)

    /** `Conversions.ToByte(String)`: rounded to even, and outside 0–255 an overflow. */
    fun toByte(s: String?): Int {
        if (s == null) return 0
        val r = DataCardNet.bankers(DataCardNet.toDouble(s))
        if (r.isNaN() || r < 0.0 || r > 255.0) throw ArithmeticException("Overflow")
        return r.toInt()
    }

    /** `Conversions.ToULong(String)`: `ParseDecimal`, rounded to even, 0 … 2^64-1 (kept in a Long's bits). */
    fun toULong(s: String?): Long {
        if (s == null) return 0L
        val d = PopupNet.toDecimal(s)
        val txt = d.toString()
        val neg = txt.startsWith("-")
        val body = txt.removePrefix("-")
        val dot = body.indexOf('.')
        var whole = if (dot < 0) body else body.substring(0, dot)
        val frac = if (dot < 0) "" else body.substring(dot + 1)
        val half = frac.isNotEmpty() && frac[0] == '5' && frac.drop(1).all { it == '0' }
        val above = frac.isNotEmpty() && (frac[0] > '5' || (frac[0] == '5' && !half))
        val odd = whole.isNotEmpty() && (whole.last() - '0') % 2 == 1
        if (above || (half && odd)) whole = incr(whole)
        whole = whole.trimStart('0').ifEmpty { "0" }
        if (neg && whole != "0") throw ArithmeticException("Overflow")
        if (whole.length > 20 || (whole.length == 20 && whole > "18446744073709551615")) throw ArithmeticException("Overflow")
        return whole.toULong().toLong()
    }

    private fun incr(d: String): String {
        val c = d.toCharArray()
        var i = c.size - 1
        while (i >= 0) { if (c[i] == '9') { c[i] = '0'; i-- } else { c[i] = c[i] + 1; return c.concatToString() } }
        return "1" + c.concatToString()
    }

    /** `Conversions.ToString(Single)` / `(Double)`. */
    fun str(v: Float) = DataCardNet.str(v)
    fun str(v: Double) = DataCardNet.str(v)
    /** `Strings.Format(Single, pattern)`. */
    fun fmt(v: Float, pattern: String) = DataCardNet.fmt(v, pattern)
    fun fmt(v: Double, pattern: String) = DataCardNet.fmt(v, pattern)
    fun fmt(v: Int, pattern: String) = DataCardNet.fmt(v, pattern)
    fun lcase(s: String?) = DataCardNet.lcase(s ?: "")

    /** VB `Strings.Trim`: spaces and ideographic spaces only; null is "". */
    fun trim(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        var a = 0
        var b = s.length
        while (a < b && (s[a] == ' ' || s[a] == '　')) a++
        while (b > a && (s[b - 1] == ' ' || s[b - 1] == '　')) b--
        return s.substring(a, b)
    }

    /** VB `Strings.Mid(s, start, length)`, 1-based. */
    fun mid(s: String, start: Int, length: Int): String {
        if (start > s.length || length <= 0) return ""
        val from = start - 1
        return s.substring(from, minOf(s.length, from + length))
    }

    // clsStringHelper
    fun firstComma(line: String?): Int {
        if (line == null) return -1
        if (line.isEmpty()) return -1
        return trim(line).indexOf(",") + 1
    }

    fun firstWordCsv(line0: String?): String {
        val line = trim(line0)
        val num = firstComma(line) - 1
        if (num < 1) return trim(line)
        return trim(mid(line, 1, num))
    }

    fun cutFirstWordCsv(line0: String): String {
        if (line0.length <= 1) return ""
        var line = line0.replace("\t", " ")
        line = trim(line)
        val num = firstComma(line)
        return if (num < 1) "" else trim(mid(line, num + 1, line.length))
    }
}

/** What a load leaves besides the model: WDP's `LoadError` bits (which parts threw) and whether it loaded at all. */
data class DtcLoadResult(val loaded: Boolean, val loadError: Int)

/**
 * `clsLoadDTC.LoadCallsign`: the cartridge's [text] (null: no such file) into [m]. [pptName] is `fclsMain.GetSAMName`
 * over the theater's `ppt.ini` (code → name; "S" is always "Search"), [coords] the theater for the lat/lon strings.
 */
object DtcLoad {
    var DEBUG = false
    private val vb = DtcVb

    fun loadCallsign(
        text: String?,
        m: DtcModel,
        pptTable: List<Pair<String, String>> = emptyList(),
        coords: PopupCoords.CoordData = PopupCoords.CoordData(),
    ): DtcLoadResult {
        if (text == null) return DtcLoadResult(false, -1)
        var err = 0
        fun part(bit: Int, f: () -> Unit) { try { f() } catch (e: Exception) { err = err or bit; if (DEBUG) println("part $bit: $e") } }
        part(1) { stpt(text, m, coords) }
        part(2) { ppt(text, m, pptTable, coords) }
        part(4) { lines(text, m, coords) }
        part(8) { wpnTarget(text, m, coords) }
        part(16) { open(text, m, coords) }
        part(32) { harpoon(text, m, coords) }
        part(64) { ews(text, m) }
        part(128) { mfd(text, m) }
        part(256) { bullseye(text, m) }
        part(512) { radio(text, m) }
        part(1024) { comm(text, m) }
        part(2048) { navOffsets(text, m) }
        part(4096) { hud(text, m) }
        part(8192) { icp(text, m) }
        part(16384) { iff(text, m) }
        part(32768) { view(text, m) }
        part(65536) { otw(text, m) }
        part(131072) { weapons(text, m) }
        part(262144) { harm(text, m) }
        part(524288) { laser(text, m) }
        part(1048576) { aim(text, m) }
        part(2097152) { agm(text, m) }
        part(4194304) { agb(text, m) }
        part(8388608) { snsr(text, m) }
        part(16777216) { light(text, m) }
        return DtcLoadResult(true, err)
    }

    private fun rd(text: String, sec: String, key: String) = DtcIni.read(text, sec, key)

    internal fun northEast(coords: PopupCoords.CoordData, feetN: Float, feetE: Float): Pair<String, String> {
        val c = PopupCoords.feetToCoordsBoth(coords, feetN.toDouble(), feetE.toDouble())
        return PopupCoords.getNorthDeg(c) to PopupCoords.getEastDeg(c)
    }

    /** `SAMName`: "S" is a search radar; otherwise the ppt.ini's name for the code, or "". */
    fun samName(code: String?, table: List<Pair<String, String>>): String {
        val c = code ?: ""
        if (c == "S") return "Search"
        for ((k, v) in table) if (c == k) return v
        return ""
    }

    /** Four or five comma fields, as `FirstWordCsv`/`CutFirstWordCsv` peel them. */
    private fun fields(v0: String, n: Int): Array<String> {
        var v = v0
        val out = Array(n) { "" }
        for (i in 0 until n) {
            out[i] = vb.firstWordCsv(v)
            if (i < n - 1) v = vb.cutFirstWordCsv(v)
        }
        return out
    }

    fun stpt(text: String, m: DtcModel, coords: PopupCoords.CoordData) {
        for (i in 0 until m.stpt.size) {
            val s = m.stpt[i]
            s.falconX = 0f; s.falconY = 0f; s.falconZ = 0f
            s.north = "00,00.000"; s.east = "000,00.000"; s.action = -1
            s.target = "Not set"
        }
        var name = ""
        for (n in 0..23) {
            var t2 = "0.000000"; var t3 = "0.000000"; var t4 = "0.000000"; var t5 = "-1"
            val v = rd(text, "STPT", "target_$n")
            if (v != "") {
                val f = fields(v, 5)
                t2 = f[0]; t3 = f[1]; t4 = f[2]; t5 = f[3]; name = f[4]
            }
            if (bothZero(t2, t3)) t5 = "-1"
            fillPoint(m.stpt[n], t2, t3, t4, t5)
            if (name == "-1") name = "Not Set"
            m.stpt[n].target = name
            val ne = northEast(coords, m.stpt[n].falconY, m.stpt[n].falconX)
            m.stpt[n].north = ne.first; m.stpt[n].east = ne.second
        }
    }

    /** `(Conversions.ToDouble(a) == 0.0) & (Conversions.ToDouble(b) == 0.0)` — both sides, as VB's `&` does. */
    private fun bothZero(a: String, b: String): Boolean {
        val x = vb.toDouble(a) == 0.0
        val y = vb.toDouble(b) == 0.0
        return x and y
    }

    private fun fillPoint(s: DtcStpt, t2: String, t3: String, t4: String, t5: String) {
        s.falconY = if (vb.isNumeric(t2)) vb.toSingle(t2) else 0f
        s.falconX = if (vb.isNumeric(t3)) vb.toSingle(t3) else 0f
        s.falconZ = if (vb.isNumeric(t4)) vb.toSingle(t4) else 0f
        s.action = if (vb.isNumeric(t5)) vb.toInteger(t5) else 0
    }

    fun ppt(text: String, m: DtcModel, table: List<Pair<String, String>>, coords: PopupCoords.CoordData) {
        for (i in 0..14) {
            val p = m.ppt[i]
            p.falconX = 0f; p.falconY = 0f; p.falconZ = 0f; p.falconRng = 0f; p.code = ""
        }
        for (n in 0..14) {
            var a = "0.000000"; var b = "0.000000"; var c = "0.000000"; var d = "0.000000"; var code = ""
            val v = rd(text, "STPT", "ppt_$n")
            if (v != "") {
                val f = fields(v, 5)
                a = f[0]; b = f[1]; c = f[2]; d = f[3]; code = f[4]
            }
            val p = m.ppt[n]
            p.falconY = if (vb.isNumeric(a)) vb.toSingle(a) else 0f
            p.falconX = if (vb.isNumeric(b)) vb.toSingle(b) else 0f
            p.falconZ = if (vb.isNumeric(c)) vb.toSingle(c) else 0f
            p.falconRng = if (vb.isNumeric(d)) vb.toSingle(d) else 0f
            p.code = code
            p.name = samName(code, table)
            val ne = northEast(coords, p.falconY, p.falconX)
            p.north = ne.first; p.east = ne.second
        }
    }

    fun lines(text: String, m: DtcModel, coords: PopupCoords.CoordData) {
        for (i in 0..23) {
            val l = m.line[i]
            l.falconX = 0f; l.falconY = 0f; l.falconZ = 0f; l.north = "00,00.000"; l.east = "000,00.000"
        }
        for (n in 0..23) {
            var a = "0.000000"; var b = "0.000000"; var c = "0.000000"
            val v = rd(text, "STPT", "lineSTPT_$n")
            if (v != "") {
                val f = fields(v, 3)
                a = f[0]; b = f[1]; c = f[2]
            }
            val l = m.line[n]
            l.falconY = if (vb.isNumeric(a)) vb.toSingle(a) else 0f
            l.falconX = if (vb.isNumeric(b)) vb.toSingle(b) else 0f
            l.falconZ = if (vb.isNumeric(c)) vb.toSingle(c) else 0f
            val ne = northEast(coords, l.falconY, l.falconX)
            l.north = ne.first; l.east = ne.second
        }
    }

    fun wpnTarget(text: String, m: DtcModel, coords: PopupCoords.CoordData) {
        for (i in 0..99) {
            val t = m.tgt[i]
            t.falconX = 0f; t.falconY = 0f; t.falconZ = 0f; t.north = "00,00.000"; t.east = "000,00.000"; t.action = -1; t.target = "Not set"
        }
        for (n in 0..99) {
            var t2 = "0.000000"; var t3 = "0.000000"; var t4 = "0.000000"; var t5 = "-1"
            var name = "Not set"
            val v = rd(text, "STPT", "wpntarget_$n")
            if (v != "") {
                val f = fields(v, 5)
                t2 = f[0]; t3 = f[1]; t4 = f[2]; t5 = f[3]; name = f[4]
            }
            val t = m.tgt[n]
            t.falconY = if (vb.isNumeric(t2)) vb.toSingle(t2) else 0f
            t.falconX = if (vb.isNumeric(t3)) vb.toSingle(t3) else 0f
            t.falconZ = if (vb.isNumeric(t4)) vb.toSingle(t4) else 0f
            t.action = if (vb.isNumeric(t5)) vb.toInteger(t5) else 0
            t.target = name
            val ne = northEast(coords, t.falconY, t.falconX)
            t.north = ne.first; t.east = ne.second
        }
    }

    /**
     * Open 1 (`target_80`…`88`, the page's STPT 81–89) and Open 2 (`target_89`…`98`, STPT 90–99): the steerpoint
     * reader over another table. WDP read Open 2 one key late and cleared the weapon targets' names instead of its
     * own; both are put right here, because the page now writes back what it reads and a round trip must not move
     * or rename anything.
     */
    private fun extraPoints(text: String, m: DtcModel, table: Array<DtcStpt>, first: Int, count: Int, coords: PopupCoords.CoordData) {
        for (i in table.indices) {
            val s = table[i]
            s.falconX = 0f; s.falconY = 0f; s.falconZ = 0f; s.north = "00,00.000"; s.east = "000,00.000"; s.action = -1
            s.target = "Not set"
        }
        var name = ""
        for (n in 0 until count) {
            var t2 = "0.000000"; var t3 = "0.000000"; var t4 = "0.000000"; var t5 = "-1"
            val v = rd(text, "STPT", "target_${n + first}")
            if (v != "") {
                val f = fields(v, 5)
                t2 = f[0]; t3 = f[1]; t4 = f[2]; t5 = f[3]; name = f[4]
            }
            if (bothZero(t2, t3)) t5 = "-1"
            fillPoint(table[n], t2, t3, t4, t5)
            if (name == "-1") name = "Not Set"
            table[n].target = name
            val ne = northEast(coords, table[n].falconY, table[n].falconX)
            table[n].north = ne.first; table[n].east = ne.second
        }
    }

    fun open(text: String, m: DtcModel, coords: PopupCoords.CoordData) = extraPoints(text, m, m.open, 80, 9, coords)
    fun harpoon(text: String, m: DtcModel, coords: PopupCoords.CoordData) = extraPoints(text, m, m.hpn, 89, 10, coords)

    private fun isTrue(s: String) = s == "1" || vb.lcase(s) == "true"

    /** `Array.Resize` over an array of structures: what it held (copied, as a value type is), new ones after. */
    private inline fun <reified T> resize(a: Array<T>?, n: Int, make: () -> T, copy: (T) -> T): Array<T> =
        Array(n) { i -> if (a != null && i < a.size) copy(a[i]) else make() }

    fun ews(text: String, m: DtcModel) {
        val e = m.ews
        e.reqjam = isTrue(rd(text, "EWS", "Reqjam"))
        e.reqctr = isTrue(rd(text, "EWS", "Reqctr"))
        e.bingo = isTrue(rd(text, "EWS", "Bingo"))
        e.fdbk = isTrue(rd(text, "EWS", "Fdbk"))
        var t = rd(text, "EWS", "Flare Bingo")
        e.flareBingo = if (vb.isNumeric(t)) { if (vb.toDouble(t) >= 0.0) vb.toByte(t) else 10 } else 10
        t = rd(text, "EWS", "Chaff Bingo")
        e.chaffBingo = if (vb.isNumeric(t)) { if (vb.toDouble(t) >= 0.0) vb.toByte(t) else 10 } else 10
        e.program = resize(e.program, 6, { DtcEwsProgram() }, { it.copy() })
        val pr = e.program!!
        fun num(key: String): Int {
            val s = rd(text, "EWS", key)
            return if (vb.isNumeric(s)) { if (vb.toDouble(s) >= 0.0) vb.toInteger(s) else 0 } else 0
        }
        for (n in 0..5) {
            pr[n].chaffBQ = num("PGM $n Chaff BQ")
            pr[n].chaffBI = num("PGM $n Chaff BI")
            pr[n].chaffSQ = num("PGM $n Chaff SQ")
            pr[n].chaffSI = num("PGM $n Chaff SI")
            pr[n].flareBQ = num("PGM $n Flare BQ")
            pr[n].flareBI = num("PGM $n Flare BI")
            pr[n].flareSQ = num("PGM $n Flare SQ")
            pr[n].flareSI = num("PGM $n Flare SI")
            pr[n].comment = rd(text, "EWS", "PGM $n Comment")
        }
        val t3 = rd(text, "EWS", "Mode Selection")
        val t4 = rd(text, "EWS", "Number Selection")
        m.cmdsIncl = t3 != "" || t4 != ""
        m.onIncl?.invoke(0, m.cmdsIncl)
        e.modeSelection = if (vb.isNumeric(t3)) vb.toInteger(t3) else 0
        e.numberSelection = if (vb.isNumeric(t4)) {
            val d = vb.toDouble(t4)
            if ((d >= 0.0) and (vb.toDouble(t4) <= 3.0)) vb.toInteger(t4) else 0
        } else 0
    }

    fun mfd(text: String, m: DtcModel) {
        val md = m.mfd
        md.aA = resize(md.aA, 4, { DtcMfd() }, { it.copy() })
        md.aG = resize(md.aG, 4, { DtcMfd() }, { it.copy() })
        md.nav = resize(md.nav, 4, { DtcMfd() }, { it.copy() })
        md.msl = resize(md.msl, 4, { DtcMfd() }, { it.copy() })
        md.dgf = resize(md.dgf, 4, { DtcMfd() }, { it.copy() })
        md.sJ = resize(md.sJ, 4, { DtcMfd() }, { it.copy() })
        for (n in 0..3) for (mode in 0..5) for (pos in 0..3) {
            val arr = when (mode) { 0 -> md.aG!!; 1 -> md.aA!!; 2 -> md.nav!!; 3 -> md.msl!!; 4 -> md.dgf!!; else -> md.sJ!! }
            val key = if (pos == 3) "Display$n-$mode-csel" else "Display$n-$mode-$pos"
            val s = rd(text, "MFD", key)
            val v = if (vb.isNumeric(s)) {
                val inRange = (vb.toDouble(s) > 0.0) and (vb.toDouble(s) < 255.0)
                if (inRange) vb.toByte(s)
                else if (mode == 0 && pos == 2) vb.toByte(s)   // A-G left: the "else" repeats the conversion
                else 0
            } else 0
            when (pos) { 0 -> arr[n].right = v; 1 -> arr[n].center = v; 2 -> arr[n].left = v; else -> arr[n].csel = v }
        }
    }

    fun bullseye(text: String, m: DtcModel) {
        m.bullseyeInfoOnMfd = isTrue(rd(text, "Bullseye", "BullseyeInfoOnMFD"))
    }

    val UHF_DEFAULT = intArrayOf(0, 297500, 381300, 275800, 294700, 279600, 349000, 377100, 292200, 264600, 286400,
        354400, 269100, 307300, 377200, 354000, 318100, 359300, 324500, 339100, 280500)
    val VHF_DEFAULT = intArrayOf(0, 138050, 138100, 138200, 126200, 134250, 133150, 132350, 126150, 132875, 132325,
        132575, 121200, 119500, 120100, 134100, 126800, 120000, 141800, 123700, 121700)

    /**
     * The defaults `GetCallsignRadio` puts in before reading, slot mistakes and all: the arrays are resized to 21
     * (keeping what they held), presets 1–20 set, and the comments set in WDP's order — UHF 1–4, then slot 1 again
     * with preset 5's text, 6–12, 13 as "UI", 14–20; VHF 1–6, slot 1 again with preset 7's, 8–20. UHF slot 5 and VHF
     * slot 7 are never assigned and keep whatever they held.
     */
    fun radioDefaults(m: DtcModel) {
        val r = m.radio
        r.uhf = r.uhf.let { a -> IntArray(21) { if (a != null && it < a.size) a[it] else 0 } }
        r.vhf = r.vhf.let { a -> IntArray(21) { if (a != null && it < a.size) a[it] else 0 } }
        r.uhfComment = r.uhfComment.let { a -> Array(21) { if (a != null && it < a.size) a[it] else null } }
        r.vhfComment = r.vhfComment.let { a -> Array(21) { if (a != null && it < a.size) a[it] else null } }
        for (i in 1..20) { r.uhf!![i] = UHF_DEFAULT[i]; r.vhf!![i] = VHF_DEFAULT[i] }
        val u = r.uhfComment!!
        for (i in 1..20) if (i != 5) u[i] = "UHF Preset $i"
        u[1] = "UHF Preset 5"
        u[13] = "UHF Preset UI"
        val v = r.vhfComment!!
        for (i in 1..20) if (i != 7) v[i] = "VHF_COMMENT_$i=VHF Preset $i"
        v[1] = "VHF_COMMENT_7=VHF Preset 7"
    }

    fun radio(text: String, m: DtcModel) {
        val r = m.radio
        radioDefaults(m)
        for (n in 1..20) {
            val s = vb.trim(rd(text, "Radio", "UHF_$n"))
            if (vb.isNumeric(s)) r.uhf!![n] = vb.toInteger(s)
            else vb.toInteger("Loading Callsign.ini")   // the message box's arguments: throws
        }
        for (n in 1..20) {
            val s = vb.trim(rd(text, "Radio", "VHF_$n"))
            if (vb.isNumeric(s)) r.vhf!![n] = vb.toInteger(s)
            else vb.toInteger("Loading Callsign.ini")
        }
        for (n in 1..20) r.uhfComment!![n] = rd(text, "Radio", "UHF_COMMENT_$n")
        for (n in 1..20) r.vhfComment!![n] = rd(text, "Radio", "VHF_COMMENT_$n")
    }

    fun comm(text: String, m: DtcModel) {
        val c = m.comm
        var t = rd(text, "COMMS", "Comm1")
        c.comm1 = if (vb.isNumeric(t)) { if ((vb.toDouble(t) > 0.0) and (vb.toDouble(t) < 20.0)) vb.toInteger(t) else 1 } else 1
        t = rd(text, "COMMS", "Comm2")
        c.comm2 = if (vb.isNumeric(t)) { if ((vb.toDouble(t) > 0.0) and (vb.toDouble(t) < 20.0)) vb.toInteger(t) else 1 } else 1
        c.comm1Comment = rd(text, "COMMS", "Comm1_Comment")
        c.comm2Comment = rd(text, "COMMS", "Comm2_Comment")
        t = rd(text, "COMMS", "TACAN Channel")
        c.tacanChannel = if (vb.isNumeric(t)) vb.toInteger(t) else 94
        t = rd(text, "COMMS", "TACAN Band")
        c.tacanBand = if (vb.isNumeric(t)) vb.toInteger(t) else 0
        t = rd(text, "COMMS", "TACAN Domain")
        c.tacanDomain = if (vb.isNumeric(t)) vb.toInteger(t) else 0
        ilsPresets(text, m)
        // WDP raised anything under 109.00 to 109.00; 4.38.1 has localizers from 108.10 (see DtcModel's notes)
        t = rd(text, "COMMS", "ILS Frequency")
        val preset = m.radio.ils[1].takeIf { it in ILS_MIN..ILS_MAX }
        c.ilsFrequency = if (vb.isNumeric(t)) ilsInBand(vb.toInteger(t)) else preset ?: 10900
        t = rd(text, "COMMS", "ILS CRS")
        c.ilsCrs = if (vb.isNumeric(t)) vb.toInteger(t) else 0
    }

    /** 4.38.1's lowest and highest ILS frequency, as the cartridge writes them (hundredths of a MHz). */
    const val ILS_MIN = 10810
    const val ILS_MAX = 11195

    /** An ILS frequency kept inside 108.10–111.95: as it is inside the band, its nearer edge outside it. */
    fun ilsInBand(v: Int): Int = v.coerceIn(ILS_MIN, ILS_MAX)

    /** `[Radio] ILS_1`…`ILS_4` and their comments; a preset that is missing or not a number reads as 0. */
    fun ilsPresets(text: String, m: DtcModel) {
        for (n in 1..4) {
            val s = vb.trim(rd(text, "Radio", "ILS_$n"))
            m.radio.ils[n] = if (vb.isNumeric(s)) vb.toInteger(s) else 0
            m.radio.ilsComment[n] = rd(text, "Radio", "ILS_COMMENT_$n").ifEmpty { null }
        }
    }

    private fun offset(o: DtcOffset, stpt: String, bearing: String, range: String, elv: String) {
        o.stpt = if (vb.isNumeric(stpt)) vb.toInteger(stpt) else 0
        o.bearing = if (vb.isNumeric(bearing)) vb.toSingle(bearing) else 0f
        o.range = if (vb.isNumeric(range)) vb.toInteger(range) else 0
        o.elv = if (vb.isNumeric(elv)) vb.toInteger(elv) else 0
    }

    fun navOffsets(text: String, m: DtcModel) {
        val nv = m.nav
        val ms = rd(text, "NAV OFFSETS", "Modesel")
        nv.modesel = when {
            vb.lcase(ms) == "none" -> 0
            vb.lcase(ms) == "vip" -> 1
            vb.lcase(ms) == "vrp" -> 2
            // a number is one more than the page's, as BMS has written it since 4.36 build 25688
            vb.isNumeric(ms) -> if ((vb.toDouble(ms) >= 0.0) and (vb.toDouble(ms) < 5.0)) {
                DataCardNet.roundInt(vb.toDouble(ms) - 1.0)
            } else 0
            else -> 0
        }
        // VIP: the four fields start empty (not "0"), so a missing key reads as all zero through IsNumeric
        var f = arrayOf("", "", "", "")
        fun four(key: String): Array<String> {
            val v = rd(text, "NAV OFFSETS", key)
            return if (v != "") fields(v, 4) else f
        }
        f = four("VIP")
        offset(nv.vip, f[0], f[1], f[2], f[3])
        f = arrayOf("0", "0", "0", "0")
        offset(nv.vipPup, four("VIPPUP"))
        f = arrayOf("0", "0", "0", "0")
        offset(nv.vrp, four("VRP"))
        f = arrayOf("0", "0", "0", "0")
        offset(nv.vrpPup, four("VRPPUP"))
        var first = 3
        // D90: steerpoints 24 and 25 too (WDP looked at 0-23 only, so a pair there was never read back, nor deleted)
        for (i in 0..25) {
            if (rd(text, "NAV OFFSETS", "OA1-$i") != "") { first = i; break }
        }
        // WDP took the second pair from the steerpoint after the first (first + 1). The offsets are keyed by their
        // steerpoint, and an attack page's Save to DTC puts its reference's pair on the target's steerpoint while the
        // other reference's pair stays where it was (D3), so the second pair is the next steerpoint that has one; only
        // with none after it is it first + 1, as WDP read it. (WDP read OA1-4 as empty after a pair went to OA1-6, and
        // the NAV OFFSETS tab showed steerpoint 4 and zeros where the file held what had just been saved.)
        var second = (first + 1..25).firstOrNull { rd(text, "NAV OFFSETS", "OA1-$it") != "" } ?: (first + 1)
        // D90: the pairs by the steerpoint they hang on, not by order — the VIP's pair (OA*_1) is the one on the VIP
        // line's steerpoint, the VRP's (OA*_2) the one on the VRP line's (Dash-34 p.426: offsets are always off the
        // steerpoint). By order, an IP after the target swapped them. Order decides only where neither names one.
        fun has(n: Int) = n in 0..25 && (rd(text, "NAV OFFSETS", "OA1-$n") != "" || rd(text, "NAV OFFSETS", "OA2-$n") != "")
        val vipS = nv.vip.stpt.takeIf { it >= 1 && has(it) }
        val vrpS = nv.vrp.stpt.takeIf { it >= 1 && has(it) && it != vipS }
        if (vipS != null || vrpS != null) {
            val other = (0..25).firstOrNull { has(it) && it != vipS && it != vrpS }
            first = vipS ?: other ?: (if (vrpS != 3) 3 else 4)
            second = vrpS ?: other?.takeIf { it != first } ?: (first + 1)
        }
        fun oa(o: DtcOffset, key: String, stpt: Int) {
            var b = "0"; var r = "0"; var e = "0"
            val v = rd(text, "NAV OFFSETS", key)
            if (v != "") { val g = fields(v, 3); b = g[0]; r = g[1]; e = g[2] }
            offset(o, stpt.toString(), b, r, e)
        }
        oa(nv.oa1_1, "OA1-$first", first)
        oa(nv.oa2_1, "OA2-$first", first)
        oa(nv.oa1_2, "OA1-$second", second)
        oa(nv.oa2_2, "OA2-$second", second)
    }

    private fun offset(o: DtcOffset, f: Array<String>) = offset(o, f[0], f[1], f[2], f[3])

    fun hud(text: String, m: DtcModel) {
        val a = rd(text, "Hud", "Scales")
        val b = rd(text, "Hud", "Brightness")
        val c = rd(text, "Hud", "FPM")
        val d = rd(text, "Hud", "DED")
        val e = rd(text, "Hud", "Velocity")
        val f = rd(text, "Hud", "Alt")
        val g = rd(text, "Hud", "SymWheelPos")
        m.hudIncl = a != "" || b != "" || c != "" || d != "" || e != "" || f != ""
        m.onIncl?.invoke(1, m.hudIncl)
        val h = m.hud
        h.scales = if (vb.isNumeric(a)) vb.toInteger(a) else 1
        h.brightness = if (vb.isNumeric(b)) vb.toInteger(b) else 1
        h.fpm = if (vb.isNumeric(c)) vb.toInteger(c) else 0
        h.ded = if (vb.isNumeric(d)) vb.toInteger(d) else 0
        h.velocity = if (vb.isNumeric(e)) vb.toInteger(e) else 1
        h.alt = if (vb.isNumeric(f)) vb.toInteger(f) else 1
        h.symWheelPos = if (vb.isNumeric(g)) vb.toInteger(g) else 1000
    }

    fun icp(text: String, m: DtcModel) {
        val i = m.icp
        var t = rd(text, "ICP", "MasterMode")
        i.masterMode = if (vb.isNumeric(t)) vb.toInteger(t) else 1
        t = rd(text, "ICP", "Alow AGL")
        i.alowAgl = if (vb.isNumeric(t)) vb.toSingle(t) else 300f
        t = rd(text, "ICP", "Alow MSL")
        i.alowMsl = if (vb.isNumeric(t)) vb.toInteger(t) else 10000
        t = rd(text, "ICP", "Alow TFAdv")
        i.alowTfAdv = if (vb.isNumeric(t)) vb.toInteger(t) else 400
        t = rd(text, "ICP", "Manual Wingspan")
        i.manualWingspan = if (vb.isNumeric(t)) vb.toSingle(t) else 35f
        t = rd(text, "ICP", "Bingo_Fuel")
        i.bingoFuel = if (vb.isNumeric(t)) vb.toSingle(t) else 1500f
    }

    fun iff(text: String, m: DtcModel) {
        val f = m.iff
        f.timeSettings = Array(m.iffTimeEvents + 1) { DtcIffTime() }
        f.posSettings = Array(m.iffPosEvents + 1) { DtcIffPos() }
        fun s(key: String) = vb.trim(rd(text, "IFF", key))
        var t = s("Mode1 On"); f.mode1On = if (vb.isNumeric(t)) vb.toByte(t) else 0
        t = s("Mode2 On"); f.mode2On = if (vb.isNumeric(t)) vb.toByte(t) else 0
        t = s("Mode3A On"); f.mode3aOn = if (vb.isNumeric(t)) vb.toByte(t) else 0
        t = s("Mode4 On"); f.mode4On = if (vb.isNumeric(t)) vb.toByte(t) else 0
        t = s("ModeC On"); f.modeCOn = if (vb.isNumeric(t)) vb.toByte(t) else 0
        t = s("ModeS On"); f.modeSOn = if (vb.isNumeric(t)) vb.toByte(t) else 0
        t = s("Mode1 Code"); f.mode1Code = if (vb.isNumeric(t)) vb.toShort(t) else 0
        t = s("Mode2 Code"); f.mode2Code = if (vb.isNumeric(t)) vb.toShort(t) else 0
        t = s("Mode3A Code"); f.mode3aCode = if (vb.isNumeric(t)) vb.toShort(t) else 1200
        t = s("Mode4 Key"); f.mode4Key = if (vb.isNumeric(t)) vb.toShort(t) else -1
        t = s("AutoChange"); f.autoChange = if (vb.isNumeric(t)) vb.toByte(t) else 0
        val ts = f.timeSettings!!
        for (i in 0 until m.iffTimeEvents) {
            t = s("TIME $i Mode1 Code"); ts[i].mode1Code = if (vb.isNumeric(t)) vb.toByte(t) else 0
            t = s("TIME $i Mode3A Code"); ts[i].mode3aCode = if (vb.isNumeric(t)) vb.toShort(t) else 0
            t = s("TIME $i Mode4 Key"); ts[i].mode4Key = if (vb.isNumeric(t)) vb.toInteger(t) else 0
            // -1 is BMS's "not used" (IFF_Def.ini writes it for every event); WDP's ToULong threw on it
            t = s("TIME $i Criteria")
            ts[i].timeCriteria = if (vb.isNumeric(t)) { if (vb.toDouble(t) < 0.0) vb.toInteger(t).toLong() else vb.toULong(t) } else 0L
        }
        val ps = f.posSettings!!
        for (j in 0 until m.iffPosEvents) {
            t = s("POS $j Mode1"); ps[j].mode1 = if (vb.isNumeric(t)) vb.toShort(t) else 0
            t = s("POS $j Mode2"); ps[j].mode2 = if (vb.isNumeric(t)) vb.toShort(t) else 0
            t = s("POS $j Mode3A"); ps[j].mode3a = if (vb.isNumeric(t)) vb.toShort(t) else 0
            t = s("POS $j Mode4"); ps[j].mode4 = if (vb.isNumeric(t)) vb.toShort(t) else 0
            t = s("POS $j ModeC"); ps[j].modeC = if (vb.isNumeric(t)) vb.toShort(t) else 0
            t = s("POS $j ModeS"); ps[j].modeS = if (vb.isNumeric(t)) vb.toShort(t) else 0
            t = s("POS $j Direction"); ps[j].direction = if (vb.isNumeric(t)) vb.toByte(t) else 0
            t = s("POS $j WayPoint"); ps[j].wayPoint = if (vb.isNumeric(t)) vb.toInteger(t) else 0
        }
    }

    fun view(text: String, m: DtcModel) {
        val t = rd(text, "Cockpit View", "WideView")
        m.viewsIncl = t != ""
        m.onIncl?.invoke(2, m.viewsIncl)
        m.wideView = if (vb.isNumeric(t)) vb.toInteger(t) else 0
    }

    fun otw(text: String, m: DtcModel) {
        val t = rd(text, "OTW", "Mode")
        m.viewsIncl = t != ""
        m.onIncl?.invoke(2, m.viewsIncl)
        m.otwMode = if (vb.isNumeric(t)) vb.toInteger(t) else 0
    }

    fun weapons(text: String, m: DtcModel) {
        val t = rd(text, "Weapons", "MasterArm")
        m.masterArmIncl = t != ""
        m.onIncl?.invoke(3, m.masterArmIncl)
        m.masterArm = if (vb.isNumeric(t)) vb.toInteger(t) else 0
    }

    fun harm(text: String, m: DtcModel) {
        val h = m.harm
        h.table = resize(h.table, 5, { DtcHarmThreats() }, { it.copy() })
        val tb = h.table!!
        for (n in 0..2) {
            for (k in 0..4) {
                val t = rd(text, "HARM", "THREAT $n $k")
                if (vb.isNumeric(t)) {
                    val v = vb.toInteger(t)
                    when (k) { 0 -> tb[n].threat0 = v; 1 -> tb[n].threat1 = v; 2 -> tb[n].threat2 = v; 3 -> tb[n].threat3 = v; else -> tb[n].threat4 = v }
                }
            }
        }
        var t = rd(text, "HARM", "MODE"); if (vb.isNumeric(t)) h.mode = vb.toInteger(t)
        t = rd(text, "HARM", "SUBMODE"); if (vb.isNumeric(t)) h.subMode = vb.toInteger(t)
        t = rd(text, "HARM", "TER"); if (vb.isNumeric(t)) h.ter = vb.toInteger(t)
    }

    fun laser(text: String, m: DtcModel) {
        val l = m.laser
        var t = vb.trim(rd(text, "Laser", "LaserST")); l.laserSt = if (vb.isNumeric(t)) vb.toInteger(t) else 8
        t = vb.trim(rd(text, "Laser", "LaserTGP")); l.laserCode = if (vb.isNumeric(t)) vb.toShort(t) else 1688
        t = vb.trim(rd(text, "Laser", "LaserLST")); l.lstCode = if (vb.isNumeric(t)) vb.toShort(t) else 1688
    }

    fun aim(text: String, m: DtcModel) {
        var t = rd(text, "FCC_AIM", "AIM-9_Spot/Scan"); m.aim.spotScan = if (vb.isNumeric(t)) vb.toInteger(t) else 0
        t = rd(text, "FCC_AIM", "AIM-9_TD/BP"); m.aim.tdBp = if (vb.isNumeric(t)) vb.toInteger(t) else 0
        t = rd(text, "FCC_AIM", "AIM120_TargetSize"); m.aim.targetSize = if (vb.isNumeric(t)) vb.toInteger(t) else 0
    }

    fun agm(text: String, m: DtcModel) {
        var t = rd(text, "FCC_AGM", "Maverick_AutoPwr"); m.agm.mavAutoPwr = if (vb.isNumeric(t)) vb.toInteger(t) else 0
        t = rd(text, "FCC_AGM", "Maverick_AutoPwrDir"); m.agm.mavAutoPwrDir = if (vb.isNumeric(t)) vb.toInteger(t) else 1
        t = rd(text, "FCC_AGM", "Maverick_AutoPwrWpt"); m.agm.mavAutoPwrWpt = if (vb.isNumeric(t)) vb.toInteger(t) else 1
    }

    fun agb(text: String, m: DtcModel) {
        fun profile(p: DtcBombProfile, n: Int, submodeDefault: Int) {
            fun r(k: String) = rd(text, "FCC_AGB", "Profile${n}_$k")
            var t = r("Submode"); p.submode = if (vb.isNumeric(t)) vb.toInteger(t) else submodeDefault
            t = r("Fuze"); p.fuze = if (vb.isNumeric(t)) vb.toInteger(t) else 1
            t = r("SGL/PAIR"); p.sglPair = if (vb.isNumeric(t)) vb.toInteger(t) else 0
            t = r("Release_Spacing"); p.releaseSpacing = if (vb.isNumeric(t)) vb.toInteger(t) else 175
            t = r("Release_Pulse"); p.releasePulse = if (vb.isNumeric(t)) DataCardNet.roundInt(vb.toDouble(t) + 1.0) else 0
            t = r("Release_Angle"); p.releaseAngle = if (vb.isNumeric(t)) vb.toInteger(t) else 45
            t = r("C1_AD1"); p.c1Ad1 = if (vb.isNumeric(t)) vb.toSingle(t) else 400f
            t = r("C1_AD2"); p.c1Ad2 = if (vb.isNumeric(t)) vb.toSingle(t) else 600f
            t = r("C2_AD"); p.c2Ad = if (vb.isNumeric(t)) vb.toSingle(t) else 150f
            t = r("C2_BA"); p.c2Ba = if (vb.isNumeric(t)) vb.toInteger(t) else 500
        }
        profile(m.agb1, 1, 8)
        profile(m.agb2, 2, 7)
    }

    fun snsr(text: String, m: DtcModel) {
        val t = rd(text, "SNSR_PWR", "RALT")
        m.snsrPowerIncl = t != ""
        m.onIncl?.invoke(4, m.snsrPowerIncl)
        m.ralt = if (vb.isNumeric(t)) vb.toInteger(t) else 0
    }

    fun light(text: String, m: DtcModel) {
        val t = rd(text, "INT_LIGHTING", "DED")
        m.intLghtIncl = t != ""
        m.onIncl?.invoke(5, m.intLghtIncl)
        m.dedLight = if (vb.isNumeric(t)) vb.toInteger(t) else 0
    }
}

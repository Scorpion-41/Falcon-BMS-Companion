package com.bmscompanion.app.data.wdp

import kotlin.math.abs

/** What a save leaves: the file's new text, and whether WDP stopped half way (an exception it does not catch). */
data class DtcSaveResult(val text: String, val threw: Boolean)

/**
 * `fclsMain.SaveCallsign_DTC`'s twenty-five `clsSaveDTC` calls, in its order, over the cartridge's current text.
 * Falcas's code, ported.
 *
 * Every key goes through [DtcIni] one at a time, as WDP writes them, so a key the model does not know survives and a
 * new key lands where Windows would put it. A null string (an EWS or radio comment that was never read) deletes its
 * key, as passing `Nothing` to `WritePrivateProfileString` does.
 *
 * `SaveCallsign_HARM` first runs the page's `ApplyHarm` (the HARM radio buttons into the model): `applyHarm`.
 * An exception WDP would not catch (a table never sized, a checked subtraction that overflows) ends the save there:
 * the text written so far is what the file holds, and [DtcSaveResult.threw] says so.
 *
 * Kept as WDP writes it: every altitude is written negative (`Math.Abs(z) * -1f`) except the PPTs' and the lines';
 * the PPT range goes through `Conversions.ToString`; `Hud`'s DED is written under the interior-lighting box, not the
 * HUD box; and the Open 2 table goes to `target_89`–`target_98`, where the reader now takes it from too.
 *
 * Put right, because each lost or changed the pilot's data (the plan's D-numbers; the DTC comparison with WDP lists
 * them as expected differences):
 * - **The steerpoint lines keep the name BMS gave them** (WDP wrote four fields and dropped it), as the weapon-target,
 *   Open and Harpoon lines do.
 * - **Positions are written from the value held, not rounded to seven digits** (D17). WDP formatted a Single with
 *   .NET's seven significant digits, so a steerpoint BMS wrote as `2074151.750000` came back as `2074152.000000` and
 *   moved a quarter of a foot on every save; the port formats the float's exact value, which is what BMS's `%f` does.
 * - **IFF codes keep their leading zeros** (D13): Mode 1 codes are written with two digits and Mode 2 / 3A codes with
 *   four, as BMS writes them (`Mode3A Code=0554`, `TIME 11 Mode1 Code=03`; its own `IFF_Def.ini`: `Mode1 Code=00`,
 *   `Mode2 Code=0000`). WDP wrote the number, so 0554 became 554 — another code. A time event's criteria is BMS's
 *   HHMM with four digits (`0500`) and -1 for "not used", which WDP wrote as 18446744073709551615.
 * - **The MFD pages are written key by key**, like every other part (D15). WDP deleted modes 0–4 until none was left
 *   and wrote `Display0-0-0=0` before the real values, which only reordered the section.
 * - **An offset aim point on steerpoint 0 is not written.** The attack pages hand over the offsets they set and leave
 *   the others at steerpoint 0; WDP wrote those as `OA1-0=0,0,0` / `OA2-0=0,0,0`, keys for a steerpoint that is not
 *   there.
 * - **No version branches**: WDP wrote the Open tables, IFF, HARM and the S-J MFD pages only for the BMS versions that
 *   had them, and `Modesel` as a number before 4.36 build 25688; the Planner writes 4.38.1's cartridge.
 *
 * The page does not write this text into the file whole: [DtcEdits] compares it with what the same save makes of
 * the cartridge as it was loaded, and only the keys the pilot's edits changed are written.
 */
object DtcSave {
    private val vb = DtcVb

    private class Ini(var text: String) {
        fun w(sec: String, key: String, v: String?) { text = if (v == null) DtcIni.deleteKey(text, sec, key) else DtcIni.write(text, sec, key, v) }
        fun dk(sec: String, key: String) { text = DtcIni.deleteKey(text, sec, key) }
        fun ds(sec: String) { text = DtcIni.deleteSection(text, sec) }
        fun r(sec: String, key: String) = DtcIni.read(text, sec, key)
    }

    fun saveCallsign(text: String?, m: DtcModel, applyHarm: (DtcModel) -> Unit = {}): DtcSaveResult {
        val f = Ini(text ?: "")
        try {
            stpt(f, m); ppt(f, m); line(f, m); wpnTarget(f, m); openStpt(f, m); harpoonStpt(f, m); ews(f, m); mfd(f, m)
            bullseye(f, m); radio(f, m); comm(f, m); navOffsets(f, m); hud(f, m); icp(f, m); iff(f, m); view(f, m)
            otw(f, m); weapons(f, m); harm(f, m, applyHarm); laser(f, m); aim(f, m); agm(f, m); agb(f, m); snsrPwr(f, m); intLight(f, m)
        } catch (e: Exception) {
            return DtcSaveResult(f.text, true)
        }
        return DtcSaveResult(f.text, false)
    }

    /**
     * A float as BMS's `%f` prints it: its exact value to six places (D17: WDP's seven digits moved positions). An
     * exact half goes to the even digit, as `%f` does (130944.6640625 → "130944.664062"), where VB's Format rounds it
     * away from zero. A float times 10^6 is exact in a double (24 + 14 significant bits), so a half is seen exactly.
     */
    internal fun f6(v: Float): String {
        val scaled = v.toDouble() * 1_000_000.0
        val a = kotlin.math.abs(scaled)
        val low = kotlin.math.floor(a)
        if (a - low != 0.5 || a >= 9.0e15) return vb.fmt(v.toDouble(), "#0.000000")
        val r = low.toLong().let { if (it % 2L == 0L) it else it + 1L }
        return (if (v < 0f) "-" else "") + (r / 1_000_000L).toString() + "." + (r % 1_000_000L).toString().padStart(6, '0')
    }
    private fun z0(s: String) = if (s == "0") "0.000000" else s
    private fun negAbs(v: Float) = abs(v) * -1f

    private fun stpt(f: Ini, m: DtcModel) {
        for (n in 0..23) {
            val s = m.stpt[n]
            val a = z0(f6(s.falconX)); val b = z0(f6(s.falconY)); val c = z0(f6(negAbs(s.falconZ)))
            val d = s.action.toString().ifEmpty { "0" }
            // BMS names its steerpoints ("..., -1, Pulandian Airbase Radio Tower"); WDP wrote four fields and lost
            // the name, which the page would now do to every steerpoint the pilot moves, so the name goes back
            val name = s.target?.trim().orEmpty()
            f.w("STPT", "target_$n", if (name.isEmpty()) "$b, $a, $c, $d" else "$b, $a, $c, $d, $name")
        }
    }

    private fun ppt(f: Ini, m: DtcModel) {
        for (n in 0..14) {
            val p = m.ppt[n]
            val a = z0(f6(p.falconX)); val b = z0(f6(p.falconY)); val c = z0(f6(p.falconZ))
            val d = z0(vb.str(p.falconRng))
            val code = vb.trim(p.code)
            f.w("STPT", "ppt_$n", if (code.isNotEmpty()) "$b, $a, $c, $d, $code" else "$b, $a, $c, $d,")
        }
    }

    private fun line(f: Ini, m: DtcModel) {
        for (n in 0..23) {
            val l = m.line[n]
            val a = z0(f6(l.falconX)); val b = z0(f6(l.falconY)); val c = z0(f6(l.falconZ))
            f.w("STPT", "lineSTPT_$n", "$b, $a, $c")
        }
    }

    private fun wpnTarget(f: Ini, m: DtcModel) {
        for (n in 0..99) {
            val t = m.tgt[n]
            val a = z0(f6(t.falconX)); val b = z0(f6(t.falconY)); val c = z0(f6(negAbs(t.falconZ)))
            f.w("STPT", "wpntarget_$n", "$b, $a, $c, ${t.action}, ${t.target ?: ""}")
        }
    }

    private fun points(f: Ini, table: Array<DtcStpt>, first: Int, count: Int) {
        for (n in 0 until count) {
            val s = table[n]
            val a = z0(f6(s.falconX)); val b = z0(f6(s.falconY)); val c = z0(f6(negAbs(s.falconZ)))
            f.w("STPT", "target_${n + first}", "$b, $a, $c, ${s.action}, ${s.target ?: ""}")
        }
    }

    private fun openStpt(f: Ini, m: DtcModel) = points(f, m.open, 80, 9)
    private fun harpoonStpt(f: Ini, m: DtcModel) = points(f, m.hpn, 89, 10)

    private fun b(v: Boolean) = if (v) "1" else "0"

    private fun ews(f: Ini, m: DtcModel) {
        val e = m.ews
        f.w("EWS", "Reqjam", b(e.reqjam)); f.w("EWS", "Reqctr", b(e.reqctr)); f.w("EWS", "Bingo", b(e.bingo)); f.w("EWS", "Fdbk", b(e.fdbk))
        f.w("EWS", "Flare Bingo", e.flareBingo.toString()); f.w("EWS", "Chaff Bingo", e.chaffBingo.toString())
        val pr = e.program ?: throw NullPointerException("CampEWS.Program")
        for (n in 0..5) {
            val p = pr[n]
            f.w("EWS", "PGM $n Chaff BQ", p.chaffBQ.toString()); f.w("EWS", "PGM $n Chaff BI", p.chaffBI.toString())
            f.w("EWS", "PGM $n Chaff SQ", p.chaffSQ.toString()); f.w("EWS", "PGM $n Chaff SI", p.chaffSI.toString())
            f.w("EWS", "PGM $n Flare BQ", p.flareBQ.toString()); f.w("EWS", "PGM $n Flare BI", p.flareBI.toString())
            f.w("EWS", "PGM $n Flare SQ", p.flareSQ.toString()); f.w("EWS", "PGM $n Flare SI", p.flareSI.toString())
            f.w("EWS", "PGM $n Comment", p.comment)
        }
        if (m.cmdsIncl) {
            f.w("EWS", "Mode Selection", e.modeSelection.toString()); f.w("EWS", "Number Selection", e.numberSelection.toString())
        } else {
            f.dk("EWS", "Mode Selection"); f.dk("EWS", "Number Selection")
        }
    }

    private fun mfd(f: Ini, m: DtcModel) {
        val md = m.mfd
        for (n in 0..3) for (mode in 0..5) {
            val arr = when (mode) { 0 -> md.aG; 1 -> md.aA; 2 -> md.nav; 3 -> md.msl; 4 -> md.dgf; else -> md.sJ } ?: throw NullPointerException("CampMFD")
            val d = arr[n]
            f.w("MFD", "Display$n-$mode-0", d.right.toString())
            f.w("MFD", "Display$n-$mode-1", d.center.toString())
            f.w("MFD", "Display$n-$mode-2", d.left.toString())
            f.w("MFD", "Display$n-$mode-csel", d.csel.toString())
        }
    }

    private fun bullseye(f: Ini, m: DtcModel) = f.w("Bullseye", "BullseyeInfoOnMFD", b(m.bullseyeInfoOnMfd))

    private fun radio(f: Ini, m: DtcModel) {
        val r = m.radio
        val u = r.uhf ?: throw NullPointerException("UHF")
        for (n in 1..20) f.w("Radio", "UHF_$n", u[n].toString())
        val v = r.vhf ?: throw NullPointerException("VHF")
        for (n in 1..20) f.w("Radio", "VHF_$n", v[n].toString())
        val uc = r.uhfComment ?: throw NullPointerException("UHFcomment")
        for (n in 1..20) f.w("Radio", "UHF_COMMENT_$n", uc[n])
        val vc = r.vhfComment ?: throw NullPointerException("VHFcomment")
        for (n in 1..20) f.w("Radio", "VHF_COMMENT_$n", vc[n])
    }

    private fun comm(f: Ini, m: DtcModel) {
        val c = m.comm
        f.w("COMMS", "Comm1", c.comm1.toString()); f.w("COMMS", "Comm2", c.comm2.toString())
        f.w("COMMS", "Comm1_Comment", c.comm1Comment); f.w("COMMS", "Comm2_Comment", c.comm2Comment)
        f.w("COMMS", "TACAN Channel", c.tacanChannel.toString()); f.w("COMMS", "TACAN Band", c.tacanBand.toString())
        f.w("COMMS", "TACAN Domain", c.tacanDomain.toString()); f.w("COMMS", "ILS Frequency", c.ilsFrequency.toString())
        f.w("COMMS", "ILS CRS", c.ilsCrs.toString())
    }

    private fun bearing(o: DtcOffset): String { val t = vb.fmt(o.bearing, "#0.0"); return if (t == "0.0") "0" else t }

    private fun navOffsets(f: Ini, m: DtcModel) {
        val nv = m.nav
        val ms = when (nv.modesel) { 0 -> "none"; 1 -> "vip"; 2 -> "vrp"; else -> "none" }
        f.w("NAV OFFSETS", "Modesel", ms)
        for ((key, o) in listOf("VIP" to nv.vip, "VIPPUP" to nv.vipPup, "VRP" to nv.vrp, "VRPPUP" to nv.vrpPup)) {
            f.w("NAV OFFSETS", key, "${o.stpt},${bearing(o)},${o.range},${o.elv}")
        }
        for ((prefix, o) in listOf("OA1-" to nv.oa1_1, "OA2-" to nv.oa2_1, "OA1-" to nv.oa1_2, "OA2-" to nv.oa2_2)) {
            // an offset left on steerpoint 0 is one nobody set: there is no steerpoint 0 to offset from
            if (o.stpt >= 1) f.w("NAV OFFSETS", prefix + o.stpt, "${bearing(o)},${o.range},${o.elv}")
        }
        // D90: 24 and 25 too (WDP's loop stopped at 23, so a pair left there stayed in the file for ever)
        for (n in 0..25) {
            if (!((n == nv.oa1_1.stpt) or (n == nv.oa1_2.stpt))) {
                f.dk("NAV OFFSETS", "OA1-$n")
                f.dk("NAV OFFSETS", "OA2-$n")
            }
        }
    }

    private fun hud(f: Ini, m: DtcModel) {
        val h = m.hud
        if (m.hudIncl) {
            f.w("Hud", "Scales", h.scales.toString()); f.w("Hud", "Brightness", h.brightness.toString())
            f.w("Hud", "FPM", h.fpm.toString()); f.w("Hud", "Velocity", h.velocity.toString())
            f.w("Hud", "Alt", h.alt.toString()); f.w("Hud", "SymWheelPos", h.symWheelPos.toString())
        } else {
            f.ds("Hud")
        }
        if (m.intLghtIncl) f.w("Hud", "DED", h.ded.toString()) else f.dk("Hud", "DED")
    }

    private fun icp(f: Ini, m: DtcModel) {
        val i = m.icp
        f.w("ICP", "MasterMode", i.masterMode.toString())
        f.w("ICP", "Alow AGL", f6(i.alowAgl))
        f.w("ICP", "Alow MSL", i.alowMsl.toString())
        f.w("ICP", "Alow TFAdv", i.alowTfAdv.toString())
        f.w("ICP", "Manual Wingspan", f6(i.manualWingspan))
        f.w("ICP", "Bingo_Fuel", f6(i.bingoFuel))
    }

    /** An IFF code as BMS writes it: [width] digits, leading zeros kept (D13); a negative number as it is. */
    internal fun code(v: Int, width: Int): String = if (v < 0) v.toString() else v.toString().padStart(width, '0')

    private fun iff(f: Ini, m: DtcModel) {
        val x = m.iff
        f.w("IFF", "Mode1 On", x.mode1On.toString()); f.w("IFF", "Mode2 On", x.mode2On.toString())
        f.w("IFF", "Mode3A On", x.mode3aOn.toString()); f.w("IFF", "Mode4 On", x.mode4On.toString())
        f.w("IFF", "ModeC On", x.modeCOn.toString()); f.w("IFF", "ModeS On", x.modeSOn.toString())
        f.w("IFF", "Mode1 Code", code(x.mode1Code.toInt(), 2)); f.w("IFF", "Mode2 Code", code(x.mode2Code.toInt(), 4))
        f.w("IFF", "Mode3A Code", code(x.mode3aCode.toInt(), 4)); f.w("IFF", "Mode4 Key", x.mode4Key.toString())
        f.w("IFF", "AutoChange", x.autoChange.toString())
        val ts = x.timeSettings ?: throw NullPointerException("timeSettings")
        for (i in 0 until m.iffTimeEvents) {
            f.w("IFF", "TIME $i Mode1 Code", code(ts[i].mode1Code, 2))
            f.w("IFF", "TIME $i Mode3A Code", code(ts[i].mode3aCode.toInt(), 4))
            f.w("IFF", "TIME $i Mode4 Key", ts[i].mode4Key.toString())
            // HHMM, and -1 for an event that is not used (BMS's own IFF_Def.ini)
            val crit = ts[i].timeCriteria
            f.w("IFF", "TIME $i Criteria", if (crit < 0L) crit.toString() else crit.toString().padStart(4, '0'))
        }
        val ps = x.posSettings ?: throw NullPointerException("posSettings")
        for (j in 0 until m.iffPosEvents) {
            f.w("IFF", "POS $j Mode1", ps[j].mode1.toString()); f.w("IFF", "POS $j Mode2", ps[j].mode2.toString())
            f.w("IFF", "POS $j Mode3A", ps[j].mode3a.toString()); f.w("IFF", "POS $j Mode4", ps[j].mode4.toString())
            f.w("IFF", "POS $j ModeC", ps[j].modeC.toString()); f.w("IFF", "POS $j ModeS", ps[j].modeS.toString())
            f.w("IFF", "POS $j Direction", ps[j].direction.toString()); f.w("IFF", "POS $j WayPoint", ps[j].wayPoint.toString())
        }
    }

    private fun view(f: Ini, m: DtcModel) { if (m.viewsIncl) f.w("Cockpit View", "WideView", m.wideView.toString()) else f.ds("Cockpit View") }
    private fun otw(f: Ini, m: DtcModel) { if (m.viewsIncl) f.w("OTW", "Mode", m.otwMode.toString()) else f.ds("OTW") }
    private fun weapons(f: Ini, m: DtcModel) { if (m.masterArmIncl) f.w("Weapons", "MasterArm", m.masterArm.toString()) else f.ds("Weapons") }

    private fun harm(f: Ini, m: DtcModel, applyHarm: (DtcModel) -> Unit) {
        applyHarm(m)
        val tb = m.harm.table ?: throw NullPointerException("CampHarm.Table")
        for (n in 0..2) {
            val t = tb[n]
            f.w("HARM", "THREAT $n 0", vb.fmt(t.threat0, "0000")); f.w("HARM", "THREAT $n 1", vb.fmt(t.threat1, "0000"))
            f.w("HARM", "THREAT $n 2", vb.fmt(t.threat2, "0000")); f.w("HARM", "THREAT $n 3", vb.fmt(t.threat3, "0000"))
            f.w("HARM", "THREAT $n 4", vb.fmt(t.threat4, "0000"))
        }
        f.w("HARM", "MODE", m.harm.mode.toString()); f.w("HARM", "SUBMODE", m.harm.subMode.toString()); f.w("HARM", "TER", m.harm.ter.toString())
    }

    private fun laser(f: Ini, m: DtcModel) {
        f.w("Laser", "LaserST", m.laser.laserSt.toString())
        f.w("Laser", "LaserTGP", m.laser.laserCode.toString())
        f.w("Laser", "LaserLST", m.laser.lstCode.toString())
    }

    private fun aim(f: Ini, m: DtcModel) {
        f.w("FCC_AIM", "AIM-9_Spot/Scan", m.aim.spotScan.toString())
        f.w("FCC_AIM", "AIM-9_TD/BP", m.aim.tdBp.toString())
        f.w("FCC_AIM", "AIM120_TargetSize", m.aim.targetSize.toString())
    }

    private fun agm(f: Ini, m: DtcModel) {
        f.w("FCC_AGM", "Maverick_AutoPwr", m.agm.mavAutoPwr.toString())
        f.w("FCC_AGM", "Maverick_AutoPwrDir", m.agm.mavAutoPwrDir.toString())
        f.w("FCC_AGM", "Maverick_AutoPwrWpt", m.agm.mavAutoPwrWpt.toString())
    }

    private fun checkedMinusOne(v: Int): Int { if (v == Int.MIN_VALUE) throw ArithmeticException("Overflow"); return v - 1 }

    private fun agb(f: Ini, m: DtcModel) {
        for ((n, p) in listOf(1 to m.agb1, 2 to m.agb2)) {
            f.w("FCC_AGB", "Profile${n}_Submode", p.submode.toString())
            f.w("FCC_AGB", "Profile${n}_Fuze", p.fuze.toString())
            f.w("FCC_AGB", "Profile${n}_SGL/PAIR", p.sglPair.toString())
            f.w("FCC_AGB", "Profile${n}_Release_Spacing", p.releaseSpacing.toString())
            f.w("FCC_AGB", "Profile${n}_Release_Pulse", checkedMinusOne(p.releasePulse).toString())
            f.w("FCC_AGB", "Profile${n}_Release_Angle", p.releaseAngle.toString())
            f.w("FCC_AGB", "Profile${n}_C1_AD1", f6(p.c1Ad1))
            f.w("FCC_AGB", "Profile${n}_C1_AD2", f6(p.c1Ad2))
            f.w("FCC_AGB", "Profile${n}_C2_AD", f6(p.c2Ad))
            f.w("FCC_AGB", "Profile${n}_C2_BA", p.c2Ba.toString())
        }
    }

    private fun snsrPwr(f: Ini, m: DtcModel) { if (m.snsrPowerIncl) f.w("SNSR_PWR", "RALT", m.ralt.toString()) else f.ds("SNSR_PWR") }
    private fun intLight(f: Ini, m: DtcModel) { if (m.intLghtIncl) f.w("INT_LIGHTING", "DED", m.dedLight.toString()) else f.ds("INT_LIGHTING") }
}

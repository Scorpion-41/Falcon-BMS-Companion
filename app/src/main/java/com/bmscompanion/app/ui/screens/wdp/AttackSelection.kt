package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.mission.AttackDrawing
import com.bmscompanion.app.data.wdp.DataCardNet
import com.bmscompanion.app.data.wdp.DtcFromMission
import kotlinx.coroutines.launch

/**
 * The attack pages' Coordinates box as the Planner has it (Pop-up, HADB and TOSS alike; D87 in `docs/WDP-PORT.md`).
 *
 * **WDP's Campaign and TE buttons are gone.** In WDP they chose which cartridge table `Get_Coords` read the target and
 * the IP from, when the DataCard said Precision: Campaign the pilot's `Callsign.ini` (`tblCampSTPT`), TE a Tactical
 * Engagement's own mission `.ini` (`tblMissionSTPT`) — two tables because WDP loaded both files and BMS reads a TE's
 * `.ini` over the cartridge. The Planner has one table, filled slot by slot ([WdpMission.slots]: the cartridge's slot,
 * else BMS's mission file beside the save — for a TE that is the TE's own `.ini` — else the save's cell), and Save to
 * DTC writes the cartridge and the TE's `.ini` alike (D46). There is nothing to choose, so both are taken off the page
 * ([REMOVED], in `WdpPage.hidden`, which the click test checks). Where a steerpoint's position came from — what the
 * Campaign lamp used to say — is in the TGT STPT and IP STPT boxes' tips.
 *
 * **IP STPT** stands beside TGT STPT ([IP_BOX]). WDP's IP is always the steerpoint before the target; the box shows
 * that and follows the target until the pilot picks another, any steerpoint 1 to [IP_MAX] but the target. The arrows
 * step to the next steerpoint with a position, passing over the target; a number typed is taken as it is (one with no
 * position leaves the page without an IP, so VIP is blocked, as it is for a target with nothing before it); picking
 * TGT STPT − 1 again follows the target again. The box reports its arrows by name (`"numIPpoint.arrows"`, [ipBoxValues]),
 * so a number typed one away from the one shown is that number, not an arrow. A new mission or flight goes back to following. Every figure that uses
 * the IP takes it: the VIP lines and their steerpoint, the nav offsets Save to DTC writes, the Coordinates box, the map,
 * the card's Delivery block and the kneeboard's attack page.
 *
 * **Save to DTC fills the DataCard** (1.3.8): once the page's Save to DTC has written the cartridge, the card's
 * Delivery block becomes this attack, as the card's own PopUp / HADB / TOSS button fills it ([saved],
 * [DataCardWiring.attackSaved]), so the card and the cartridge agree; a save that fails leaves the card as it was. The
 * 1.3.8 test builds' separate Send to DataCard button is gone (`wdpadded.mjs` drops it).
 *
 * **Since 1.3.8 (D94) IP STPT shows only where an IP means something** ([selectors]): Pop-up and TOSS in VIP mode, and
 * in VRP mode while VIP is blocked (no IP with a position), so one can be picked; never on HADB, which plans from a VRP
 * only. **IP STPT at the VRP** ([IP_AT_VRP], [ipAtVrp]) turns a VRP attack into a VIP one from a steerpoint placed on
 * the VRP. A change of a page's inputs or its Save to DTC makes it the Planner's current attack ([AttackFocus],
 * [countsValue], [clicked]); the heading figures' tips give the magnetic heading at the target ([magneticTip], D93).
 */
internal object AttackSelection {
    /** WDP's Campaign and TE buttons: off the three attack pages. */
    val REMOVED = listOf("btnTE", "btnCamp")
    const val IP_BOX = "numIPpoint"
    const val IP_LABEL = "lblIPpoint"
    /** The highest steerpoint the IP box takes: the route's 24 (25 is no flight-plan point). */
    const val IP_MAX = 24

    /** The IP as the page uses it: the one [chosen], else the steerpoint before [target]. */
    fun ipOf(target: Int, chosen: Int?): Int = chosen ?: (target - 1)

    /** The IP box's own wiring values: its arrows come back by name ([upDownArrow]), so a number typed is never a step. */
    fun ipBoxValues(m: MutableMap<String, String>) {
        m["$IP_BOX.arrows"] = "click"
    }

    /**
     * A number typed into the IP box ([value]), given the [target] and the pick so far ([chosen], null = following the
     * target): taken as it is, any steerpoint 1 to [IP_MAX] but the target, whether or not it has a position.
     * Answers the new pick: null to follow the target (TGT STPT − 1), else the steerpoint; [chosen] when nothing changes.
     */
    fun typed(value: String, target: Int, chosen: Int?): Int? {
        val n = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it.coerceIn(-1e6, 1e6).toInt() } ?: return chosen
        return settle(n, target, chosen)
    }

    /**
     * One of the IP box's arrows ([by] 1 up, -1 down) from the IP shown now ([shown]): the next steerpoint with a
     * position, passing over the target. The new pick as [typed] answers it; [chosen] when there is nowhere to go.
     */
    fun arrow(mission: WdpMission, by: Int, shown: Int, target: Int, chosen: Int?): Int? {
        val n = step(mission, shown, by, target) ?: return chosen
        return settle(n, target, chosen)
    }

    private fun settle(n: Int, target: Int, chosen: Int?): Int? {
        if (n !in 1..IP_MAX || n == target) return chosen
        return if (n == target - 1) null else n
    }

    /** The next steerpoint from [from] in the direction [by] that has a position and is not the [target], or null. */
    fun step(mission: WdpMission, from: Int, by: Int, target: Int): Int? {
        var n = from + by
        while (n in 1..IP_MAX) {
            if (n != target && mission.steerpoint(n)?.placed() == true) return n
            n += by
        }
        return null
    }

    /** "position from your DTC" / "no position in this mission", for steerpoint [n]. */
    private fun whence(mission: WdpMission, n: Int): String =
        mission.source(n)?.let { "position from ${it.from}" } ?: "no position in this mission"

    /** The TGT STPT box's tip: what it is, and where this steerpoint's position came from. */
    fun targetTip(mission: WdpMission, target: Int): String =
        "TGT STPT: the steerpoint the attack is planned on, 1 to 25 (STPT $target: ${whence(mission, target)}). " +
            "A new mission or flight sets it once to your first strike steerpoint; after that it is yours."

    /** The IP STPT box's tip: what the IP is used for, whether it follows the target, and where its position came from. */
    fun ipTip(mission: WdpMission, target: Int, chosen: Int?, haveIp: Boolean): String {
        val ip = ipOf(target, chosen)
        val what = "VIP only: the steerpoint you overfly as the visual initial point — the jet's VIP $ip (LIST 3). The jet " +
            "steers to it; VIP-TO-TGT, VIP-TO-PUP and the offsets are measured from it. VRP needs no IP."
        val how = if (chosen == null) " It follows the target (TGT STPT − 1) until you pick another."
        else " Picked by you; pick TGT STPT − 1 to follow the target again."
        val where = if (ip < 1) " The target has no steerpoint before it: no IP, so no VIP."
        else " STPT $ip: ${whence(mission, ip)}" + (if (haveIp) "." else ", so no VIP.")
        return what + how + where
    }

    // ---------------------------------------------------------------- what makes an attack the current one (AttackFocus)

    /** The attack pages' own inputs pressed: a heading or angle-off letter, the knobs, the OA2 box, the steerpoint boxes' arrows. */
    private val INPUT_CLICKS = setOf(
        "lblN", "lblE", "lblS", "lblW", "lblN360", "lbl0", "lbl30", "lbl60", "lbl90",
        "pnlRefUp", "pnlRefDown", "pnlRef_Up", "pnlRef_Down", "pnlDED_Ref_Up", "pnlDED_Ref_Down",
        "pnlProfile_Up", "pnlProfile_Down", "pnlProfile2_Up", "pnlProfile2_Down", "pnlBomb_Up", "pnlBomb_Down",
        "chbOA2", "numWaypoint", IP_BOX,
    )

    /** A value of an attack page that changes the attack: any slider but the zoom, TGT STPT, IP STPT. */
    fun countsValue(name: String): Boolean = name != "trbZoom" && (name.startsWith("trb") || name == "numWaypoint" || name == IP_BOX)

    /** A press on an attack page that changes the attack (not the mode knob, zoom, Show PPT, Save Map). */
    fun countsClick(name: String): Boolean = name.substringBefore(':') in INPUT_CLICKS

    /** A press that applies the attack: the page's own Save to DTC (which also fills the DataCard once it has saved). */
    fun appliesClick(name: String): Boolean = name.substringBefore(':') == "btnSaveDTC"

    /** After an attack page handled [name]: the current attack follows a change or an apply of [page] (AttackFocus). */
    fun clicked(page: WdpPage, name: String) {
        if (countsClick(name)) AttackFocus.touch(page) else if (appliesClick(name)) AttackFocus.apply(page)
    }

    // ---------------------------------------------------------------- which selectors show (1.3.8)

    /** The button that turns a VRP attack into a VIP one from a steerpoint placed at the VRP (Pop-up and TOSS). */
    const val IP_AT_VRP = "btnIpAtVrp"
    const val IP_AT_VRP_TITLE = "IP STPT at the VRP"

    /** The Coordinates box's IP lines: only in VIP mode. */
    val IP_LINES = listOf("lblIP_N", "lblIP_E", "lblIP_elv", "lblIPelv", "lblIPelv2", "lblIP_EW", "lblIP_NS", "lblNewCoordinate", "lblIPCoordinate")

    /**
     * What the selectors show (SPEC b; D94): the IP STPT box only where an IP means something — Pop-up and TOSS in VIP
     * mode, and in VRP mode while VIP is blocked (no IP with a position), so an IP can be picked to unblock it; never on
     * HADB, which plans from a VRP only. The Coordinates box's IP lines in VIP mode only. **IP STPT at the VRP** in VRP
     * mode (blocked or not), and in VIP mode only while the IP STPT is the one it created ([again]: pressed again, it
     * moves that steerpoint to the VRP the inputs lay out now); never on HADB, disabled with no target. [ip] is the IP
     * STPT shown.
     */
    fun selectors(
        m: MutableMap<String, String>, vip: Boolean, vipAvailable: Boolean, gotTarget: Boolean, hadb: Boolean, ip: Int,
        again: Boolean = false,
    ) {
        if (hadb) {
            m[IP_BOX] = "hidden"; m[IP_LABEL] = "hidden"
            for (n in IP_LINES) m[n] = "hidden"
            m[IP_AT_VRP] = "hidden"
            return
        }
        if (vip) {
            if (!again) { m[IP_AT_VRP] = "hidden"; return }
            m["$IP_AT_VRP.enabled"] = gotTarget.toString()
            m["$IP_AT_VRP.tip"] = "Moves IP STPT $ip, which this button created, to the VRP these inputs lay out now, and " +
                "plans the attack as VIP from it again. Save to DTC writes it."
            return
        }
        if (vipAvailable) { m[IP_BOX] = "hidden"; m[IP_LABEL] = "hidden" }
        for (n in IP_LINES) m[n] = "hidden"
        m["$IP_AT_VRP.enabled"] = gotTarget.toString()
        m["$IP_AT_VRP.tip"] = if (!gotTarget) "Pick a TGT STPT with a position first."
        else "Puts a steerpoint on the VRP and plans this attack as VIP from it: the jet steers to that steerpoint and " +
            "VIP-TO-TGT, VIP-TO-PUP and the offsets hang on it. Finish the Input Panel first: the steerpoint stays where " +
            "it is placed. Save to DTC writes it; delete it on DTC → STPT (Change, Clear, Apply)."
        if (!vipAvailable) {
            val tip = "VIP is blocked: the IP STPT${if (ip >= 1) " ($ip)" else ""} has no position in this mission. Pick another " +
                "IP STPT, or press IP STPT at the VRP. The page plans from the VRP."
            for (n in listOf("pnlBlocked_1", "pnlBlocked_2", "pnlBlocked_3")) m["$n.tip"] = tip
        }
    }

    // ---------------------------------------------------------------- IP STPT at the VRP (1.3.8, D94)

    /**
     * **IP STPT at the VRP**: a steerpoint placed on the VRP becomes the IP, and the attack is planned as VIP from it.
     * The slot is the first one after the route with nothing in the cartridge and nothing in the route or the save, or
     * the IP STPT now ([ipNow], 1-24 and not the target); pressed again for the IP STPT it created ([created]), that
     * steerpoint and no other. **One question** says it all before anything is written (the pilot's 1.3.8 request):
     * where the VRP is, to finish the Input Panel and check the attack on the map first (the steerpoint stays where it
     * is placed, so a later change moves the attack and not the steerpoint), what each slot holds — a cartridge point
     * by name, a route or save point with what moving it does — and how to delete the steerpoint on the DTC page.
     * Its buttons: **Create IP STPT** (one slot) or one **Create IP STPT n** per slot, and Cancel. The point goes into
     * the DTC page as its own edit (`DtcWiring.placeSteerpoint`, action −1, at the VRP line's ELEV — "the elevation of
     * the STPT and the VIP elevation must be the same", TrM p.360), which Save to DTC writes; [plan] then plans the page
     * from it (the mission given with the steerpoints placed this session, the IP STPT set, VIP chosen) and keeps the
     * [CreatedIp] for the page's line about it ([ipNotice]). No box after it: the page says it.
     */
    fun ipAtVrp(
        page: WdpPage,
        mission: WdpMission,
        target: Int,
        ipNow: Int,
        targetAt: Pair<Double, Double>?,
        vrp: Pair<Double, Double>?,
        vrpElevFt: Int,
        created: CreatedIp?,
        plan: (WdpMission, Int, CreatedIp) -> Unit,
    ) {
        if (targetAt == null || vrp == null) { WdpDialogs.message(IP_AT_VRP_TITLE, "Pick a TGT STPT with a position first."); return }
        val dtc = WdpSession.dtc
        val free = dtc.freeSlots()
        if (free == null) {
            WdpDialogs.message(IP_AT_VRP_TITLE, "The cartridge is not loaded yet: the Planner reads it from Falcon BMS on the PC once it is linked. Press IP STPT at the VRP again in a moment.")
            return
        }
        val cartridgeFree = free.stpts.toSet()
        val br = com.bmscompanion.app.data.wdp.AttackGeometry.bearingRange(targetAt.first, targetAt.second, vrp.first, vrp.second)
        val here = ipNow.takeIf { it in 1..IP_MAX && it != target }
        // pressed again for the IP STPT this button created: that steerpoint moves, and no second one is made
        val again = created != null && here == created.n
        val freeSlot = if (again) null else freeIpSlot(mission, cartridgeFree, target)?.takeIf { it != here }
        val slots = listOfNotNull(freeSlot, here)
        if (slots.isEmpty()) {
            WdpDialogs.message(IP_AT_VRP_TITLE, "There is no steerpoint 1-24 to put the IP in: every slot holds a point. Free one on the DTC page (STPT tab) first.")
            return
        }
        val text = buildString {
            append("The VRP is ${AttackDrawing.nmText(br.rangeFt)} from STPT $target on ${DataCardNet.fmt(br.bearingDeg, "0.0")}° true. ")
            append(if (again) "IP STPT ${here} moves there" else "A steerpoint there becomes the IP STPT")
            append(", and the attack is planned as VIP from it.\n\n")
            append("Finish the Input Panel and check the attack on the map (Profile) first. The steerpoint stays where it is ")
            append("placed: changing the attack angle, distances or any other input afterwards moves the attack but not the ")
            append("steerpoint, and the route no longer matches it. Plan in VRP freely; create the IP STPT when you are done.\n\n")
            if (again) append("STPT $here is the IP STPT created earlier: it is moved, no new steerpoint is made.")
            else if (slots.size == 1) append("It goes into ${slotWords(mission, slots[0], cartridgeFree)}.")
            else slots.forEachIndexed { i, n -> if (i > 0) append('\n'); append("Create IP STPT $n: ${slotWords(mission, n, cartridgeFree)}.") }
            append("\n\nTo delete it later: DTC page, STPT tab, ")
            append(slots.joinToString(" or ") { "Change $it" })
            append(" on its row, then Clear and Apply.")
        }
        val buttons = if (slots.size == 1) listOf(CREATE, "Cancel") else slots.map { "$CREATE $it" } + "Cancel"
        WdpDialogs.message(IP_AT_VRP_TITLE, text, buttons) { a ->
            val n = when {
                a == CREATE -> slots[0]
                a.startsWith("$CREATE ") -> a.removePrefix("$CREATE ").toIntOrNull()
                else -> null
            } ?: return@message
            placeIp(page, mission, n, vrp, vrpElevFt, plan)
        }
    }

    /** The question's create button: "Create IP STPT", or "Create IP STPT n" where it offers two slots. */
    const val CREATE = "Create IP STPT"

    /** What slot [n] holds, for the question: a cartridge point by name, a route or save point with what moving it does, or a free slot. */
    private fun slotWords(mission: WdpMission, n: Int, cartridgeFree: Set<Int>): String {
        val src = mission.source(n)
        return when {
            n !in cartridgeFree -> {
                val name = WdpSession.dtc.model?.stpt?.getOrNull(n - 1)?.target?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Not set", true) } ?: "a point"
                "STPT $n, which holds $name in your cartridge (replaced)"
            }
            src == StptSource.ROUTE || src == StptSource.SAVE -> {
                val action = mission.steerpoint(n)?.action ?: 0
                val word = com.bmscompanion.app.data.wdp.DataCardPlan.actionString(action).trim().ifEmpty { "flight-plan" }
                "STPT $n, your route's $word point (moved: the HSD draws the route through the VRP, and its time over " +
                    "steerpoint stays BMS's)"
            }
            else -> "STPT $n, a free slot"
        }
    }

    /** The IP steerpoint IP STPT at the VRP created on a page this session: its slot, and the point it holds there (feet north, east). */
    data class CreatedIp(val n: Int, val north: Double, val east: Double)

    /** The line an attack page shows about the IP STPT it created ([ipNotice]), and whether it is the warning. */
    data class IpNotice(val text: String, val stale: Boolean)

    /** How far the VRP may move from the IP STPT created on it before the page says it is no longer there. */
    const val IP_STALE_FT = 100.0

    /**
     * The page's line about the IP STPT this button [created], or null: only while that steerpoint is the IP STPT
     * shown ([ipShown]) and still holds the point it was put on ([slotNow], 5 ft — moved or cleared on the DTC page, it
     * is the pilot's own and nothing is said). Then the notice that it was created and can be deleted, or — once the
     * VRP the inputs lay out now ([vrpNow]) is more than [IP_STALE_FT] from it — the warning that it no longer is.
     * It cannot nag about an IP the pilot chose: no other steerpoint is ever measured.
     */
    fun ipNotice(created: CreatedIp?, ipShown: Int, vrpNow: Pair<Double, Double>?, slotNow: Pair<Double, Double>?): IpNotice? {
        val c = created ?: return null
        if (ipShown != c.n || vrpNow == null || slotNow == null) return null
        if (kotlin.math.hypot(slotNow.first - c.north, slotNow.second - c.east) > 5.0) return null
        return if (kotlin.math.hypot(vrpNow.first - c.north, vrpNow.second - c.east) <= IP_STALE_FT)
            IpNotice("IP STPT ${c.n} created at the VRP — delete it on DTC → STPT if no longer needed.", false)
        else IpNotice("IP STPT ${c.n} is no longer at the VRP for these inputs — press IP STPT at the VRP again, or delete it on DTC → STPT.", true)
    }

    /**
     * The free slot the question offers: the first of 1-24 after the route's last point with no cartridge point and no route
     * or save point; with none after it, the first such slot anywhere; null when there is none.
     */
    internal fun freeIpSlot(mission: WdpMission, cartridgeFree: Set<Int>, target: Int): Int? {
        val placed = mission.slots.filter { it.point.placed() }
        val last = placed.filter { it.source != StptSource.DTC }.maxOfOrNull { it.point.n }
            ?: placed.filter { it.point.action != -1 }.maxOfOrNull { it.point.n } ?: 0
        fun empty(n: Int) = n != target && n in cartridgeFree && mission.source(n) == null
        return (1..IP_MAX).firstOrNull { it > last && empty(it) } ?: (1..IP_MAX).firstOrNull { empty(it) }
    }

    /**
     * Steerpoint [n] on the VRP (the DTC page's own edit), then the page planned as VIP from it ([plan], handed the
     * point as the DTC page holds it, for [ipNotice]). A refusal is said in a box; success is said on the page.
     */
    internal fun placeIp(page: WdpPage, mission: WdpMission, n: Int, vrp: Pair<Double, Double>, vrpElevFt: Int, plan: (WdpMission, Int, CreatedIp) -> Unit) {
        val dtc = WdpSession.dtc
        val r = dtc.placeSteerpoint(
            n, DtcFromMission.Place("IP (${page.label} VRP)", DtcFromMission.Pt(vrp.first, vrp.second), vrpElevFt.toDouble(), "attack"), ask = false,
        )
        if (!r.startsWith("STPT $n:")) { WdpDialogs.message(IP_AT_VRP_TITLE, r); return }
        val at = dtc.stptAt(n)?.let { CreatedIp(n, it.first, it.second) } ?: CreatedIp(n, vrp.first, vrp.second)
        plan(mission.withPlaced(dtc.placedThisSession()), n, at)
        AttackFocus.touch(page)
    }

    // ---------------------------------------------------------------- magnetic headings in the tips (D93)

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** Asks the PC once for the theater's magnetic variation (`/api/campaign/magvar`), then [done]; nothing when it is known. */
    fun askVariation(mission: WdpMission, done: () -> Unit) {
        if (WdpMapIntel.cachedVar(mission) != null) return
        scope.launch { runCatching { WdpMapIntel.magVar(mission) }; done() }
    }

    /**
     * A heading figure's tip with its magnetic figure (D93): the fields stay true, as WDP prints them and the DED takes
     * bearings, but the HUD and the HSD fly magnetic headings (Dash-34 p.103). [base] is the figure's own tip, [deg] the
     * true heading, [at] the target. Null when the variation is not known there.
     */
    fun magneticTip(base: String?, deg: Double?, mission: WdpMission, at: Pair<Double, Double>?): String? {
        if (deg == null || at == null) return null
        val v = WdpMapIntel.cachedVar(mission)?.at(at.first, at.second) ?: return null
        val mag = ((kotlin.math.round(deg - v).toInt() % 360) + 360) % 360
        val side = if (v < 0) "W" else "E"
        return (base?.trim()?.let { "$it " } ?: "") + "True; magnetic M ${mag.toString().padStart(3, '0')} at the target " +
            "(variation ${DataCardNet.fmt(kotlin.math.abs(v), "0.0")}° $side)."
    }

    /** The DataCard the attack pages send to: the Planner's ([WdpSession.dataCard]) unless a headless check sets its own. */
    var card: (() -> DataCardWiring)? = null

    private fun theCard(): DataCardWiring? = card?.invoke() ?: if (WdpSession.started) WdpSession.dataCard else null

    /** The profile the DataCard's Delivery block holds ("PopUp", "HADB", "TOSS", "None"), or null before the Planner runs. */
    private fun cardProfile(): String? = runCatching { theCard()?.plan?.attack?.strProfile }.getOrNull()

    /**
     * The page's Save to DTC tip, for the page whose card profile is [profile]: what it writes, and that the DataCard's
     * Delivery block follows once the cartridge is saved (and says whether the card holds this attack now).
     */
    fun saveValues(m: MutableMap<String, String>, profile: String, label: String, vipLines: Boolean) {
        val lines = if (vipLines) "VIP-TO-TGT, VIP-TO-PUP, OA1, OA2" else "TGT-TO-VRP, TGT-TO-PUP, OA1, OA2"
        m["btnSaveDTC.tip"] = "Writes the four DED lines in use ($lines) into your cartridge's nav offsets and saves the " +
            "cartridge now; the other reference's lines are cleared. Once it has saved, the DataCard's Delivery section " +
            "becomes this $label attack (profile and every field), so the card and the cartridge agree; a save that fails " +
            "leaves the card as it was." + if (cardProfile() == profile) " The card holds this $label attack now." else ""
    }

    /**
     * An attack page's Save to DTC has written the cartridge: the DataCard's Delivery block becomes [profile]'s attack,
     * as the card's own button fills it ([DataCardWiring.attackSaved]). Not called when the save failed.
     */
    fun saved(profile: String) {
        (theCard() ?: WdpSession.dataCard).attackSaved(profile)
    }
}

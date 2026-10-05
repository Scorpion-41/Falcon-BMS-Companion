package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.AttackDrawing
import com.bmscompanion.app.data.mission.AttackOverlay
import com.bmscompanion.app.data.wdp.AttackMap
import com.bmscompanion.app.data.wdp.PopupPlan
import com.bmscompanion.app.ui.screens.mission.MissionEpoch

/**
 * The attack an attack page has worked out, for Populate from Planner, and the attack the PC holds, for the boards' map.
 *
 * An attack page's answer is a handful of points the pilot enters in the jet — the VRP or VIP, the pull-up point, the
 * offset aimpoints. Drawn on the theater map around the target, that is the attack as it will be flown: which way the
 * run-in comes from, where the pull-up is, where the offsets sit on the ground.
 *
 * The points are the ones the page's own map draws ([AttackMap.Plot]). Since WDP's attack-page bugs were fixed (TOSS's
 * OA1 block carried the pull-up point, its VIP figures were measured with north and east swapped, Pop-up's Type 2
 * measured OA2 from half of OA1), the DED's figures laid out again from their steerpoint — the target, or the IP in
 * VIP mode — land on these same points, to 0.1° and a foot or two (`--wdppagetest toss geometry`): what the pilot
 * types into the jet, what the page draws and what the Mission map shows are one attack.
 *
 * **Nothing reaches the Mission map by itself** (R3-PLAN A10). Up to 1.3.7 every change on an attack page was
 * published to the Mission map and the PC as it happened; the pilot asked for the hand-over to the rest of the app to
 * be a button. So the attack page's own map keeps its live preview, and [current] is what **Populate from Planner** sends
 * with the flight; the Mission map and the VR boards draw the populated attack (`plan.attack` in `/api/mission`, WDP mode).
 *
 * **One attack, drawn one way** (1.3.8): the attack is built by [AttackDrawing] and drawn by `drawAttackModel` on every
 * map, and only the current attack ([AttackFocus]) is drawn outside the attack pages and the DataCard.
 */
object WdpAttackOverlay {
    /**
     * An attack published to the rest of the app by the Planner itself, which since A10 is never: nothing sets it.
     * Kept for `--wdprender`'s check that a page's change of value publishes no attack by itself.
     */
    var shown by mutableStateOf<AttackOverlay?>(null)

    /**
     * The attack [page] has worked out now — what its own map draws — stamped with the Planner's theater, so a map of
     * another theater leaves it off. Null for a page that plans no attack, or with no target. This is what Send to
     * Mission sends (A10); nothing calls it on a change of value.
     */
    fun current(page: WdpPage, wiring: WdpWiring?): AttackOverlay? =
        of(page, wiring)?.copy(theater = WdpSession.theater?.id.orEmpty())

    /**
     * The attack Populate from Planner sends and every map outside the attack pages draws: the current attack
     * ([AttackFocus], the attack page last changed or applied), and nothing else. Null when there is none — the maps
     * then draw the cartridge's own ([AttackDrawing.fromCartridge]). [saved] says whether the cartridge's offsets as
     * saved already hold it.
     */
    fun toSend(): AttackOverlay? {
        val p = AttackFocus.profile ?: return null
        val a = current(p, wiringOf(p)) ?: return null
        return a.copy(saved = runCatching { AttackFocus.savedInCartridge(a) }.getOrDefault(true))
    }

    /** The three pages that work out an attack. */
    val ATTACK_PAGES = setOf(WdpPage.POPUP, WdpPage.HADB, WdpPage.TOSS)

    internal fun wiringOf(page: WdpPage): WdpWiring? = when (page) {
        WdpPage.POPUP -> WdpSession.popup
        WdpPage.HADB -> WdpSession.hadb
        WdpPage.TOSS -> WdpSession.toss
        else -> null
    }

    /** The attack an attack page's own map shows, from its wiring; null for any other page, or with no target. */
    fun of(page: WdpPage, wiring: WdpWiring?): AttackOverlay? = when (page) {
        WdpPage.POPUP -> (wiring as? PopupWiring)?.let { ofPlan(it.plan) }
        WdpPage.HADB -> (wiring as? HadbWiring)?.let { ofPlan(it.plan) }
        WdpPage.TOSS -> (wiring as? TossWiring)?.let { ofPlan(it.plan) }
        else -> null
    }

    /** The attack of an attack page's plan ([PopupPlan], [com.bmscompanion.app.data.wdp.HadbPlan], [com.bmscompanion.app.data.wdp.TossPlan]); null for anything else or with no target. */
    fun ofPlan(plan: Any?): AttackOverlay? = when (plan) {
        is PopupPlan -> plan.attackPlot()?.let { plot ->
            val tgt = plan.numWaypointText.trim().toIntOrNull() ?: 0
            AttackDrawing.fromPlot(AttackDrawing.POPUP, plot, tgt, AttackSelection.ipOf(tgt, plan.ipStpt).takeIf { it >= 1 })
        }
        is com.bmscompanion.app.data.wdp.HadbPlan -> plan.attackPlot()?.let { plot -> AttackDrawing.fromPlot(AttackDrawing.HADB, plot, plan.numWaypoint, null) }
        is com.bmscompanion.app.data.wdp.TossPlan -> plan.attackPlot()?.let { plot ->
            AttackDrawing.fromPlot(AttackDrawing.TOSS, plot, plan.waypoint, plan.ipWaypoint.takeIf { it >= 1 })
        }
        else -> null
    }

    /** [plot]'s attack as every map draws it ([AttackDrawing.fromPlot]): [page] is "Pop-up", "HADB" or "TOSS". */
    fun of(page: String, plot: AttackMap.Plot, tgtStpt: Int = 0, ipStpt: Int? = null): AttackOverlay =
        AttackDrawing.fromPlot(page, plot, tgtStpt, ipStpt)

    /**
     * An attack page's map picture as it is now, in WDP's white-map inks when [whiteMap], with the one attack drawing
     * over its route and threats ([PopupPlan.MapPicture.attack]): what the DataCard's picMap and the kneeboard's attack
     * page draw (B1: Pop-up's is made on the spot, where its own was only made while its Profile panel was up).
     */
    fun picture(page: WdpPage, whiteMap: Boolean = false): PopupPlan.MapPicture? = runCatching {
        val pic = when (page) {
            WdpPage.POPUP -> WdpSession.popup.plan.picture(whiteMap)
            WdpPage.HADB -> WdpSession.hadb.plan.mapPicture(whiteMap)
            WdpPage.TOSS -> WdpSession.toss.plan.mapPicture(whiteMap)
            else -> null
        }
        pic?.withAttack(of(page, wiringOf(page)))
    }.getOrNull()
}

/**
 * **The current attack** (1.3.8): the attack page (Pop-up, HADB or TOSS) whose last user action was a change of its
 * inputs (a slider, VIP/VRP, Type, drag, the OA2 box, Turn, TGT STPT, IP STPT, a typed ELEV or ground, IP STPT at the
 * VRP — [touch]) or an apply (its own Save to DTC, which also fills the card, the card's PopUp / HADB / TOSS button, the card's
 * Save DTC with a profile — [apply]). Opening a page, the mode knob, zoom and Show PPT do not count, and nor does a
 * recompute when a mission or the ground's height arrives. It is the only attack drawn outside the attack pages: the
 * Planner's Map page, the Upd Kneeboard attack page and the next Populate; the others keep their settings but are not
 * drawn. A new mission or flight, Re-read DTC and the delivery being cleared forget it ([clear]). The DataCard keeps its
 * own profile (its map and Delivery block are one printed record) and says so when they differ ([cardNotice]).
 *
 * It is kept in the device's preferences ([KEY]) with the mission it was for ([MissionEpoch.seenText]: the mode and
 * the flight, as the device last saw them), as the attack pages keep their settings, and taken back at the next start
 * only when that is still the mission ([restore]); a mission that changed while the app was closed is cleared by
 * [MissionEpoch] at its first look, through [WdpSession.resetAttack] → [clear].
 */
object AttackFocus {
    /** The preference holding the current attack's page and the mission it was for. */
    internal const val KEY = "planner_attack_focus"

    /** The current attack's page, or null. */
    var profile by mutableStateOf<WdpPage?>(null)
        private set
    /** Moves at every [touch], [apply] and [clear]. */
    var stamp by mutableStateOf(0L)
        private set

    init { restore() }

    /** The pilot changed an input of [page]. */
    fun touch(page: WdpPage) { if (page in WdpAttackOverlay.ATTACK_PAGES) { set(page); stamp++ } }

    /** [page]'s attack was applied (sent to the card, saved to the cartridge). */
    fun apply(page: WdpPage) { if (page in WdpAttackOverlay.ATTACK_PAGES) { set(page); stamp++ } }

    /** A new mission or flight, Re-read DTC, the delivery cleared: no attack is current. */
    fun clear() { profile = null; stamp++; runCatching { Repo.putString(KEY, null) } }

    /** Makes [page] current, and keeps it with the mission now when it is another page than before (never throws). */
    private fun set(page: WdpPage) {
        val was = profile
        profile = page
        if (was != page) runCatching {
            // with no mission seen yet nothing is kept: it could not be told at the next start whose it was
            val mission = MissionEpoch.seenText()
            Repo.putString(KEY, mission?.let { page.name + "\n" + it })
        }
    }

    /**
     * Takes back the current attack kept in the preferences when it was kept for the mission this device last saw
     * ([MissionEpoch.isSeen]); otherwise there is none (and the kept one is dropped). Run once at start; never throws.
     */
    fun restore() {
        val kept = runCatching { Repo.getString(KEY) }.getOrNull()
        val page = kept?.substringBefore('\n')?.let { n -> WdpAttackOverlay.ATTACK_PAGES.firstOrNull { it.name == n } }
        val ok = page != null && runCatching { MissionEpoch.isSeen(kept.substringAfter('\n', "")) }.getOrDefault(false)
        profile = if (ok) page else null
        if (!ok && kept != null) runCatching { Repo.putString(KEY, null) }
        stamp++
    }

    /** The current attack as the maps draw it, or null. */
    fun current(): AttackOverlay? = profile?.let { WdpAttackOverlay.current(it, WdpAttackOverlay.wiringOf(it)) }

    /** The page of a card profile ("PopUp", "HADB", "TOSS"), or null. */
    fun pageOf(cardProfile: String?): WdpPage? = when (cardProfile) { "PopUp" -> WdpPage.POPUP; "HADB" -> WdpPage.HADB; "TOSS" -> WdpPage.TOSS; else -> null }

    /**
     * The DataCard's one line when the Planner's current attack is not the card's profile: "The Planner's latest attack
     * is TOSS; its page's Save to DTC puts it on the card." Null when they agree or there is no current attack.
     */
    fun cardNotice(cardProfile: String?): String? {
        val p = profile ?: return null
        if (pageOf(cardProfile) == p) return null
        return "The Planner's latest attack is ${p.label}; its page's Save to DTC puts it on the card."
    }

    /** Whether the cartridge as saved (the DTC page's file as loaded or last saved) holds [a]. True with no cartridge to compare. */
    fun savedInCartridge(a: AttackOverlay): Boolean {
        // no cartridge on the DTC page yet (not linked, still reading): nothing to compare, so no warning
        if (WdpSession.dtc.model == null) return true
        val saved = WdpSession.dtc.savedAttack(a.profile.ifBlank { null }) ?: return false
        return AttackDrawing.same(a, saved)
    }
}

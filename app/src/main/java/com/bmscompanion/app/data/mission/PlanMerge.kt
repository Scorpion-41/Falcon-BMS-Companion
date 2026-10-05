package com.bmscompanion.app.data.mission

import kotlin.math.abs
import kotlin.math.hypot

/**
 * The mission as every view draws it: the printed briefing (or, with no printed briefing, the save's own), with
 * Falcon BMS's mission file route and the plan the pilot sent with **Send to Mission** merged in (R3-IMPORT §3.3-3.4,
 * R3-PLAN A10-A12). The merge happens here, on each device, in shared code — the PC only parses, stores and serves —
 * and nothing of the printed briefing is ever lost: its rows stay its rows, and what the plan adds is appended and
 * marked with where it came from ([PlanItemSource]) and whether the jet has it yet ([MergedPoint.notInJet]).
 *
 * The Mission views (Map, Briefing, Taxi, Comms, Dashboard, Support) and the VR boards read one [MergedMission] rather
 * than `MissionData.briefing`/`dtc` directly, so they all agree.
 *
 * **Where a position comes from.** Slot by slot, the first of: the jet (in 3D — it is what the jet has), the plan, the
 * pilot's cartridge on disk (a non-zero slot: a Recon ACCEPT writes the exact target there), BMS's mission file beside
 * the save. PPTs and lines take the file BMS uses for the mode first (a TE's mission file, a campaign's cartridge),
 * then the other. An item the plan holds unchanged keeps the source it had without the plan, so a PLAN mark always
 * means the plan changed or added something.
 *
 * **Not in the jet.** A plan item is [MergedPoint.notInJet] when the jet does not have it: in 3D its position differs
 * from the jet's by more than [SAME_FT]; before 3D it differs from what the jet would load (the cartridge, then the
 * mission file). The PC's own list ([PlanOverlay.notInJet]) is honoured too, so the two never disagree on a mark.
 *
 * **Which briefing.** A plan made from a flight of a save carries that flight as a [Briefing] (`CampFlight.briefing`).
 * It is the mission when there is no printed briefing, or when the printed one is for another flight and the PC still
 * applied the plan (the printed briefing is older than the save, R3-PLAN A12). A parked plan merges nothing.
 *
 * **WDP mode** (`MissionData.mode`, 1.3.8): the data is the snapshot Populate from Planner took. Its briefing is BMS's
 * printed one when that is the populated flight's (origin [ORIGIN_PRINTED]: merged as EZBoards mode merges it, so both
 * modes show the same mission), else the save's own (origin "save", nothing printed under it: [MergedMission.printed]
 * is null); its plan carries the flight and the attack but no cartridge of its own (so nothing is marked as the
 * plan's), and there is no banner: the page's source line says where the mission came from.
 */
object PlanMerge {
    /** Two positions closer than this are the same place: the PC's rule for "not in the jet". */
    const val SAME_FT = 50.0

    /** The STPT bank a flight plan and its precision targets live in (1-24, and 25 the bullseye). */
    private const val LAST_STPT = 25

    /**
     * The attack the cartridge's `[NAV OFFSETS]` lay out ([AttackDrawing.fromCartridge]): each line from its own
     * steerpoint, placed by the cartridge, else by BMS's route. Null when there is none.
     */
    fun cartridgeAttack(data: MissionData?): AttackOverlay? = runCatching {
        val d = data?.dtc ?: return@runCatching null
        val nav = d.navOffsets ?: return@runCatching null
        val route = data.route?.steerpoints.orEmpty()
        AttackDrawing.fromCartridge(nav, { n ->
            (d.steerpoints.firstOrNull { it.n == n && (it.x != 0.0 || it.y != 0.0) } ?: route.firstOrNull { it.n == n && (it.x != 0.0 || it.y != 0.0) })
                ?.let { it.x to it.y }
        })
    }.getOrNull()

    /**
     * The merged mission for [data] (null before the first fetch), with the jet's own [live] data when BMS is in 3D
     * (it wins on positions: it is what the jet has).
     */
    fun merge(data: MissionData?, live: Live? = null): MergedMission {
        val wdp = data?.mode == MissionMode.WDP
        val present = data?.plan?.takeIf { it.present }
        val plan = present?.takeIf { it.applied }
        val parked = present?.takeIf { !it.applied }
        // WDP mode: the snapshot carries BMS's printed briefing (origin "printed") when it is the populated flight's
        val printed = if (wdp) (plan?.flight?.briefing ?: data?.briefing)?.takeIf { it.origin == ORIGIN_PRINTED } else data?.briefing
        val saveBriefing = (plan?.flight?.briefing ?: data?.briefing?.takeIf { wdp })?.let { if (it.origin == null) it.copy(origin = ORIGIN_SAVE) else it }
        val otherFlight = printed != null && plan != null && printed.origin != ORIGIN_SAVE && !sameFlight(printed, plan)
        val useSave = saveBriefing != null && (printed == null || otherFlight)
        val briefing = if (useSave) saveBriefing else printed

        val te = teMode(data, plan)
        val nav = live?.navPoints.orEmpty().filter { it.x != 0.0 || it.y != 0.0 }
        val inJet = nav.any { it.type == "WP" }
        val said = plan?.notInJet.orEmpty().toSet()
        val planHasCartridge = plan != null && plan.dtc.run {
            steerpoints.isNotEmpty() || ppts.isNotEmpty() || lines.isNotEmpty() || uhf.isNotEmpty() || vhf.isNotEmpty() ||
                weaponTargets.isNotEmpty() || modified != 0L
        }
        val flags = ArrayList<String>()

        // the save's own waypoints (cell + ½) place the flight's steerpoints when nothing better does (R3-PLAN A4): with
        // no printed briefing that is the only place their positions exist before 3D
        val flightRoute = if (plan?.flight != null && (useSave || (printed != null && sameFlight(printed, plan)))) plan.flight.route else emptyList()
        val stpts = mergeSteerpoints(data, plan, briefing, nav, said, planHasCartridge, flags, flightRoute)
        val targets = mergeTargets(data, plan, stpts, said, planHasCartridge, flags)
        val ppts = mergePpts(data, plan, te, nav, said, planHasCartridge, flags)
        val lines = mergeLines(data, plan, te, nav, said, planHasCartridge, flags)
        val presets = mergePresets(data, plan, said, flags)
        val settings = mergeSettings(data, plan, flags)
        mergeIff(data, plan, flags)

        val notInJet = (plan?.notInJet.orEmpty() + flags).distinct()
        val kind = when {
            wdp -> null
            parked != null -> BannerKind.PARKED
            plan == null -> null
            useSave && printed != null -> BannerKind.SAVE_OLDER
            useSave -> BannerKind.SAVE_ONLY
            briefing == null -> BannerKind.NO_BRIEFING
            else -> BannerKind.APPLIED
        }
        val saveBull = (plan?.route?.bullseye() ?: data?.route?.bullseye())
        val reprinted = plan != null && !useSave && printed != null && plan.id > 0 && (data?.briefingModified ?: 0L) > plan.id
        return MergedMission(
            briefing = briefing,
            dtc = data?.dtc,
            route = data?.route,
            plan = plan,
            parked = parked,
            steerpoints = stpts.filter { it.onRoute },
            targets = targets,
            ppts = ppts,
            lines = lines,
            presets = presets,
            // WDP mode: the attack Populate took; EZBoards mode: the cartridge's own, laid out as every map lays it
            // (1.3.8: both modes draw the attack the jet will load the same way)
            attack = plan?.attack?.takeIf { it.cues.isNotEmpty() } ?: if (!wdp) cartridgeAttack(data) else null,
            notInJet = notInJet,
            banner = kind?.let { bannerText(it, plan ?: parked, printed, notInJet, reprinted) },
            allSteerpoints = stpts,
            settings = settings,
            bannerKind = kind,
            inJet = inJet,
            saveBullseye = saveBull,
            printed = printed,
            saveFile = plan?.ref?.file?.takeIf { it.isNotBlank() },
            reprinted = reprinted,
        )
    }

    /** "save": a [Briefing] the PC built from a flight of a save rather than BMS's printed one. */
    const val ORIGIN_SAVE = "save"

    /**
     * "printed": BMS's printed briefing carried in WDP mode's snapshot, because it is the populated flight's
     * (`Populated.briefingFrom`); merged exactly as EZBoards mode merges the printed briefing.
     */
    const val ORIGIN_PRINTED = "printed"

    // ---------------------------------------------------------------- steerpoints

    private fun mergeSteerpoints(
        data: MissionData?, plan: PlanOverlay?, briefing: Briefing?, nav: List<NavPoint>, said: Set<String>,
        planHasCartridge: Boolean, flags: MutableList<String>, flightRoute: List<CampWaypoint> = emptyList(),
    ): List<MergedPoint> {
        val rows = briefing?.steerpoints.orEmpty()
        val jet = nav.filter { it.type == "WP" }.associateBy { it.i }
        fun bank(points: List<DtcPoint>?) = points.orEmpty().filter { it.n in 1..LAST_STPT && it.nonZero() }.associateBy { it.n }
        val planDtc = bank(plan?.dtc?.steerpoints)
        val planRoute = bank(plan?.route?.steerpoints)
        val disk = bank(data?.dtc?.steerpoints)
        val route = bank(data?.route?.steerpoints)
        val save = bank(flightRoute.map { DtcPoint(n = it.n, x = it.x, y = it.y, altFt = it.altFt, action = it.action) })
        val numbers = (rows.map { it.n } + jet.keys + planDtc.keys + planRoute.keys + disk.keys + route.keys + save.keys).filter { it > 0 }.distinct().sorted()
        return numbers.map { n ->
            val row = rows.firstOrNull { it.n == n }
            val j = jet[n]
            val p = planDtc[n] ?: planRoute[n]
            val base = disk[n] ?: route[n] ?: save[n]
            val baseSource = if (disk[n] != null) PlanItemSource.CARTRIDGE
                else if (route[n] != null) (if (data?.route?.fromSave == true) PlanItemSource.SAVE else PlanItemSource.ROUTE)
                else if (save[n] != null) PlanItemSource.SAVE else null
            val planDiffers = p != null && (base == null || far(p.x, p.y, base.x, base.y))
            val cleared = plan != null && planHasCartridge && p == null && disk[n] != null
            val (x, y) = when {
                j != null -> j.x to j.y
                p != null -> p.x to p.y
                base != null -> base.x to base.y
                else -> 0.0 to 0.0
            }
            val source = when {
                j != null -> PlanItemSource.JET
                planDiffers -> PlanItemSource.PLAN
                baseSource != null -> baseSource
                else -> PlanItemSource.BRIEFING
            }
            val notInJet = plan != null && p != null && (
                (j != null && far(p.x, p.y, j.x, j.y)) || (j == null && planDiffers) || "STPT $n" in said)
            if (notInJet) flags += "STPT $n"
            val action = p?.action ?: disk[n]?.action ?: route[n]?.action ?: save[n]?.action ?: 0
            val name = p?.name ?: disk[n]?.name ?: route[n]?.name
            val planRow = row == null && source == PlanItemSource.PLAN
            val desc = row?.desc
            val isTarget = (p ?: base)?.isTarget == true || (row == null && action == -1) ||
                listOf("Attack", "Target", "Recon").any { desc?.contains(it, ignoreCase = true) == true }
            // in 3D the jet's point is shown and the plan's drawn hollow beside it; before 3D the plan's is shown and a
            // thin line goes to where the jet would load it
            val showPlanBeside = notInJet && j != null
            val showJetBeside = notInJet && j == null && base != null
            MergedPoint(
                n = n, x = x, y = y, altFt = j?.altFt ?: p?.altFt ?: base?.altFt ?: 0.0, action = action, name = name,
                source = source, notInJet = notInJet, planRow = planRow, cleared = cleared,
                desc = desc, time = row?.time, cas = row?.cas, altText = row?.alt, actionText = row?.action, comments = row?.comments,
                // the route line joins the flight plan only: the briefing's rows and the plan's own points that are not
                // precision targets — and, with no briefing at all, every point that is not a target
                onRoute = row != null || (planRow && action >= 0) || (rows.isEmpty() && action >= 0 && !isTarget),
                isTarget = isTarget,
                planX = if (showPlanBeside) p?.x else null,
                planY = if (showPlanBeside) p?.y else null,
                jetX = if (showJetBeside) base?.x else null,
                jetY = if (showJetBeside) base?.y else null,
            )
        }
    }

    /**
     * The Targets card: every precision target steerpoint (action −1) of STPT 1-24 and 81-99, and the weapon targets.
     * The plan's replace the cartridge's slot for slot; a slot the plan cleared while the disk still holds it stays,
     * marked [MergedPoint.cleared] ("cleared in the Planner — still in the jet until saved").
     */
    private fun mergeTargets(
        data: MissionData?, plan: PlanOverlay?, stpts: List<MergedPoint>, said: Set<String>, planHasCartridge: Boolean,
        flags: MutableList<String>,
    ): List<MergedPoint> {
        val out = ArrayList<MergedPoint>()
        // STPT 1-25: the merged points themselves, those that are targets and are not flight-plan rows
        stpts.filter { it.isTarget && it.action == -1 && (it.hasPos || it.cleared) }.forEach { out += it }
        // STPT 81-99 and the weapon targets: the same slot rule, without a jet (BMS does not send them as WP points)
        fun slots(pick: (Dtc) -> List<DtcPoint>, range: IntRange, weapon: Boolean, label: String) {
            val planPts = plan?.dtc?.let(pick).orEmpty().filter { it.n in range && it.nonZero() }.associateBy { it.n }
            val planRoute = plan?.route?.let { r -> if (weapon) r.weaponTargets else r.steerpoints }.orEmpty().filter { it.n in range && it.nonZero() }.associateBy { it.n }
            val disk = data?.dtc?.let(pick).orEmpty().filter { it.n in range && it.nonZero() }.associateBy { it.n }
            val route = data?.route?.let { r -> if (weapon) r.weaponTargets else r.steerpoints }.orEmpty().filter { it.n in range && it.nonZero() }.associateBy { it.n }
            for (n in (planPts.keys + planRoute.keys + disk.keys + route.keys).distinct().sorted()) {
                val p = planPts[n] ?: planRoute[n]
                val base = disk[n] ?: route[n]
                val baseSource = if (disk[n] != null) PlanItemSource.CARTRIDGE else PlanItemSource.ROUTE
                val planDiffers = p != null && (base == null || far(p.x, p.y, base.x, base.y))
                val cleared = plan != null && planHasCartridge && p == null && disk[n] != null
                val shown = p ?: base ?: continue
                if (!weapon && shown.action != -1 && !shown.isTarget) continue
                val key = "$label $n"
                val notInJet = plan != null && p != null && (planDiffers || key in said)
                if (notInJet) flags += key
                out += MergedPoint(
                    n = n, x = shown.x, y = shown.y, altFt = shown.altFt, action = shown.action, name = shown.name,
                    source = if (planDiffers) PlanItemSource.PLAN else baseSource, notInJet = notInJet, cleared = cleared,
                    isTarget = true, weapon = weapon,
                    jetX = if (notInJet && base != null) base.x else null, jetY = if (notInJet && base != null) base.y else null,
                )
            }
        }
        slots({ it.steerpoints }, 81..99, weapon = false, label = "STPT")
        slots({ it.weaponTargets }, 0..200, weapon = true, label = "WPN")
        return out
    }

    // ---------------------------------------------------------------- PPTs

    private fun mergePpts(
        data: MissionData?, plan: PlanOverlay?, te: Boolean, nav: List<NavPoint>, said: Set<String>,
        planHasCartridge: Boolean, flags: MutableList<String>,
    ): List<MergedPpt> {
        // the jet's PT points, by slot: BMS numbers them 56-70 like the DTC; anything else is taken in order
        val jetList = nav.filter { it.type == "PT" }
        val jet = if (jetList.all { it.i in 56..70 }) jetList.associateBy { it.i } else jetList.withIndex().associate { (k, v) -> 56 + k to v }
        fun bank(list: List<DtcPpt>?) = list.orEmpty().filter { it.x != 0.0 || it.y != 0.0 }.associateBy { it.n }
        val planFirst = bank(if (te) plan?.route?.ppts else plan?.dtc?.ppts)
        val planSecond = bank(if (te) plan?.dtc?.ppts else plan?.route?.ppts)
        val cart = bank(data?.dtc?.ppts)
        val file = bank(data?.route?.ppts)
        val baseFirst = if (te) file else cart
        val baseSecond = if (te) cart else file
        val out = ArrayList<MergedPpt>()
        val numbers = (jet.keys + planFirst.keys + planSecond.keys + cart.keys + file.keys).distinct().sorted()
        for (n in numbers) {
            val j = jet[n]
            val p = planFirst[n] ?: planSecond[n]
            val base = baseFirst[n] ?: baseSecond[n]
            val baseSource = if (cart[n] != null && base === cart[n]) PlanItemSource.CARTRIDGE else PlanItemSource.ROUTE
            val key = "PPT $n"
            if (j != null) {
                val jp = MergedPpt(
                    n = n, x = j.x, y = j.y, rangeFt = (j.rangeNm ?: 0.0) * FT_PER_NM, code = p?.code ?: base?.code,
                    name = pptLabel(j.name, p?.code ?: base?.code, n), marker = (j.rangeNm ?: 0.0) <= MARKER_NM,
                    source = PlanItemSource.JET,
                )
                out += jp
                if (plan != null && p != null && (pptDiffers(p, jp.x, jp.y, jp.rangeFt) || key in said)) {
                    flags += key
                    out += p.merged(PlanItemSource.PLAN, notInJet = true)
                }
                continue
            }
            if (p != null) {
                val differs = base == null || pptDiffers(p, base.x, base.y, rangeFt(base))
                val notInJet = plan != null && (differs || key in said)
                if (notInJet) flags += key
                out += p.merged(if (differs) PlanItemSource.PLAN else baseSource, notInJet)
                continue
            }
            if (base != null) {
                val cleared = plan != null && planHasCartridge && cart[n] != null
                out += base.merged(baseSource, notInJet = false, cleared = cleared)
            }
        }
        return out
    }

    private fun DtcPpt.merged(source: PlanItemSource, notInJet: Boolean, cleared: Boolean = false) = MergedPpt(
        n = n, x = x, y = y, rangeFt = rangeFt(this), code = code, name = pptLabel(name, code, n), marker = isMarker(this),
        source = source, notInJet = notInJet, cleared = cleared,
    )

    private fun pptDiffers(p: DtcPpt, x: Double, y: Double, rangeFt: Double): Boolean =
        far(p.x, p.y, x, y) || abs(rangeFt(p) - rangeFt) > maxOf(SAME_FT, 0.01 * rangeFt)

    /** A range under 100 ft is a point with no ring (AWACS, tanker, a friendly): Ppt.ini gives those 0.1 ft. */
    private const val MARKER_FT = 100.0
    private const val MARKER_NM = 0.1
    private const val FT_PER_NM = 6076.12

    /** Whether a PPT is a marker: the PC says so (1.3.8), or an older PC's range gives it away. */
    fun isMarker(p: DtcPpt): Boolean = p.marker ||
        (p.rangeFt > 0.0 && p.rangeFt < MARKER_FT) ||
        (p.rangeFt <= 0.0 && p.rangeNm <= MARKER_NM)

    /** The ring's radius in feet: the file's own figure when the PC sent it, else the older nautical miles. */
    fun rangeFt(p: DtcPpt): Double = when {
        isMarker(p) -> 0.0
        p.rangeFt > 0.0 -> p.rangeFt
        else -> p.rangeNm * FT_PER_NM
    }

    /**
     * What a PPT is called: the label the PC resolved from the theater's `Ppt.ini` ("SA-10"), or — from a PC that only
     * sends the code — the code turned into the stock `Ppt.ini`'s own label.
     */
    fun pptLabel(name: String?, code: String?, n: Int): String {
        val given = name?.trim()?.takeIf { it.isNotEmpty() }
        if (given != null && (code == null || !given.equals(code.trim(), ignoreCase = true))) return STOCK_LABELS[given.uppercase()] ?: pretty(given)
        val c = (code ?: given)?.trim()?.takeIf { it.isNotEmpty() } ?: return "PPT $n"
        return STOCK_LABELS[c.uppercase()] ?: pretty(c)
    }

    private fun pretty(c: String): String = when {
        c.all { it.isDigit() } -> "SA-$c"
        Regex("^SA(\\d+)$", RegexOption.IGNORE_CASE).matches(c) -> "SA-" + c.drop(2)
        else -> c
    }

    /** The stock `Ppt.ini`'s labels for its non-SAM codes (the SAMs read "SA-n" from their code). */
    private val STOCK_LABELS = mapOf(
        "AAA" to "AAA", "HWK" to "Hawk", "MNP" to "ManPad", "NKE" to "Nike", "PAT" to "Patriot", "RM7" to "RIM-7",
        "SKY" to "Skyguard", "SM1" to "SM-1MR", "SM2" to "SM-2", "SSM" to "RIM-162", "ZSU" to "ZSU", "AWC" to "AWACS",
        "FRI" to "Friendly forces", "FAC" to "FAC(A)", "JTA" to "JTAC", "JST" to "J-STARS", "TNK" to "Tanker",
        "ASW" to "ASUW", "BDZ" to "Base defence zone", "CAP" to "CAP", "CP1" to "Contact point 1", "CP2" to "Contact point 2",
        "CSR" to "CSAR range", "IP1" to "IP 1", "IP2" to "IP 2", "HP1" to "Holding point 1", "HP2" to "Holding point 2",
        "19" to "SA-19 (2S6)",
    )

    // ---------------------------------------------------------------- lines

    private fun mergeLines(
        data: MissionData?, plan: PlanOverlay?, te: Boolean, nav: List<NavPoint>, said: Set<String>,
        planHasCartridge: Boolean, flags: MutableList<String>,
    ): List<List<MergedPoint>> {
        val jet = nav.filter { it.type.length == 2 && it.type[0] == 'L' && it.type[1] in '1'..'4' }
            .groupBy { it.type[1] - '0' }.mapValues { (_, v) -> v.sortedBy { it.i }.map { it.x to it.y } }
        val planFirst = lineGroups(if (te) plan?.route?.lines else plan?.dtc?.lines)
        val planSecond = lineGroups(if (te) plan?.dtc?.lines else plan?.route?.lines)
        val cart = lineGroups(data?.dtc?.lines)
        val file = lineGroups(data?.route?.lines)
        val out = ArrayList<List<MergedPoint>>()
        for (g in 1..4) {
            val j = jet[g]
            val p = planFirst[g] ?: planSecond[g]
            val base = (if (te) file[g] else cart[g]) ?: (if (te) cart[g] else file[g])
            val baseSource = if (base == null || base === cart[g]) PlanItemSource.CARTRIDGE else PlanItemSource.ROUTE
            val key = "LINE $g"
            fun line(points: List<Pair<Double, Double>>, source: PlanItemSource, notInJet: Boolean, cleared: Boolean = false) =
                points.mapIndexed { i, (x, y) -> MergedPoint(n = i + 1, x = x, y = y, source = source, notInJet = notInJet, cleared = cleared, line = g) }
            if (j != null && j.size >= 2) {
                out += line(j, PlanItemSource.JET, false)
                if (plan != null && p != null && (lineDiffers(p, j) || key in said)) {
                    flags += key
                    out += line(p, PlanItemSource.PLAN, true)
                }
                continue
            }
            if (p != null) {
                val differs = base == null || lineDiffers(p, base)
                val notInJet = plan != null && (differs || key in said)
                if (notInJet) flags += key
                out += line(p, if (differs) PlanItemSource.PLAN else baseSource, notInJet)
                continue
            }
            if (base != null) out += line(base, baseSource, false, cleared = plan != null && planHasCartridge && cart[g] != null)
        }
        return out.filter { it.size >= 2 }
    }

    /**
     * A cartridge's line points grouped into lines 1-4: the PC's `line` (1.3.8), else the slot (lineSTPT_0-5 is line 1,
     * 6-11 line 2 …). Zero points are left out and each line keeps its own order; lines are never joined.
     */
    fun lineGroups(points: List<DtcPoint>?): Map<Int, List<Pair<Double, Double>>> =
        points.orEmpty().filter { it.nonZero() }
            .groupBy { it.line ?: (it.n / 6 + 1) }
            .filterKeys { it in 1..4 }
            .mapValues { (_, v) -> v.sortedBy { it.n }.map { it.x to it.y } }

    private fun lineDiffers(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): Boolean =
        a.size != b.size || a.indices.any { far(a[it].first, a[it].second, b[it].first, b[it].second) }

    // ---------------------------------------------------------------- presets and settings

    private fun mergePresets(data: MissionData?, plan: PlanOverlay?, said: Set<String>, flags: MutableList<String>): List<MergedPreset> {
        fun band(label: String, disk: List<Preset>?, planned: List<Preset>?): List<MergedPreset> {
            val d = disk.orEmpty().associateBy { it.ch }
            val p = planned.orEmpty().associateBy { it.ch }
            return (d.keys + p.keys).distinct().sorted().map { ch ->
                val pp = p[ch]
                val dp = d[ch]
                val changed = plan != null && pp != null && (dp == null || !sameFreq(pp.freq, dp.freq) || (pp.comment ?: "") != (dp.comment ?: ""))
                val key = "$label $ch"
                val notInJet = plan != null && (changed || key in said)
                if (notInJet) flags += key
                val shown = (pp ?: dp)!!
                MergedPreset(
                    band = label, ch = ch, freq = shown.freq, comment = shown.comment,
                    source = if (changed) PlanItemSource.PLAN else PlanItemSource.CARTRIDGE, changed = changed, notInJet = notInJet,
                    was = if (changed) dp?.freq else null,
                )
            }
        }
        return band("UHF", data?.dtc?.uhf, plan?.dtc?.uhf) + band("VHF", data?.dtc?.vhf, plan?.dtc?.vhf)
    }

    private fun sameFreq(a: String, b: String): Boolean {
        val x = a.trim().toDoubleOrNull()
        val y = b.trim().toDoubleOrNull()
        return if (x != null && y != null) abs(x - y) < 0.0005 else a.trim() == b.trim()
    }

    /**
     * The cartridge's own settings for the Plan card: laser codes, bingo, ALOW and the MSL floor, the six EWS program
     * names, the presets tuned at load and the TACAN and ILS WDP writes. The plan's value wins; one that differs from
     * the cartridge on disk is the plan's. [MergedSetting.untested] marks the keys only WDP writes, which the real 4.38.1
     * cartridge does not hold — whether BMS reads them is not established.
     */
    private fun mergeSettings(data: MissionData?, plan: PlanOverlay?, flags: MutableList<String>): List<MergedSetting> {
        val disk = data?.dtc
        val planned = plan?.dtc
        val out = ArrayList<MergedSetting>()
        fun add(key: String, label: String, untested: Boolean, get: (Dtc) -> String?) {
            val d = disk?.let(get)?.takeIf { it.isNotBlank() }
            val p = planned?.let(get)?.takeIf { it.isNotBlank() }
            val value = p ?: d ?: return
            val fromPlan = p != null && p != d
            // NOT IN JET only where the cartridge holds the key too, as the PC counts them (PlanStore.notInJet): a
            // key the cartridge lacks is BMS's default in the jet, and WDP's writer puts its keys into every
            // cartridge it writes whether or not the pilot touched them
            val differs = fromPlan && d != null
            // the device's list names it as the PC does (PlanStore.notInJet), so the banner and the Plan card agree
            if (differs) SETTING_LABELS[key]?.let { flags += it }
            out += MergedSetting(key, label, value, if (fromPlan) PlanItemSource.PLAN else PlanItemSource.CARTRIDGE, notInJet = differs, untested = untested, was = if (fromPlan) d else null)
        }
        add("laserTgp", "TGP laser code", true) { it.laserTgp?.toString() }
        add("laserLst", "LST laser code", true) { it.laserLst?.toString() }
        add("laserSt", "Laser start", true) { it.laserSt?.let { s -> "$s s before impact" } }
        add("bingo", "Bingo", true) { it.bingoLbs?.let { b -> "$b lb" } }
        add("alow", "ALOW", true) { it.alowFt?.let { a -> "$a ft" } }
        add("mslFloor", "MSL floor", true) { it.mslFloorFt?.let { a -> "$a ft" } }
        add("comm1", "UHF at load", false) { it.comm?.comm1?.let { c -> "preset $c" + (it.uhf.firstOrNull { u -> u.ch == c }?.freq?.let { f -> " ($f)" } ?: "") } }
        add("comm2", "VHF at load", false) { it.comm?.comm2?.let { c -> "preset $c" + (it.vhf.firstOrNull { u -> u.ch == c }?.freq?.let { f -> " ($f)" } ?: "") } }
        add("tacan", "TACAN", true) { it.comm?.tacan }
        add("ils", "ILS", true) { it.comm?.ils?.let { f -> f + (it.comm.ilsCrs?.let { c -> " CRS ${c.toString().padStart(3, '0')}" } ?: "") } }
        add("ews", "EWS programs", false) { it.ewsNames.filter { n -> n.isNotBlank() }.takeIf { n -> n.isNotEmpty() }?.joinToString(" · ") }
        return out
    }

    /** The PC's words for a setting that is not in the jet (PlanStore.notInJet), by [MergedSetting.key]. */
    private val SETTING_LABELS = mapOf(
        "laserTgp" to "LASER TGP", "laserLst" to "LASER LST", "laserSt" to "LASER ST", "bingo" to "BINGO", "alow" to "ALOW",
        "mslFloor" to "MSL FLOOR", "comm1" to "COMM1", "comm2" to "COMM2", "tacan" to "TACAN", "ils" to "ILS", "ews" to "EWS",
    )

    /**
     * IFF codes the plan changed where the cartridge holds them too ("IFF Mode3A"), compared as numbers when both are,
     * as the PC compares them: the Comms page's IFF card marks the same codes.
     */
    private fun mergeIff(data: MissionData?, plan: PlanOverlay?, flags: MutableList<String>) {
        if (plan == null) return
        for ((key, a) in plan.dtc.iff) {
            val b = data?.dtc?.iff?.get(key) ?: continue
            val x = a.trim(); val y = b.trim()
            val xi = x.toIntOrNull(); val yi = y.toIntOrNull()
            val same = if (xi != null && yi != null) xi == yi else x.equals(y, ignoreCase = true)
            if (!same) flags += "IFF " + key.substringBefore(' ')
        }
    }

    // ---------------------------------------------------------------- the mission and the banner

    /**
     * Whether the printed briefing is for the flight the plan was made for: the callsign and the package number agree
     * wherever both sides know them (a side that does not know cannot disagree).
     */
    fun sameFlight(b: Briefing, plan: PlanOverlay): Boolean {
        val bCall = b.overview.flight?.key()
        val pCall = (plan.callsign ?: plan.flight?.row?.callsign)?.key()
        if (bCall != null && pCall != null && bCall != pCall) return false
        val bPkg = b.overview.packageId?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        val pPkg = (plan.packageId ?: plan.flight?.packageNumber?.takeIf { it > 0 }?.toString())?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        if (bPkg != null && pPkg != null && bPkg.trimStart('0') != pPkg.trimStart('0')) return false
        return true
    }

    /** A Tactical Engagement (or training) keeps its PPTs and lines in its own mission file; a campaign in the cartridge. */
    private fun teMode(data: MissionData?, plan: PlanOverlay?): Boolean {
        val kind = plan?.route?.kind?.takeIf { it.isNotEmpty() } ?: data?.route?.kind?.takeIf { it.isNotEmpty() }
        if (kind != null) return kind == CampKind.TE || kind == CampKind.TRAINING
        val file = plan?.ref?.file.orEmpty().lowercase()
        return file.endsWith(".tac") || file.endsWith(".trn")
    }

    private fun bannerText(kind: BannerKind, plan: PlanOverlay?, printed: Briefing?, notInJet: List<String>, reprinted: Boolean): String {
        val who = flightName(plan)
        return when (kind) {
            BannerKind.PARKED -> "A plan for $who is set aside: " + (plan?.note?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
                ?: "this briefing is for ${printed?.overview?.flight ?: "another flight"}") + "."
            BannerKind.SAVE_OLDER -> "The printed briefing (" + listOfNotNull(printed?.overview?.flight, printed?.generated).joinToString(", ") +
                ") is older than your save and for another flight: showing ${plan?.flight?.row?.callsign ?: plan?.callsign ?: "the Planner's flight"} from the Planner. PRINT in BMS for the full briefing."
            BannerKind.SAVE_ONLY -> "No printed briefing: showing ${plan?.flight?.row?.callsign ?: plan?.callsign ?: "the Planner's flight"}" +
                (plan?.ref?.file?.takeIf { it.isNotBlank() }?.let { " from your save $it" } ?: " from your save") +
                ". PRINT in BMS for the situation, weather, comm ladder and ROE."
            BannerKind.NO_BRIEFING -> "Plan from the Planner, with no briefing: only the cartridge's items are shown (targets, threats, lines, presets). PRINT in BMS for the briefing."
            BannerKind.APPLIED -> "Plan from the Planner" + (plan?.from?.let { " (" + deviceWord(it) + ")" } ?: "") + " — " + appliedDetail(notInJet, reprinted)
        }
    }

    /** The applied banner after its dash: how much of the plan the jet does not have yet, and what to do about it. */
    fun appliedDetail(notInJet: List<String>, reprinted: Boolean): String = when {
        notInJet.isEmpty() -> "all of it is in the jet."
        else -> "${notInJet.size} ${if (notInJet.size == 1) "item" else "items"} not in the jet (${notInJet.take(4).joinToString(", ")}${if (notInJet.size > 4) " …" else ""}): Save to DTC in the Planner, then LOAD in BMS's DTC window. Save DTC, then PRINT/Generate, for the in-game kneeboards."
    } + if (reprinted) " Briefing re-printed after the plan." else ""

    /** "PC", "Android", "Browser" as a sentence says them. */
    fun deviceWord(from: String): String = when (from) {
        "PC" -> "the PC"
        "Browser" -> "a browser"
        else -> from
    }

    private fun flightName(plan: PlanOverlay?): String {
        val call = plan?.callsign ?: plan?.flight?.row?.callsign ?: "another flight"
        val pkg = plan?.packageId ?: plan?.flight?.packageNumber?.takeIf { it > 0 }?.toString()
        return if (pkg != null) "$call (package $pkg)" else call
    }

    private fun String.key() = lowercase().filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() }

    private fun MissionRoute.bullseye(): Pair<Double, Double>? {
        val x = bullseyeX ?: return null
        val y = bullseyeY ?: return null
        return if (x == 0.0 && y == 0.0) null else x to y
    }

    private fun DtcPoint.nonZero() = x != 0.0 || y != 0.0

    private fun far(ax: Double, ay: Double, bx: Double, by: Double) = hypot(ax - bx, ay - by) > SAME_FT

    /** Short name for a waypoint action, as WDP's `ActionString` words it (cartridge action codes). */
    fun actionWord(action: Int): String = when (action) {
        -1 -> "Target"; 0 -> "Nav"; 1 -> "Takeoff"; 2 -> "Push"; 3 -> "Split"; 4 -> "Refuel"; 5 -> "Rearm"; 6 -> "Pickup"
        7 -> "Land"; 8 -> "Holding"; 9 -> "Contact"; 10 -> "Escort"; 11 -> "Sweep"; 12 -> "CAP"; 13 -> "Intercept"
        14 -> "GndStrike"; 15 -> "NavStrike"; 16 -> "SAD"; 17 -> "Strike"; 18 -> "Bomb"; 19 -> "SEAD"; 20 -> "ELINT"
        21 -> "Recon"; 22 -> "Rescue"; 23 -> "ASW"; 24 -> "Fuel"; 25 -> "Airdrop"; 26 -> "Jam"
        else -> "STPT"
    }
}

/** Where a merged item came from. */
enum class PlanItemSource {
    /** the printed briefing (or the save's, when [MergedMission.fromSave]) */
    BRIEFING,
    /** Falcon BMS's mission file beside the save (`MissionData.route`; a route with `fromSave` is [SAVE]) */
    ROUTE,
    /** the pilot's cartridge on disk (`MissionData.dtc`) */
    CARTRIDGE,
    /** the flight's own waypoint in the save the Planner opened (the cell's middle), when nothing better places it */
    SAVE,
    /** the jet, in 3D (shared memory) */
    JET,
    /** the plan sent from the Planner (`MissionData.plan`) */
    PLAN,
}

/** Which of the banner's sentences [MergedMission.banner] is. */
enum class BannerKind {
    /** a plan is applied over a printed briefing (or its cartridge items over none) */
    APPLIED,
    /** a plan is set aside: the printed briefing is now for another flight */
    PARKED,
    /** the printed briefing is for another flight and older than the plan's save: the save's flight is shown */
    SAVE_OLDER,
    /** no printed briefing: the save's flight is the mission */
    SAVE_ONLY,
    /** a plan with no briefing and no flight: cartridge items only */
    NO_BRIEFING,
}

/**
 * The merged mission. [briefing] keeps every row and field of the briefing it was made from; [plan] is the applied
 * plan (null when none was sent, or it is parked — then [parked] holds it and [banner] says why). The lists are the
 * merged items the views draw; each says where it came from.
 */
data class MergedMission(
    val briefing: Briefing? = null,
    val dtc: Dtc? = null,
    val route: MissionRoute? = null,
    val plan: PlanOverlay? = null,
    val parked: PlanOverlay? = null,
    /** flight-plan steerpoints (1-24), in order, the ones the route line joins */
    val steerpoints: List<MergedPoint> = emptyList(),
    /** target steerpoints that are not flight-plan points (action −1, the 15-22 bank, 81-99) and weapon targets */
    val targets: List<MergedPoint> = emptyList(),
    /** pre-planned threats, slots 56-70 (with a differing plan PPT beside the jet's in 3D) */
    val ppts: List<MergedPpt> = emptyList(),
    /** the cartridge's lines (up to 4 groups of 6), each a list of points in order, zero points left out */
    val lines: List<List<MergedPoint>> = emptyList(),
    /** UHF then VHF presets, with the plan's changes marked */
    val presets: List<MergedPreset> = emptyList(),
    /** the attack the plan carries (drawn on the Mission map and the VR maps) */
    val attack: AttackOverlay? = null,
    /** what the jet will not have until the pilot saves and loads the DTC ("STPT 5", "PPT 57") */
    val notInJet: List<String> = emptyList(),
    /** one line for the top of the Mission section, or null */
    val banner: String? = null,
    // added by M1
    /** every steerpoint slot 1-25 in number order — briefing rows, the plan's own rows, cartridge and mission-file points — each flagged; the tables and the map's markers use this, the route line only [steerpoints] */
    val allSteerpoints: List<MergedPoint> = emptyList(),
    /** the Plan card: laser, bingo, ALOW, MSL floor, EWS names, presets at load, TACAN/ILS */
    val settings: List<MergedSetting> = emptyList(),
    val bannerKind: BannerKind? = null,
    /** BMS is in 3D and the jet's own navigation points are in: positions are the jet's */
    val inJet: Boolean = false,
    /** the bullseye from the save's own header (x north, y east) — before 3D only, marked "from the save" */
    val saveBullseye: Pair<Double, Double>? = null,
    /** the printed briefing as BMS wrote it, even when [briefing] is the save's */
    val printed: Briefing? = null,
    /** the save the plan's flight came from ("Auto Save.cam") */
    val saveFile: String? = null,
    /** the briefing was printed again after the plan was sent */
    val reprinted: Boolean = false,
) {
    /** a plan is applied: the views mark what came from it */
    val planApplied: Boolean get() = plan != null
    /** the briefing was made from the save the Planner opened, not printed by BMS */
    val fromSave: Boolean get() = briefing?.origin == "save"
    /** the flight-plan points that have a position: the route line */
    val routeLine: List<MergedPoint> get() = steerpoints.filter { it.hasPos }
    /** the home field's steerpoint: STPT 1 of the merged route (the take-off), else the first flight-plan point placed */
    val home: MergedPoint? get() = allSteerpoints.firstOrNull { it.n == 1 && it.hasPos } ?: routeLine.firstOrNull()
    /** anything on the page came from the plan */
    val anyFromPlan: Boolean get() = planApplied && (allSteerpoints.any { it.source == PlanItemSource.PLAN } || targets.any { it.source == PlanItemSource.PLAN } ||
        ppts.any { it.source == PlanItemSource.PLAN } || lines.any { l -> l.any { it.source == PlanItemSource.PLAN } } || presets.any { it.changed } ||
        settings.any { it.source == PlanItemSource.PLAN } || attack != null)
}

/** One point after the merge: where it is, what it is, and where that came from. */
data class MergedPoint(
    val n: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val altFt: Double = 0.0,
    val action: Int = 0,
    val name: String? = null,
    val source: PlanItemSource = PlanItemSource.BRIEFING,
    /** the plan's position differs from the jet's (or from the cartridge on disk, before 3D) */
    val notInJet: Boolean = false,
    /** a steerpoint the briefing does not have, appended as a PLAN row */
    val planRow: Boolean = false,
    /** the plan cleared this slot while the jet (or the disk) still has it */
    val cleared: Boolean = false,
    // added by M1: the briefing row's own words (null for a point with no row), and how the views treat the point
    val desc: String? = null,
    val time: String? = null,
    val cas: String? = null,
    val altText: String? = null,
    val actionText: String? = null,
    val comments: String? = null,
    /** a flight-plan point: the route line joins it */
    val onRoute: Boolean = false,
    /** a target (action −1, or a briefing row that attacks) */
    val isTarget: Boolean = false,
    /** a weapon target (the 100 SPICE targets), not a steerpoint */
    val weapon: Boolean = false,
    /** a line point: which line (1-4) */
    val line: Int = 0,
    /** where the plan puts it, when that is not [x]/[y] (in 3D the jet's position is shown and the plan's drawn hollow) */
    val planX: Double? = null,
    val planY: Double? = null,
    /** where the jet has it (before 3D: will load it), when that is not [x]/[y] */
    val jetX: Double? = null,
    val jetY: Double? = null,
) {
    val hasPos: Boolean get() = x != 0.0 || y != 0.0
    val isAlternate: Boolean get() = comments?.contains("Alternate", ignoreCase = true) == true
    /** what a row is called: the briefing's words, the point's name, or its action */
    val title: String get() = desc ?: name ?: if (planRow) PlanMerge.actionWord(action) else "STPT $n"
}

/** One pre-planned threat after the merge. [marker]: a point with no ring (AWACS, tanker, a friendly). */
data class MergedPpt(
    val n: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val rangeFt: Double = 0.0,
    val code: String? = null,
    val name: String? = null,
    val marker: Boolean = false,
    val source: PlanItemSource = PlanItemSource.CARTRIDGE,
    val notInJet: Boolean = false,
    // added by M1
    /** the plan cleared this PPT while the cartridge on disk still holds it */
    val cleared: Boolean = false,
) {
    val rangeNm: Double get() = rangeFt / 6076.12
}

/** One radio preset after the merge. [band] is "UHF" or "VHF"; [changed] marks a plan preset that differs. */
data class MergedPreset(
    val band: String = "UHF",
    val ch: Int = 0,
    val freq: String = "",
    val comment: String? = null,
    val source: PlanItemSource = PlanItemSource.CARTRIDGE,
    val changed: Boolean = false,
    val notInJet: Boolean = false,
    // added by M1
    /** the cartridge's frequency for this preset before the plan changed it */
    val was: String? = null,
)

/**
 * One line of the Plan card: [label] and [value] as shown. [untested] marks a key only WDP writes (`[Laser]`, `[ICP]`,
 * the `[COMMS]` TACAN and ILS): BMS 4.38.1's own cartridge has none of them, and whether BMS reads them is not
 * established. [was] is the cartridge's value when the plan changed it.
 */
data class MergedSetting(
    val key: String = "",
    val label: String = "",
    val value: String = "",
    val source: PlanItemSource = PlanItemSource.CARTRIDGE,
    val notInJet: Boolean = false,
    val untested: Boolean = false,
    val was: String? = null,
)

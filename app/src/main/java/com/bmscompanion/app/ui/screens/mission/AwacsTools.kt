package com.bmscompanion.app.ui.screens.mission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.ui.components.MapState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// AWACS / GCI tools: state of the AWACS page and the geometry behind its calls.
// Theater coordinates: x = north ft, y = east ft; bearings true (BMS magnetic variation is 0).

/** AWACS layer settings, remembered across launches. */
object AwacsSettings {
    private fun b(key: String, def: Boolean) = mutableStateOf(Repo.getInt("aw_$key", if (def) 1 else 0) == 1)
    private fun i(key: String, def: Int) = mutableIntStateOf(Repo.getInt("aw_$key", def))

    var friendlies by b("fr", true)
    var hostiles by b("ho", true)
    var unknowns by b("un", true)
    var ships by b("sh", true)
    var missiles by b("mi", true)
    var crews by b("cr", false)
    var labels by b("lb", true)
    var trails by b("tr", true)
    var groups by b("gr", true)
    var locks by b("lk", true)
    var fields by b("fd", false)
    var threats by b("th", false)
    var commitRings by b("cm", false)
    /** speed vector length: where the contact will be in this many minutes at its ground speed (0 = off; 1–3) */
    var vectorMin by mutableIntStateOf(Repo.getInt("aw_vm", 1).coerceIn(0, 3))
    var bullRingNm by i("br", 20)
    var groupNm by i("gn", 5)
    var commitNm by i("cn", 30)
    /** altitude filter in thousands of feet */
    var altMinK by i("amin", 0)
    var altMaxK by i("amax", 80)
    /** fuel flow assumed for fuel estimates of other aircraft (pph) */
    var cruiseFf by i("ff", 3500)

    fun save() {
        fun p(k: String, v: Boolean) = Repo.putInt("aw_$k", if (v) 1 else 0)
        p("fr", friendlies); p("ho", hostiles); p("un", unknowns); p("sh", ships); p("mi", missiles); p("cr", crews)
        p("lb", labels); p("tr", trails); p("gr", groups); p("lk", locks); p("fd", fields); p("th", threats); p("cm", commitRings)
        Repo.putInt("aw_vm", vectorMin); Repo.putInt("aw_br", bullRingNm); Repo.putInt("aw_gn", groupNm); Repo.putInt("aw_cn", commitNm)
        Repo.putInt("aw_amin", altMinK); Repo.putInt("aw_amax", altMaxK); Repo.putInt("aw_ff", cruiseFf)
    }
}

sealed interface AwPick {
    data class Ctc(val id: String) : AwPick
    data class Pt(val x: Double, val y: Double) : AwPick
}

enum class AwPanel(val label: String) { PICTURE("Picture"), CONTACT("Contact"), CONTROL("Control"), MEASURE("Measure"), CALC("Calc"), LAYERS("Layers") }

data class TrailPt(val x: Double, val y: Double)

/** Per-screen AWACS state (selection, controlled flight, open panel, calculator inputs, trails). Survives tab switches. */
class AwacsState {
    val mapState = MapState()
    var framed = false
    var selA by mutableStateOf<AwPick?>(null)
    var selB by mutableStateOf<AwPick?>(null)
    var measuring by mutableStateOf(false)
    var reference by mutableStateOf<String?>(null)
    var panel by mutableStateOf(AwPanel.PICTURE)

    // calculator inputs (text so partially typed numbers survive)
    var calcDist by mutableStateOf("120")
    var calcGs by mutableStateOf("450")
    var calcFf by mutableStateOf("3500")
    var calcReserve by mutableStateOf("1500")
    var convFrBrg by mutableStateOf("")
    var convFrRng by mutableStateOf("")
    var convGrBrg by mutableStateOf("")
    var convGrRng by mutableStateOf("")
    var mergeRange by mutableStateOf("40")
    var mergeClosure by mutableStateOf("900")

    /** last positions per contact, oldest first (sampled once per contacts update, ~2 minutes kept) */
    val trails = HashMap<String, ArrayDeque<TrailPt>>()
    private var lastSample = 0L

    fun sample(contacts: List<Contact>, t: Long) {
        if (t == lastSample) return
        lastSample = t
        val alive = HashSet<String>()
        contacts.forEach { c ->
            if (c.kind == "bullseye") return@forEach
            alive += c.id
            val q = trails.getOrPut(c.id) { ArrayDeque() }
            val last = q.lastOrNull()
            if (last == null || hypot(last.x - c.x, last.y - c.y) > 300) q.addLast(TrailPt(c.x, c.y))
            while (q.size > 24) q.removeFirst()
        }
        trails.keys.retainAll(alive)
    }
}

// ---------------- geometry ----------------

/** Velocity in knots as (north, east). */
fun velocity(hdg: Double, gs: Double): Pair<Double, Double> {
    val r = Math.toRadians(hdg)
    return gs * cos(r) to gs * sin(r)
}

fun hdgOf(north: Double, east: Double): Double = (Math.toDegrees(atan2(east, north)) + 360) % 360

/** Positive when the two tracks close on each other, knots. */
fun closureKts(ax: Double, ay: Double, aHdg: Double, aGs: Double, bx: Double, by: Double, bHdg: Double, bGs: Double): Double {
    val dn = (bx - ax) / NM
    val de = (by - ay) / NM
    val r = hypot(dn, de).takeIf { it > 1e-6 } ?: return 0.0
    val (avn, ave) = velocity(aHdg, aGs)
    val (bvn, bve) = velocity(bHdg, bGs)
    return -((bvn - avn) * dn + (bve - ave) * de) / r
}

data class Intercept(val heading: Double, val minutes: Double, val rangeNm: Double)

/**
 * Collision-course intercept: the heading an interceptor at (px, py) flying [speed] kt must hold to meet a target
 * moving at [tHdg]/[tGs]. Null when it can't catch the target.
 */
fun intercept(px: Double, py: Double, speed: Double, tx: Double, ty: Double, tHdg: Double, tGs: Double): Intercept? {
    if (speed <= 1) return null
    val dn = (tx - px) / NM
    val de = (ty - py) / NM
    val (vn, ve) = velocity(tHdg, tGs)
    val a = vn * vn + ve * ve - speed * speed
    val b = 2 * (dn * vn + de * ve)
    val c = dn * dn + de * de
    val t = if (abs(a) < 1e-9) {
        if (abs(b) < 1e-9) return null
        -c / b
    } else {
        val disc = b * b - 4 * a * c
        if (disc < 0) return null
        val s = sqrt(disc)
        listOf((-b - s) / (2 * a), (-b + s) / (2 * a)).filter { it > 0 }.minOrNull() ?: return null
    }
    if (t <= 0 || t > 3) return null // more than 3 hours: not a useful intercept
    val hn = dn + vn * t
    val he = de + ve * t
    return Intercept(hdgOf(hn, he), t * 60, hypot(hn, he))
}

private val cardinals = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
fun cardinal(hdg: Double): String = cardinals[(((hdg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

fun b3(v: Double) = String.format(Locale.US, "%03d", (v.roundToInt() % 360 + 360) % 360)

fun altCall(ft: Double): String = when {
    ft < 1000 -> "low"
    else -> "${(ft / 1000).roundToInt()} thousand"
}

fun Contact.isAirborneTrack() = kind == "air" || kind == "heli"

/** A group of hostile air contacts that are close together (single-link within [radiusNm]). */
class AwGroup(val members: List<Contact>) {
    val x = members.sumOf { it.x } / members.size
    val y = members.sumOf { it.y } / members.size
    val minAlt = members.minOf { it.altFt }
    val maxAlt = members.maxOf { it.altFt }
    val gs = members.sumOf { it.gsKts } / members.size
    val hdg: Double = members.map { velocity(it.hdg, 1.0) }.let { v -> hdgOf(v.sumOf { it.first }, v.sumOf { it.second }) }
    val lead = members.first()
    val name get() = lead.group ?: lead.name ?: "Group"
    val types get() = members.mapNotNull { it.name }.distinct().joinToString("/")
    val size get() = members.size
}

fun groupContacts(list: List<Contact>, radiusNm: Int): List<AwGroup> {
    val left = list.toMutableList()
    val out = ArrayList<AwGroup>()
    val r = radiusNm * NM
    while (left.isNotEmpty()) {
        val group = mutableListOf(left.removeAt(0))
        var grew = true
        while (grew) {
            grew = false
            val it = left.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (group.any { g -> hypot(g.x - c.x, g.y - c.y) <= r }) { group += c; it.remove(); grew = true }
            }
        }
        out += AwGroup(group.sortedByDescending { it.altFt })
    }
    return out
}

/** Group strength, said last: ", heavy" for 3 or more, ", 2 contacts" for a pair. */
private fun sizeCall(g: AwGroup) = when {
    g.size >= 3 -> ", heavy"
    g.size == 2 -> ", 2 contacts"
    else -> ""
}

private fun speedCall(g: AwGroup) = when {
    g.gs >= 900 -> ", very fast"
    g.gs >= 600 -> ", fast"
    else -> ""
}

private fun stackCall(g: AwGroup) = if (g.maxAlt - g.minAlt >= 4000) "stack ${altCall(g.minAlt).removeSuffix(" thousand")} and ${altCall(g.maxAlt)}" else altCall(g.maxAlt)

/** "GROUP BULLSEYE 270/35, 20 THOUSAND, TRACK NORTH, HOSTILE" */
fun bullseyeCall(g: AwGroup, bull: Pair<Double, Double>, label: String = "group"): String {
    val (brg, rng) = com.bmscompanion.app.ui.screens.bearingRange(bull.first, bull.second, g.x, g.y)
    return "$label bullseye ${b3(brg)}/${rng.roundToInt()}, ${stackCall(g)}, track ${cardinal(g.hdg)}${speedCall(g)}, hostile${sizeCall(g)}".uppercase(Locale.US)
}

/** "COLT1, GROUP BRAA 045/22, 18 THOUSAND, HOT, HOSTILE" */
fun braaCall(friendly: Contact, g: AwGroup): String {
    val (brg, rng) = com.bmscompanion.app.ui.screens.bearingRange(friendly.x, friendly.y, g.x, g.y)
    val asp = aspect(g.hdg, g.x, g.y, friendly.x, friendly.y)
    return "${friendly.group ?: friendly.name}, group BRAA ${b3(brg)}/${rng.roundToInt()}, ${stackCall(g)}, $asp${speedCall(g)}, hostile${sizeCall(g)}".uppercase(Locale.US)
}

/** "COLT1, VECTOR 315 FOR 28, GROUP 24 THOUSAND, INTERCEPT IN 3 MIN" */
fun vectorCall(friendly: Contact, g: AwGroup, i: Intercept): String =
    "${friendly.group ?: friendly.name}, vector ${b3(i.heading)} for ${i.rangeNm.roundToInt()}, ${stackCall(g)}, intercept in ${i.minutes.roundToInt()} min".uppercase(Locale.US)

/** Full picture: "PICTURE, 2 GROUPS. NORTH GROUP BULLSEYE …; SOUTH GROUP …" */
fun pictureCall(groups: List<AwGroup>, bull: Pair<Double, Double>?): String {
    if (groups.isEmpty()) return "PICTURE CLEAN"
    if (bull == null) return "PICTURE, ${groups.size} GROUP${if (groups.size > 1) "S" else ""} (no bullseye for positions)"
    if (groups.size == 1) return "PICTURE, SINGLE GROUP. ${bullseyeCall(groups[0], bull)}"
    val cx = groups.sumOf { it.x } / groups.size
    val cy = groups.sumOf { it.y } / groups.size
    val names = groups.map { g -> cardinal(com.bmscompanion.app.ui.screens.bearingRange(cx, cy, g.x, g.y).first) }
    val unique = names.toSet().size == names.size && groups.size <= 4
    val parts = groups.mapIndexed { i, g -> bullseyeCall(g, bull, if (unique) "${names[i]} group" else "group ${i + 1}") }
    return "PICTURE, ${groups.size} GROUPS. " + parts.joinToString(". ")
}

data class AwAlert(val text: String, val severity: Int, val focus: Contact)

/** Merges, commits, radar locks and low fuel for every friendly aircraft. */
fun awacsAlerts(ctcs: List<Contact>, commitNm: Int): List<AwAlert> {
    val byId = ctcs.associateBy { it.id }
    val friendlies = ctcs.filter { it.friendly && it.isAirborneTrack() && !it.isCrew() }
    val hostiles = ctcs.filter { !it.friendly && it.isAirborneTrack() && !it.isCrew() && !it.coalition.isNullOrBlank() }
    val out = ArrayList<AwAlert>()
    friendlies.forEach { f ->
        val who = f.group ?: f.name ?: "Friendly"
        hostiles.minByOrNull { hypot(it.x - f.x, it.y - f.y) }?.let { h ->
            val r = hypot(h.x - f.x, h.y - f.y) / NM
            if (r <= 5) out += AwAlert("$who MERGED with ${h.group ?: h.name}", 3, f)
            else if (r <= commitNm) out += AwAlert("$who: ${h.group ?: h.name} ${r.roundToInt()} nm ${aspect(h.hdg, h.x, h.y, f.x, f.y)}", 2, h)
        }
        f.fuelLb?.let { if (it in 1.0..1500.0) out += AwAlert("$who fuel ${it.roundToInt()} lb", 2, f) }
    }
    ctcs.forEach { c ->
        val target = c.locked?.let { byId[it] } ?: return@forEach
        if (!c.friendly && target.friendly) out += AwAlert("${target.group ?: target.name} SPIKED by ${c.group ?: c.name}", 3, target)
    }
    ctcs.filter { it.kind == "missile" && !it.friendly }.forEach { m ->
        friendlies.minByOrNull { hypot(it.x - m.x, it.y - m.y) }?.let { f ->
            val r = hypot(f.x - m.x, f.y - m.y) / NM
            if (r <= 15) out += AwAlert("MISSILE ${r.roundToInt()} nm from ${f.group ?: f.name}", 3, f)
        }
    }
    // wingmen of one flight report the same threat: keep one line per text
    return out.distinctBy { it.text }.sortedByDescending { it.severity }
}

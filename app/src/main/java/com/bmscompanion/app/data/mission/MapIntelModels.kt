package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

// What the Planner's Map page draws of a save beyond the flight itself (WDP's MAP tab, brought across in 1.3.8):
// every ground unit and task force with its side, what the flight's side has seen of it and whether its radar still
// works; the objectives that carry a search radar; the airfields with the side holding each now and the squadrons
// based there; the JSTARS stations of the flight's side. GET /api/campaign/mapintel (docs/PROTOCOL.md, "Map intel").
// And the theater's magnetic variation, GET /api/campaign/magvar.
//
// Read on the PC from the save, its start file, the theater's class tables and BMS's own variation map; nothing is
// written. Every field has a default, so a PC a version ahead or behind never breaks decoding. Positions are theater
// feet, x north and y east, as everywhere in this protocol; times are campaign milliseconds (day 1 00:00 = 0).

/**
 * A save as the Map page draws it: `GET /api/campaign/mapintel?theater=&file=[&flight=][&team=]` (added by the PC in
 * 1.3.8; an older PC answers 404, and the page then falls back on [CampFlight.airDefences] and [CampFlight.ships]).
 *
 * [file]/[flight] name what was used (with `file` left out, the save and flight BMS briefed). [team] is the flight's
 * team (or the `team` asked for), [controller] the team that controls it, whose spotted bit counts. Every `side` is
 * said from [team]'s point of view: "friendly", "hostile", "neutral", or "unknown" when [team] is 0. [notes] says, in
 * sentences, what could not be read (the objective changes, the class tables…) and what that means.
 */
@Serializable
data class CampMapIntel(
    val theater: String = "",
    val file: String = "",
    /** the save's file time, ms since 1970 */
    val modified: Long = 0,
    /** the save's campaign clock, the same as [CampFlight.clock] */
    val clock: Long = 0,
    /** the flight whose side this is, "num/creator"; null when none */
    val flight: String? = null,
    /** that flight's team, or the `team` asked for; 0 = unknown, and then every side is "unknown" */
    val team: Int = 0,
    /** the team controlling [team] (whose spotted bit counts) */
    val controller: Int = 0,
    val teams: List<CampMapTeam> = emptyList(),
    val units: List<CampMapUnit> = emptyList(),
    val radars: List<CampMapRadar> = emptyList(),
    val fields: List<CampMapField> = emptyList(),
    val jstars: List<CampMapJstar> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** One of the save's teams: its number, name, the colour the campaign header gives it (0xRRGGBB) and its side. */
@Serializable
data class CampMapTeam(
    val n: Int = 0,
    val name: String = "",
    val colour: Int = 0,
    val side: String = "unknown",
)

/**
 * A ground battalion (`domain` "land") or a naval task force ("sea") of the save; brigades are left out, because
 * their battalions are listed.
 *
 * [kind] is what it is, from the class table's sub-type: land "airdefence", "armor", "cavalry", "infantry",
 * "mechanized", "marine", "airmobile", "engineer", "artillery" (self-propelled), "towed", "rocket", "missile",
 * "supply", "hq", "recon", "other"; sea "carrier", "cruiser", "destroyer", "frigate", "patrol", "tanker", "ship".
 * [system] is its main vehicle as the class tables name it ("SA-2 (S-75)", "S-60", "Osa II CLS"), as [CampSite.system];
 * [shorad] the short-range air defence a unit of another kind carries ("SA-13 (9K35)"), so the unit's threat type is
 * `shorad ?: system`.
 *
 * [spotted]: the save's spotted bit of [CampMapIntel.controller] or [CampMapIntel.team] (always true for a friendly
 * unit); [seen] when it was last seen (its spot time, else its last combat; null for neither); [recent]: WDP's
 * recon-loss rule — seen within 60 min (not moving), 20 min (foot), 10 min (tracked, naval, rail) or 1 min (air)
 * before the clock, a wheeled unit never; [jstar]: within [CampMapJstar.rangeNm] of a JSTARS station of the flight's
 * side that is on station now. [radar] is "alive", "dead" (the radar vehicles are gone), "none" (the unit has no radar
 * vehicle: guns, MANPADS, most units that are not air defence) or "unknown". [moving] and [dead] are the unit's own
 * flags. [alive]/[total]: vehicles left and vehicles in the unit.
 */
@Serializable
data class CampMapUnit(
    val id: String = "",
    val campId: Int = 0,
    val kind: String = "other",
    val domain: String = "land",
    val system: String = "",
    val shorad: String? = null,
    val name: String? = null,
    val owner: Int = 0,
    val side: String = "unknown",
    /** north ft, the unit's cell middle */
    val x: Double = 0.0,
    /** east ft */
    val y: Double = 0.0,
    val spotted: Boolean = false,
    val seen: Long? = null,
    val recent: Boolean = false,
    val jstar: Boolean = false,
    /** the unit table's MoveType: 0 none, 1 foot, 2 wheeled, 3 tracked, 4 low air, 5 air, 6 naval, 7 rail */
    val moveType: Int? = null,
    val radar: String = "unknown",
    val moving: Boolean = false,
    val dead: Boolean = false,
    val alive: Int? = null,
    val total: Int? = null,
)

/**
 * An objective that carries a search radar: a radar station, a SAM site, an airbase's approach radar. Listed when the
 * objective is flagged or built as one and its buildings include a radar (Falcon4_FCD.xml names it "Radar …").
 * [working]: one of those radars stands ([intact] of [radars] are not destroyed), from the save's objective changes,
 * else the start file's. The page draws WDP's 25 nm ring; the PC sends no range, because BMS publishes none.
 */
@Serializable
data class CampMapRadar(
    val id: String = "",
    val campId: Int = 0,
    val name: String = "",
    /** the objective type as Strings.txt names it ("Radar Station", "SAM Site", "Airbase") */
    val type: String = "",
    /** north ft, the objective's own position */
    val x: Double = 0.0,
    /** east ft */
    val y: Double = 0.0,
    /** the team holding it now (the save's objective change, else the start file's owner) */
    val owner: Int = 0,
    val side: String = "unknown",
    val working: Boolean = false,
    val radars: Int = 0,
    val intact: Int = 0,
)

/**
 * An airbase or airstrip objective of the save, with the side holding it now and what is based there. [airport] is the
 * app's airport id when the field's campaign id matches one (as [CampPlace] kinds "airbase"). [owner] the team now,
 * [startOwner] the start file's.
 */
@Serializable
data class CampMapField(
    val id: String = "",
    val campId: Int = 0,
    val name: String = "",
    /** "Airbase" | "Airstrip" (as Strings.txt names the objective type) */
    val type: String = "",
    val airport: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val owner: Int = 0,
    val side: String = "unknown",
    val startOwner: Int = 0,
    val squadrons: List<CampMapSquadron> = emptyList(),
)

/** A squadron based at a [CampMapField]: what it flies, and for the flight's own side its name ("36th FS (F-16C)"). */
@Serializable
data class CampMapSquadron(
    val aircraft: String = "",
    /** the squadron's name; null for a squadron of another side */
    val name: String? = null,
    val owner: Int = 0,
    val side: String = "unknown",
    /** aircraft it has, when the save says (the squadron's vehicle count); null when it does not */
    val count: Int? = null,
)

/**
 * A JSTARS of the flight's side (mission 27): its station, the middle of the leg its ELINT action starts (cell
 * middles), and when it is there. [active]: [from] <= the save's clock <= [to]. Units within [rangeNm] of an active
 * one count as seen ([CampMapUnit.jstar]).
 */
@Serializable
data class CampMapJstar(
    val callsign: String = "",
    val aircraft: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val from: Long = 0,
    val to: Long = 0,
    val active: Boolean = false,
    val rangeNm: Int = 200,
)

/**
 * A theater's magnetic variation, as Falcon BMS ships it: `GET /api/campaign/magvar[?theater=]` (added by the PC in
 * 1.3.8; 404 when the theater has no variation map, and the page then hides VAR). The grid is BMS's own
 * `<terraindir>\Weather\MagVarMap_<terrain>.csv`: [xKm] the columns (km east), [yKm] the rows (km north, ascending),
 * [deg] row-major, degrees, negative = west.
 */
@Serializable
data class CampMagVar(
    val theater: String = "",
    /** the file, relative to the BMS folder */
    val file: String = "",
    val xKm: List<Double> = emptyList(),
    val yKm: List<Double> = emptyList(),
    val deg: List<Float> = emptyList(),
) {
    /**
     * The variation at [northFt]/[eastFt] (theater feet), in degrees (negative = west): bilinear between the four grid
     * points around it, the position clamped to the grid. Null when the grid is empty or malformed.
     */
    fun at(northFt: Double, eastFt: Double): Double? {
        val nx = xKm.size
        val ny = yKm.size
        if (nx == 0 || ny == 0 || deg.size < nx * ny) return null
        val x = eastFt / FEET_PER_KM
        val y = northFt / FEET_PER_KM
        fun span(axis: List<Double>, v: Double): Pair<Int, Double> {
            if (axis.size == 1 || v <= axis[0]) return 0 to 0.0
            if (v >= axis[axis.size - 1]) return (axis.size - 2) to 1.0
            var i = 0
            while (i < axis.size - 2 && v >= axis[i + 1]) i++
            val w = axis[i + 1] - axis[i]
            return i to (if (w > 0.0) (v - axis[i]) / w else 0.0)
        }
        val (c, fx) = span(xKm, x)
        val (r, fy) = span(yKm, y)
        val c1 = if (nx == 1) 0 else c + 1
        val r1 = if (ny == 1) 0 else r + 1
        fun v(row: Int, col: Int) = deg[row * nx + col].toDouble()
        val south = v(r, c) + (v(r, c1) - v(r, c)) * fx
        val north = v(r1, c) + (v(r1, c1) - v(r1, c)) * fx
        return south + (north - south) * fy
    }

    companion object {
        /** A grid kilometre in theater feet (the campaign's cell). */
        const val FEET_PER_KM = 3279.98
    }
}

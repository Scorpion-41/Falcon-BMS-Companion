package com.bmscompanion.app.data.airfield

import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.Live
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where the jet is on a carrier's deck, in the deck's own frame.
 *
 * A ship's chart is drawn in its model's frame: north up the page is the ship's bow, east its starboard side, and the
 * origin the model's own ([AfShip]). But a ship moves. The field origin the airport list gives for a carrier is
 * where the campaign put it at the start, which has nothing to do with where it is now, so the jet cannot be put on
 * the deck by subtracting a fixed point the way it is put on an airfield ([fieldOffset]).
 *
 * The ship's live position and heading come from the **Tacview feed**: BMS sends every ship in it, the server moves
 * the whole feed into the same frame as the jet's own shared-memory position (`TacviewClient.snapshot`), and the ship
 * object's position is its model's origin — the origin the deck is drawn about. So the jet's offset from the ship,
 * turned by the ship's heading, is where it stands on the deck, and its heading less the ship's is which way it
 * points there.
 *
 * Checked against a real recording: four Hornets parked on the Roosevelt while she turned through 130° stayed on
 * BMS's own spots for her — (98,111), (97,179), (102,-25), (102,-83) — to 2 ft across and 4 ft along, so the feed's
 * ship position is the model's origin and its heading (Tacview's last transform field; the yaw field is a constant
 * -90 for every ship) is the ship's own, in the same frame as the jet's.
 *
 * The two halves do not arrive together. The jet comes from shared memory four times a second, the feed once a
 * second, so the ship in hand is up to a second old — and a carrier at 20 kt covers 34 ft in that second, which put
 * the jet creeping up the deck and snapping back. The ship is therefore sailed on to the jet's moment, along its
 * heading at its own speed ([feedT] is when the feed was taken, [Live.t] when the jet was read, both on the
 * server's clock).
 *
 * Without the feed (ACMI recording off) there is no ship to measure from, and null comes back: the chart then draws
 * the deck with no jet on it rather than a jet in the wrong place. Nothing in BMS's shared memory gives a ship's
 * position.
 */
data class DeckFix(
    /** feet to starboard of the ship's origin: the chart's east */
    val e: Double,
    /** feet forward of it: the chart's north */
    val n: Double,
    /** the jet's heading measured from the ship's bow, which is the chart's north */
    val heading: Double,
    /** the ship the position was measured from, as the feed names it */
    val shipName: String?,
)

/** How far from a ship's origin a jet can be and still be on it: half the longest deck, with room to spare. */
const val ON_DECK_FT = 700.0

/** One knot, in feet a second. */
private const val FT_PER_KT_SECOND = 6076.12 / 3600.0

/** The most the ship is sailed on to meet the jet: the feed comes once a second, so anything longer is a stale feed. */
private const val MAX_FEED_LAG_S = 3.0

/**
 * The jet on [field]'s deck, or null when [field] is not a ship, there is no jet, or the Tacview feed has no ship
 * under it. When the feed names the ship with a hull number and the chart's name carries one too, the two must agree:
 * a jet on the Roosevelt is not drawn on the Vinson's chart because the two sail together.
 */
fun deckPosition(field: Airfield, live: Live?, contacts: List<Contact>?, feedT: Long = 0L): DeckFix? {
    if (field.ship == null) return null
    val jet = live?.takeIf { it.x != 0.0 || it.y != 0.0 } ?: return null
    val ship = contacts.orEmpty()
        .filter { it.kind == "ship" }
        .minByOrNull { hypot(it.x - jet.x, it.y - jet.y) }
        ?.takeIf { hypot(it.x - jet.x, it.y - jet.y) < ON_DECK_FT } ?: return null
    if (!sameHull(field.name, ship.name)) return null
    val h = ship.hdg * PI / 180.0
    // where the ship had got to when the jet was read; a feed more than a few seconds out is not stretched further
    val dt = if (feedT > 0L && jet.t > 0L) ((jet.t - feedT) / 1000.0).coerceIn(-MAX_FEED_LAG_S, MAX_FEED_LAG_S) else 0.0
    val run = ship.gsKts * FT_PER_KT_SECOND * dt
    // offsets east and north of the ship, turned into its frame: along its bow, and to its starboard
    val de = jet.y - (ship.y + run * sin(h))
    val dn = jet.x - (ship.x + run * cos(h))
    val along = de * sin(h) + dn * cos(h)
    val across = de * cos(h) - dn * sin(h)
    val rel = ((jet.hdgTrue - ship.hdg) % 360.0 + 360.0) % 360.0
    return DeckFix(e = across, n = along, heading = rel, shipName = ship.name)
}

/**
 * Do two names for a ship agree on its hull number? Only a clash counts: a name with no hull number agrees with any.
 * Only a number after a hull prefix is one — "Type 001" is a class, not the Liaoning's 16.
 */
private fun sameHull(chart: String, feed: String?): Boolean {
    val a = hullNumbers(chart)
    val b = hullNumbers(feed.orEmpty())
    return a.isEmpty() || b.isEmpty() || a.any { it in b }
}

private val HULL = Regex("\\b(?:CVN|CVL|CV|LHD|LHA|LPH)[- ]?(\\d{1,3})\\b", RegexOption.IGNORE_CASE)

private fun hullNumbers(s: String): Set<String> =
    HULL.findAll(s).map { it.groupValues[1].trimStart('0').ifEmpty { "0" } }.toSet()

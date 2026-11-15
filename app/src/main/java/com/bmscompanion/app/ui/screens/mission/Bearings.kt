package com.bmscompanion.app.ui.screens.mission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.CampMagVar
import com.bmscompanion.app.data.mission.MissionLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The bearings the Mission section prints (every [bra]: the map's ownship box and cards, the steerpoint list, the
 * Dashboard, the AWACS page and its calls, the VR boards): **magnetic**, as the F-16 shows them in BMS 4.38.1 —
 * the HSD's and the FCR's bullseye figure is "the magnetic bearing from the bullseye to the current aircraft
 * position" (TO 1F-16CMAM-34-1-1, BMS 4.38.1, HSD and FCR bullseye), magnetic variation being modelled since 4.38
 * (Comms & Nav Book 4.38.1, "Magnetic variation") — or **true**, as the theater map is drawn (north up). A choice of
 * each device's (pref `map_bearing_mag`, magnetic by default; MissionSync carries it to the pilot's other devices).
 *
 * The variation (east positive, as BMS's `MagVarMap_*.csv`): in 3D the jet's own — its true heading less the magnetic
 * one it shows (FlightData `yaw` and `currentHeading`), which is the variation at the aircraft's present position the
 * F-16's EGI uses for every display (TO 1F-16CMAM-1, Navigation) — else BMS's variation map at the point the bearing
 * is measured from (`/api/campaign/magvar`, read once per theater), else none (the bearing stays true).
 */
object BearingPrefs {
    private const val KEY = "map_bearing_mag"

    var magnetic by com.bmscompanion.app.data.mission.prefState(KEY) { Repo.getInt(KEY, 1) == 1 }
        private set

    fun set(mag: Boolean) { magnetic = mag; Repo.putInt(KEY, if (mag) 1 else 0) }

    /** "magnetic" / "true", for the lines that say what a bearing is */
    val word: String get() = if (magnetic) "magnetic" else "true"
}

/** The magnetic variation the Mission section's bearings are turned by ([BearingPrefs]). */
object MagVar {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** by theater name: the PC's variation map, or null where it has none (404) */
    private val grids = mutableStateMapOf<String, CampMagVar?>()
    private val asked = HashSet<String>()

    /** For the checks: a variation map to use for [theater] instead of asking the PC. */
    fun put(theater: String, grid: CampMagVar?) { grids[theater] = grid; asked += theater }

    /** The theater the Mission section shows: WDP mode's populated one, else the one BMS is on. */
    private fun theaterNow(): String? {
        val info = MissionLink.info.value
        val populated = MissionLink.mission.value?.populated?.theater?.takeIf { info.wdpMode && it.isNotBlank() }
        return populated ?: info?.bms?.theater?.takeIf { it.isNotBlank() }
    }

    /** +1 or −1 once [jet] has seen FlightData2's `magDeviationSystem` agree with the headings, 0 before. */
    private var devSign = 0

    /**
     * The jet's own variation in 3D (east positive, −180..180), when it reads as one: FlightData2's
     * `magDeviationSystem` (steady through a turn) once its sign is known — learned from the headings, true less
     * magnetic, when the two agree to a degree — else the headings themselves.
     */
    fun jet(): Double? {
        val l = MissionLink.live.value ?: return null
        if (!l.flying || (l.hdgTrue == 0.0 && l.hdgMag == 0.0)) return null
        val v = ((l.hdgTrue - l.hdgMag) % 360 + 540) % 360 - 180
        val dev = l.magDev
        if (dev != null && abs(dev) > 0.5 && abs(v) > 0.5) {
            if (abs(dev - v) < 1.0) devSign = 1 else if (abs(dev + v) < 1.0) devSign = -1
        }
        if (dev != null && devSign != 0) return dev * devSign
        return v.takeIf { abs(it) <= 40 }
    }

    /** The variation map's figure at [north]/[east] feet, asking the PC for the map once per theater. */
    fun grid(north: Double, east: Double): Double? {
        val t = theaterNow() ?: return null
        if (t !in grids && asked.add(t)) scope.launch {
            val a = runCatching { MissionLink.campaignMagVar(t) }.getOrNull()
            if (a != null && a.reached) grids[t] = a.value?.takeIf { a.ok } else asked.remove(t)
        }
        return grids[t]?.at(north, east)
    }

    /** The variation for a bearing measured from [north]/[east]: the jet's in 3D, else the map's there. */
    fun at(north: Double, east: Double): Double? = jet() ?: grid(north, east)
}

/** A true bearing measured from [fromX]/[fromY] as the device shows bearings ([BearingPrefs]): 0-360. */
fun shownBearing(trueBrg: Double, fromX: Double, fromY: Double): Double {
    if (!BearingPrefs.magnetic) return trueBrg
    val v = MagVar.at(fromX, fromY) ?: return trueBrg
    return ((trueBrg - v) % 360 + 360) % 360
}

package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.Contact
import com.bmscompanion.app.data.mission.Contacts
import com.bmscompanion.app.data.mission.Dtc
import com.bmscompanion.app.data.mission.DtcPoint
import com.bmscompanion.app.data.mission.DtcPpt
import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.mission.Preset
import com.bmscompanion.app.data.mission.RwrContact
import com.bmscompanion.app.data.mission.Voice
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic mission (Hellas, SEAD from Larissa towards Tirana) so everything can be tried without BMS running.
 * Everything is generated; nothing is read from the BMS install.
 */
class DemoSource {
    private val start = System.currentTimeMillis()

    val briefingText: String = DemoSource::class.java.getResourceAsStream("/bridge/demo_briefing.txt")?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    val briefingModified: Long = System.currentTimeMillis()

    /** Demo time runs 6x faster than real time. */
    private val simSeconds get() = (System.currentTimeMillis() - start) / 1000.0 * 6

    private data class Wp(val x: Double, val y: Double, val desc: String)

    private val route = listOf(
        Wp(2293126.0, 962235.0, "Takeoff"), Wp(2492326.0, 741035.0, "Holding Pt"), Wp(2528258.0, 695069.0, "Push"), Wp(2705234.0, 491385.0, "Pre IP"),
        Wp(2741700.0, 452298.0, "IP"), Wp(2901884.0, 261470.0, "Grnd Attack"), Wp(2719964.0, 456458.0, "Turn Pt"), Wp(2527725.0, 693932.0, "Split"),
        Wp(2414037.0, 829372.0, "--"), Wp(2293126.0, 962235.0, "Land"), Wp(2129284.0, 1054691.0, "Alternate"),
    )
    private val bullseye = 2480000.0 to 700000.0
    private val samSite = 2910660.0 to 258538.0
    private val awacsOrbit = 2230000.0 to 800000.0

    private data class Along(val x: Double, val y: Double, val hdg: Double, val next: Int)

    /** Position along the route (loops): point, heading and the index of the next steerpoint. */
    private fun along(distance: Double, lastLeg: Int = 9): Along {
        var total = 0.0
        for (i in 0 until lastLeg) total += dist(route[i], route[i + 1])
        var d = distance % total
        for (i in 0 until lastLeg) {
            val leg = dist(route[i], route[i + 1])
            if (d <= leg) {
                val f = d / leg
                val a = route[i]
                val b = route[i + 1]
                return Along(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, (atan2(b.y - a.y, b.x - a.x) * 180 / PI + 360) % 360, i + 2)
            }
            d -= leg
        }
        return Along(route[0].x, route[0].y, 0.0, 2)
    }

    private fun dist(a: Wp, b: Wp) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    fun live(): Live {
        val t = simSeconds
        val gsKts = 480.0
        val (x, y, hdg, next) = along(t * gsKts * FT_PER_NM / 3600)
        val fuelBurn = (t % 2300) * 2.2
        fun rel(tx: Double, ty: Double) = ((atan2(ty - y, tx - x) * 180 / PI) + 360) % 360
        val navPoints = route.mapIndexed { i, w -> NavPoint(i + 1, "WP", w.x, w.y, if (i == 0 || i == 9 || i == 10) 0.0 else 22000.0) } + listOf(
            NavPoint(56, "PT", samSite.first, samSite.second, name = "SA-5", rangeNm = 60.0),
            NavPoint(57, "PT", 2760000.0, 330000.0, name = "SA-6", rangeNm = 14.0),
            NavPoint(26, "MK", 2600000.0, 600000.0, 1200.0),
        )
        return Live(
            t = System.currentTimeMillis(), flying = true, theater = "Hellas", aircraft = "F-16C Block 52+",
            x = x, y = y, altFt = 22000 + 400 * sin(t / 50), hdgTrue = hdg, hdgMag = (hdg - 4 + 360) % 360,
            kias = 355.0, mach = 0.86, gsKts = gsKts, vviFpm = 80 * cos(t / 50), gLoad = 1.0, aoa = 3.1, radarAltFt = 0.0,
            fuelInternal = max(1200.0, 7100 - fuelBurn), fuelExternal = max(0.0, 1800 - fuelBurn), fuelFlow = 4800.0, bingo = 2400.0,
            chaff = 60, flares = 30, gear = 0.0, speedBrake = 0.0,
            bullX = bullseye.first, bullY = bullseye.second,
            timeSec = ((3 * 3600 + 43 * 60 + t).toInt()) % 86400,
            tacan = "16Y A/A", tacanUfc = "27X", tacanAux = "16Y A/A", beaconBrg = 120.0, beaconNm = 40.0, desiredCourse = 132.0, navMode = 2, uhfPreset = 15, uhfFreq = 345950,
            ded = listOf(
                "      STPT  ${next.toString().padStart(2)}  AUTO    ",
                "  LAT  N 40°12.345'     ",
                "  LNG  E 20°51.004'     ",
                "ELEV   22000FT          ",
                " TOS  ${timeString(((3 * 3600 + 58 * 60 + t).toInt()) % 86400)}          ",
            ),
            voice = Voice(flight = "Ouranos5", seats = "PAXX", awacs = "Dragnet5", departure = "Larissa", arrival = "Larissa", alternate = "Nea Anchialos"),
            navPoints = navPoints,
            // SA-5 site, a MiG-29 CAP and a search radar. Bearings are true, like BMS (the app subtracts heading).
            rwr = listOf(
                RwrContact(sym = 10, brg = rel(samSite.first, samSite.second), lethality = 0.7, lock = (t % 90) > 60),
                RwrContact(sym = 2, brg = rel(2850000.0, 420000.0), lethality = 0.5, new = (t % 40) < 4),
                RwrContact(sym = 17, brg = rel(2980000.0, 300000.0), lethality = 0.2),
            ),
        )
    }

    fun contacts(): Contacts {
        val t = simSeconds
        val own = live()
        val list = ArrayList<Contact>()
        fun add(id: String, kind: String, x: Double, y: Double, alt: Double, hdg: Double, gs: Double, name: String, group: String?, coalition: String, friendly: Boolean, isOwn: Boolean = false): Int {
            var c = Contact(
                id = id, kind = kind, x = x, y = y, altFt = alt, hdg = round(hdg * 10) / 10, gsKts = gs, name = name, group = group,
                coalition = coalition, color = if (friendly) "Blue" else "Red", friendly = friendly, own = isOwn,
            )
            if (kind == "air" || kind == "heli") c = c.copy(
                ias = round(gs * (if (alt > 15000) 0.72 else 0.92)), // rough IAS from ground speed at altitude
                mach = round(gs / 600.0 * 100) / 100,
                fuelLb = round(max(900.0, 7400 - (t % 3600) * 1.4 - id.length * 300)),
            )
            list += c
            return list.size - 1
        }

        add("1", "air", own.x, own.y, own.altFt, own.hdgTrue, own.gsKts, "F-16CM-52", "Ouranos5", "Greece", true, true)
        val wing = along(max(0.0, t * 480 * FT_PER_NM / 3600 - 1.5 * FT_PER_NM))
        add("2", "air", wing.x + 2500, wing.y - 2500, 21500.0, wing.hdg, 480.0, "F-16CM-52", "Ouranos5", "Greece", true)
        val rider = along(max(0.0, t * 470 * FT_PER_NM / 3600 - 9 * FT_PER_NM))
        add("3", "air", rider.x - 6000, rider.y + 4000, 20000.0, rider.hdg, 470.0, "F-4E", "Rider5", "Greece", true)
        add("4", "air", rider.x - 8500, rider.y + 6500, 20500.0, rider.hdg, 470.0, "F-4E", "Rider5", "Greece", true)
        val a = t / 400
        add("5", "air", awacsOrbit.first + cos(a) * 15 * FT_PER_NM, awacsOrbit.second + sin(a) * 25 * FT_PER_NM, 30000.0, (a * 180 / PI + 90) % 360, 320.0, "EMB-145H", "Dragnet5", "Greece", true)
        val k = t / 520 // tanker racetrack north-east of Larissa
        add("6", "air", 2400000 + cos(k) * 8 * FT_PER_NM, 1080000 + sin(k) * 18 * FT_PER_NM, 24000.0, (k * 180 / PI + 90) % 360, 310.0, "KC-135R", "Texaco1", "Greece", true)
        val m = t / 260
        add("10", "air", 2850000 + cos(m) * 12 * FT_PER_NM, 420000 + sin(m) * 20 * FT_PER_NM, 26000.0, (m * 180 / PI + 90) % 360, 450.0, "MiG-29A", "Falcon2", "Albania", false)
        add("11", "air", 2850000 + cos(m) * 12 * FT_PER_NM - 8000, 420000 + sin(m) * 20 * FT_PER_NM + 6000, 25000.0, (m * 180 / PI + 90) % 360, 450.0, "MiG-29A", "Falcon2", "Albania", false)
        add("12", "heli", 2560000.0, 640000 + (t % 600) * 200, 800.0, 90.0, 110.0, "UH-60", "Pedro1", "Greece", true)
        // AWACS demo: a friendly CAP (Colt1) orbiting west of the bullseye, and a hostile pair (Uzi2) running in from the north-west
        val cap = t / 300
        val coltX = 2600000 + cos(cap) * 10 * FT_PER_NM
        val coltY = 560000 + sin(cap) * 16 * FT_PER_NM
        val coltHdg = (cap * 180 / PI + 90) % 360
        add("50", "air", coltX, coltY, 24000.0, coltHdg, 430.0, "F-16CM-50", "Colt1", "Greece", true)
        add("51", "air", coltX - 9000, coltY + 7000, 23000.0, coltHdg, 430.0, "F-16CM-50", "Colt1", "Greece", true)
        val run = (t % 900) / 900 // restarts every 900 s of demo time
        val uziX = 2950000 - run * 60 * FT_PER_NM * 5.2
        val uziY = 330000 + run * 60 * FT_PER_NM * 3.4
        val uziHdg = atan2(3.4, -5.2) * 180 / PI + 360
        val uzi1 = add("60", "air", uziX, uziY, 31000.0, uziHdg % 360, 540.0, "Su-27", "Uzi2", "Albania", false)
        add("61", "air", uziX + 12000, uziY - 9000, 29000.0, uziHdg % 360, 540.0, "Su-27", "Uzi2", "Albania", false)
        if (sqrt((uziX - coltX).pow(2) + (uziY - coltY).pow(2)) < 45 * FT_PER_NM) list[uzi1] = list[uzi1].copy(locked = "50")
        add("20", "ship", 2400000.0, 330000.0, 0.0, 150.0, 12.0, "Elli class frigate", null, "Greece", true)
        // hostile ejected crew close to ownship: the Picture list must still show the MiGs first
        add("40", "crew", own.x + 4 * FT_PER_NM, own.y - 3 * FT_PER_NM, 0.0, 0.0, 0.0, "Ejected Crew", null, "Albania", false)
        if ((t % 120) < 25) {
            val f = (t % 120) / 25
            add("30", "missile", samSite.first + (own.x - samSite.first) * f * 0.6, samSite.second + (own.y - samSite.second) * f * 0.6, 5000 + 30000 * f, 0.0, 1800.0, "SA-5 Gammon", null, "Albania", false)
        }
        add("90", "bullseye", bullseye.first, bullseye.second, 0.0, 0.0, 0.0, "Bullseye", null, "Greece", true)
        return Contacts(t = own.t, connected = true, state = "demo", contacts = list)
    }

    fun dtc(): Dtc {
        val uhf = listOf(
            "382.500|Base ops", "264.500|DEP Ground", "362.400|DEP Tower", "265.150|DEP Approach", "342.275|AWACS Check-in", "399.125|Tactical",
            "265.150|ARR Approach", "362.400|ARR Tower", "264.500|ARR Ground", "342.600|ALT Approach", "364.400|ALT Tower", "340.300|ALT Ground",
            "348.350|", "339.750|Advisory", "345.950|Intra Flight 1", "251.000|Intra Flight 2",
        ).mapIndexed { i, s -> s.split('|').let { Preset(i + 1, it[0], it[1].ifEmpty { null }) } }
        return Dtc(
            modified = briefingModified,
            steerpoints = route.mapIndexed { i, w ->
                DtcPoint(n = i + 1, x = w.x, y = w.y, altFt = if (i == 0 || i == 9 || i == 10) 0.0 else 22000.0, action = if (i == 5) -1 else 1, isTarget = i == 5,
                    name = if (i == 5) "SA-5 launchers, 4 nm S of Tirana" else null)
            },
            ppts = listOf(DtcPpt(56, samSite.first, samSite.second, 0.0, 60.0, "SA-5"), DtcPpt(57, 2760000.0, 330000.0, 0.0, 14.0, "SA-6")),
            uhf = uhf,
            vhf = listOf(Preset(2, "123.250", "DEP ATIS"), Preset(3, "120.550", "DEP Tower"), Preset(14, "119.500", "UNICOM"), Preset(15, "52.450", "Flight-1")),
            iff = linkedMapOf("Mode1 Code" to "60", "Mode2 Code" to "1404", "Mode3A Code" to "7504", "Mode4 Key" to "1"),
        )
    }

    private fun timeString(s: Int) = "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)

    private companion object {
        const val FT_PER_NM = 6076.12
    }
}

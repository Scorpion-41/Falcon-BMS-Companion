package com.bmscompanion.app.data.wdp

import com.bmscompanion.app.data.Theater
import com.bmscompanion.app.data.TheaterProjection
import com.bmscompanion.app.data.WdpTerrain
import kotlin.math.sqrt

/**
 * The projection the Planner's pages print latitude and longitude with — the one source every page, kneeboard and
 * list of the app takes a position's latitude and longitude from ([coordData], through [PopupCoords.feetToCoordsBoth]).
 *
 * **What the pages get: the latitude and longitude BMS itself gives** (D26). Falcon BMS 4.38.1 writes each object's
 * latitude and longitude beside its position in feet in its ACMI recordings, and prints a "BMS coord" for every
 * airbase in its AIPs. Both are Weapon Delivery Planner's grid, built from three figures of the terrain the theater
 * is flown on — its size, its centre, and the heightmap's sample count — and nothing else:
 *
 * - metres are feet ÷ **3.28084** (not the 3.27998 of a campaign kilometre);
 * - the false origin is GeographicLib's forward projection of `Theater.txt`'s centre, less half the theater;
 * - a point's northing is one heightmap sample more (1,024,000 m ÷ 32,768 = 31.25 m).
 *
 * Measured (docs/WDP-PORT.md, D26): 148,441 positions of 88,340 objects in twenty recordings on three terrains
 * (Korea, Hellas, Israel) print within 1.3 m of BMS's own figure (0.6 m on average: the print's thousandth of a
 * minute), and so do the 22 airbases of the KTO AIP and 103 of the Balkans AIP's 104 airports (the other is a
 * misprint in the AIP); `Theater.txt`'s projection string, which up to this fix was taken for "BMS's own", lies
 * 140-220 m away on average (up to 290 m) and never within 2 m. The grid is taken from the theater's [Theater.projection] record,
 * the terrain BMS itself flies it on — so Korea TvT, which names no terrain and is flown on KTO's, gets KTO's, where
 * WDP found none.
 *
 * **Where the grid is not established** — a theater that is not 1,024 km (the Falklands, 2,048 km: WDP's grid counts
 * 1,024 km whatever the size, and no BMS figure for that theater has been checked) or whose terrain lacks a
 * heightmap or a centre — the pages get the projection string ([appMeta]), printed sign and magnitude.
 *
 * **WDP's own arithmetic** ([meta], [wdpCoordData]) — `fclsMain.InitNewTerrain` and `InitTransverseMercator`, Falcas's
 * code, ported — is what builds the grid, so the Planner prints WDP's own strings wherever WDP
 * reads the same terrain.
 *
 * What WDP reads (the extractor records the same, `tools/extractor/src/wdpterrain.mjs`, into [WdpTerrain]):
 *
 * - **The heightmap's length, and nothing else of it.** Samples a side are `sqrt(bytes / 2)`, rounded; the grid's
 *   metres per sample are **1,024,000** divided by that — a fixed kilometre count, whatever the theater's size — and
 *   the feet per metre are 3.28084. On a 2,048 km theater (the Falklands) the northing, counted down from the top of
 *   a 1,024 km square, puts every latitude about 1,024 km north of the point, which is why the grid is only handed
 *   out for 1,024 km.
 * - **Theater.txt's size and centre.** The projection's false origin is not the one in its projection string: WDP
 *   reads those figures and then replaces them with GeographicLib's forward projection of the centre, less half the
 *   theater's size in metres.
 *
 * The port gives each theater what WDP computes when it is the **first** theater WDP loads. WDP keeps some of this
 * between theaters (the heightmap's path is only looked up while it has none, and a key a Theater.txt lacks keeps
 * the previous theater's value), which no page can observe unless the pilot switches theaters in the program; every
 * stock heightmap has the same length, so the grid is the same either way.
 *
 * Checked against the program: `wdpref page Coords` makes WDP run these two methods for every theater of a BMS
 * install (and a few synthetic ones) and records the result bit for bit, with thousands of converted points each;
 * `--wdppagetest coords` still demands the same of [meta], and measures every theater's prints against BMS's own.
 */
object WdpCoords {

    /** `L.METERS_TO_FT`, as BMSUtils' CampLib declares it (a float). */
    const val METERS_TO_FT = 3.28084f

    /**
     * `fclsMain.TransverseMercatorMeta` after `InitNewTerrain` for a theater whose files say [w]; null (no NewTerrain
     * where WDP looks) and a missing heightmap both leave it as the program starts it, all zero.
     */
    fun meta(w: WdpTerrain?): PopupCoords.TransverseMercatorMeta {
        val bytes = w?.heightmapBytes ?: return PopupCoords.TransverseMercatorMeta()   // no file: InitNewTerrain returns
        // HEIGHTMAP_SAMPLES_UINT = (uint)Math.Round(Math.Sqrt((double)length / 2.0)), and its square (checked)
        val samples = PopupPlan.bankersD(sqrt(bytes.toDouble() / 2.0)).toLong()
        if (samples * samples > 0xffffffffL) return PopupCoords.TransverseMercatorMeta()   // overflows before any field
        val s = samples.toFloat()                                   // HEIGHTMAP_SAMPLES, a float
        val heightmapSize = s - 1f
        val meterRes = 1024000f / s
        val gridToFt = meterRes * METERS_TO_FT
        val ftToGrid = 1f / gridToFt
        val gridOffset = heightmapSize / 2f
        val grid = PopupCoords.TransverseMercatorMeta(
            heightmapSize = heightmapSize, meterRes = meterRes, ftToGrid = ftToGrid, gridToFt = gridToFt, gridOffset = gridOffset,
        )
        // InitTransverseMercator: no Theater.txt is a message box, and the rest stays as it was
        if (!w.theaterTxt) return grid
        // the keys in the file's own order (size, then the centre); a size that will not fit a uint throws, and
        // the catch skips everything after it, the forward projection included
        val meters = w.sizeKm?.let { PopupPlan.bankersD(it * 1000.0) } ?: 0.0
        if (meters.isNaN() || meters < 0.0 || meters >= 4294967296.0) return grid
        val size = meters.toLong()
        val lat = w.centerLat ?: 0.0
        val lon = w.centerLon ?: 0.0
        val (x, y) = PopupCoords.transverseMercatorForward(lon, lat, lon)
        return grid.copy(
            meridian = lon,
            offsetX = x - size.toDouble() / 2.0,
            offsetY = y - size.toDouble() / 2.0,
            theaterSizeInMeters = size,
        )
    }

    /**
     * What `SetCoordData` hands a page for [theater]: the latitude and longitude BMS gives ([bmsGrid]) wherever that is
     * established, else the terrain's projection string ([appMeta], the Falklands); and the theater's width and
     * height in feet (3,358,699.5 ft for 1,024 km as WDP has it, 3.27998 ft/m for another size), which the Pop-up
     * page's terrain lookup and the DTC page's "inside the theater" test use. Null for a theater the app does not
     * know or whose projection it cannot honour: the pages then show feet, as before a theater is loaded.
     */
    fun coordData(theater: Theater?): PopupCoords.CoordData? {
        val p = theater?.projection ?: return null
        bmsGrid(p)?.let { return it }
        val tm = appMeta(p) ?: return null
        val size = p.sizeKm * 1000.0 * p.ftPerM
        if (!(size > 0.0)) return null
        return PopupCoords.CoordData(originLat = 0.0, originLong = 0.0, campW = size, campH = size, enableNewTerrain = true, tm = tm)
    }

    /** The one theater size BMS's grid has been checked on, km. */
    const val GRID_KM = 1024.0

    /** A 1,024 km theater's width and height in feet, as WDP's `SetCoordData` has it. */
    const val CAMP_1024_FT = 3358699.5

    /**
     * BMS's own latitude and longitude for the terrain [p] describes: WDP's grid ([meta]) over the size, centre and
     * heightmap length of the terrain BMS flies the theater on. Null where it is not established — a theater that is
     * not 1,024 km, or a terrain record without a heightmap length or a centre (data older than 1.3.8).
     */
    fun bmsGrid(p: TheaterProjection): PopupCoords.CoordData? {
        if (p.sizeKm != GRID_KM) return null
        val bytes = p.heightmapBytes ?: return null
        val lat = p.centerLat ?: return null
        val lon = p.centerLon ?: return null
        val tm = meta(WdpTerrain(heightmapBytes = bytes, theaterTxt = true, sizeKm = p.sizeKm, centerLat = lat, centerLon = lon))
        if (!(tm.meterRes > 0f) || tm.theaterSizeInMeters <= 0L) return null
        return PopupCoords.CoordData(originLat = 0.0, originLong = 0.0, campW = CAMP_1024_FT, campH = CAMP_1024_FT, enableNewTerrain = true, tm = tm)
    }

    /**
     * [p]'s projection string as the conversions take it — the fallback where [bmsGrid] is not established; null for
     * what [PopupCoords]' GeographicLib series cannot compute exactly: another projection or ellipsoid, a missing
     * meridian, a scale or feet-per-metre that is not a positive number.
     */
    fun appMeta(p: TheaterProjection): PopupCoords.TransverseMercatorMeta? {
        if (p.type != "tmerc") return null
        if (p.ellps != "WGS84") return null
        if (!p.lon0.isFinite() || !p.x0.isFinite() || !p.y0.isFinite() || !p.lat0.isFinite()) return null
        if (!(p.k0 > 0.0) || !(p.ftPerM > 0.0)) return null
        // a parameter the record does not carry (an ellipsoid given by radius, a towgs84 shift, a unit other than the
        // metre) would move every position without a word: the file's own string must say nothing this class ignores
        if (Regex("""\+(a|b|rf|R|towgs84|nadgrids|axis|pm|to_meter)=|\+units=(?!m(\s|$))""").containsMatchIn(p.proj)) return null
        return PopupCoords.TransverseMercatorMeta(
            meridian = p.lon0,
            offsetX = -p.x0,
            offsetY = -p.y0,
            theaterSizeInMeters = kotlin.math.round(p.sizeKm * 1000.0).toLong(),
            ftPerM = p.ftPerM,
            k0 = p.k0,
            lat0 = p.lat0,
        )
    }

    /**
     * WDP's own `SetCoordData` under the new terrain (`g_bEnableNewTerrain`), from what WDP itself reads ([WdpTerrain]),
     * for the coordinates test's replay of WDP: the width and height are WDP's for a theater of that size (its
     * database's 1,024 or 2,048 km), the origin is left at zero. On every 1,024 km theater WDP finds a terrain for,
     * this is exactly what [coordData] hands the pages.
     */
    fun wdpCoordData(theater: Theater?): PopupCoords.CoordData? {
        theater ?: return null
        val w = theater.wdpTerrain
        val camp = if (w?.sizeKm == 2048.0) 6717399.0 else 3358699.5
        return PopupCoords.CoordData(
            originLat = 0.0, originLong = 0.0, campW = camp, campH = camp, enableNewTerrain = true, tm = meta(w),
        )
    }
}

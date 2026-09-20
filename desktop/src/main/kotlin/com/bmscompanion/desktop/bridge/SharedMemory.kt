package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.Live
import com.bmscompanion.app.data.mission.NavPoint
import com.bmscompanion.app.data.mission.Pilot
import com.bmscompanion.app.data.mission.RwrContact
import com.bmscompanion.app.data.mission.Voice
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Falcon BMS shared memory (<BMS>\Tools\SharedMem\FlightData.h; BMS 4.38: FlightData v118, FlightData2 v23, StringData v5).
//
// UPDATING FOR A NEW BMS VERSION: diff the new FlightData.h against the field lists below. BMS only appends fields at the
// end, so usually nothing changes here; add new trailing fields in header order and types. See docs/UPDATING.md.

/** Computes C struct offsets with MSVC default alignment (the structs only hold 1-, 2- and 4-byte types). */
private class StructLayout {
    var size = 0
        private set
    private fun field(bytes: Int, count: Int = 1): Int {
        size = (size + bytes - 1) / bytes * bytes
        return size.also { size += bytes * count }
    }
    fun f(count: Int = 1) = field(4, count)    // float
    fun i(count: Int = 1) = field(4, count)    // int / unsigned long
    fun s(count: Int = 1) = field(2, count)    // short / unsigned short
    fun b(count: Int = 1) = field(1, count)    // char / unsigned char
    val totalSize get() = (size + 3) / 4 * 4
}

/** FlightData field offsets (header order). */
private object FD {
    private val l = StructLayout()
    val x = l.f(); val y = l.f(); val z = l.f(); val xDot = l.f(); val yDot = l.f(); val zDot = l.f()
    val alpha = l.f(); val beta = l.f(); val gamma = l.f(); val pitch = l.f(); val roll = l.f(); val yaw = l.f()
    val mach = l.f(); val kias = l.f(); val vt = l.f(); val gs = l.f(); val windOffset = l.f(); val nozzlePos = l.f()
    val internalFuel = l.f(); val externalFuel = l.f(); val fuelFlow = l.f(); val rpm = l.f(); val ftit = l.f(); val gearPos = l.f(); val speedBrake = l.f(); val epuFuel = l.f(); val oilPressure = l.f()
    val lightBits = l.i()
    val headPitch = l.f(); val headRoll = l.f(); val headYaw = l.f()
    val lightBits2 = l.i(); val lightBits3 = l.i()
    val chaffCount = l.f(); val flareCount = l.f()
    val noseGearPos = l.f(); val leftGearPos = l.f(); val rightGearPos = l.f()
    val adiIlsHorPos = l.f(); val adiIlsVerPos = l.f()
    val courseState = l.i(); val headingState = l.i(); val totalStates = l.i()
    val courseDeviation = l.f(); val desiredCourse = l.f(); val distanceToBeacon = l.f(); val bearingToBeacon = l.f(); val currentHeading = l.f(); val desiredHeading = l.f()
    val deviationLimit = l.f(); val halfDeviationLimit = l.f(); val localizerCourse = l.f(); val airbaseX = l.f(); val airbaseY = l.f(); val totalValues = l.f()
    val trimPitch = l.f(); val trimRoll = l.f(); val trimYaw = l.f()
    val hsiBits = l.i()
    val dedLines = l.b(5 * 26); val invert = l.b(5 * 26); val pflLines = l.b(5 * 26); val pflInvert = l.b(5 * 26)
    val ufcTChan = l.i(); val auxTChan = l.i()
    val rwrObjectCount = l.i()
    val rwrSymbol = l.i(40); val bearing = l.f(40); val missileActivity = l.i(40); val missileLaunch = l.i(40); val selected = l.i(40); val lethality = l.f(40); val newDetection = l.i(40)
    val fwd = l.f(); val aft = l.f(); val total = l.f()
    val versionNum = l.i()
    val headX = l.f(); val headY = l.f(); val headZ = l.f()
    val mainPower = l.i()
    val size = l.totalSize
}

/** FlightData2 field offsets (header order). */
private object FD2 {
    private val l = StructLayout()
    val nozzlePos2 = l.f(); val rpm2 = l.f(); val ftit2 = l.f(); val oilPressure2 = l.f()
    val navMode = l.b()
    val aauz = l.f()
    val tacanInfo = l.b(2)
    val altCalReading = l.i()
    val altBits = l.i(); val powerBits = l.i(); val blinkBits = l.i()
    val cmdsMode = l.i()
    val uhfPanelPreset = l.i(); val uhfPanelFrequency = l.i()
    val cabinAlt = l.f(); val hydPressureA = l.f(); val hydPressureB = l.f()
    val currentTime = l.i()
    val vehicleACD = l.s()
    val versionNum = l.i()
    val fuelFlow2 = l.f()
    val rwrInfo = l.b(512)
    val lefPos = l.f(); val tefPos = l.f(); val vtolPos = l.f()
    val pilotsOnline = l.b()
    val pilotsCallsign = l.b(32 * 12)
    val pilotsStatus = l.b(32)
    val bumpIntensity = l.f()
    val latitude = l.f(); val longitude = l.f()
    val rttSize = l.s(2); val rttArea = l.s(7 * 4)
    val iffBackupMode1Digit1 = l.b(); val iffBackupMode1Digit2 = l.b(); val iffBackupMode3ADigit1 = l.b(); val iffBackupMode3ADigit2 = l.b()
    val instrLight = l.b()
    val bettyBits = l.i(); val miscBits = l.i()
    val ralt = l.f(); val bingoFuel = l.f(); val caraAlow = l.f(); val bullseyeX = l.f(); val bullseyeY = l.f()
    val bmsVersionMajor = l.i(); val bmsVersionMinor = l.i(); val bmsVersionMicro = l.i(); val bmsBuildNumber = l.i()
    val stringAreaSize = l.i(); val stringAreaTime = l.i(); val drawingAreaSize = l.i()
    val turnRate = l.f()
    val floodConsole = l.b()
    val magDeviationSystem = l.f(); val magDeviationReal = l.f()
    val ecmBits = l.i(5)
    val ecmOper = l.b()
    val rwrJammingStatus = l.b(40)
    val radio2Preset = l.i(); val radio2Frequency = l.i()
    val iffTransponderActiveCode1 = l.b()
    val iffTransponderActiveCode2 = l.s(); val iffTransponderActiveCode3A = l.s(); val iffTransponderActiveCodeC = l.s(); val iffTransponderActiveCode4 = l.s()
    val tacanIlsFrequency = l.i()
    val desiredRttFps = l.i()
    val sideSlipdeg = l.f(); val gsMax = l.f(); val gsMin = l.f()
    val size = l.totalSize
}

/** StringData identifiers (enum StringIdentifier in FlightData.h), verified against a running BMS 4.38.1 with --dumpstrings. */
object StringId {
    /** Where the ACMI recordings go (`User\Acmi` unless the install says otherwise). */
    const val BmsAcmiDirectory = 7
    const val BmsBriefingsDirectory = 8
    const val BmsPictureDirectory = 12
    const val ThrName = 13
    const val AcName = 29
    const val NavPoint = 33
    const val VoiceHelpers = 35
    private val names = listOf(
        "BmsExe", "KeyFile", "BmsBasedir", "BmsBinDirectory", "BmsDataDirectory", "BmsUIArtDirectory", "BmsUserDirectory", "BmsAcmiDirectory",
        "BmsBriefingsDirectory", "BmsConfigDirectory", "BmsLogsDirectory", "BmsPatchDirectory", "BmsPictureDirectory", "ThrName", "ThrCampaigndir",
        "ThrTerraindir", "ThrArtdir", "ThrMoviedir", "ThrUisounddir", "ThrObjectdir", "Thr3ddatadir", "ThrMisctexdir", "ThrSounddir", "ThrTacrefdir",
        "ThrSplashdir", "ThrCockpitdir", "ThrSimdatadir", "ThrSubtitlesdir", "ThrTacrefpicsdir", "AcName", "AcNCTR", "ButtonsFile", "CockpitFile",
        "NavPoint", "ThrTerrdatadir", "VoiceHelpers",
    )
    fun name(id: Int) = names.getOrNull(id) ?: "id$id"
}

/** Everything read from BMS shared memory at one moment. */
class BmsSnapshot {
    var available = false
    var flying = false
    var live = Live()
    val strings = HashMap<Int, String>()
    val navPointStrings = ArrayList<String>()
    var version: String? = null
    var hsiBits = 0L
    var lightBits = 0L
    var pilotsOnline = 0
    val pilotStatus = ArrayList<Int>()
}

/** Reads the BMS shared memory areas. Opens them fresh on every read so a closed BMS never leaves stale data. */
object SharedMemoryReader {
    private const val FT_PER_NM = 6076.12
    private const val HSI_FLYING = 0x80000000L
    const val FLIGHT_DATA = "FalconSharedMemoryArea"
    const val FLIGHT_DATA2 = "FalconSharedMemoryArea2"
    const val STRINGS = "FalconSharedMemoryAreaString"

    val structSizes get() = FD.size to FD2.size

    fun read(): BmsSnapshot {
        val snap = BmsSnapshot()
        if (!BmsInstall.isBmsRunning()) return snap
        val fd = map(FLIGHT_DATA, FD.size) ?: return snap
        val fd2 = map(FLIGHT_DATA2, FD2.size)
        snap.available = true
        val hsi = fd.u32(FD.hsiBits)
        snap.flying = hsi and HSI_FLYING != 0L
        snap.hsiBits = hsi
        snap.lightBits = fd.u32(FD.lightBits)
        // BMS 4.38.1 does not set the documented hsiBits Flying flag; the pilot status table (3 = FLYING) is reliable.
        if (!snap.flying && fd2 != null && (fd.f(FD.x) != 0f || fd.f(FD.y) != 0f)) {
            val pilots = fd2.u8(FD2.pilotsOnline).coerceIn(0, 32)
            if ((0 until pilots).any { fd2.u8(FD2.pilotsStatus + it) == 3 }) snap.flying = true
        }
        if (fd2 != null && fd2.i(FD2.bmsVersionMajor) > 0)
            snap.version = "${fd2.i(FD2.bmsVersionMajor)}.${fd2.i(FD2.bmsVersionMinor)}.${fd2.i(FD2.bmsVersionMicro)} (${fd2.i(FD2.bmsBuildNumber)})"

        readStrings(fd2?.u32(FD2.stringAreaSize) ?: 0L, snap)

        val rwr = (0 until fd.i(FD.rwrObjectCount).coerceIn(0, 40)).map { k ->
            RwrContact(
                sym = fd.i(FD.rwrSymbol + 4 * k),
                brg = norm360(fd.f(FD.bearing + 4 * k) * 180.0 / PI),
                lethality = fd.f(FD.lethality + 4 * k).toDouble(),
                launch = fd.i(FD.missileLaunch + 4 * k) != 0,
                lock = fd.i(FD.missileActivity + 4 * k) != 0,
                selected = fd.i(FD.selected + 4 * k) != 0,
                new = fd.i(FD.newDetection + 4 * k) != 0,
            )
        }
        val xDot = fd.f(FD.xDot).toDouble()
        val yDot = fd.f(FD.yDot).toDouble()
        var live = Live(
            t = System.currentTimeMillis(),
            flying = snap.flying,
            theater = snap.strings[StringId.ThrName],
            aircraft = snap.strings[StringId.AcName],
            x = fd.f(FD.x).toDouble(), y = fd.f(FD.y).toDouble(), altFt = -fd.f(FD.z).toDouble(),
            hdgTrue = norm360(fd.f(FD.yaw) * 180.0 / PI),
            hdgMag = norm360(fd.f(FD.currentHeading).toDouble()),
            kias = fd.f(FD.kias).toDouble(), mach = fd.f(FD.mach).toDouble(),
            gsKts = sqrt(xDot * xDot + yDot * yDot) * 3600 / FT_PER_NM,
            vviFpm = -fd.f(FD.zDot) * 60.0,
            gLoad = fd.f(FD.gs).toDouble(), aoa = fd.f(FD.alpha).toDouble(),
            fuelInternal = fd.f(FD.internalFuel).toDouble(), fuelExternal = fd.f(FD.externalFuel).toDouble(), fuelFlow = fd.f(FD.fuelFlow).toDouble(),
            chaff = max(0f, fd.f(FD.chaffCount)).toInt(), flares = max(0f, fd.f(FD.flareCount)).toInt(),
            gear = fd.f(FD.gearPos).toDouble(), speedBrake = fd.f(FD.speedBrake).toDouble(),
            desiredCourse = fd.f(FD.desiredCourse).toDouble(),
            beaconBrg = fd.f(FD.bearingToBeacon).toDouble(), beaconNm = fd.f(FD.distanceToBeacon).toDouble(),
            ded = (0 until 5).map { dedString(fd, FD.dedLines + it * 26, 26) },
            rwr = rwr,
        )
        if (fd2 != null) {
            val online = fd2.u8(FD2.pilotsOnline).coerceIn(0, 32)
            snap.pilotsOnline = online
            for (k in 0 until max(online, 1)) snap.pilotStatus += fd2.u8(FD2.pilotsStatus + k)
            val ufcBits = fd2.u8(FD2.tacanInfo)
            val auxBits = fd2.u8(FD2.tacanInfo + 1)
            val ufc = fd.i(FD.ufcTChan)
            val aux = fd.i(FD.auxTChan)
            val bullX = fd2.f(FD2.bullseyeX)
            val bullY = fd2.f(FD2.bullseyeY)
            live = live.copy(
                radarAltFt = fd2.f(FD2.ralt).toDouble(),
                bingo = fd2.f(FD2.bingoFuel).toDouble(),
                timeSec = fd2.i(FD2.currentTime),
                lat = fd2.f(FD2.latitude).toDouble(), lon = fd2.f(FD2.longitude).toDouble(),
                navMode = fd2.u8(FD2.navMode),
                ilsFreq = fd2.i(FD2.tacanIlsFrequency),
                uhfPreset = fd2.i(FD2.uhfPanelPreset), uhfFreq = fd2.i(FD2.uhfPanelFrequency),
                bullX = if (bullX != 0f || bullY != 0f) bullX.toDouble() else null,
                bullY = if (bullX != 0f || bullY != 0f) bullY.toDouble() else null,
                // Two TACAN sources: UFC (DED T-ILS page, usually the home base from the DTC) and the AUX COMM panel.
                tacanUfc = tacanLabel(ufc, ufcBits),
                tacanAux = tacanLabel(aux, auxBits),
                tacan = preferredTacan(ufc, ufcBits, aux, auxBits),
                pilots = (0 until online).mapNotNull { k ->
                    latin1(fd2, FD2.pilotsCallsign + k * 12, 12).takeIf { it.isNotEmpty() }?.let { Pilot(it, fd2.u8(FD2.pilotsStatus + k)) }
                },
            )
        }
        snap.strings[StringId.VoiceHelpers]?.let { live = live.copy(voice = parseVoice(it)) }
        snap.live = live.copy(navPoints = snap.navPointStrings.mapNotNull(::parseNavPoint))
        return snap
    }

    /** Copies a named shared memory area (at most [size] bytes; shorter areas of older BMS versions are zero-padded). */
    private fun map(name: String, size: Int): ByteBuffer? {
        val k = Kernel32.INSTANCE
        val handle = k.OpenFileMapping(WinNT.FILE_MAP_READ, false, name) ?: return null
        try {
            val view = k.MapViewOfFile(handle, WinNT.FILE_MAP_READ, 0, 0, 0) ?: return null
            try {
                val available = regionSize(view)
                val bytes = view.getByteArray(0, min(size.toLong(), available).toInt())
                return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).put(bytes).also { it.rewind() }
            } finally {
                k.UnmapViewOfFile(view)
            }
        } catch (_: Throwable) {
            return null
        } finally {
            k.CloseHandle(handle)
        }
    }

    private fun regionSize(p: Pointer): Long {
        val info = WinNT.MEMORY_BASIC_INFORMATION()
        Kernel32.INSTANCE.VirtualQueryEx(Kernel32.INSTANCE.GetCurrentProcess(), p, info, BaseTSD.SIZE_T(info.size().toLong()))
        return info.regionSize.toLong()
    }

    private fun readStrings(areaSize: Long, snap: BmsSnapshot) {
        val k = Kernel32.INSTANCE
        val handle = k.OpenFileMapping(WinNT.FILE_MAP_READ, false, STRINGS) ?: return
        try {
            val view = k.MapViewOfFile(handle, WinNT.FILE_MAP_READ, 0, 0, 0) ?: return
            try {
                var cap = regionSize(view)
                if (areaSize > 0) cap = min(cap, areaSize)
                if (cap < 12) return
                val count = view.getInt(4).toLong() and 0xFFFFFFFFL
                var off = 12L
                var n = 0L
                while (n < count && off + 8 <= cap) {
                    val id = view.getInt(off)
                    val len = view.getInt(off + 4).toLong() and 0xFFFFFFFFL
                    off += 8
                    if (off + len + 1 > cap) break
                    val str = String(view.getByteArray(off, len.toInt()), Charsets.ISO_8859_1)
                    off += len + 1
                    if (id == StringId.NavPoint) snap.navPointStrings += str else snap.strings[id] = str
                    n++
                }
            } finally {
                k.UnmapViewOfFile(view)
            }
        } catch (_: Throwable) {
            // area not present
        } finally {
            k.CloseHandle(handle)
        }
    }

    /** NP:<index>,<type>,<x>,<y>,<z>,<grnd_elev>;[O1:..;][O2:..;][PT:"name",range,declutter;] */
    fun parseNavPoint(s: String): NavPoint? {
        var np: NavPoint? = null
        for (part in s.split(';').filter { it.isNotEmpty() }) {
            val colon = part.indexOf(':')
            if (colon < 0) continue
            val tag = part.substring(0, colon).trim()
            val fields = part.substring(colon + 1).split(',')
            if (tag == "NP" && fields.size >= 5) {
                // FlightData.h says "10s of feet" but BMS 4.38.1 sends feet (matches the DTC .ini values exactly)
                np = NavPoint(i = num(fields[0]).toInt(), type = fields[1].trim(), x = num(fields[2]), y = num(fields[3]), altFt = abs(num(fields[4])))
            } else if (tag == "PT" && np != null && fields.size >= 2) {
                val r = num(fields[1])
                // BMS stores PPT range in feet; tolerate nm just in case.
                np = np.copy(name = fields[0].trim().trim('"'), rangeNm = if (r > 500) r / FT_PER_NM else r)
            }
        }
        return np
    }

    fun parseVoice(s: String): Voice {
        val parts = s.split(',')
        val ps = parts.getOrNull(0)?.split('|').orEmpty()
        fun tok(i: Int) = parts.getOrNull(i)?.trim()?.takeUnless { it.equals("None", ignoreCase = true) }
        return Voice(
            flight = ps.getOrNull(0)?.trim(), seats = ps.getOrNull(1)?.trim(),
            tanker = tok(1), awacs = tok(2), departure = tok(3), arrival = tok(4), alternate = tok(5),
        )
    }

    /** "75X", "12Y A/A". tacanInfo bits: 0x01 = X band, 0x02 = air-to-air mode. */
    fun tacanLabel(channel: Int, bits: Int): String? =
        if (channel > 0) "$channel${if (bits and 1 != 0) "X" else "Y"}${if (bits and 2 != 0) " A/A" else ""}" else null

    /**
     * The channel to show as the aircraft's TACAN. An air-to-air or Y-band channel is one the pilot tuned
     * (tanker, wingman), while an X-band T/R channel is normally an airbase, so the A/A or Y source wins.
     */
    fun preferredTacan(ufcChannel: Int, ufcBits: Int, auxChannel: Int, auxBits: Int): String? {
        fun rank(ch: Int, bits: Int) = when { ch <= 0 -> 0; bits and 2 != 0 -> 3; bits and 1 == 0 -> 2; else -> 1 }
        val u = rank(ufcChannel, ufcBits)
        val a = rank(auxChannel, auxBits)
        if (u == 0 && a == 0) return null
        return if (a > u) tacanLabel(auxChannel, auxBits) else tacanLabel(ufcChannel, ufcBits)
    }

    private fun num(s: String) = s.trim().toDoubleOrNull() ?: 0.0
    private fun norm360(d: Double): Double { val r = d % 360; return if (r < 0) r + 360 else r }

    /** DED uses special glyphs: 0x01 = selection box/asterisk, 0x02 = degree sign. */
    private fun dedString(b: ByteBuffer, off: Int, max: Int): String = buildString {
        for (k in 0 until max) {
            val c = b.get(off + k).toInt() and 0xFF
            if (c == 0) break
            append(when { c == 1 -> '*'; c == 2 -> '°'; c < 32 -> ' '; else -> c.toChar() })
        }
    }

    private fun latin1(b: ByteBuffer, off: Int, max: Int): String {
        var len = 0
        while (len < max && b.get(off + len).toInt() != 0) len++
        return String(ByteArray(len) { b.get(off + it) }, Charsets.ISO_8859_1).trim()
    }

    private fun ByteBuffer.f(off: Int) = getFloat(off)
    private fun ByteBuffer.i(off: Int) = getInt(off)
    private fun ByteBuffer.u32(off: Int) = getInt(off).toLong() and 0xFFFFFFFFL
    private fun ByteBuffer.u8(off: Int) = get(off).toInt() and 0xFF
}

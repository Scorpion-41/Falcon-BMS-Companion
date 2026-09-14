using System.Globalization;
using System.IO.MemoryMappedFiles;
using System.Runtime.CompilerServices;
using System.Text;
using BmsCompanion.Bridge.Server;

namespace BmsCompanion.Bridge.Bms;

/// <summary>Snapshot of everything the bridge reads from BMS shared memory.</summary>
public sealed class BmsSnapshot
{
    public bool Available;
    public bool Flying;
    public LiveDto Live = new();
    public Dictionary<StringId, string> Strings = new();
    public List<string> NavPointStrings = new();
    public string? Version;
    public uint HsiBits, LightBits;
    public int PilotsOnline;
    public List<int> PilotStatus = new();
}

/// <summary>Reads the BMS shared memory areas. Opens them fresh on every read so a closed BMS never leaves stale data.</summary>
public static unsafe class SharedMemoryReader
{
    private const double FtPerNm = 6076.12;

    public static BmsSnapshot Read()
    {
        var snap = new BmsSnapshot();
        if (!BmsInstall.IsBmsRunning()) return snap;

        FlightDataRaw fd;
        FlightData2Raw fd2 = default;
        bool haveFd2;
        if (!TryReadStruct(SharedMemNames.FlightData, out fd)) return snap;
        haveFd2 = TryReadStruct(SharedMemNames.FlightData2, out fd2);
        snap.Available = true;
        snap.Flying = (fd.hsiBits & SharedMemNames.HsiFlying) != 0;
        snap.HsiBits = fd.hsiBits; snap.LightBits = fd.lightBits;
        // BMS 4.38.1 does not set the documented hsiBits Flying flag; the pilot status table (3 = FLYING) is reliable.
        if (!snap.Flying && haveFd2 && (fd.x != 0 || fd.y != 0))
        {
            int pilots = Math.Clamp((int)fd2.pilotsOnline, 0, 32);
            for (int i = 0; i < pilots; i++) if (fd2.pilotsStatus[i] == 3) { snap.Flying = true; break; }
        }
        if (haveFd2 && fd2.BMSVersionMajor > 0)
            snap.Version = $"{fd2.BMSVersionMajor}.{fd2.BMSVersionMinor}.{fd2.BMSVersionMicro} ({fd2.BMSBuildNumber})";

        ReadStrings(haveFd2 ? fd2.StringAreaSize : 0, snap);
        var live = snap.Live;
        live.T = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        live.Flying = snap.Flying;
        live.Theater = snap.Strings.GetValueOrDefault(StringId.ThrName);
        live.Aircraft = snap.Strings.GetValueOrDefault(StringId.AcName);

        live.X = fd.x; live.Y = fd.y; live.AltFt = -fd.z;
        live.HdgTrue = Norm360(fd.yaw * 180 / Math.PI);
        live.HdgMag = Norm360(fd.currentHeading);
        live.Kias = fd.kias; live.Mach = fd.mach;
        live.GsKts = Math.Sqrt(fd.xDot * fd.xDot + fd.yDot * fd.yDot) * 3600 / FtPerNm;
        live.VviFpm = -fd.zDot * 60;
        live.GLoad = fd.gs; live.Aoa = fd.alpha;
        live.FuelInternal = fd.internalFuel; live.FuelExternal = fd.externalFuel; live.FuelFlow = fd.fuelFlow;
        live.Chaff = (int)Math.Max(0, fd.ChaffCount); live.Flares = (int)Math.Max(0, fd.FlareCount);
        live.Gear = fd.gearPos; live.SpeedBrake = fd.speedBrake;
        live.DesiredCourse = fd.desiredCourse;
        live.BeaconBrg = fd.bearingToBeacon; live.BeaconNm = fd.distanceToBeacon;
        live.Ded = Lines(fd.DEDLines, 5, 26);

        int n = Math.Clamp(fd.RwrObjectCount, 0, 40);
        for (int i = 0; i < n; i++)
        {
            live.Rwr.Add(new RwrDto
            {
                Sym = fd.RWRsymbol[i],
                Brg = Norm360(fd.bearing[i] * 180 / Math.PI),
                Lethality = fd.lethality[i],
                Launch = fd.missileLaunch[i] != 0,
                Lock = fd.missileActivity[i] != 0,
                Selected = fd.selected[i] != 0,
                New = fd.newDetection[i] != 0,
            });
        }

        if (haveFd2)
        {
            live.RadarAltFt = fd2.RALT;
            live.Bingo = fd2.bingoFuel;
            live.TimeSec = fd2.currentTime;
            live.Lat = fd2.latitude; live.Lon = fd2.longitude;
            live.NavMode = fd2.navMode;
            live.IlsFreq = fd2.tacan_ils_frequency;
            live.UhfPreset = fd2.uhf_panel_preset; live.UhfFreq = fd2.uhf_panel_frequency;
            if (fd2.bullseyeX != 0 || fd2.bullseyeY != 0) { live.BullX = fd2.bullseyeX; live.BullY = fd2.bullseyeY; }
            // tacanInfo bits: 0x01 = X band, 0x02 = A/A mode
            byte ufc = fd2.tacanInfo[0];
            live.Tacan = fd.UFCTChan > 0 ? $"{fd.UFCTChan}{((ufc & 1) != 0 ? "X" : "Y")}{((ufc & 2) != 0 ? " A/A" : "")}" : null;
            int online = Math.Clamp((int)fd2.pilotsOnline, 0, 32);
            snap.PilotsOnline = online;
            for (int i = 0; i < Math.Max(online, 1); i++) snap.PilotStatus.Add(fd2.pilotsStatus[i]);
            for (int i = 0; i < online; i++)
            {
                var cs = CString(fd2.pilotsCallsign + i * 12, 12);
                if (cs.Length > 0) live.Pilots.Add(new PilotDto { Callsign = cs, Status = fd2.pilotsStatus[i] });
            }
        }

        foreach (var kv in snap.Strings)
        {
            if (kv.Key == StringId.VoiceHelpers) live.Voice = ParseVoice(kv.Value);
        }
        live.NavPoints = NavPointsFrom(snap);
        return snap;
    }

    private static List<NavPointDto> NavPointsFrom(BmsSnapshot s) => s.NavPointStrings.Select(ParseNavPoint).Where(p => p != null).Cast<NavPointDto>().ToList();

    private static bool TryReadStruct<T>(string name, out T value) where T : unmanaged
    {
        value = default;
        try
        {
            using var mmf = MemoryMappedFile.OpenExisting(name, MemoryMappedFileRights.Read);
            using var view = mmf.CreateViewAccessor(0, 0, MemoryMappedFileAccess.Read);
            long size = Unsafe.SizeOf<T>();
            if (view.Capacity >= size)
            {
                view.Read(0, out value);
                return true;
            }
            // Older BMS with a shorter struct: copy what exists, rest stays zero.
            var buf = new byte[size];
            view.ReadArray(0, buf, 0, (int)view.Capacity);
            fixed (byte* p = buf) value = Unsafe.Read<T>(p);
            return true;
        }
        catch { return false; }
    }

    private static void ReadStrings(uint areaSize, BmsSnapshot snap)
    {
        var dict = snap.Strings;
        try
        {
            using var mmf = MemoryMappedFile.OpenExisting(SharedMemNames.Strings, MemoryMappedFileRights.Read);
            using var view = mmf.CreateViewAccessor(0, 0, MemoryMappedFileAccess.Read);
            long cap = view.Capacity;
            if (areaSize > 0) cap = Math.Min(cap, areaSize);
            if (cap < 12) return;
            uint count = view.ReadUInt32(4);
            long off = 12;
            for (uint i = 0; i < count && off + 8 <= cap; i++)
            {
                uint id = view.ReadUInt32(off);
                uint len = view.ReadUInt32(off + 4);
                off += 8;
                if (off + len + 1 > cap) break;
                var bytes = new byte[len];
                view.ReadArray(off, bytes, 0, (int)len);
                off += len + 1;
                var str = Encoding.Latin1.GetString(bytes);
                if ((StringId)id == StringId.NavPoint) snap.NavPointStrings.Add(str);
                else dict[(StringId)id] = str;
            }
        }
        catch { /* area not present */ }
    }

    /// <summary>NP:&lt;index&gt;,&lt;type&gt;,&lt;x&gt;,&lt;y&gt;,&lt;z&gt;,&lt;grnd_elev&gt;;[O1:..;][O2:..;][PT:"name",range,declutter;]</summary>
    public static NavPointDto? ParseNavPoint(string s)
    {
        NavPointDto? np = null;
        foreach (var part in s.Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            var colon = part.IndexOf(':');
            if (colon < 0) continue;
            var tag = part[..colon].Trim();
            var fields = part[(colon + 1)..].Split(',');
            if (tag == "NP" && fields.Length >= 5)
            {
                np = new NavPointDto
                {
                    I = (int)Num(fields[0]),
                    Type = fields[1].Trim(),
                    X = Num(fields[2]),
                    Y = Num(fields[3]),
                    // FlightData.h says "10s of feet" but BMS 4.38.1 sends feet (matches the DTC .ini values exactly)
                    AltFt = Math.Abs(Num(fields[4])),
                };
            }
            else if (tag == "PT" && np != null && fields.Length >= 2)
            {
                np.Name = fields[0].Trim().Trim('"');
                var r = Num(fields[1]);
                // BMS stores PPT range in feet; tolerate nm just in case.
                np.RangeNm = r > 500 ? r / FtPerNm : r;
            }
        }
        return np;
    }

    public static VoiceDto ParseVoice(string s)
    {
        var parts = s.Split(',');
        var v = new VoiceDto();
        if (parts.Length > 0)
        {
            var ps = parts[0].Split('|');
            v.Flight = ps[0].Trim();
            if (ps.Length > 1) v.Seats = ps[1].Trim();
        }
        string? Tok(int i) => parts.Length > i && !parts[i].Trim().Equals("None", StringComparison.OrdinalIgnoreCase) ? parts[i].Trim() : null;
        v.Tanker = Tok(1); v.Awacs = Tok(2); v.Departure = Tok(3); v.Arrival = Tok(4); v.Alternate = Tok(5);
        return v;
    }

    private static double Num(string s) => double.TryParse(s.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var d) ? d : 0;
    private static double Norm360(double d) { d %= 360; return d < 0 ? d + 360 : d; }

    private static List<string> Lines(byte* p, int rows, int cols)
    {
        var list = new List<string>(rows);
        for (int r = 0; r < rows; r++) list.Add(DedString(p + r * cols, cols));
        return list;
    }

    /// <summary>DED uses special glyphs: 0x01 = selection box/asterisk, 0x02 = degree sign.</summary>
    private static string DedString(byte* p, int max)
    {
        var sb = new StringBuilder(max);
        for (int i = 0; i < max; i++)
        {
            byte b = p[i];
            if (b == 0) break;
            sb.Append(b switch { 1 => '*', 2 => '°', _ => b < 32 ? ' ' : (char)b });
        }
        return sb.ToString();
    }

    private static string CString(byte* p, int max)
    {
        int len = 0;
        while (len < max && p[len] != 0) len++;
        return Encoding.Latin1.GetString(p, len).Trim();
    }
}

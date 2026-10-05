using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;

// Latitude and longitude as Weapon Delivery Planner prints them, for every new-terrain theater of a Falcon BMS
// install, written down so the port (app/.../data/wdp/WdpCoords.kt + PopupCoords.kt) can be checked against it.
//
// WDP sets its projection up once per theater, in fclsMain.InitNewTerrain + InitTransverseMercator: the heightmap's
// LENGTH gives the grid (samples a side = sqrt(bytes / 2)), Theater.txt the theater's size and centre, and
// GeographicLib's forward projection of that centre the offsets. This harness makes WDP do exactly that, by
// reflection, for each theater — not a reading of the code, the code itself — and then asks the clsCoordinates a
// page would hold (EnableNewTerrain, and that TransverseMercatorMeta, as SetCoordData copies them) for thousands of
// points each: FeetToCoordsBoth, GetNorthDeg/GetEastDeg of its answer, and ConvertLatLonToFeet (the way the TOSS
// and HADB pages turn a coordinate label back into feet) of that. GeographicLib's own Forward and Reverse are
// sampled directly too, since the whole chain rests on them.
//
// The BMS install is only READ. InitNewTerrain opens the heightmap for writing (File.Open's default), so WDP is
// never pointed at the install: each theater's Theater.txt is copied into a scratch folder beside <out> and the
// heightmap there is a sparse file of the real one's length, which is all WDP reads of it. A few synthetic
// theaters (other heightmap sizes, a southern and an antimeridian centre, missing keys, no heightmap) exercise the
// set-up arithmetic where a 32768-sample grid would hide it.
//
//   wdpref page Coords <WeaponDeliveryPlanner.exe> <out.tsv>      (run from WDP's own folder)
//   env BMS_ROOT=<the BMS folder> (else the registry's), COORDS_POINTS=n (points per theater, default 3000),
//   COORDS_TOSS=n (TOSS rows per theater, default 150)
//
// Beside <out.tsv> it writes <out.tsv>.toss.tsv: the TOSS page's VIP mode driven through Get_Coords with each
// theater's projection (see Toss() below).
public static class Coords
{
    private const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

    private static Type mainType, tmType, coordType, tmLibType;
    private static object main;
    private static int points = 3000, tossRows = 150;

    /** One terrain WDP can load: where its files are, and the raw figures a synthetic one is made from. */
    private sealed class Terrain
    {
        public string Key, Names = "", Dir;
        public bool Real;
        public long Bytes = -1;          // heightmap length, -1: none
        public string TxtBody;           // Theater.txt as written (synthetic), null: none
    }

    public static int Run(string exePath, string outPath)
    {
        Thread.CurrentThread.CurrentCulture = new CultureInfo("en-US");
        Thread.CurrentThread.CurrentCulture.NumberFormat.NumberDecimalSeparator = ".";
        if (int.TryParse(Environment.GetEnvironmentVariable("COORDS_POINTS"), out var pts)) points = pts;
        if (int.TryParse(Environment.GetEnvironmentVariable("COORDS_TOSS"), out var tr)) tossRows = tr;

        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        var myProject = asm.GetType("WeaponDeliveryPlanner.My.MyProject", throwOnError: true);
        var forms = myProject.GetProperty("Forms", BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public).GetValue(null, null);
        main = forms.GetType().GetProperty("fclsMain", Any).GetValue(forms, null);
        mainType = main.GetType();
        tmType = mainType.GetField("TransverseMercatorMeta", Any).FieldType;
        coordType = asm.GetType("WeaponDeliveryPlanner.clsCoordinates", throwOnError: true);
        var geo = Assembly.LoadFrom(Path.Combine(Path.GetDirectoryName(Path.GetFullPath(exePath)), "NETGeographic.dll"));
        tmLibType = geo.GetType("NETGeographicLib.TransverseMercator", throwOnError: true);
        var version = (string)geo.GetType("NETGeographicLib.VersionInfo").GetMethod("GetString").Invoke(null, null);

        var root = Environment.GetEnvironmentVariable("BMS_ROOT");
        if (string.IsNullOrEmpty(root))
        {
            using (var k = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Benchmark Sims\Falcon BMS 4.38"))
                root = k?.GetValue("baseDir") as string;
        }
        if (string.IsNullOrEmpty(root) || !Directory.Exists(root)) { Console.Error.WriteLine("no BMS folder: set BMS_ROOT"); return 2; }

        var scratch = Path.GetFullPath(outPath + ".terrain");
        if (Directory.Exists(scratch)) Directory.Delete(scratch, true);
        Directory.CreateDirectory(scratch);

        var terrains = RealTerrains(root);
        terrains.AddRange(Synthetic());
        int n = 0;
        foreach (var t in terrains) Mirror(t, Path.Combine(scratch, "t" + (n++)), root);

        var sb = new StringBuilder();
        sb.AppendLine("kind\ta\tb\tc\td\te\tf\tg\th\ti\tj\tk\tl\tm\tn\to\tp");
        sb.AppendLine("info\tgeographiclib\t" + version);
        var metas = new Dictionary<string, object>();
        foreach (var t in terrains)
        {
            if (t.Bytes >= 0 && t.TxtBody == null && !File.Exists(Path.Combine(t.Dir, "NewTerrain", "Theater.txt")))
            {
                // WDP would stop on a message box ("File not found") here; there is none of these in a stock install
                Console.WriteLine("skipped " + t.Key + ": a heightmap and no Theater.txt opens a message box");
                continue;
            }
            var meta = Init(t);
            metas[t.Key] = meta;
            sb.AppendLine(TheaterRow(t, meta));
            Sample(sb, t, meta);
        }
        Direct(sb);
        Degrees(sb);
        File.WriteAllText(outPath, sb.ToString(), new UTF8Encoding(false));
        Console.WriteLine("wrote " + outPath);

        Toss(asm, outPath + ".toss.tsv", terrains.Where(t => t.Real && metas.ContainsKey(t.Key)).ToList(), metas);
        try { Directory.Delete(scratch, true); } catch (Exception) { }
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ the theaters

    /**
     * Every theater definition in theater.lst, parsed the way fclsMain parses one (first word, the rest after the
     * first space), grouped by the terrain folder WDP would read: the definition's terraindir, or WDP's own
     * default Terrdata\korea\terrain\ when it names none.
     */
    private static List<Terrain> RealTerrains(string root)
    {
        var data = Path.Combine(root, "Data");
        var byDir = new Dictionary<string, Terrain>(StringComparer.OrdinalIgnoreCase);
        var list = new List<Terrain>();
        foreach (var raw in File.ReadAllLines(Path.Combine(data, "TerrData", "TheaterDefinition", "theater.lst")))
        {
            var rel = raw.Trim();
            if (rel.Length == 0 || rel.StartsWith("#")) continue;
            var tdf = Path.Combine(data, rel);
            if (!File.Exists(tdf)) continue;
            string name = Path.GetFileNameWithoutExtension(tdf), terrain = data + "\\Terrdata\\korea\\terrain\\";
            foreach (var line in File.ReadAllLines(tdf))
            {
                if (line.Length <= 1) continue;
                int sp = line.IndexOf(' ');
                var word = (sp < 0 ? line : line.Substring(0, sp)).ToLowerInvariant();
                var rest = sp < 0 ? "" : line.Substring(sp + 1);
                if (word == "name") name = rest;
                if (word == "terraindir")
                {
                    rest = rest.Replace("/", "\\");
                    if (!rest.EndsWith("\\")) rest += "\\";
                    terrain = data + "\\" + rest;
                }
            }
            var key = Path.GetFullPath(terrain);
            if (!byDir.TryGetValue(key, out var t))
            {
                t = new Terrain { Key = "real" + byDir.Count, Dir = terrain, Real = true };
                byDir[key] = t;
                list.Add(t);
            }
            t.Names += (t.Names.Length > 0 ? "|" : "") + name.Trim();
        }
        return list;
    }

    private static List<Terrain> Synthetic()
    {
        string Txt(string size, string lat, string lon) =>
            "Theater name=Synthetic\r\n" + (size == null ? "" : "Theater size in KM=" + size + "\r\n") + "Map size in pixels=1024\r\n" +
            (lat == null ? "" : "Center latitude=" + lat + "\r\n") + (lon == null ? "" : "Center longitude=" + lon + "\r\n") +
            "Projection string=+proj=tmerc +lon_0=" + (lon ?? "0") + " +ellps=WGS84 +k=0.9996 +units=m +x_0=512000 +y_0=-1e+06";
        return new List<Terrain>
        {
            new Terrain { Key = "syn1024", Bytes = 2L * 1024 * 1024, TxtBody = Txt("1024", "45.25", "-122.5") },
            new Terrain { Key = "syn4097", Bytes = 2L * 4097 * 4097 + 3, TxtBody = Txt("2048", "-33.9", "151.2") },
            new Terrain { Key = "syn16384", Bytes = 2L * 16384 * 16384, TxtBody = Txt("1024", "64.5", "179.9") },
            new Terrain { Key = "syn3000", Bytes = 2L * 3000 * 3000 - 5, TxtBody = Txt("512", "0.5", "-0.25") },
            new Terrain { Key = "synodd", Bytes = 2L * 32768 * 32768, TxtBody = Txt("1000.4", "12.345678901", "-179.99") },
            new Terrain { Key = "synnosize", Bytes = 2L * 32768 * 32768, TxtBody = Txt(null, "38.5", "127.5") },
            new Terrain { Key = "synnocentre", Bytes = 2L * 32768 * 32768, TxtBody = Txt("1024", null, null) },
            new Terrain { Key = "synnoheightmap", Bytes = -1, TxtBody = Txt("1024", "38.5", "127.5") },
            new Terrain { Key = "syntiny", Bytes = 2, TxtBody = Txt("1024", "38.5", "127.5") },
        };
    }

    /** The theater's two files in a scratch folder of its own: Theater.txt copied, the heightmap a sparse file. */
    private static void Mirror(Terrain t, string dir, string root)
    {
        var nt = Path.Combine(dir, "NewTerrain");
        Directory.CreateDirectory(Path.Combine(nt, "Heightmaps"));
        if (t.Real)
        {
            var src = t.Dir;
            var txt = Path.Combine(src, "NewTerrain", "Theater.txt");
            if (File.Exists(txt)) File.WriteAllBytes(Path.Combine(nt, "Theater.txt"), File.ReadAllBytes(txt));
            var hm = Path.Combine(src, "NewTerrain", "Heightmaps", "HeightMap.raw");
            t.Bytes = File.Exists(hm) ? new FileInfo(hm).Length : -1;
        }
        else if (t.TxtBody != null)
        {
            File.WriteAllText(Path.Combine(nt, "Theater.txt"), t.TxtBody);
        }
        if (t.Bytes >= 0)
        {
            using (var fs = new FileStream(Path.Combine(nt, "Heightmaps", "HeightMap.raw"), FileMode.CreateNew, FileAccess.ReadWrite))
            {
                MakeSparse(fs.SafeFileHandle);
                fs.SetLength(t.Bytes);
            }
        }
        t.Dir = dir + "\\";
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool DeviceIoControl(SafeFileHandle h, uint code, IntPtr inBuf, uint inSize, IntPtr outBuf, uint outSize, out uint returned, IntPtr overlapped);

    private static void MakeSparse(SafeFileHandle h)
    {
        const uint FSCTL_SET_SPARSE = 0x000900C4;
        DeviceIoControl(h, FSCTL_SET_SPARSE, IntPtr.Zero, 0, IntPtr.Zero, 0, out _, IntPtr.Zero);
    }

    /** fclsMain.InitNewTerrain for this theater, as the first theater WDP loads: nothing carried over. */
    private static object Init(Terrain t)
    {
        mainType.GetField("terraindir", Any).SetValue(main, t.Dir);
        mainType.GetField("HeightmapFile", Any).SetValue(main, null);
        mainType.GetField("HEIGHTMAP_SAMPLES_UINT", Any).SetValue(main, 0u);
        mainType.GetField("TransverseMercatorMeta", Any).SetValue(main, Activator.CreateInstance(tmType));
        mainType.GetMethod("InitNewTerrain", Any).Invoke(main, null);
        return mainType.GetField("TransverseMercatorMeta", Any).GetValue(main);
    }

    private static string TheaterRow(Terrain t, object meta)
    {
        string raw(string key)
        {
            if (t.TxtBody == null && !t.Real) return "-";
            var body = t.TxtBody ?? (File.Exists(Path.Combine(t.Dir, "NewTerrain", "Theater.txt")) ? File.ReadAllText(Path.Combine(t.Dir, "NewTerrain", "Theater.txt")) : null);
            if (body == null) return "-";
            string v = "-";
            foreach (var line in body.Split(new[] { "\r\n", "\n", "\r" }, StringSplitOptions.None))
            {
                int i = line.IndexOf('=');
                if (i > 0 && line.Substring(0, i).ToLowerInvariant() == key) v = line.Substring(i + 1);
            }
            return v;
        }
        bool txt = t.TxtBody != null || File.Exists(Path.Combine(t.Dir, "NewTerrain", "Theater.txt"));
        uint samples = (uint)mainType.GetField("HEIGHTMAP_SAMPLES_UINT", Any).GetValue(main);
        return string.Join("\t", "theater", t.Key, t.Real ? t.Names : "-", txt ? "1" : "0", t.Bytes.ToString(CultureInfo.InvariantCulture),
            raw("theater size in km"), raw("center latitude"), raw("center longitude"), samples.ToString(CultureInfo.InvariantCulture), MetaText(meta));
    }

    /** Every field of the TransverseMercatorMeta, bit for bit: doubles and floats in hex, the size as it is. */
    private static string MetaText(object m)
    {
        double D(string f) => (double)tmType.GetField(f).GetValue(m);
        float F(string f) => (float)tmType.GetField(f).GetValue(m);
        return string.Join(",", H(D("Meridian")), H(D("offsetX")), H(D("offsetY")), H(D("centerLat")), H(D("centerLon")),
            ((uint)tmType.GetField("theaterSizeInMeters").GetValue(m)).ToString(CultureInfo.InvariantCulture),
            H(F("HEIGHTMAP_SIZE")), H(F("METER_RES")), H(F("FT_TO_GRID")), H(F("GRID_TO_FT")), H(F("GRID_OFFSET")));
    }

    // ------------------------------------------------------------------------------------------------ the points

    /** A page's clsCoordinates after SetCoordData under new terrain: EnableNewTerrain and the theater's meta. */
    private static object Coordinates(object meta)
    {
        var co = Activator.CreateInstance(coordType);
        coordType.GetProperty("FALCON_ORIGIN_LAT").SetValue(co, 0.0);
        coordType.GetProperty("FALCON_ORIGIN_LONG").SetValue(co, 0.0);
        coordType.GetProperty("CampW").SetValue(co, 3358699.5);
        coordType.GetProperty("CampH").SetValue(co, 3358699.5);
        coordType.GetProperty("EnableNewTerrain").SetValue(co, true);
        coordType.GetProperty("TransverseMercatorMeta").SetValue(co, meta);
        return co;
    }

    private static void Sample(StringBuilder sb, Terrain t, object meta)
    {
        var co = Coordinates(meta);
        var both = coordType.GetMethod("FeetToCoordsBoth");
        var north = coordType.GetMethod("GetNorthDeg");
        var east = coordType.GetMethod("GetEastDeg");
        var back = coordType.GetMethod("ConvertLatLonToFeet");
        var toSim = coordType.GetMethod("ConvertLatLonToSimXY", new[] { typeof(float), typeof(float), typeof(float).MakeByRefType(), typeof(float).MakeByRefType() });
        var dec = coordType.GetMethod("CoordinatesToDec");
        var r = new Random(t.Key.GetHashCode() & 0x7fffffff ^ 1234);
        uint size = (uint)tmType.GetField("theaterSizeInMeters").GetValue(meta);
        double w = Math.Max(size, 1024000u) * 3.28084;    // the theater's width in feet, roughly: sampled a little past it

        var inputs = new List<(double, double)>();
        // the edges: nothing, one axis, the corners, just inside and outside them, negative, far out
        foreach (var a in new[] { 0.0, 0.1, 0.5, 1.0, -1.0, 3358699.5, 3358700.0, 3358698.0, 6717399.0, w, w + 1, w - 1, -w, 1.0e7, -1.0e7, 1.0e9 })
            foreach (var b in new[] { 0.0, 1.0, 1679349.75, 3358699.5, w, -0.5, 1.0e8 })
            { inputs.Add((a, b)); inputs.Add((b, a)); }
        // the DataCard's steerpoints: a campaign cell's centre, (grid + 0.5) × KM_TO_FT, as a float
        for (int g = 0; g < 200; g++)
        {
            int gx = r.Next(1100), gy = r.Next(1100);
            inputs.Add(((float)((gy + 0.5) * (double)3279.98f), (float)((gx + 0.5) * (double)3279.98f)));
        }
        // anywhere on and around the map, as floats (what the tables hold) and as doubles (what TOSS holds)
        while (inputs.Count < points)
        {
            double fn = r.NextDouble() * w * 1.2 - w * 0.1, fe = r.NextDouble() * w * 1.2 - w * 0.1;
            if (r.Next(2) == 0) { fn = (float)fn; fe = (float)fe; }
            if (r.Next(10) == 0) { fn = Math.Round(fn); fe = Math.Round(fe); }
            inputs.Add((fn, fe));
        }
        foreach (var (fn, fe) in inputs)
        {
            string res = Try(() => (string)both.Invoke(co, new object[] { fn, fe }));
            string n = res.StartsWith("ERR") ? "" : Try(() => (string)north.Invoke(co, new object[] { res }));
            string e = res.StartsWith("ERR") ? "" : Try(() => (string)east.Invoke(co, new object[] { res }));
            string simXY = "", sim = "", decs = "";
            if (!res.StartsWith("ERR"))
            {
                simXY = Try(() => { var args = new object[] { res, "" }; back.Invoke(co, args); return (string)args[1]; });
                // the two steps inside it, bit for bit: the strings to degrees (as floats), and the degrees to feet
                decs = Try(() =>
                {
                    var a = new object[] { n, e, 0.0, 0.0 }; dec.Invoke(co, a);
                    float la = (float)(double)a[2], lo = (float)(double)a[3];
                    var s = new object[] { la, lo, 0f, 0f }; toSim.Invoke(co, s);
                    sim = H((float)s[2]) + "," + H((float)s[3]);
                    return H((double)a[2]) + "," + H((double)a[3]);
                });
            }
            sb.AppendLine(string.Join("\t", "c", t.Key, H(fn), H(fe), res, n, e, simXY, decs, sim));
        }
        // label strings a page could hold that FeetToCoordsBoth never writes: VB's parsing decides
        foreach (var s in new[] { "37,24.123/127,01.456", "-48,30.000/-065,03.000", "00,00.000/000,00.000", "90,00.000/180,00.000",
                     "91,00.000/000,00.000", "37,60.000/127,59.999", "37,5/127,5", "37,/127,", "37,24.123/127", "3.5,1/1,1", "(1),1/1,1",
                     "37,24.123 /127,01.456", "38,30.000/127,30.000", "-01,59.999/-001,00.001", "1e1,0/1,0", "&H10,0/1,0" })
        {
            string simXY = Try(() => { var args = new object[] { s, "" }; back.Invoke(co, args); return (string)args[1]; });
            sb.AppendLine(string.Join("\t", "back", t.Key, Esc(s), simXY));
        }
    }

    /** GeographicLib itself: Forward and Reverse around every theater's meridian, bit for bit. */
    private static void Direct(StringBuilder sb)
    {
        var tm = Activator.CreateInstance(tmLibType);
        var fwd = tmLibType.GetMethods().First(m => m.Name == "Forward" && m.GetParameters().Length == 5);
        var rev = tmLibType.GetMethods().First(m => m.Name == "Reverse" && m.GetParameters().Length == 5);
        var r = new Random(515);
        var meridians = new[] { 127.5, 16.4191, 25.0, 35.0, -64.9519, 0.0, 180.0, -180.0, 179.9, -122.5, 151.2 };
        for (int k = 0; k < 20000; k++)
        {
            double lon0 = meridians[r.Next(meridians.Length)];
            double lat = r.NextDouble() * 180 - 90, lon = lon0 + (r.NextDouble() * 24 - 12);
            switch (k % 23)
            {
                case 0: lat = 0; break;
                case 1: lon = lon0; break;
                case 2: lat = r.Next(2) == 0 ? 90 : -90; break;
                case 3: lat = Math.Round(lat); lon = Math.Round(lon); break;
                case 4: lon = lon0 + (r.Next(2) == 0 ? 90 : -90) + r.NextDouble() * 2 - 1; break;   // round the back
                case 5: lat = (float)lat; lon = (float)lon; break;
                case 6: lat = 45; lon = lon0 + 45; break;
                case 7: lon = lon0 + 180; break;
                case 8: lat = 90.0000001; break;
            }
            string f = Try(() => { var a = new object[] { lon0, lat, lon, 0.0, 0.0 }; fwd.Invoke(tm, a); return H((double)a[3]) + "," + H((double)a[4]); });
            sb.AppendLine(string.Join("\t", "fwd", H(lon0), H(lat), H(lon), f));
            double x = r.NextDouble() * 2.4e6 - 1.2e6, y = r.NextDouble() * 1.4e7 - 7e6;
            if (k % 17 == 0) x = 0;
            if (k % 19 == 0) y = 0;
            string b = Try(() => { var a = new object[] { lon0, x, y, 0.0, 0.0 }; rev.Invoke(tm, a); return H((double)a[3]) + "," + H((double)a[4]); });
            sb.AppendLine(string.Join("\t", "rev", H(lon0), H(x), H(y), b));
        }
    }

    /** GetNorthDeg / GetEastDeg of what else a label can hold. */
    private static void Degrees(StringBuilder sb)
    {
        var co = Activator.CreateInstance(coordType);
        foreach (var s in new[] { null, "", "/", "a", "ab", "a/", "/b", "12,34.567/123,45.678", "no slash here", "a/b/c", "00,00.000/000,00.000", "x/y" })
        {
            string n = Try(() => (string)coordType.GetMethod("GetNorthDeg").Invoke(co, new object[] { s }));
            string e = Try(() => (string)coordType.GetMethod("GetEastDeg").Invoke(co, new object[] { s }));
            sb.AppendLine(string.Join("\t", "deg", s == null ? "<null>" : Esc(s), Esc(n), Esc(e)));
        }
    }

    // ------------------------------------------------------------------------------------------------ TOSS, VIP mode

    private static readonly string[] TossLabels = {
        "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E", "lblIP_elv",
        "lblVIPwp", "lblVIPbrg", "lblVIPrng", "lblVIPelv", "lblVIPnm",
        "lblPUPwp", "lblPUPbrg", "lblPUPrng", "lblPUPelv", "lblPUPnm",
        "lblOA1wp", "lblOA1brg", "lblOA1rng", "lblOA1elv", "lblOA1nm",
        "lblOA2wp", "lblOA2brg", "lblOA2rng", "lblOA2elv", "lblOA2nm",
        "lblDEDvip_1", "lblDEDvip_2", "lblDEDpup_1", "lblDEDpup_2",
    };

    /**
     * The TOSS page in VIP mode, where coordinates decide the answer: WDP's Get_Coords prints the target and the
     * IP as lat/lon labels, and CoordFlow turns those LABELS back into feet (ConvertLatLonToFeet) and measures the
     * VIP, pull-up and offset-aim-point figures from what comes back. A cntTOSS is made as the TOSS reference
     * makes it, given the theater through SetCoordData, handed a target and an IP through fclsMain's campaign
     * steerpoint table (DataCard Precision on, the page's source "Camp": the path the app's cartridge stands in
     * for), and its labels are copied out after the flow has run.
     */
    private static void Toss(Assembly asm, string path, List<Terrain> theaters, Dictionary<string, object> metas)
    {
        var type = asm.GetType("WeaponDeliveryPlanner.cntTOSS", throwOnError: true);
        var dataCard = Prop(mainType, main, "cntDataCard");
        var campType = mainType.GetField("tblCampSTPT", Any).FieldType.GetElementType();
        var sb = new StringBuilder();
        sb.Append("theater\twp\ttgtN\ttgtE\ttgtZ\tipN\tipE\tipZ\tvip\tingrHeight\tg\tturn\trelAngle\trelCas\trelHeight\tangleOff\theading\tref\terrors");
        foreach (var l in TossLabels) { sb.Append('\t').Append(l); sb.Append('\t').Append(l + ".visible"); }
        sb.AppendLine();
        var r = new Random(99);
        int rows = 0;
        foreach (var t in theaters)
        {
            var meta = metas[t.Key];
            uint size = (uint)tmType.GetField("theaterSizeInMeters").GetValue(meta);
            double w = size * 3.28084;
            for (int k = 0; k < tossRows; k++)
            {
                mainType.GetField("TransverseMercatorMeta", Any).SetValue(main, meta);
                mainType.GetField("g_bEnableNewTerrain", Any).SetValue(main, true);
                mainType.GetField("blnLoaded", Any).SetValue(main, false);       // Draw() stays out of it
                dataCard.GetType().GetField("Precision", Any).SetValue(dataCard, true);
                var camp = Array.CreateInstance(campType, 25);
                int wp = 3 + r.Next(20);
                float tn = (float)(w * (0.2 + r.NextDouble() * 0.6)), te = (float)(w * (0.2 + r.NextDouble() * 0.6));
                float d = (float)(r.Next(4) == 0 ? r.Next(20000) : 30000 + r.NextDouble() * 200000);
                double a = r.NextDouble() * 2 * Math.PI;
                if (k % 7 == 0) a = r.Next(8) * Math.PI / 4;                        // due north, east, north-east …
                float ipn = (float)(tn + Math.Cos(a) * d), ipe = (float)(te + Math.Sin(a) * d);
                if (k % 11 == 0) { ipn = 0; ipe = 0; }                                 // no IP: no VIP
                if (k % 13 == 0) { ipn = tn; }                                        // an offset of exactly zero
                float tz = r.Next(3) == 0 ? 0 : r.Next(5000), iz = r.Next(3) == 0 ? 0 : r.Next(8000);
                Put(campType, camp, wp - 1, tn, te, tz);
                Put(campType, camp, wp - 2, ipn, ipe, iz);
                mainType.GetField("tblCampSTPT", Any).SetValue(main, camp);

                // the TOSS reference's own sequence (Toss.cs): Setup as Load runs it, then every slider set and its
                // handler run, so nothing comes from the machine's Setup.ini; then the steerpoint, whose
                // STPTChange runs Get_Coords; then the reference knob, and the flow
                var c = (System.Windows.Forms.Control)Activator.CreateInstance(type);
                int errors = 0;
                void Do(Action act) { try { act(); } catch (Exception) { errors++; } }
                Set(type, c, "blnLoadedFlag", false);
                Set(type, c, "blnLAT", true);
                Set(type, c, "blnMAT", false);
                Set(type, c, "blnVersion", true);
                Do(() => Call(type, c, "Setup"));
                Set(type, c, "blnLoadedFlag", true);
                Do(() => Call(type, c, "SetCoordData"));
                Set(type, c, "blnRef", false);
                Do(() => Call(type, c, "ProgramFlow", false));
                int ingrHeight = new[] { 5, 10 }[r.Next(2)], g = new[] { 3, 5 }[r.Next(2)], turn = r.Next(2);
                int relAngle = new[] { 20, 30, 45 }[r.Next(3)], relCas = new[] { 45, 50 }[r.Next(2)], relHeight = new[] { 30, 40, 55 }[r.Next(3)];
                int angleOff = new[] { 0, 30, 60 }[r.Next(3)], heading = r.Next(4) == 0 ? r.Next(8) * 45 : r.Next(361);
                bool vip = r.Next(3) != 0;
                Do(() =>
                {
                    Set(type, c, "strDTC", "Camp");
                    ((System.Windows.Forms.NumericUpDown)Prop(type, c, "numWaypoint")).Value = wp;
                    Call(type, c, "STPTChange");
                    Slider(type, c, "trbIngrSpd", 45); Call(type, c, "ChangeIngressSpeed");
                    Slider(type, c, "trbIngrHeight", ingrHeight); Call(type, c, "ChangeIngressHeight");
                    Slider(type, c, "trbG", g); Call(type, c, "ChangePullingG");
                    Slider(type, c, "trbTurn", turn); Call(type, c, "ChangeTurn");
                    Slider(type, c, "trbOa2ToPup", 3); Call(type, c, "ChangeOa2ToPup");
                    Slider(type, c, "trbReleaseAngle", relAngle); Call(type, c, "ChangeRelAngle");
                    Slider(type, c, "trbReleaseSpd", relCas); Call(type, c, "ChangeRelSpd");
                    Slider(type, c, "trbReleaseHeight", relHeight); Call(type, c, "ChangeRelHeight");
                    Slider(type, c, "trbAngleOff", angleOff); Call(type, c, "ChangeAngleOff");
                    Slider(type, c, "trbHeading", heading); Call(type, c, "ChangeHeading");
                    Set(type, c, "blnRef", vip);
                    Call(type, c, "Ref");
                    Call(type, c, "ProgramFlow", true);
                });
                sb.Append(string.Join("\t", t.Names.Split('|')[0], wp, H(tn), H(te), H(tz), H(ipn), H(ipe), H(iz), vip ? "1" : "0",
                    ingrHeight, g, turn == 1 ? "Right" : "Left", relAngle, relCas * 10, Get(type, c, "trbReleaseHeight"), angleOff, heading,
                    (bool)type.GetField("blnRef", Any).GetValue(c) ? "VIP" : "VRP", errors));
                foreach (var l in TossLabels)
                {
                    var lbl = Prop(type, c, l) as System.Windows.Forms.Label;
                    sb.Append('\t').Append(lbl?.Text ?? "").Append('\t').Append(lbl == null ? "" : Visible(lbl) ? "1" : "0");
                }
                sb.AppendLine();
                rows++;
                c.Dispose();
            }
        }
        File.WriteAllText(path, sb.ToString(), new UTF8Encoding(false));
        Console.WriteLine("wrote " + rows + " TOSS VIP rows to " + path);
    }

    /** A label's own Visible flag (its parent is never shown here, so Control.Visible would always say false). */
    private static bool Visible(System.Windows.Forms.Control c)
    {
        var m = typeof(System.Windows.Forms.Control).GetMethod("GetState", BindingFlags.NonPublic | BindingFlags.Instance);
        return (bool)m.Invoke(c, new object[] { 2 });   // STATE_VISIBLE
    }

    private static void Put(Type campType, Array camp, int i, float n, float e, float z)
    {
        if (i < 0) return;
        var s = camp.GetValue(i);
        campType.GetField("FalconY").SetValue(s, n);
        campType.GetField("FalconX").SetValue(s, e);
        campType.GetField("FalconZ").SetValue(s, z);
        camp.SetValue(s, i);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static string H(double v) => BitConverter.DoubleToInt64Bits(v).ToString("X16");
    private static string H(float v) => BitConverter.ToInt32(BitConverter.GetBytes(v), 0).ToString("X8");
    private static string Esc(string s) => "[" + s.Replace("\t", "\\t") + "]";

    private static string Try(Func<string> f)
    {
        try { return f() ?? "<null>"; }
        catch (TargetInvocationException e) { return "ERR:" + e.InnerException?.GetType().Name; }
        catch (Exception e) { return "ERR:" + e.GetType().Name; }
    }

    private static void Set(Type t, object o, string field, object value)
    {
        var f = t.GetField(field, Any) ?? throw new Exception("no field " + field);
        f.SetValue(o, value);
    }

    private static void Call(Type t, object o, string method, params object[] args)
    {
        var m = t.GetMethod(method, Any) ?? throw new Exception("no method " + method);
        m.Invoke(o, args);
    }

    private static object Prop(Type t, object o, string name)
    {
        var p = t.GetProperty(name, Any);
        return p != null ? p.GetValue(o, null) : t.GetField("_" + name, BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(o);
    }

    private static int Get(Type t, object o, string name) => ((System.Windows.Forms.TrackBar)Prop(t, o, name)).Value;

    private static void Slider(Type t, object o, string name, int value)
    {
        var tb = (System.Windows.Forms.TrackBar)Prop(t, o, name);
        tb.Value = Math.Max(tb.Minimum, Math.Min(tb.Maximum, value));
    }
}

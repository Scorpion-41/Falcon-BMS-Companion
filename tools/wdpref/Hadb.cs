using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

// Drives the real High Altitude Dive Bomb page (cntHADB) and writes down what it shows.
//
// Every case is a SEQUENCE of what a user and the rest of the program do to the page, written as ops
// ("ini=;load;click=pnlBomb_Down;dive=25;spd=45;..."), applied to a freshly made control on a form of its own, off
// screen, as it sits on fclsMain: "load" shows the form, which is when the page gets its window and its Load runs.
// The Kotlin test (--wdppagetest hadb) replays the very same ops against the port and demands the same text on
// every label, the same visibility of every control the page flips, the same slider values and ranges, picture,
// buttons and nav offsets.
//
// Nothing depends on the machine: the control's ProgramPath is a scratch folder holding the Setup.ini each case
// writes ([HADB] only) and the three profile pictures, each a different width so the one Profile() loaded can be
// told from the Image it leaves. fclsMain's state the page reads — the campaign and TE steerpoint tables, the flight
// table, the DataCard's Precision flag, the theater's coordinate data (SetCoordData) — is reset for every case and
// set by the case's own ops, by reflection. fclsMain.blnLoaded is false, so Draw() (the map, not ported) returns at
// once; it is the last thing each of its callers does. Save to DTC (which writes the pilot's cartridge) and Save Map
// (a file dialog) are never run.
//
// Ops:  ini=K:V|K:V... (the [HADB] section; empty = the section with no keys)  noini (no Setup.ini at all)  load
//       dive|spd|rel|track|ingr|g|turn|vrp|aoff|hdg|zoom=<pos> (the slider, clamped to its range, then its Scroll)
//       wp=<n> (numWaypoint.Value)  click=<control> (its Click handler)  sel=<pnlSelections_Up|Middle|Down>:L|R
//       prec=0|1  src=Camp|TE|Both|None|null  campte=0|1  camp=i,N,E,Z / te=i,N,E,Z  flight=nr,sel,count
//       fwp=i,gx,gy,gz  coord=<preset>  getcoords  camptecall  stpt  flow=0|1
//
//   wdpref page Hadb <WeaponDeliveryPlanner.exe> <out.tsv>       (run from WDP's own folder)
//   env HADB_ROWS=n (an even sample of n cases), HADB_VERBOSE=1 (each case to stderr)
internal static class Hadb
{
    private const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

    internal static readonly string[] Labels = {
        "lblDiveAngleVal", "lblRelCasVal", "lblReleaseHeightVal", "lblTrackingTimeVal", "lblIngrCasVal", "lblGVal",
        "lblTurnVal", "lblVrpToPupVal", "lblAngleOffVal", "lblAttackHdgVal", "lblApproachHedVal", "lblTASval",
        "lblBombrangeVAl", "lblMAPVal", "lblAODval", "lblWheelRad", "lblSlantRangeFeet", "lblSlantRangeNm", "lblWheelElv",
        "lblVRPwp", "lblVRPbrg", "lblVRPrng", "lblVRPelv", "lblVRPnm",
        "lblVRPPUPwp", "lblVRPPUPbrg", "lblVRPPUPrng", "lblVRPPUPelv", "lblPUPnm",
        "lblVRPOA1wp", "lblOA1brg", "lblOA1rng", "lblOA1elv", "lblOA1nm",
        "lblVRPOA2wp", "lblOA2brg", "lblOA2rng", "lblOA2elv", "lblOA2nm",
        "lblAdvIngAltVal", "lblTargetHUDval", "lblZoom",
        "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E", "lblIP_elv",
    };

    internal static readonly string[] Shown = {
        "pnlSelections_Up", "pnlSelections_Middle", "pnlSelections_Down", "pnlSelections", "pnlProfile", "pnlDEDData",
        "pnlBomb_Up", "pnlBomb_Down", "pnlExtraInfo_Up", "pnlExtraInfo_Down", "pnlExtraInfo", "pnlTheWheel",
        "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E", "lblIP_elv",
    };

    internal static readonly string[] Sliders = {
        "trbDiveAngle", "trbReleaseSpd", "trbReleaseHeight", "trbTrackingTime", "trbIngrSpd", "trbG", "trbTurn",
        "trbVrpToPup", "trbAngleOff", "trbHeading", "trbZoom",
    };

    private static readonly string[] Offsets = { "VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2" };
    private static readonly string[] OffsetParts = { "Stpt", "Bearing", "Range", "Elv" };
    private static readonly string[] StateStrings = { "strTurnDirection", "strHighLow", "strDTC", "strWaypoint" };
    private static readonly string[] StateBools = { "blnLoadedFlag", "blnBomb", "blnGotTGT", "blnDoVIP", "blnCampTE" };
    private static readonly string[] StateInts = { "intTAS", "intIngressTAS", "intTurnRadius", "intTrackPointAlt", "intBombRange", "intZoomFactor" };

    internal static readonly string[] Pictures = { "HADB_Left.jpg", "HADB_Right.jpg", "HADB_None.jpg" };

    internal sealed class Coord
    {
        public double Lat, Lon, CampW = 3358699.5, CampH = 3358699.5;
        public bool NewTerrain;
        public double Meridian, OffsetX, OffsetY;
        public uint Size;
        public float HeightmapSize, MeterRes, FtToGrid, GridToFt, GridOffset;
    }

    /** The same presets as the Pop-up harness (and the Kotlin tests). */
    internal static readonly Dictionary<string, Coord> Coords = new Dictionary<string, Coord>
    {
        ["old1"] = new Coord { Lat = 34.0, Lon = 124.0 },
        ["old2"] = new Coord { Lat = -53.5, Lon = -62.0, CampW = 2000000.0, CampH = 2500000.0 },
        ["new1"] = new Coord { NewTerrain = true, Meridian = 127.5, OffsetX = -512000.0, OffsetY = 3700000.0, Size = 1024000u, HeightmapSize = 16384f, MeterRes = 62.5f, FtToGrid = 1f / (62.5f * 3.27998f), GridToFt = 62.5f * 3.27998f, GridOffset = 8192f },
        ["new2"] = new Coord { NewTerrain = true, Meridian = 22.0, OffsetX = -400000.0, OffsetY = 4300000.0, Size = 1024000u, HeightmapSize = 1024f, MeterRes = 1000f, FtToGrid = 1f / 3279.98f, GridToFt = 3279.98f, GridOffset = 512f },
        ["new3"] = new Coord { NewTerrain = true, Meridian = -60.0, OffsetX = -500000.0, OffsetY = -6100000.0, Size = 1024000u, HeightmapSize = 4096f, MeterRes = 250f, FtToGrid = 1f / (250f * 3.27998f), GridToFt = 250f * 3.27998f, GridOffset = 2048f },
    };

    private static int errors;
    private static Type type, campType, teType, flightType, wpType, tmType, conversions;
    private static object main, dataCard;
    private static string progDir;
    private static readonly Dictionary<int, string> pictureByWidth = new Dictionary<int, string>();

    public static int Run(string exePath, string outPath)
    {
        Thread.CurrentThread.CurrentCulture = new CultureInfo("en-US");
        Thread.CurrentThread.CurrentCulture.NumberFormat.NumberDecimalSeparator = ".";
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (sender, e) => errors++;

        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        type = asm.GetType("WeaponDeliveryPlanner.cntHADB", throwOnError: true);
        var myProject = asm.GetType("WeaponDeliveryPlanner.My.MyProject", throwOnError: true);
        var forms = myProject.GetProperty("Forms", BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public).GetValue(null, null);
        main = forms.GetType().GetProperty("fclsMain", Any).GetValue(forms, null);
        dataCard = Control(main.GetType(), main, "cntDataCard");
        var mt = main.GetType();
        campType = mt.GetField("tblCampSTPT", Any).FieldType.GetElementType();
        teType = mt.GetField("tblMissionSTPT", Any).FieldType.GetElementType();
        flightType = mt.GetField("FlightTable", Any).FieldType.GetElementType();
        wpType = flightType.GetField("waypoints", Any).FieldType.GetElementType();
        tmType = mt.GetField("TransverseMercatorMeta", Any).FieldType;
        conversions = Type.GetType("Microsoft.VisualBasic.CompilerServices.Conversions, Microsoft.VisualBasic, Version=10.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a", throwOnError: true);

        progDir = Path.GetFullPath(outPath + ".prog");
        Directory.CreateDirectory(Path.Combine(progDir, "Pictures"));
        for (int i = 0; i < Pictures.Length; i++)
        {
            int w = 20 + i;
            pictureByWidth[w] = Pictures[i];
            using (var bmp = new Bitmap(w, 10)) bmp.Save(Path.Combine(progDir, "Pictures", Pictures[i]), ImageFormat.Jpeg);
        }

        var cases = new List<List<string>>();
        // HADB_CASES=<file>: the caller's own sequences, one a line ("op;op;..."), in place of the generated ones
        var caseFile = Environment.GetEnvironmentVariable("HADB_CASES");
        if (!string.IsNullOrEmpty(caseFile))
        {
            foreach (var line in File.ReadAllLines(caseFile)) if (line.Trim().Length > 0 && !line.StartsWith("#")) cases.Add(line.Trim().Split(';').ToList());
        }
        else
        {
            Grid(cases, 1300);
            Extremes(cases);
            Sweeps(cases);
            Targets(cases, 500);
            Inis(cases);
        }
        int limit = int.TryParse(Environment.GetEnvironmentVariable("HADB_ROWS"), out var lim) ? lim : int.MaxValue;
        if (cases.Count > limit) { var all = cases; cases = Enumerable.Range(0, limit).Select(i => all[(int)((long)i * all.Count / limit)]).ToList(); }

        var head = new List<string> { "case", "ops" };
        head.AddRange(Labels);
        head.Add("lblTargetHUDval.back");
        head.AddRange(Shown.Select(s => "vis_" + s));
        foreach (var s in Sliders) { head.Add("sld_" + s); head.Add("sld_" + s + ".min"); head.Add("sld_" + s + ".max"); }
        head.AddRange(new[] { "numWaypoint", "picProfile", "btnPPTnr.text", "btnCamp.enabled", "btnCamp.back", "btnTE.enabled", "btnTE.back" });
        head.Add("nav_Modesel");
        foreach (var o in Offsets) foreach (var p in OffsetParts) head.Add("nav_" + o + "_" + p);
        head.AddRange(StateStrings.Select(s => "st_" + s));
        head.AddRange(StateBools.Select(s => "st_" + s));
        head.AddRange(StateInts.Select(s => "st_" + s));
        head.AddRange(new[] { "ini_Bomb", "ini_DiveAngle", "ini_CAS", "ini_ReleaseHeight", "ini_TrackingTime", "ini_IngressCAS", "ini_PullingGs", "ini_Turn", "ini_VRPtoPUP", "ini_AngleOff", "ini_AttackHdg", "ini_HighLow", "ini_Waypoint" });
        head.Add("errors");

        using (var w = new StreamWriter(outPath, false, new UTF8Encoding(false)))
        {
            w.WriteLine(string.Join("\t", head));
            for (int i = 0; i < cases.Count; i++)
            {
                if (Environment.GetEnvironmentVariable("HADB_VERBOSE") != null) Console.Error.WriteLine("case " + i + ": " + string.Join(";", cases[i]));
                var row = RunCase(cases[i]);
                w.WriteLine(I(i) + "\t" + string.Join(";", cases[i]) + "\t" + string.Join("\t", row));
                if ((i + 1) % 250 == 0) { Console.WriteLine((i + 1) + " of " + cases.Count + " cases"); w.Flush(); }
            }
        }
        Console.WriteLine("wrote " + cases.Count + " HADB cases to " + outPath);
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ one case

    private static List<string> RunCase(List<string> ops)
    {
        errors = 0;
        ResetMain();
        var c = Make();
        foreach (var op in ops)
        {
            try { Apply(c, op); }
            catch (Exception e) { errors++; Console.Error.WriteLine("op " + op + " escaped: " + e.GetType().Name + " " + e.Message); }
        }
        var row = Snapshot(c);
        Unmake(c);
        return row;
    }

    private static Control Make()
    {
        var c = (Control)Activator.CreateInstance(type);
        Set(type, c, "ProgramPath", progDir);
        Set(type, c, "blnVersion", true);
        var host = new Form { StartPosition = FormStartPosition.Manual, Location = new Point(-32000, -32000), ShowInTaskbar = false, FormBorderStyle = FormBorderStyle.None, Size = new Size(1300, 800) };
        host.Controls.Add(c);
        return c;
    }

    private static void Unmake(Control c)
    {
        var host = c.Parent as Form;
        try { host?.Dispose(); } catch (Exception) { }
        try { c.Dispose(); } catch (Exception) { }
    }

    private static void ResetMain()
    {
        var mt = main.GetType();
        mt.GetField("tblCampSTPT", Any).SetValue(main, Array.CreateInstance(campType, 25));
        mt.GetField("tblMissionSTPT", Any).SetValue(main, Array.CreateInstance(teType, 25));
        mt.GetField("FlightTable", Any).SetValue(main, null);
        mt.GetField("FlightNR", Any).SetValue(main, 0);
        mt.GetField("SelFlightNr", Any).SetValue(main, -1);
        dataCard.GetType().GetField("Precision", Any).SetValue(dataCard, false);
        mt.GetField("CampW", Any).SetValue(main, 3358699.5);
        mt.GetField("CampH", Any).SetValue(main, 3358699.5);
        mt.GetField("FALCON_ORIGIN_LAT", Any).SetValue(main, 0.0);
        mt.GetField("FALCON_ORIGIN_LONG", Any).SetValue(main, 0.0);
        mt.GetField("g_bEnableNewTerrain", Any).SetValue(main, false);
        mt.GetField("TransverseMercatorMeta", Any).SetValue(main, Activator.CreateInstance(tmType));
        mt.GetField("blnLoaded", Any).SetValue(main, false);   // Draw() (the map) returns at once
        var navF = mt.GetField("HADBNavOffsets", Any);
        navF.SetValue(main, Activator.CreateInstance(navF.FieldType));
        File.WriteAllText(Path.Combine(progDir, "Setup.ini"), "[HADB]\r\n");
    }

    private static void Apply(Control c, string op)
    {
        var t = type;
        int eq = op.IndexOf('=');
        string k = eq < 0 ? op : op.Substring(0, eq), v = eq < 0 ? "" : op.Substring(eq + 1);
        var mt = main.GetType();
        switch (k)
        {
            case "ini":
            {
                var sb = new StringBuilder("[HADB]\r\n");
                if (v.Length > 0) foreach (var kv in v.Split('|')) { int i = kv.IndexOf(':'); sb.Append(kv.Substring(0, i)).Append('=').Append(kv.Substring(i + 1)).Append("\r\n"); }
                File.WriteAllText(Path.Combine(progDir, "Setup.ini"), sb.ToString());
                break;
            }
            case "noini": File.Delete(Path.Combine(progDir, "Setup.ini")); break;
            case "load": Guard(() => ((Form)c.Parent).Show()); break;
            case "dive": Slide(c, "trbDiveAngle", v); break;
            case "spd": Slide(c, "trbReleaseSpd", v); break;
            case "rel": Slide(c, "trbReleaseHeight", v); break;
            case "track": Slide(c, "trbTrackingTime", v); break;
            case "ingr": Slide(c, "trbIngrSpd", v); break;
            case "g": Slide(c, "trbG", v); break;
            case "turn": Slide(c, "trbTurn", v); break;
            case "vrp": Slide(c, "trbVrpToPup", v); break;
            case "aoff": Slide(c, "trbAngleOff", v); break;
            case "hdg": Slide(c, "trbHeading", v); break;
            case "zoom": Slide(c, "trbZoom", v); break;
            case "wp":
            {
                var num = (NumericUpDown)Control(t, c, "numWaypoint");
                decimal d = decimal.Parse(v, CultureInfo.InvariantCulture);
                d = Math.Max(num.Minimum, Math.Min(num.Maximum, d));
                Guard(() => num.Value = d);
                break;
            }
            case "click": Call(t, c, v + "_Click", null, EventArgs.Empty); break;
            case "sel":
            {
                var parts = v.Split(':');
                var e = new MouseEventArgs(parts[1] == "R" ? MouseButtons.Right : MouseButtons.Left, 1, 0, 0, 0);
                Call(t, c, parts[0] + "_MouseClick", null, e);
                break;
            }
            case "prec": dataCard.GetType().GetField("Precision", Any).SetValue(dataCard, v == "1"); break;
            case "src": Set(t, c, "strDTC", v == "null" ? null : v); break;
            case "campte": Set(t, c, "blnCampTE", v == "1"); break;
            case "camp": SetStpt("tblCampSTPT", v); break;
            case "te": SetStpt("tblMissionSTPT", v); break;
            case "flight":
            {
                var p = v.Split(',').Select(s => int.Parse(s, CultureInfo.InvariantCulture)).ToArray();
                mt.GetField("FlightNR", Any).SetValue(main, p[0]);
                mt.GetField("SelFlightNr", Any).SetValue(main, p[1]);
                var table = Array.CreateInstance(flightType, Math.Max(p[1] + 1, 1));
                for (int i = 0; i < table.Length; i++)
                {
                    var f = MakeObj(flightType);
                    var wps = Array.CreateInstance(wpType, p[2]);
                    for (int j = 0; j < p[2]; j++) wps.SetValue(MakeObj(wpType), j);
                    flightType.GetField("waypoints", Any).SetValue(f, wps);
                    table.SetValue(f, i);
                }
                mt.GetField("FlightTable", Any).SetValue(main, table);
                break;
            }
            case "fwp":
            {
                var p = v.Split(',').Select(s => int.Parse(s, CultureInfo.InvariantCulture)).ToArray();
                var table = (Array)mt.GetField("FlightTable", Any).GetValue(main);
                int sel = (int)mt.GetField("SelFlightNr", Any).GetValue(main);
                var f = table.GetValue(sel);
                var wps = (Array)flightType.GetField("waypoints", Any).GetValue(f);
                var w = wps.GetValue(p[0]);
                wpType.GetField("GridX", Any).SetValue(w, (short)p[1]);
                wpType.GetField("GridY", Any).SetValue(w, (short)p[2]);
                wpType.GetField("GridZ", Any).SetValue(w, (short)p[3]);
                wps.SetValue(w, p[0]);
                break;
            }
            case "coord":
            {
                var cd = Coords[v];
                mt.GetField("FALCON_ORIGIN_LAT", Any).SetValue(main, cd.Lat);
                mt.GetField("FALCON_ORIGIN_LONG", Any).SetValue(main, cd.Lon);
                mt.GetField("CampW", Any).SetValue(main, cd.CampW);
                mt.GetField("CampH", Any).SetValue(main, cd.CampH);
                mt.GetField("g_bEnableNewTerrain", Any).SetValue(main, cd.NewTerrain);
                mt.GetField("TransverseMercatorMeta", Any).SetValue(main, Meta(cd));
                Call(t, c, "SetCoordData");
                break;
            }
            case "kto":
            {
                // a real theater's new terrain, as fclsMain loads it (InitNewTerrain over <dir>NewTerrainTheater.txt and
                // its HeightMap.raw), then SetCoordData on the page: lat/lon labels and the terrain under the target as WDP has them
                var dir = v.EndsWith("\\") ? v : v + "\\";
                mt.GetField("terraindir", Any).SetValue(main, dir);
                mt.GetField("HeightmapFile", Any).SetValue(main, null);
                mt.GetField("TransverseMercatorMeta", Any).SetValue(main, Activator.CreateInstance(mt.GetField("TransverseMercatorMeta", Any).FieldType));
                mt.GetMethod("InitNewTerrain", Any).Invoke(main, null);
                mt.GetField("CampW", Any).SetValue(main, 3358699.5);
                mt.GetField("CampH", Any).SetValue(main, 3358699.5);
                mt.GetField("FALCON_ORIGIN_LAT", Any).SetValue(main, 0.0);
                mt.GetField("FALCON_ORIGIN_LONG", Any).SetValue(main, 0.0);
                mt.GetField("g_bEnableNewTerrain", Any).SetValue(main, true);
                mt.GetField("terrainLoaded", Any).SetValue(main, true);
                mt.GetField("NewTerrainElvLoaded", Any).SetValue(main, true);
                Call(t, c, "SetCoordData");
                break;
            }
            case "getcoords": Call(t, c, "Get_Coords"); break;
            case "camptecall": Call(t, c, "CampTE"); break;
            case "stpt": Call(t, c, "STPTChange"); break;
            case "flow": Call(t, c, "ProgramFlow", v == "1"); break;
            default: throw new Exception("unknown op " + op);
        }
    }

    internal static object Meta(Coord cd)
    {
        object tm = Activator.CreateInstance(tmType);
        void S(string n, object val) { var f = tmType.GetField(n); f.SetValue(tm, Convert.ChangeType(val, f.FieldType, CultureInfo.InvariantCulture)); }
        S("Meridian", cd.Meridian); S("offsetX", cd.OffsetX); S("offsetY", cd.OffsetY); S("theaterSizeInMeters", cd.Size);
        S("HEIGHTMAP_SIZE", cd.HeightmapSize); S("METER_RES", cd.MeterRes); S("FT_TO_GRID", cd.FtToGrid); S("GRID_TO_FT", cd.GridToFt); S("GRID_OFFSET", cd.GridOffset);
        return tm;
    }

    private static object MakeObj(Type t)
    {
        try { return Activator.CreateInstance(t); }
        catch (MissingMethodException) { return System.Runtime.Serialization.FormatterServices.GetUninitializedObject(t); }
    }

    private static void SetStpt(string table, string v)
    {
        var p = v.Split(',');
        int i = int.Parse(p[0], CultureInfo.InvariantCulture);
        var arr = (Array)main.GetType().GetField(table, Any).GetValue(main);
        var e = arr.GetValue(i);
        var et = e.GetType();
        et.GetField("FalconY").SetValue(e, float.Parse(p[1], CultureInfo.InvariantCulture));
        et.GetField("FalconX").SetValue(e, float.Parse(p[2], CultureInfo.InvariantCulture));
        et.GetField("FalconZ").SetValue(e, float.Parse(p[3], CultureInfo.InvariantCulture));
        arr.SetValue(e, i);
    }

    private static void Slide(Control c, string name, string v)
    {
        var tb = (TrackBar)Control(type, c, name);
        int n = int.Parse(v, CultureInfo.InvariantCulture);
        Guard(() => tb.Value = Math.Max(tb.Minimum, Math.Min(tb.Maximum, n)));
        Call(type, c, name + "_Scroll", null, EventArgs.Empty);
    }

    // ------------------------------------------------------------------------------------------------ what it shows

    private static List<string> Snapshot(Control c)
    {
        var t = type;
        var row = new List<string>();
        foreach (var l in Labels) row.Add(Clean(((Label)Control(t, c, l)).Text));
        row.Add(((Label)Control(t, c, "lblTargetHUDval")).BackColor.Name);
        foreach (var s in Shown) row.Add(StateVisible((Control)Control(t, c, s)) ? "shown" : "hidden");
        foreach (var s in Sliders) { var tb = (TrackBar)Control(t, c, s); row.Add(I(tb.Value)); row.Add(I(tb.Minimum)); row.Add(I(tb.Maximum)); }
        row.Add(((NumericUpDown)Control(t, c, "numWaypoint")).Value.ToString(CultureInfo.InvariantCulture));
        var img = ((PictureBox)Control(t, c, "picProfile")).BackgroundImage;
        row.Add(img == null ? "" : (pictureByWidth.TryGetValue(img.Width, out var pn) ? pn : "?" + img.Width));
        row.Add(Clean(((Button)Control(t, c, "btnPPTnr")).Text));
        foreach (var b in new[] { "btnCamp", "btnTE" })
        {
            var btn = (Button)Control(t, c, b);
            row.Add(btn.Enabled ? "1" : "0"); row.Add(btn.BackColor.Name);
        }
        var nav = main.GetType().GetField("HADBNavOffsets", Any).GetValue(main);
        row.Add(Convert.ToString(nav.GetType().GetField("Modesel").GetValue(nav), CultureInfo.InvariantCulture));
        foreach (var o in Offsets)
        {
            var off = nav.GetType().GetField(o).GetValue(nav);
            foreach (var p in OffsetParts)
            {
                var v = off.GetType().GetField(p).GetValue(off);
                row.Add(v is float f ? R(f) : Convert.ToString(v, CultureInfo.InvariantCulture));
            }
        }
        foreach (var s in StateStrings) { var v = (string)t.GetField(s, Any).GetValue(c); row.Add(v == null ? "<null>" : Clean(v)); }
        foreach (var s in StateBools) row.Add((bool)t.GetField(s, Any).GetValue(c) ? "1" : "0");
        foreach (var s in StateInts) row.Add(I((int)t.GetField(s, Any).GetValue(c)));

        // what fclsMain writes to Setup.ini's [HADB] on the way out (fclsMain.cs, the INIWrite block)
        var cts = conversions.GetMethods().Where(m => m.Name == "ToString").ToArray();
        string VbStr(object o) => (string)cts.First(m => m.GetParameters().Length == 1 && m.GetParameters()[0].ParameterType == o.GetType()).Invoke(null, new[] { o });
        int Fi(string n) => (int)t.GetField(n, Any).GetValue(c);
        row.Add(VbStr((bool)t.GetField("blnBomb", Any).GetValue(c)));
        row.Add(VbStr(Fi("intDiveAngleDeg")));
        row.Add(VbStr(Fi("intCAS")));
        row.Add(VbStr(Fi("intReleaseHeight")));
        row.Add(VbStr(Fi("intTrackingTime")));
        row.Add(VbStr(Fi("intIngressCAS")));
        row.Add(VbStr(Fi("intPullingGs")));
        { var s = (string)t.GetField("strTurnDirection", Any).GetValue(c); row.Add(s ?? "<null>"); }
        row.Add(VbStr(Fi("intVRPtoVRPPUPnm")));
        row.Add(VbStr(Fi("intAngleOff")));
        row.Add(VbStr(Fi("intAttackHeadingDeg")));
        { var s = (string)t.GetField("strHighLow", Any).GetValue(c); row.Add(s ?? "<null>"); }
        row.Add(VbStr(((NumericUpDown)Control(t, c, "numWaypoint")).Value));
        row.Add(I(errors));
        return row;
    }

    // ------------------------------------------------------------------------------------------------ the cases

    private static string F(float v) => v.ToString("R", CultureInfo.InvariantCulture);

    private static readonly string[] SliderOps = { "dive", "spd", "rel", "track", "ingr", "g", "turn", "vrp", "aoff", "hdg" };

    private static string RandomSlider(Random r, string k)
    {
        switch (k)
        {
            case "dive": return "dive=" + r.Next(10, 46);
            case "spd": return "spd=" + r.Next(30, 56);
            case "rel": return "rel=" + r.Next(1, 151);
            case "track": return "track=" + r.Next(1, 11);
            case "ingr": return "ingr=" + r.Next(30, 56);
            case "g": return "g=" + r.Next(2, 7);
            case "turn": return "turn=" + r.Next(0, 2);
            case "vrp": return "vrp=" + r.Next(1, 6);
            case "aoff": return "aoff=" + r.Next(0, 91);
            case "hdg": return "hdg=" + r.Next(0, 361);
        }
        throw new Exception(k);
    }

    /** Every input a pilot sets, explicitly, in the page's own order: the bomb, then the sliders top to bottom. */
    private static void AllInputs(List<string> ops, Random r)
    {
        ops.Add(r.Next(2) == 0 ? "click=pnlBomb_Up" : "click=pnlBomb_Down");
        foreach (var k in SliderOps) ops.Add(RandomSlider(r, k));
    }

    private static string RandomIni(Random r)
    {
        // what fclsMain writes on exit (and so what the app keeps): valid numbers in the page's own units
        var dive = r.Next(10, 46);
        return "ini=Bomb:" + (r.Next(2) == 0 ? "True" : "False") + "|DiveAngle:" + dive + "|CAS:" + (r.Next(30, 56) * 10) +
               "|ReleaseHeight:" + (r.Next(15, 151) * 100) + "|TrackingTime:" + r.Next(1, 11) + "|IngressCAS:" + (r.Next(30, 56) * 10) +
               "|PullingGs:" + r.Next(2, 7) + "|Turn:" + (r.Next(2) == 0 ? "Left" : "Right") + "|VRPtoPUP:" + r.Next(1, 6) +
               "|AngleOff:" + r.Next(0, 91) + "|AttackHdg:" + r.Next(0, 361) + "|HighLow:Low|Waypoint:" + r.Next(3, 26);
    }

    private static void Grid(List<List<string>> cases, int n)
    {
        var r = new Random(4101);
        for (int i = 0; i < n; i++)
        {
            var ops = new List<string> { r.Next(4) == 0 ? RandomIni(r) : "ini=", "load" };
            AllInputs(ops, r);
            // then a few more moves, in any order, as a pilot does
            int more = r.Next(0, 6);
            for (int j = 0; j < more; j++) ops.Add(RandomOp(r));
            cases.Add(ops);
        }
    }

    private static string RandomOp(Random r)
    {
        int k = r.Next(20);
        if (k < 10) return RandomSlider(r, SliderOps[r.Next(SliderOps.Length)]);
        switch (k)
        {
            case 10: return "click=" + new[] { "lblN", "lblE", "lblS", "lblW", "lblN360" }[r.Next(5)];
            case 11: return "click=" + new[] { "lbl0", "lbl30", "lbl60", "lbl90" }[r.Next(4)];
            case 12: return r.Next(2) == 0 ? "click=pnlBomb_Up" : "click=pnlBomb_Down";
            case 13: return "sel=" + new[] { "pnlSelections_Up", "pnlSelections_Middle", "pnlSelections_Down" }[r.Next(3)] + ":" + (r.Next(3) == 0 ? "R" : "L");
            case 14: return r.Next(2) == 0 ? "click=pnlExtraInfo_Up" : "click=pnlExtraInfo_Down";
            case 15: return "click=btnPPTnr";
            case 16: return "zoom=" + r.Next(0, 101);
            case 17: return "wp=" + r.Next(3, 26);
            case 18: return "dive=" + (10 + 5 * r.Next(8));
            default: return "rel=" + new[] { 0, 1, 15, 20, 30, 40, 100, 150, 999 }[r.Next(9)];
        }
    }

    private static void Extremes(List<List<string>> cases)
    {
        var r = new Random(4102);
        for (int m = 0; m < 1024; m++)
        {
            int B(int bit) => (m >> bit) & 1;
            var ops = new List<string> { "ini=", "load",
                B(0) == 0 ? "click=pnlBomb_Up" : "click=pnlBomb_Down",
                "dive=" + (B(1) == 0 ? 10 : 45), "spd=" + (B(2) == 0 ? 30 : 55), "rel=" + (B(3) == 0 ? 0 : 999),
                "track=" + (B(4) == 0 ? 1 : 10), "ingr=" + (B(5) == 0 ? 30 : 55), "g=" + (B(6) == 0 ? 2 : 6),
                "turn=" + B(7), "vrp=" + r.Next(1, 6), "aoff=" + (B(8) == 0 ? 0 : 90), "hdg=" + (B(9) == 0 ? 0 : 360) };
            cases.Add(ops);
        }
    }

    private static void Sweeps(List<List<string>> cases)
    {
        var r = new Random(4103);
        // every dive angle, with the release height at the bottom, the top and in the middle of its range
        for (int d = 10; d <= 45; d++)
            foreach (var rel in new[] { 0, 60, 999 })
            {
                var ops = new List<string> { "ini=", "load" };
                AllInputs(ops, r);
                ops.Add("rel=" + rel); ops.Add("dive=" + d);
                cases.Add(ops);
            }
        // every angle off, both turns
        for (int a = 0; a <= 90; a++)
            for (int turn = 0; turn < 2; turn++)
            {
                var ops = new List<string> { "ini=", "load" };
                AllInputs(ops, r);
                ops.Add("turn=" + turn); ops.Add("aoff=" + a);
                cases.Add(ops);
            }
        // headings round the compass
        for (int h = 0; h <= 360; h += 3)
        {
            var ops = new List<string> { "ini=", "load" };
            AllInputs(ops, r);
            ops.Add("hdg=" + h);
            cases.Add(ops);
        }
        // the page before anything is set: no Setup.ini at all, and the empty section
        cases.Add(new List<string> { "noini", "load" });
        cases.Add(new List<string> { "ini=", "load" });
        cases.Add(new List<string> { "noini", "load", "dive=30" });
        cases.Add(new List<string> { "noini", "load", "turn=0", "turn=1" });
        cases.Add(new List<string> { "noini", "load", "sel=pnlSelections_Up:L" });
    }

    /** Targets from the tables, as the app fills them: target at steerpoint wp, IP at wp - 1. */
    private static void Targets(List<List<string>> cases, int n)
    {
        var r = new Random(4104);
        string[] presets = { "", "old1", "old2", "new1", "new2", "new3" };
        for (int i = 0; i < n; i++)
        {
            var ops = new List<string> { "ini=", "load" };
            var coord = presets[r.Next(presets.Length)];
            if (coord != "") ops.Add("coord=" + coord);
            bool te = r.Next(5) == 0;
            int wp = r.Next(3, 26);
            float tn = (float)(r.NextDouble() * 3000000), tE = (float)(r.NextDouble() * 3000000), tz = (float)(r.Next(4) == 0 ? 0 : r.NextDouble() * 6000);
            if (r.Next(10) == 0) { tn = r.Next(0, 2000000); tE = r.Next(0, 2000000); tz = r.Next(0, 5000) + 0.5f; }
            int ipMode = r.Next(4);   // 0: no IP, else an IP
            string tbl = te ? "te" : "camp";
            ops.Add(tbl + "=" + (wp - 1) + "," + F(tn) + "," + F(tE) + "," + F(tz));
            if (ipMode != 0)
            {
                float ipn = (float)(tn + (r.NextDouble() - 0.5) * 200000), ipe = (float)(tE + (r.NextDouble() - 0.5) * 200000);
                if (ipMode == 3 && r.Next(2) == 0) ipe = 0;   // an IP with no east
                ops.Add(tbl + "=" + (wp - 2) + "," + F(ipn) + "," + F(ipe) + "," + F((float)(r.NextDouble() * 5000)));
            }
            // other steerpoints, so a waypoint change finds something (or nothing)
            int others = r.Next(0, 4);
            for (int j = 0; j < others; j++)
                ops.Add(tbl + "=" + r.Next(1, 25) + "," + F((float)(r.NextDouble() * 3000000)) + "," + F((float)(r.NextDouble() * 3000000)) + "," + F((float)(r.NextDouble() * 4000)));
            ops.Add("prec=" + (r.Next(6) == 0 ? "0" : "1"));
            ops.Add("src=" + new[] { "Camp", "Camp", "Camp", "TE", "Both", "None", "null" }[r.Next(7)]);
            if (r.Next(3) == 0) ops.Add("campte=" + r.Next(2));
            ops.Add("camptecall");
            ops.Add("wp=" + wp);
            if (r.Next(2) == 0) AllInputs(ops, r);
            int more = r.Next(0, 8);
            for (int j = 0; j < more; j++)
            {
                int k = r.Next(10);
                if (k == 0) ops.Add("click=" + (r.Next(2) == 0 ? "btnCamp" : "btnTE"));
                else if (k == 1) ops.Add("wp=" + r.Next(3, 26));
                else if (k == 2) ops.Add("getcoords");
                else if (k == 3) ops.Add("stpt");
                else if (k == 4 && r.Next(3) == 0) ops.Add("coord=" + presets[1 + r.Next(presets.Length - 1)]);
                else if (k == 5) ops.Add("prec=" + r.Next(2));
                else ops.Add(RandomOp(r));
            }
            // a campaign flight table, sometimes (FlightNR set): read when the tables give no target
            if (r.Next(8) == 0)
            {
                ops.Add("flight=1,0,26");
                for (int j = 0; j < 26; j++) if (r.Next(2) == 0) ops.Add("fwp=" + j + "," + r.Next(0, 1000) + "," + r.Next(0, 1000) + "," + r.Next(0, 3000));
                ops.Add("prec=0");
                ops.Add("getcoords");
            }
            cases.Add(ops);
        }
    }

    private static void Inis(List<List<string>> cases)
    {
        var r = new Random(4105);
        for (int i = 0; i < 60; i++)
        {
            var ops = new List<string> { RandomIni(r), "load" };
            int more = r.Next(0, 5);
            for (int j = 0; j < more; j++) ops.Add(RandomOp(r));
            cases.Add(ops);
        }
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    private static string I(int v) => v.ToString(CultureInfo.InvariantCulture);
    private static string R(float v) => v.ToString("R", CultureInfo.InvariantCulture);
    private static string Clean(string s) => (s ?? "").Replace('\t', ' ').Replace('\n', ' ').Replace('\r', ' ');

    /** The control's own Visible flag (STATE_VISIBLE), not the parent chain. */
    private static bool StateVisible(Control ctl)
    {
        if (ctl.IsDisposed) return false;
        var m = typeof(Control).GetMethod("GetState", BindingFlags.NonPublic | BindingFlags.Instance, null, new[] { typeof(int) }, null);
        return (bool)m.Invoke(ctl, new object[] { 0x2 });
    }

    private static void Guard(Action a)
    {
        try { a(); }
        catch (Exception) { errors++; }
    }

    private static void Set(Type t, object o, string field, object value)
    {
        var f = t.GetField(field, Any);
        if (f == null) throw new Exception("no field " + field);
        f.SetValue(o, value);
    }

    private static void Call(Type t, object o, string method, params object[] args)
    {
        var m = t.GetMethod(method, Any);
        if (m == null) throw new Exception("no method " + method);
        try { m.Invoke(o, args); }
        catch (TargetInvocationException) { errors++; }
    }

    private static object Control(Type t, object o, string name)
    {
        var p = t.GetProperty(name, Any);
        return p != null ? p.GetValue(o, null) : t.GetField("_" + name, BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(o);
    }
}

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
internal static class HadbVerify
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
        Build(cases);
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
        try { EventProbe(outPath + ".events.txt"); } catch (Exception e) { Console.Error.WriteLine("probe: " + e); }
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

    // ------------------------------------------------------------------------------------------------ the cases (verifier)

    private static string F(float v) => v.ToString("R", CultureInfo.InvariantCulture);

    private static readonly string[] SliderOps = { "dive", "spd", "rel", "track", "ingr", "g", "turn", "vrp", "aoff", "hdg" };

    private static int[] Edge(string k)
    {
        switch (k)
        {
            case "dive": return new[] { 10, 11, 14, 15, 20, 24, 25, 26, 30, 35, 40, 44, 45 };
            case "spd": return new[] { 30, 31, 54, 55 };
            case "rel": return new[] { 0, 1, 14, 15, 16, 19, 20, 29, 30, 39, 40, 41, 99, 100, 101, 149, 150, 151 };
            case "track": return new[] { 1, 2, 9, 10 };
            case "ingr": return new[] { 30, 31, 54, 55 };
            case "g": return new[] { 2, 3, 5, 6 };
            case "turn": return new[] { 0, 1 };
            case "vrp": return new[] { 1, 2, 4, 5 };
            case "aoff": return new[] { 0, 1, 29, 30, 45, 59, 60, 89, 90 };
            case "hdg": return new[] { 0, 1, 90, 179, 180, 181, 269, 270, 271, 359, 360 };
        }
        throw new Exception(k);
    }

    private static string EdgeOp(Random r, string k) { var e = Edge(k); return k + "=" + e[r.Next(e.Length)]; }

    private static string AnyClick(Random r)
    {
        var all = new[] { "lblN", "lblE", "lblS", "lblW", "lblN360", "lbl0", "lbl30", "lbl60", "lbl90", "pnlBomb_Up", "pnlBomb_Down", "pnlExtraInfo_Up", "pnlExtraInfo_Down", "btnPPTnr" };
        return "click=" + all[r.Next(all.Length)];
    }

    private static string EdgeIni(Random r)
    {
        // what the app keeps from an earlier session (fclsMain's INIWrite values), at the edges of every range,
        // including a dive angle that is not a multiple of five with a release height from a wider range
        int dive = new[] { 10, 11, 12, 14, 15, 19, 21, 25, 29, 30, 31, 44, 45 }[r.Next(13)];
        int rel = new[] { 1500, 1600, 2000, 2900, 3000, 3900, 4000, 9900, 10000, 10100, 12000, 14900, 15000 }[r.Next(13)];
        return "ini=Bomb:" + (r.Next(2) == 0 ? "True" : "False") + "|DiveAngle:" + dive + "|CAS:" + new[] { 300, 310, 540, 550 }[r.Next(4)] +
               "|ReleaseHeight:" + rel + "|TrackingTime:" + new[] { 1, 10 }[r.Next(2)] + "|IngressCAS:" + new[] { 300, 550 }[r.Next(2)] +
               "|PullingGs:" + new[] { 2, 6 }[r.Next(2)] + "|Turn:" + (r.Next(2) == 0 ? "Left" : "Right") + "|VRPtoPUP:" + new[] { 1, 5 }[r.Next(2)] +
               "|AngleOff:" + new[] { 0, 90, 45 }[r.Next(3)] + "|AttackHdg:" + new[] { 0, 180, 360, 359 }[r.Next(4)] + "|HighLow:Low|Waypoint:" + new[] { 3, 25, 12 }[r.Next(3)];
    }

    private static void Build(List<List<string>> cases)
    {
        var r = new Random(90210);

        // 1. every slider twice in a row: to the same position, then to another, in random order, at the edges
        for (int i = 0; i < 700; i++)
        {
            var ops = new List<string> { r.Next(3) == 0 ? EdgeIni(r) : (r.Next(8) == 0 ? "noini" : "ini="), "load" };
            int n = r.Next(4, 16);
            for (int j = 0; j < n; j++)
            {
                int k = r.Next(10);
                if (k < 6)
                {
                    var op = EdgeOp(r, SliderOps[r.Next(SliderOps.Length)]);
                    ops.Add(op);
                    if (r.Next(2) == 0) ops.Add(op);
                    else if (r.Next(2) == 0) ops.Add(EdgeOp(r, op.Substring(0, op.IndexOf('='))));
                }
                else if (k < 8) ops.Add(AnyClick(r));
                else if (k == 8) ops.Add("sel=" + new[] { "pnlSelections_Up", "pnlSelections_Middle", "pnlSelections_Down" }[r.Next(3)] + ":" + (r.Next(2) == 0 ? "R" : "L"));
                else ops.Add("zoom=" + new[] { 0, 1, 9, 10, 11, 89, 90, 91, 99, 100 }[r.Next(10)]);
            }
            cases.Add(ops);
        }

        // 2. the release-height range under dive angles that are and are not multiples of five, in both orders
        foreach (var d1 in new[] { 10, 25, 30, 45 })
            foreach (var d2 in new[] { 11, 12, 20, 24, 26, 44, 45, 10 })
                foreach (var rel in new[] { 0, 15, 20, 30, 40, 100, 151 })
                {
                    cases.Add(new List<string> { "ini=", "load", "dive=" + d1, "rel=" + rel, "dive=" + d2 });
                    cases.Add(new List<string> { "ini=", "load", "dive=" + d2, "rel=" + rel, "dive=" + d1, "rel=" + rel });
                }

        // 3. all at the maximum / minimum in reversed page order, and then everything again
        for (int m = 0; m < 256; m++)
        {
            int B(int bit) => (m >> bit) & 1;
            var set = new List<string> {
                "hdg=" + (B(0) == 0 ? 360 : 181), "aoff=" + (B(1) == 0 ? 90 : 1), "vrp=" + (B(2) == 0 ? 1 : 5), "turn=" + B(3),
                "g=" + (B(4) == 0 ? 2 : 6), "ingr=" + (B(5) == 0 ? 55 : 30), "track=" + (B(6) == 0 ? 10 : 1), "rel=150", "spd=55",
                "dive=" + (B(7) == 0 ? 45 : 11) };
            var ops = new List<string> { "ini=", "load", B(0) == B(3) ? "click=pnlBomb_Down" : "click=pnlBomb_Up" };
            ops.AddRange(set);
            if (m % 2 == 0) ops.AddRange(set);
            cases.Add(ops);
        }

        // 4. a single change, then the same change again (what a WinForms TB_ENDTRACK adds after real input)
        for (int i = 0; i < 200; i++)
        {
            var ops = new List<string> { "ini=", "load" };
            foreach (var k in SliderOps) ops.Add(EdgeOp(r, k));
            var one = EdgeOp(r, SliderOps[r.Next(SliderOps.Length)]);
            ops.Add(one);
            cases.Add(new List<string>(ops));
            ops.Add(one);
            cases.Add(ops);
        }

        // 5. the mission as the app hands it over: Precision, source Camp, CampTE, then the waypoint box, with edge data
        for (int i = 0; i < 400; i++)
        {
            var ops = new List<string> { r.Next(4) == 0 ? EdgeIni(r) : "ini=", "load" };
            if (r.Next(3) != 0) ops.Add("coord=" + new[] { "old1", "old2", "new1", "new2", "new3" }[r.Next(5)]);
            int wp = new[] { 3, 4, 24, 25, r.Next(3, 26) }[r.Next(5)];
            int pts = r.Next(1, 25);
            for (int j = 0; j < pts; j++)
            {
                int idx = r.Next(0, 25);
                float n = PickCoord(r), e = PickCoord(r), z = PickZ(r);
                ops.Add("camp=" + idx + "," + F(n) + "," + F(e) + "," + F(z));
            }
            ops.Add("camp=" + (wp - 1) + "," + F(PickCoord(r)) + "," + F(PickCoord(r)) + "," + F(PickZ(r)));
            if (r.Next(3) != 0) ops.Add("camp=" + (wp - 2) + "," + F(PickCoord(r)) + "," + F(PickCoord(r)) + "," + F(PickZ(r)));
            ops.Add("prec=1"); ops.Add("src=Camp"); ops.Add("camptecall");
            ops.Add("wp=" + wp);
            int more = r.Next(0, 8);
            for (int j = 0; j < more; j++)
            {
                int k = r.Next(6);
                if (k == 0) ops.Add("wp=" + Math.Max(3, Math.Min(25, new[] { 3, 25, wp, wp + 1, wp - 1 }[r.Next(5)])));
                else if (k == 1) ops.Add(AnyClick(r));
                else if (k == 2) { ops.Add("camp=" + (wp - 1) + "," + F(PickCoord(r)) + "," + F(PickCoord(r)) + "," + F(PickZ(r))); ops.Add("prec=1"); ops.Add("src=Camp"); ops.Add("camptecall"); }
                else if (k == 3) ops.Add("click=" + (r.Next(2) == 0 ? "btnCamp" : "btnTE"));
                else { var op = EdgeOp(r, SliderOps[r.Next(SliderOps.Length)]); ops.Add(op); if (r.Next(2) == 0) ops.Add(op); }
            }
            cases.Add(ops);
        }

        // 6. the mission going away (no steerpoints, no selection): source None, Precision off, CampTE
        for (int i = 0; i < 60; i++)
        {
            var ops = new List<string> { "ini=", "load", "camp=5," + F(PickCoord(r)) + "," + F(PickCoord(r)) + "," + F(PickZ(r)),
                "camp=6," + F(PickCoord(r)) + "," + F(PickCoord(r)) + "," + F(PickZ(r)), "prec=1", "src=Camp", "camptecall", "wp=7" };
            if (r.Next(2) == 0) ops.Add(EdgeOp(r, SliderOps[r.Next(SliderOps.Length)]));
            ops.Add("prec=0"); ops.Add("src=None"); ops.Add("camptecall");
            if (r.Next(2) == 0) ops.Add("wp=" + r.Next(3, 26));
            if (r.Next(2) == 0) ops.Add(EdgeOp(r, SliderOps[r.Next(SliderOps.Length)]));
            cases.Add(ops);
        }
    }

    private static float PickCoord(Random r)
    {
        switch (r.Next(9))
        {
            case 0: return 0f;
            case 1: return 0.5f;
            case 2: return 3358699.5f;
            case 3: return 1f;
            case 4: return (float)(r.Next(0, 3358700) + 0.5);
            default: return (float)(r.NextDouble() * 3358699.5);
        }
    }

    private static float PickZ(Random r)
    {
        switch (r.Next(8))
        {
            case 0: return 0f;
            case 1: return 0.5f;
            case 2: return 1.5f;
            case 3: return 2.5f;
            case 4: return -2.5f;
            case 5: return -(float)(r.NextDouble() * 5000);
            case 6: return r.Next(0, 9000) + 0.5f;
            default: return (float)(r.NextDouble() * 9000);
        }
    }

    // ------------------------------------------------------------------------------------------------ real input
    //
    // What a pilot's own input does to a WinForms TrackBar: the native control reports TB_LINEDOWN / TB_PAGEDOWN
    // and then TB_ENDTRACK when the key or button comes up, and WinForms raises Scroll for each. Sent here as real
    // window messages to the real trackbar, counting Scroll and noting the labels, beside one and two direct Scroll
    // calls at the same final position. Written to <out>.events.txt, not to the TSV.

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr h, int msg, IntPtr w, IntPtr l);

    private static void EventProbe(string path)
    {
        var sb = new StringBuilder();
        string[] watch = { "lblTASval", "lblWheelRad", "lblWheelElv", "lblVRPbrg", "lblVRPrng", "lblVRPelv", "lblVRPPUPbrg", "lblVRPPUPrng", "lblAdvIngAltVal", "lblSlantRangeFeet" };
        foreach (var slider in new[] { "trbDiveAngle", "trbIngrSpd", "trbReleaseHeight", "trbTrackingTime", "trbReleaseSpd" })
            foreach (var mode in new[] { "key", "mouse", "scroll1", "scroll2" })
            {
                errors = 0;
                ResetMain();
                var c = Make();
                Apply(c, "ini=");
                Apply(c, "load");
                foreach (var op in new[] { "dive=20", "spd=45", "rel=60", "track=5", "ingr=45", "g=4", "turn=1", "vrp=3", "aoff=30", "hdg=90" }) Apply(c, op);
                var tb = (TrackBar)Control(type, c, slider);
                int scrolls = 0;
                tb.Scroll += (s, e) => scrolls++;
                int before = tb.Value;
                const int WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, VK_RIGHT = 0x27, WM_LBUTTONDOWN = 0x201, WM_LBUTTONUP = 0x202;
                if (mode == "key")
                {
                    SendMessage(tb.Handle, WM_KEYDOWN, (IntPtr)VK_RIGHT, (IntPtr)1);
                    SendMessage(tb.Handle, WM_KEYUP, (IntPtr)VK_RIGHT, unchecked((IntPtr)(int)0xC0000001));
                }
                else if (mode == "mouse")
                {
                    int x = tb.Width - 12, y = tb.Height / 2 - 4;
                    IntPtr lp = (IntPtr)((y << 16) | (x & 0xFFFF));
                    SendMessage(tb.Handle, WM_LBUTTONDOWN, (IntPtr)1, lp);
                    SendMessage(tb.Handle, WM_LBUTTONUP, IntPtr.Zero, lp);
                }
                else
                {
                    tb.Value = Math.Min(tb.Maximum, before + 1);
                    Call(type, c, slider + "_Scroll", null, EventArgs.Empty);
                    if (mode == "scroll2") Call(type, c, slider + "_Scroll", null, EventArgs.Empty);
                    scrolls = mode == "scroll2" ? 2 : 1;
                }
                Application.DoEvents();
                var vals = watch.Select(l => l + "=" + ((Label)Control(type, c, l)).Text);
                sb.AppendLine(slider + "\t" + mode + "\t" + before + "->" + tb.Value + "\tScroll x" + scrolls + "\t" + string.Join("  ", vals));
                Unmake(c);
            }
        File.WriteAllText(path, sb.ToString());
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

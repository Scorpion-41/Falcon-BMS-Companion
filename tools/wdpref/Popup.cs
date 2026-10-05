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

// Drives the real Pop-up page (cntPopUp) and writes down what it shows.
//
// Every case is a SEQUENCE of what a user and the rest of the program do to the page, written as ops
// ("dive=25;click=pnlRefDown;wp=7;..."), applied to a freshly made control. The Kotlin test replays the very same
// ops against the port and demands the same text on every label, so the order of events is part of what is checked:
// the same slider moved several times, a waypoint changed after a target was set, a target replaced or its IP
// removed, switches flipped back and forth, the page hidden and shown, the zoom moved.
//
// The page sits on a form of its own, off screen, as it sits on fclsMain: the "load" op shows the form, which is
// when the page gets its window, its Load event runs (once) and its VisibleChanged runs the flow. An exception inside
// a window message is what WDP shows the user in .NET's error dialog before carrying on; here it is counted.
//
// Nothing depends on the machine: the control's ProgramPath is pointed at a scratch folder this harness fills —
// a Setup.ini written for each case (so Setup() reads exactly the [PopUp] keys the case names, including missing,
// garbled and out-of-range ones), and the sixteen profile pictures, each a different width, so the picture
// ProfileSwitch() loads can be told from the Image it leaves in picProfile. fclsMain's state that the page reads —
// the campaign and TE steerpoint tables, the flight table, the DataCard's Precision flag, the terrain (a synthetic
// Heightmap.raw in the scratch folder), the theater's coordinate data (SetCoordData), blnLoaded and a map bitmap
// for Draw() — is reset for every case and set by the case's own ops, by reflection. The DTC save (btnSaveDTC,
// which writes the pilot's cartridge through fclsMain) and the map JPEG save (a file dialog) are never run.
//
// Ops:  ver=0|1 (SetVersion, before load)  mainloaded=0|1  ini=K:V|K:V...  load (show the form)
//       ingr|dive|speed|rel|track|g|turn|hdg|vrp|zoom=<pos> (the slider, clamped, then its Scroll handler)
//       wp=<n> (numWaypoint.Value)  oa2=0|1  click=<control> (its Click handler; a check box toggles first)
//       sel=<pnlSelections_Up|Middle|Down>:L|R  vis=0|1 (the control's Visible)
//       prec=0|1 (cntDataCard.Precision)  src=Camp|TE|Both|None|X|null (strDTC)  campte=0|1 (blnCampTE)
//       camp=i,N,E,Z / te=i,N,E,Z (tblCampSTPT / tblMissionSTPT[i]: FalconY=N, FalconX=E, FalconZ=Z)
//       flight=nr,sel,count  flightnr=nr  fwp=i,gx,gy,gz (FlightTable[sel].waypoints[i])  terrain=0|1|2  coord=<preset>
//       getcoords  camptecall  stpt  flow=0|1  draw=0|1
//       noini (no Setup.ini at all)  wpdec=<decimal> (numWaypoint.Value)  wpup  wpdown (its arrows)
//       wptext=<text> (typed into the box)  mdtc=0|1 (cntDTC.blnMissionDtcLoaded)
//
// With POPUP_HARMONY=<0Harmony.dll> (Lib.Harmony, lib/net48) the harness also records what Draw() draws (the map
// column), answers message boxes (msgbox) and stubs Save to DTC's calls into the DTC page and the main form (save);
// without it those columns are left out, and the cases that would open a message box or write a cartridge too.
//
// Beside <out.tsv> it writes <out.tsv>.geo.tsv (direct samples of Bearing, Distance, NewPos, ScalePointToMap,
// FeetToRad/RadToFeet, clsCoordinates.FeetToCoordsBoth under every coordinate preset, fclsMain's
// ReadNewTerrainElvLoc, and VB's own string conversions) and <out.tsv>.heightmap.raw (the synthetic terrain).
//
//   wdpref page Popup <WeaponDeliveryPlanner.exe> <out.tsv>       (run from WDP's own folder)
//   env POPUP_ROWS=n (an even sample of n cases), POPUP_VERBOSE=1 (each case to stderr), POPUP_TRACE="ops" with
//   POPUP_TRACE_COLS="lblX,sld_trbY,st_field,handle" (one sequence, those values after every op)
internal static class Popup
{
    private const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

    internal static readonly string[] Labels = {
        "lblIngressHeightVal", "lblDiveAngleVal", "lblCasVal", "lblReleaseHeightVal", "lblTrackingTimeVal",
        "lblGVal", "lblTurnVal", "lblAttackHdgVal", "lblVrpToPupVal",
        "lblTASval", "lblBombTime", "lblGsVal", "lblSpeedExpl", "lblClimbAngleVal", "lblAngleOffVal",
        "lblPulldownAltVal", "lblAODval", "lblOffsetAngleVal", "lblPullHeadingVal", "lblTargetHUDval",
        "lblVRPtoPUPdist", "lblDEDvip_1", "lblDEDvip_2", "lblDEDpup_1", "lblDEDpup_2",
        "lblVIPwp", "lblVIPbrg", "lblVIPrng", "lblVIPelv", "lblVIPnm",
        "lblPUPwp", "lblPUPbrg", "lblPUPrng", "lblPUPelv", "lblPUPnm",
        "lblOA1wp", "lblOA1brg", "lblOA1rng", "lblOA1elv", "lblOA1nm",
        "lblOA2wp", "lblOA2brg", "lblOA2rng", "lblOA2elv", "lblOA2nm", "lblZoom",
        "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E", "lblIP_elv", "lblWP", "lblVIP",
    };

    internal static readonly string[] Shown = {
        "pnlRefUp", "pnlRefDown", "pnlDED_Ref_Up", "pnlDED_Ref_Down", "pnlProfile_Up", "pnlProfile_Down",
        "pnlProfile2_Up", "pnlProfile2_Down", "pnlBomb_Up", "pnlBomb_Down", "pnlBlocked_1", "pnlBlocked_3",
        "trbVrpToPup", "lblVrpToPupVal", "lblVIPtoPUPnm",
        "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E", "lblIP_elv",
        "pnlSelections", "pnlProfile", "pnlDEDData", "pnlSelections_Up", "pnlSelections_Middle", "pnlSelections_Down",
    };

    internal static readonly string[] Sliders = {
        "trbIngressHeight", "trbDiveAngle", "trbSpeed", "trbReleaseHeight", "trbTrackingTime", "trbG", "trbTurn",
        "trbHeading", "trbVrpToPup", "trbZoom",
    };

    private static readonly string[] Offsets = { "VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2" };
    private static readonly string[] OffsetParts = { "Stpt", "Bearing", "Range", "Elv" };
    private static readonly string[] DtcStrings = { "strIngrHgt", "strIngrSpd", "strRelHgt", "strRelSpd", "strAttHed", "strDA", "strPullingG", "strTurn", "strTGTHUD" };

    private static readonly string[] StateStrings = { "strWaypoint", "strIPpoint", "strTGT_STPT_deg", "strIP_STPT_deg", "strDTC", "strProfile", "strBomb" };
    private static readonly string[] StateBools = { "blnLoadedFlag", "blnVersion", "blnCampTE", "blnGotTGT", "blnDoVIP", "blnRef", "blnProfile", "blnBomb", "blnTurn" };
    private static readonly string[] StateInts = { "TargetElv", "intZoomFactor", "intIngressAlt" };
    private static readonly string[] StatePoints = { "Map", "AOD", "Apex", "OA1", "OA2" };
    private static readonly string[] StateArrays = { "dblT1_VIP", "dblT1_VIPPUP", "dblT1_VRP", "dblT1_VRPPUP", "dblT2_VIP", "dblT2_VIPPUP", "dblT2_VRP", "dblT2_VRPPUP" };

    private static readonly string[] IniKeys = { "Ref", "Profile", "Bomb", "IngressAlt", "DiveAngle", "CAS", "ReleaseHeight", "TrackingTime", "PullingGs", "Turn", "AttackHdg", "VIPtoPUP", "OA2atAO", "Waypoint" };

    internal static readonly string[] Pictures = {
        "PopUp_VIP_Type1_Right.jpg", "PopUp_VIPOA2_Type1_Right.jpg", "PopUp_VIP_Type2_Right.jpg", "PopUp_VIPOA2_Type2_Right.jpg",
        "PopUp_VIP_Type1_Left.jpg", "PopUp_VIPOA2_Type1_Left.jpg", "PopUp_VIP_Type2_Left.jpg", "PopUp_VIPOA2_Type2_Left.jpg",
        "PopUp_VRP_Type1_Right.jpg", "PopUp_VRPOA2_Type1_Right.jpg", "PopUp_VRP_Type2_Right.jpg", "PopUp_VRPOA2_Type2_Right.jpg",
        "PopUp_VRP_Type1_Left.jpg", "PopUp_VRPOA2_Type1_Left.jpg", "PopUp_VRP_Type2_Left.jpg", "PopUp_VRPOA2_Type2_Left.jpg",
    };

    /** The coordinate presets `coord=` names; the Kotlin test has the same table. */
    internal sealed class Coord
    {
        public double Lat, Lon, CampW = 3358699.5, CampH = 3358699.5;
        public bool NewTerrain;
        public double Meridian, OffsetX, OffsetY;
        public uint Size;
        public float HeightmapSize, MeterRes, FtToGrid, GridToFt, GridOffset;
    }

    internal static readonly Dictionary<string, Coord> Coords = new Dictionary<string, Coord>
    {
        ["old1"] = new Coord { Lat = 34.0, Lon = 124.0 },
        ["old2"] = new Coord { Lat = -53.5, Lon = -62.0, CampW = 2000000.0, CampH = 2500000.0 },
        ["new1"] = new Coord { NewTerrain = true, Meridian = 127.5, OffsetX = -512000.0, OffsetY = 3700000.0, Size = 1024000u, HeightmapSize = 16384f, MeterRes = 62.5f, FtToGrid = 1f / (62.5f * 3.27998f), GridToFt = 62.5f * 3.27998f, GridOffset = 8192f },
        ["new2"] = new Coord { NewTerrain = true, Meridian = 22.0, OffsetX = -400000.0, OffsetY = 4300000.0, Size = 1024000u, HeightmapSize = 1024f, MeterRes = 1000f, FtToGrid = 1f / 3279.98f, GridToFt = 3279.98f, GridOffset = 512f },
        ["new3"] = new Coord { NewTerrain = true, Meridian = -60.0, OffsetX = -500000.0, OffsetY = -6100000.0, Size = 1024000u, HeightmapSize = 4096f, MeterRes = 250f, FtToGrid = 1f / (250f * 3.27998f), GridToFt = 250f * 3.27998f, GridOffset = 2048f },
    };

    private static int errors;
    private static Type type, pointType, campType, teType, flightType, wpType, tmType;
    private static object main, dtc, dataCard;
    private static string progDir, terrainDir;
    private static readonly Dictionary<int, string> pictureByWidth = new Dictionary<int, string>();
    private static Type conversions, versioned;

    public static int Run(string exePath, string outPath)
    {
        // what cntPopUp_Load does first
        Thread.CurrentThread.CurrentCulture = new CultureInfo("en-US");
        Thread.CurrentThread.CurrentCulture.NumberFormat.NumberDecimalSeparator = ".";

        // an exception inside a window message (showing the page runs its Load and its flow) opens .NET's error dialog
        // in WDP and the program carries on when it is dismissed; here it is counted instead, and the message returns
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (sender, e) => errors++;   // what the user would dismiss; the message returns

        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        type = asm.GetType("WeaponDeliveryPlanner.cntPopUp", throwOnError: true);
        pointType = asm.GetType("WeaponDeliveryPlanner.cntPopUp+FalconPoint", throwOnError: true);
        var myProject = asm.GetType("WeaponDeliveryPlanner.My.MyProject", throwOnError: true);
        var forms = myProject.GetProperty("Forms", BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public).GetValue(null, null);
        main = forms.GetType().GetProperty("fclsMain", Any).GetValue(forms, null);
        dtc = Control(main.GetType(), main, "cntDTC");
        dataCard = Control(main.GetType(), main, "cntDataCard");
        var mt = main.GetType();
        campType = mt.GetField("tblCampSTPT", Any).FieldType.GetElementType();
        teType = mt.GetField("tblMissionSTPT", Any).FieldType.GetElementType();
        flightType = mt.GetField("FlightTable", Any).FieldType.GetElementType();
        wpType = flightType.GetField("waypoints", Any).FieldType.GetElementType();
        tmType = mt.GetField("TransverseMercatorMeta", Any).FieldType;
        conversions = Type.GetType("Microsoft.VisualBasic.CompilerServices.Conversions, Microsoft.VisualBasic, Version=10.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a", throwOnError: true);
        versioned = Type.GetType("Microsoft.VisualBasic.CompilerServices.Versioned, Microsoft.VisualBasic, Version=10.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a", throwOnError: true);

        // ---- the scratch "program folder": pictures of sixteen widths, a terrain, and the Setup.ini each case writes
        progDir = Path.GetFullPath(outPath + ".prog");
        Directory.CreateDirectory(Path.Combine(progDir, "Pictures"));
        for (int i = 0; i < Pictures.Length; i++)
        {
            int w = 20 + i;
            pictureByWidth[w] = Pictures[i];
            using (var bmp = new Bitmap(w, 10)) bmp.Save(Path.Combine(progDir, "Pictures", Pictures[i]), ImageFormat.Jpeg);
        }
        terrainDir = Path.Combine(progDir, "terrain") + "\\";
        Directory.CreateDirectory(terrainDir + "NewTerrain\\Heightmaps");
        var hm = new byte[64 * 64 * 2 + 6];      // not a square number of shorts: the reader's own rounding decides
        var hr = new Random(77);
        for (int i = 0; i + 1 < hm.Length; i += 2)
        {
            short h = (short)(hr.Next(100) < 10 ? -hr.Next(1500) : hr.Next(9000));
            hm[i] = (byte)(h & 0xff); hm[i + 1] = (byte)((h >> 8) & 0xff);
        }
        // POPUP_HEIGHTMAP=<file>: a terrain of the caller's own (a mission's steerpoints at their real heights) in place of the random one
        var hmFile = Environment.GetEnvironmentVariable("POPUP_HEIGHTMAP");
        if (!string.IsNullOrEmpty(hmFile)) hm = File.ReadAllBytes(hmFile);
        File.WriteAllBytes(terrainDir + "NewTerrain\\Heightmaps\\Heightmap.raw", hm);
        File.WriteAllBytes(outPath + ".heightmap.raw", hm);

        InstallCapture();

        // POPUP_TRACE="op;op;..." POPUP_TRACE_COLS="lblX,sld_trbY,...": one sequence, the named values after every op
        var trace = Environment.GetEnvironmentVariable("POPUP_TRACE");
        if (!string.IsNullOrEmpty(trace)) { Trace(trace.Split(';').ToList(), (Environment.GetEnvironmentVariable("POPUP_TRACE_COLS") ?? "lblReleaseHeightVal,lblTrackingTimeVal").Split(',')); return 0; }

        // ---- the cases
        var cases = new List<List<string>>();
        // POPUP_CASES=<file>: the caller's own sequences, one a line ("op;op;..."), in place of the generated ones
        var caseFile = Environment.GetEnvironmentVariable("POPUP_CASES");
        if (!string.IsNullOrEmpty(caseFile))
        {
            foreach (var line in File.ReadAllLines(caseFile)) if (line.Trim().Length > 0 && !line.StartsWith("#")) cases.Add(line.Trim().Split(';').ToList());
        }
        else {
        Grid(cases);
        Sweep(cases);
        Awkward(cases);
        IniFuzz(cases);
        Sources(cases);
        Versions(cases);
        Sequences(cases);
        // the independent verifier's cases (its harness, PopupVerify.cs, folded in), then the page's life and the waypoint box
        Handcrafted(cases);
        EdgeGeometry(cases);
        HardIni(cases);
        FlightGrid(cases);
        LongChains(cases, 700);
        Lifecycle(cases);
        Waypoints(cases);
        }
        // with no Setup.ini WDP opens a message box; without the capture (which answers it) that would wait forever
        // and Save to DTC would really write the cartridge through the main form: only with the capture, which stubs it
        if (!captureOn) cases.RemoveAll(o => o.Contains("noini") || o.Contains("click=btnSaveDTC"));
        int limit = int.TryParse(Environment.GetEnvironmentVariable("POPUP_ROWS"), out var lim) ? lim : int.MaxValue;
        if (cases.Count > limit) { var all = cases; cases = Enumerable.Range(0, limit).Select(i => all[(int)((long)i * all.Count / limit)]).ToList(); }
        // Allied Force has no Campaign / TE buttons (SetVersion disposes them): nobody can click them
        foreach (var ops in cases) if (ops.Contains("ver=0")) ops.RemoveAll(o => o == "click=btnCamp" || o == "click=btnTE");

        var head = new List<string> { "case", "ops" };
        head.AddRange(Labels);
        head.Add("lblTargetHUDval.back"); head.Add("lblVIP.fore");
        head.AddRange(Shown.Select(s => "vis_" + s));
        foreach (var s in Sliders) { head.Add("sld_" + s); head.Add("sld_" + s + ".min"); head.Add("sld_" + s + ".max"); }
        head.AddRange(new[] { "numWaypoint", "chbOA2", "chbShowPPT", "chbShowPPTNr", "picProfile",
            "btnCamp.enabled", "btnCamp.back", "btnCamp.disposed", "btnTE.enabled", "btnTE.back", "btnTE.disposed", "btnSaveDTC.text", "btnSaveDTC.size" });
        head.Add("nav_Modesel");
        foreach (var o in Offsets) foreach (var p in OffsetParts) head.Add("nav_" + o + "_" + p);
        head.AddRange(DtcStrings.Select(s => "dtc_" + s));
        head.AddRange(StateStrings.Select(s => "st_" + s));
        head.AddRange(StateBools.Select(s => "st_" + s));
        head.AddRange(StateInts.Select(s => "st_" + s));
        foreach (var s in new[] { "decOrig_TGT", "decOrig_IP" }) { head.Add("st_" + s + ".X"); head.Add("st_" + s + ".Y"); head.Add("st_" + s + ".Z"); }
        foreach (var s in StatePoints) { head.Add("st_" + s + ".X"); head.Add("st_" + s + ".Y"); }
        foreach (var s in StateArrays) { head.Add("st_" + s + "[0]"); head.Add("st_" + s + "[1]"); }
        head.AddRange(IniKeys.Select(s => "ini_" + s));
        for (int r = 1; r <= 26; r++) for (int k = 0; k < 2; k++) head.Add("prt_" + r + "_" + k);
        head.Add("errors");
        head.Add("msgbox");
        head.Add("numWaypoint.text");
        if (captureOn) { head.Add("map"); head.Add("save"); }

        using (var w = new StreamWriter(outPath, false, new UTF8Encoding(false)))
        {
            w.WriteLine(string.Join("\t", head));
            for (int i = 0; i < cases.Count; i++)
            {
                if (Environment.GetEnvironmentVariable("POPUP_VERBOSE") != null) Console.Error.WriteLine("case " + i + ": " + string.Join(";", cases[i]));
                var row = RunCase(cases[i]);
                w.WriteLine(I(i) + "\t" + string.Join(";", cases[i]) + "\t" + string.Join("\t", row));
                if ((i + 1) % 250 == 0) { Console.WriteLine((i + 1) + " of " + cases.Count + " cases"); w.Flush(); }
            }
        }
        Console.WriteLine("wrote " + cases.Count + " Pop-up cases to " + outPath);

        Geometry(outPath + ".geo.tsv");
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ one case

    private static List<string> RunCase(List<string> ops)
    {
        errors = 0;
        msgBoxes = 0; lastPicture = ""; drawLog.Clear(); saveLog.Clear();
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

    /**
     * A fresh page on a form of its own, as it sits on fclsMain: the form is shown (off screen) by the "load" op,
     * which is when the page gets its window and its Load event runs — once, as on the real form — and every window
     * message after that (showing and hiding the page runs its flow) happens inside the op that caused it.
     */
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

    private static void Trace(List<string> ops, string[] cols)
    {
        errors = 0;
        ResetMain();
        var c = Make();
        foreach (var op in ops)
        {
            Apply(c, op);
            var sb = new StringBuilder(op.Length > 40 ? op.Substring(0, 40) : op).Append("  =>");
            foreach (var col in cols)
            {
                string v;
                if (col == "handle") v = c.IsHandleCreated + "/" + ((Control)Control(type, c, "trbTrackingTime")).IsHandleCreated;
                else if (col.StartsWith("sld_")) v = I(((TrackBar)Control(type, c, col.Substring(4))).Value);
                else if (col.StartsWith("st_")) v = Convert.ToString(type.GetField(col.Substring(3), Any).GetValue(c), CultureInfo.InvariantCulture);
                else v = ((Label)Control(type, c, col)).Text;
                sb.Append(' ').Append(col).Append('=').Append(v);
            }
            Console.WriteLine(sb.Append(" errors=").Append(errors));
        }
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
        mt.GetField("terrainLoaded", Any).SetValue(main, false);
        mt.GetField("NewTerrainElvLoaded", Any).SetValue(main, false);
        mt.GetField("terraindir", Any).SetValue(main, terrainDir);
        mt.GetField("CampW", Any).SetValue(main, 3358699.5);
        mt.GetField("CampH", Any).SetValue(main, 3358699.5);
        mt.GetField("FALCON_ORIGIN_LAT", Any).SetValue(main, 0.0);
        mt.GetField("FALCON_ORIGIN_LONG", Any).SetValue(main, 0.0);
        mt.GetField("g_bEnableNewTerrain", Any).SetValue(main, false);
        mt.GetField("TransverseMercatorMeta", Any).SetValue(main, Activator.CreateInstance(tmType));
        mt.GetField("blnLoaded", Any).SetValue(main, true);
        mt.GetField("FeetPerPixel", Any).SetValue(main, 1639.989990234375);
        if (mt.GetField("bitThrMap", Any).GetValue(main) == null) mt.GetField("bitThrMap", Any).SetValue(main, new Bitmap(512, 512));
        var navF = mt.GetField("PopUpNavOffsets", Any);
        navF.SetValue(main, Activator.CreateInstance(navF.FieldType));
        foreach (var s in DtcStrings) dtc.GetType().GetField(s, Any).SetValue(dtc, null);
        dtc.GetType().GetField("blnMissionDtcLoaded", Any).SetValue(dtc, false);
        dtc.GetType().GetField("strProfile", Any).SetValue(dtc, null);
        File.WriteAllText(Path.Combine(progDir, "Setup.ini"), "[PopUp]\r\n");
    }

    private static void Apply(Control c, string op)
    {
        var t = type;
        int eq = op.IndexOf('=');
        string k = eq < 0 ? op : op.Substring(0, eq), v = eq < 0 ? "" : op.Substring(eq + 1);
        var mt = main.GetType();
        switch (k)
        {
            case "ver":
                Set(t, c, "blnVersion", v == "1");
                Call(t, c, "SetVersion", v == "1");
                break;
            case "mainloaded": mt.GetField("blnLoaded", Any).SetValue(main, v == "1"); break;
            case "ini":
            {
                var sb = new StringBuilder("[PopUp]\r\n");
                if (v.Length > 0) foreach (var kv in v.Split('|')) { int i = kv.IndexOf(':'); sb.Append(kv.Substring(0, i)).Append('=').Append(kv.Substring(i + 1)).Append("\r\n"); }
                File.WriteAllText(Path.Combine(progDir, "Setup.ini"), sb.ToString());
                break;
            }
            case "load":
                // as on the real form: the Load event is the control getting its window, which happens once. (Calling
                // cntPopUp_Load by hand leaves the control without one, and the first thing that later creates it — a
                // Select() in Selections — runs the Load a second time, Setup and all.) Off screen, never shown to anyone.
                Guard(() => ((Form)c.Parent).Show());
                break;
            case "ingr": Slide(c, "trbIngressHeight", v); break;
            case "dive": Slide(c, "trbDiveAngle", v); break;
            case "speed": Slide(c, "trbSpeed", v); break;
            case "rel": Slide(c, "trbReleaseHeight", v); break;
            case "track": Slide(c, "trbTrackingTime", v); break;
            case "g": Slide(c, "trbG", v); break;
            case "turn": Slide(c, "trbTurn", v); break;
            case "hdg": Slide(c, "trbHeading", v); break;
            case "vrp": Slide(c, "trbVrpToPup", v); break;
            case "zoom": Slide(c, "trbZoom", v); break;
            case "wp":
            {
                var num = (NumericUpDown)Control(t, c, "numWaypoint");
                decimal d = decimal.Parse(v, CultureInfo.InvariantCulture);
                d = Math.Max(num.Minimum, Math.Min(num.Maximum, d));
                Guard(() => num.Value = d);
                break;
            }
            case "oa2": Guard(() => ((CheckBox)Control(t, c, "chbOA2")).CheckState = v == "1" ? CheckState.Checked : CheckState.Unchecked); break;
            case "click":
                if (v.StartsWith("chb")) { var cb = (CheckBox)Control(t, c, v); cb.Checked = !cb.Checked; }
                Call(t, c, v + "_Click", null, EventArgs.Empty);
                break;
            case "sel":
            {
                var parts = v.Split(':');
                var e = new MouseEventArgs(parts[1] == "R" ? MouseButtons.Right : MouseButtons.Left, 1, 0, 0, 0);
                Call(t, c, parts[0] + "_MouseClick", null, e);
                break;
            }
            case "vis": Guard(() => c.Visible = v == "1"); break;   // the page's own Visible, as fclsMain flips it
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
                    var f = Make(flightType);
                    var wps = Array.CreateInstance(wpType, p[2]);
                    for (int j = 0; j < p[2]; j++) wps.SetValue(Make(wpType), j);
                    flightType.GetField("waypoints", Any).SetValue(f, wps);
                    table.SetValue(f, i);
                }
                mt.GetField("FlightTable", Any).SetValue(main, table);
                break;
            }
            case "flightnr": mt.GetField("FlightNR", Any).SetValue(main, int.Parse(v, CultureInfo.InvariantCulture)); break;
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
            case "terrain":
                mt.GetField("terrainLoaded", Any).SetValue(main, v != "0");
                mt.GetField("NewTerrainElvLoaded", Any).SetValue(main, v == "1");
                break;
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
            case "draw": Call(t, c, "Draw", v == "1"); break;
            case "noini": File.Delete(Path.Combine(progDir, "Setup.ini")); break;
            case "mdtc": dtc.GetType().GetField("blnMissionDtcLoaded", Any).SetValue(dtc, v == "1"); break;   // the DTC page has a TE mission's cartridge
            case "wpdec":   // numWaypoint.Value = a Decimal, kept inside the box's range first (as NumericUpDown.Constrain does)
            {
                var num = (NumericUpDown)Control(t, c, "numWaypoint");
                decimal d = decimal.Parse(v, CultureInfo.InvariantCulture);
                d = Math.Max(num.Minimum, Math.Min(num.Maximum, d));
                Guard(() => num.Value = d);
                break;
            }
            case "wpup": Guard(() => ((NumericUpDown)Control(t, c, "numWaypoint")).UpButton()); break;
            case "wpdown": Guard(() => ((NumericUpDown)Control(t, c, "numWaypoint")).DownButton()); break;
            case "wptext":  // text typed into the box: the Text setter marks it a user edit (when it changed) and validates it at once
                Guard(() => ((NumericUpDown)Control(t, c, "numWaypoint")).Text = v);
                break;
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

    private static object Make(Type t)
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
        row.Add(((Label)Control(t, c, "lblVIP")).ForeColor.Name);
        foreach (var s in Shown) row.Add(StateVisible((Control)Control(t, c, s)) ? "shown" : "hidden");
        foreach (var s in Sliders) { var tb = (TrackBar)Control(t, c, s); row.Add(I(tb.Value)); row.Add(I(tb.Minimum)); row.Add(I(tb.Maximum)); }
        row.Add(((NumericUpDown)Control(t, c, "numWaypoint")).Value.ToString(CultureInfo.InvariantCulture));
        row.Add(I((int)((CheckBox)Control(t, c, "chbOA2")).CheckState));
        row.Add(((CheckBox)Control(t, c, "chbShowPPT")).Checked ? "1" : "0");
        row.Add(((CheckBox)Control(t, c, "chbShowPPTNr")).Checked ? "1" : "0");
        var img = ((PictureBox)Control(t, c, "picProfile")).Image;
        row.Add(img == null ? "" : (pictureByWidth.TryGetValue(img.Width, out var pn) ? pn : "?" + img.Width));
        foreach (var b in new[] { "btnCamp", "btnTE" })
        {
            var btn = (Button)Control(t, c, b);
            row.Add(btn.Enabled ? "1" : "0"); row.Add(btn.BackColor.Name); row.Add(btn.IsDisposed ? "1" : "0");
        }
        var save = (Button)Control(t, c, "btnSaveDTC");
        row.Add(Clean(save.Text)); row.Add(I(save.Width) + "x" + I(save.Height));
        var nav = main.GetType().GetField("PopUpNavOffsets", Any).GetValue(main);
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
        foreach (var s in DtcStrings) row.Add(Clean((string)dtc.GetType().GetField(s, Any).GetValue(dtc) ?? ""));
        foreach (var s in StateStrings) { var v = (string)t.GetField(s, Any).GetValue(c); row.Add(v == null ? "<null>" : Clean(v)); }
        foreach (var s in StateBools) row.Add((bool)t.GetField(s, Any).GetValue(c) ? "1" : "0");
        foreach (var s in StateInts) row.Add(I((int)t.GetField(s, Any).GetValue(c)));
        foreach (var s in new[] { "decOrig_TGT", "decOrig_IP" })
        {
            var p = t.GetField(s, Any).GetValue(c);
            row.Add(R((float)pointType.GetField("X").GetValue(p))); row.Add(R((float)pointType.GetField("Y").GetValue(p))); row.Add(R((float)pointType.GetField("Z").GetValue(p)));
        }
        foreach (var s in StatePoints) { var p = (PointF)t.GetField(s, Any).GetValue(c); row.Add(R(p.X)); row.Add(R(p.Y)); }
        foreach (var s in StateArrays) { var a = (double[])t.GetField(s, Any).GetValue(c); row.Add(D(a[0])); row.Add(D(a[1])); }

        // what fclsMain writes to Setup.ini's [PopUp] on the way out (fclsMain.cs, the INIWrite block)
        var cts = conversions.GetMethods().Where(m => m.Name == "ToString").ToArray();
        string VbStr(object o) => (string)cts.First(m => m.GetParameters().Length == 1 && m.GetParameters()[0].ParameterType == o.GetType()).Invoke(null, new[] { o });
        row.Add(VbStr((bool)t.GetField("blnRef", Any).GetValue(c)));
        row.Add(VbStr((bool)t.GetField("blnProfile", Any).GetValue(c)));
        row.Add(VbStr((bool)t.GetField("blnBomb", Any).GetValue(c)));
        row.Add(VbStr((int)t.GetField("intIngressAlt", Any).GetValue(c)));
        row.Add(VbStr((int)t.GetField("intDiveAngleDeg", Any).GetValue(c)));
        row.Add(VbStr((int)t.GetField("intCAS", Any).GetValue(c)));
        row.Add(VbStr((int)t.GetField("intReleaseHeight", Any).GetValue(c)));
        row.Add(VbStr((int)t.GetField("intTrackingTime", Any).GetValue(c)));
        row.Add(VbStr((double)t.GetField("dblPullingGs", Any).GetValue(c)));
        row.Add(VbStr(((TrackBar)Control(t, c, "trbTurn")).Value));
        row.Add(VbStr((int)t.GetField("intAttackHeadingDeg", Any).GetValue(c)));
        row.Add(VbStr((int)t.GetField("intVIPtoPUPswitch", Any).GetValue(c)));
        row.Add(VbStr((int)((CheckBox)Control(t, c, "chbOA2")).CheckState));
        row.Add(VbStr(((NumericUpDown)Control(t, c, "numWaypoint")).Value));

        // the print document (MakePrintDocument is never called by the program; its table is checked all the same)
        int before = errors;
        Call(t, c, "MakePrintDocument");
        var prt = (string[,])t.GetField("strPopUpPrint", Any).GetValue(c);
        for (int r = 1; r <= 26; r++) for (int k = 0; k < 2; k++) row.Add(prt[r, k] == null ? "<null>" : Clean(prt[r, k]));
        row.Add(I(before) + (errors != before ? "+print" : ""));
        row.Add(I(msgBoxes));
        row.Add(Clean(((NumericUpDown)Control(t, c, "numWaypoint")).Text));
        if (captureOn) { row.Add(lastPicture); row.Add(string.Join(";", saveLog)); }
        return row;
    }

    // ------------------------------------------------------------------------------------------------ the cases

    private static string StdIni(Random r = null) =>
        "ini=Ref:False|Profile:True|Bomb:True|IngressAlt:300|DiveAngle:30|CAS:450|ReleaseHeight:5000|TrackingTime:5|PullingGs:3|Turn:1|AttackHdg:0|VIPtoPUP:3|OA2atAO:0|Waypoint:5";

    private static string F(float v) => v.ToString("R", CultureInfo.InvariantCulture);

    /** A target at steerpoint wp and (mode 2) an IP at wp - 1, through the tables, as fclsMain holds them. */
    private static void Targets(List<string> ops, Random rnd, int wp, int mode, bool te, float tn, float teast, float tz, float ipn, float ipe, float ipz)
    {
        string tbl = te ? "te" : "camp";
        if (mode >= 1) ops.Add(tbl + "=" + (wp - 1) + "," + F(tn) + "," + F(teast) + "," + F(tz));
        if (mode >= 2) ops.Add(tbl + "=" + (wp - 2) + "," + F(ipn) + "," + F(ipe) + "," + F(ipz));
    }

    private static void Grid(List<List<string>> cases)
    {
        var rnd = new Random(20260927);
        var presets = new[] { "", "old1", "old2", "new1", "new2", "new3" };
        for (int i = 0; i < 1100; i++)
        {
            var ops = new List<string> { StdIni(), "load" };
            int mode = i < 9 ? i % 3 : (rnd.Next(100) < 40 ? 0 : (rnd.Next(100) < 30 ? 1 : 2));
            int wp = 3 + rnd.Next(23);
            string preset = presets[rnd.Next(presets.Length)];
            if (preset != "") ops.Add("coord=" + preset);
            if (rnd.Next(2) == 0) ops.Add("terrain=1");
            ops.Add("prec=1"); ops.Add("src=Camp");
            float tn = (float)(150000 + rnd.NextDouble() * 3000000), te = (float)(150000 + rnd.NextDouble() * 3000000);
            float tz = rnd.Next(100) < 30 ? 0 : rnd.Next(4000);
            float ipn = 0, ipe = 0, ipz = rnd.Next(100) < 50 ? 0 : rnd.Next(20000);
            if (mode == 2)
            {
                double dist = 6076.1157 * (3 + rnd.NextDouble() * 40);
                double brg = rnd.NextDouble() * 2 * Math.PI;
                if (rnd.Next(100) < 5) brg = rnd.Next(4) * Math.PI / 2;
                ipn = (float)(tn + Math.Cos(brg) * dist); ipe = (float)(te + Math.Sin(brg) * dist);
                if (rnd.Next(100) < 3) ipn = tn;
                if (rnd.Next(100) < 3) ipe = te;
            }
            Targets(ops, rnd, wp, mode, false, tn, te, tz, ipn, ipe, ipz);
            ops.Add("wp=" + wp);
            ops.Add(rnd.Next(2) == 0 ? "getcoords" : "camptecall");
            int dive = rnd.Next(100) < 60 ? 10 + 5 * rnd.Next(8) : 10 + rnd.Next(36);
            ops.Add("ingr=" + (1 + rnd.Next(5)));
            ops.Add("dive=" + dive);
            ops.Add("speed=" + (rnd.Next(100) < 60 ? 300 + 5 * rnd.Next(51) : 300 + rnd.Next(251)));
            ops.Add("rel=" + (rnd.Next(100) < 8 ? (rnd.Next(2) == 0 ? 1 : 100) : 1 + rnd.Next(100)));
            ops.Add("track=" + (1 + rnd.Next(10)));
            ops.Add("g=" + (rnd.Next(100) < 50 ? 20 + 5 * rnd.Next(9) : 20 + rnd.Next(41)));
            ops.Add("turn=" + rnd.Next(2));
            ops.Add("hdg=" + (rnd.Next(100) < 15 ? new[] { 0, 90, 180, 270, 360 }[rnd.Next(5)] : rnd.Next(361)));
            ops.Add("vrp=" + (1 + rnd.Next(5)));
            ops.Add("oa2=" + rnd.Next(2));
            ops.Add("click=" + (rnd.Next(2) == 0 ? "pnlProfile_Up" : "pnlProfile_Down"));
            ops.Add("click=" + (rnd.Next(2) == 0 ? "pnlBomb_Up" : "pnlBomb_Down"));
            ops.Add("click=" + (rnd.Next(2) == 0 ? "pnlRefUp" : "pnlRefDown"));
            if (rnd.Next(100) < 25) ops.Add("dive=" + (rnd.Next(2) == 0 ? 10 + 5 * rnd.Next(8) : 10 + rnd.Next(36)));
            if (rnd.Next(100) < 20) ops.Add("zoom=" + rnd.Next(101));
            ops.Add("flow=1");
            cases.Add(ops);
        }
    }

    /** Every dive angle that matters against every release height, including the num10 holes. */
    private static void Sweep(List<List<string>> cases)
    {
        var rnd = new Random(515);
        int[] dives = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 24, 25, 26, 29, 30, 31, 34, 35, 39, 40, 41, 44, 45 };
        int[] rels = { 1, 3, 5, 10, 11, 12, 13, 14, 15, 19, 20, 21, 29, 30, 31, 39, 40, 41, 49, 50, 51, 60, 79, 80, 81, 99, 100 };
        int[] speeds = { 300, 301, 302, 304, 305, 453, 500, 546, 549, 550 };
        int[] gs = { 20, 21, 39, 40, 41, 45, 60 };
        int[] hdgs = { 0, 1, 45, 89, 90, 91, 179, 180, 181, 269, 270, 271, 359, 360 };
        int n = 0;
        foreach (var d in dives) foreach (var r in rels)
        {
            if (rnd.Next(100) >= 45) continue;
            var ops = new List<string> { StdIni(), "load", "prec=1", "src=Camp" };
            int wp = new[] { 3, 4, 5, 13, 23, 24, 25 }[n % 7];
            int mode = n % 3;
            Targets(ops, rnd, wp, mode, false, 1234567.5f, 2345678.25f, (n % 5) * 1000, 1234567.5f + 30000f * ((n % 4) - 1.5f), 2345678.25f + 20000f, 0);
            ops.Add("wp=" + wp); ops.Add("getcoords");
            ops.Add("speed=" + speeds[n % speeds.Length]);
            ops.Add("dive=" + d); ops.Add("rel=" + r);
            ops.Add("track=" + new[] { 1, 4, 5, 6, 9, 10 }[n % 6]);
            ops.Add("g=" + gs[n % gs.Length]);
            ops.Add("hdg=" + hdgs[n % hdgs.Length]);
            ops.Add("turn=" + (n / 3 % 2));
            ops.Add("vrp=" + (1 + n % 5));
            if (n % 4 == 1) ops.Add("click=pnlRefDown");
            if (n % 5 == 2) ops.Add("click=pnlProfile_Up");
            if (n % 7 == 3) ops.Add("click=pnlBomb_Up");
            if (n % 3 == 0) ops.Add("oa2=1");
            ops.Add("flow=1");
            cases.Add(ops);
            n++;
        }
    }

    /** Targets and IPs at awkward places. */
    private static void Awkward(List<List<string>> cases)
    {
        var rnd = new Random(31337);
        var spots = new List<float[]> {
            new float[] { 1000000, 1000000, 1000000, 1000000 },      // the IP on the target
            new float[] { 1000000, 1000000, 1000001, 1000000 },
            new float[] { 1000000, 1000000, 999999, 1000000 },
            new float[] { 1000000, 1000000, 1000000, 1000001 },
            new float[] { 1000000, 1000000, 1000000, 999999 },
            new float[] { 1000000, 1000000, 1100000, 0 },             // IP east 0: no VIP
            new float[] { 1000000, 1000000, 0, 1100000 },             // IP north 0: a VIP all the same
            new float[] { 12345678, 9999999, 12000000, 9800000 },     // exponent-form ToString
            new float[] { 9999999, 12345678, 9950000, 12300000 },
            new float[] { 3, 5, 100000, 100000 },                     // near the origin
            new float[] { 0, 1000000, 1100000, 1000000 },             // target north 0
            new float[] { 1000000, 0, 1100000, 1000000 },             // target east 0: no target
            new float[] { 1234567.125f, 2345678.75f, 1250000.5f, 2360000.25f },
            new float[] { 3358699.5f, 3358699.5f, 3300000, 3300000 },  // the theater's corner
            new float[] { 3400000, 3400000, 3300000, 3300000 },       // beyond it
            new float[] { -50000, 1000000, 100000, 1000000 },         // negative
        };
        var presets = new[] { "", "old1", "old2", "new1", "new2", "new3" };
        int i = 0;
        foreach (var s in spots) foreach (var preset in presets) for (int rep = 0; rep < 3; rep++)
        {
            var ops = new List<string> { StdIni(), "load" };
            if (preset != "") ops.Add("coord=" + preset);
            if (rep == 1) ops.Add("terrain=1");
            ops.Add("prec=1"); ops.Add("src=" + (rep == 2 ? "TE" : "Camp"));
            int wp = 3 + rnd.Next(23);
            float tz = new float[] { 0, 1, -1, -1457, 20000, 45000, 123.5f }[rnd.Next(7)];
            Targets(ops, rnd, wp, 2, rep == 2, s[0], s[1], tz, s[2], s[3], rnd.Next(2) * 5000);
            ops.Add("wp=" + wp);
            ops.Add("getcoords");
            ops.Add("dive=" + (10 + rnd.Next(36))); ops.Add("rel=" + (1 + rnd.Next(100)));
            ops.Add("hdg=" + rnd.Next(361)); ops.Add("turn=" + rnd.Next(2));
            ops.Add("click=" + (rnd.Next(2) == 0 ? "pnlRefDown" : "pnlRefUp"));
            ops.Add("click=" + (rnd.Next(2) == 0 ? "pnlProfile_Up" : "pnlProfile_Down"));
            if (rnd.Next(2) == 0) ops.Add("oa2=1");
            ops.Add("flow=1");
            cases.Add(ops);
            i++;
        }
    }

    /** Setup() over what a Setup.ini can hold: missing keys, garbage, hex, fractions, spaces, out-of-range values. */
    private static void IniFuzz(List<List<string>> cases)
    {
        var rnd = new Random(4711);
        var bools = new[] { "True", "False", "true", "FALSE", "1", "0", "-1", "2", "yes", "", "abc", " True ", "&H1", "0.0", "1e0" };
        var nums = new Dictionary<string, string[]> {
            ["IngressAlt"] = new[] { "100", "300", "500", "501", "550", "549", "50", "0", "-100", "", "abc", "250", "350", "&H64", "1e2", " 300 ", "3,00", "300.5", "(300)", "$300", "4500" },
            ["DiveAngle"] = new[] { "10", "30", "45", "9", "46", "12.5", "13.5", "", "x", "&H1E", "2e1", " 25 ", "30.0", "-30" },
            ["CAS"] = new[] { "300", "450", "550", "551", "299", "453", "457.5", "", "?", "&H1C2", "4.5e2", "500" },
            ["ReleaseHeight"] = new[] { "5000", "3000", "10000", "10001", "99", "100", "4250", "4251", "4249", "", "zz", "&H1388", "5e3", "2000", "8000" },
            ["TrackingTime"] = new[] { "5", "1", "10", "11", "0", "5.5", "6.5", "", "t", "&HA", "7" },
            ["PullingGs"] = new[] { "3", "2", "6", "7", "1.9", "6.05", "4.25", "4.35", "60", "61", "", "g", "&H5", "3.5", "5e0" },
            ["Turn"] = new[] { "1", "0", "True", "False", "", "0.5", "1.5", "2", "-1", "&H1" },
            ["AttackHdg"] = new[] { "0", "360", "361", "-1", "180.5", "181.5", "", "h", "&HB4", "90" },
            ["VIPtoPUP"] = new[] { "1", "3", "5", "6", "0", "2.5", "3.5", "", "v", "&H2" },
            ["OA2atAO"] = new[] { "0", "1", "2", "3", "-1", "", "x", "True", "0.5", "1.5" },
            ["Waypoint"] = new[] { "5", "3", "25", "24", "23", "23.99", "4.5", "5.0", "5.50", "0", "2", "2.5", "-1", "", "abc", "1e1", "&HA", " 7 ", "3.5", "12" },
        };
        for (int i = 0; i < 360; i++)
        {
            var kv = new List<string>();
            foreach (var key in IniKeys)
            {
                if (rnd.Next(100) < 12) continue;   // missing key: INIRead answers ""
                string[] vocab = nums.TryGetValue(key, out var vs) ? vs : bools;
                string v = rnd.Next(100) < 35 ? vocab[0] : vocab[rnd.Next(vocab.Length)];
                kv.Add(key + ":" + v);
            }
            var ops = new List<string> { "ini=" + string.Join("|", kv) };
            if (rnd.Next(100) < 20) ops.Add("mainloaded=0");
            ops.Add("load");
            // and some life after it
            if (rnd.Next(2) == 0) { ops.Add("prec=1"); ops.Add("src=Camp"); ops.Add("camp=4,1000000,1100000,500"); ops.Add("camp=3,1050000,1150000,0"); ops.Add("getcoords"); }
            int more = rnd.Next(4);
            for (int m = 0; m < more; m++) ops.Add(RandomOp(rnd));
            cases.Add(ops);
        }
        // an empty [PopUp]: every key missing
        cases.Add(new List<string> { "ini=", "load" });
        cases.Add(new List<string> { "ini=", "load", "dive=25", "flow=1" });
    }

    /** Where Get_Coords takes the target from: Precision, strDTC, blnCampTE, the tables, the flight table. */
    private static void Sources(List<List<string>> cases)
    {
        var rnd = new Random(8086);
        var srcs = new[] { "Camp", "TE", "Both", "None", "X", "null" };
        for (int i = 0; i < 420; i++)
        {
            var ops = new List<string> { StdIni(), "load" };
            if (rnd.Next(3) == 0) ops.Add("coord=" + new[] { "old1", "old2", "new1", "new2", "new3" }[rnd.Next(5)]);
            if (rnd.Next(3) == 0) ops.Add("terrain=" + rnd.Next(3));
            int wp = 3 + rnd.Next(23);
            int fill = rnd.Next(4);  // 0 none, 1 camp, 2 te, 3 both
            if ((fill & 1) != 0) Targets(ops, rnd, wp, 1 + rnd.Next(2), false, 1000000 + rnd.Next(200000), 1200000 + rnd.Next(200000), rnd.Next(3000), 1100000, 1250000, 0);
            if ((fill & 2) != 0) Targets(ops, rnd, wp, 1 + rnd.Next(2), true, 2000000 + rnd.Next(200000), 900000 + rnd.Next(200000), rnd.Next(3000), 2050000, 950000, 100);
            int fl = rnd.Next(5);   // 0 none, 1 valid flight, 2 short waypoints (throws inside the try), 3 FlightNR set but no table, 4 flight with zero waypoints set
            if (fl == 1 || fl == 2 || fl == 4)
            {
                int sel = rnd.Next(3);
                int count = fl == 2 ? Math.Max(0, wp - 3) : 30;
                ops.Add("flight=" + (1 + rnd.Next(3)) + "," + sel + "," + count);
                if (fl == 1)
                {
                    ops.Add("fwp=" + (wp - 1) + "," + (100 + rnd.Next(800)) + "," + (100 + rnd.Next(800)) + "," + rnd.Next(2000));
                    if (rnd.Next(3) > 0) ops.Add("fwp=" + (wp - 2) + "," + (100 + rnd.Next(800)) + "," + (100 + rnd.Next(800)) + "," + rnd.Next(2000));
                }
            }
            else if (fl == 3) ops.Add(rnd.Next(2) == 0 ? "flightnr=2" : "flight=2,5,0");
            ops.Add("prec=" + rnd.Next(2));
            ops.Add("src=" + srcs[rnd.Next(srcs.Length)]);
            ops.Add("campte=" + rnd.Next(2));
            ops.Add("wp=" + wp);
            int how = rnd.Next(5);
            if (how == 0) ops.Add("getcoords");
            else if (how == 1) ops.Add("camptecall");
            else if (how == 2) ops.Add("click=btnCamp");
            else if (how == 3) ops.Add("click=btnTE");
            else ops.Add("stpt");
            int more = rnd.Next(6);
            for (int m = 0; m < more; m++) ops.Add(RandomOp(rnd));
            if (rnd.Next(3) == 0) { ops.Add("src=" + srcs[rnd.Next(srcs.Length)]); ops.Add(rnd.Next(2) == 0 ? "camptecall" : "getcoords"); }
            cases.Add(ops);
        }
    }

    /** Allied Force (blnVersion false): SetVersion, FillLabelsAF, Get_Coords' strDTC = "Camp". */
    private static void Versions(List<List<string>> cases)
    {
        var rnd = new Random(1998);
        for (int i = 0; i < 240; i++)
        {
            var ops = new List<string> { "ver=" + (i % 4 == 0 ? "1" : "0"), StdIni(), "load" };
            int wp = 3 + rnd.Next(23);
            ops.Add("prec=" + rnd.Next(2));
            ops.Add("src=" + new[] { "None", "TE", "Both", "Camp" }[rnd.Next(4)]);
            Targets(ops, rnd, wp, 1 + rnd.Next(2), rnd.Next(2) == 0, 1000000 + rnd.Next(300000), 1200000 + rnd.Next(300000), rnd.Next(3000), 1100000 + rnd.Next(100000), 1150000 + rnd.Next(100000), 0);
            ops.Add("wp=" + wp);
            ops.Add("getcoords");
            int more = 4 + rnd.Next(12);
            for (int m = 0; m < more; m++) ops.Add(RandomOp(rnd));
            ops.Add("flow=1");
            cases.Add(ops);
        }
    }

    private static readonly string[] Clicks = {
        "lblN", "lblE", "lblS", "lblW", "lblN360", "pnlRefUp", "pnlRefDown", "pnlDED_Ref_Up", "pnlDED_Ref_Down",
        "pnlProfile_Up", "pnlProfile_Down", "pnlProfile2_Up", "pnlProfile2_Down", "pnlBomb_Up", "pnlBomb_Down",
        "btnCamp", "btnTE", "chbShowPPT", "chbShowPPTNr",
    };

    private static string RandomOp(Random rnd)
    {
        switch (rnd.Next(24))
        {
            case 0: return "ingr=" + (1 + rnd.Next(5));
            case 1: return "dive=" + (rnd.Next(2) == 0 ? 10 + 5 * rnd.Next(8) : 10 + rnd.Next(36));
            case 2: return "speed=" + (300 + rnd.Next(251));
            case 3: return "rel=" + (1 + rnd.Next(100));
            case 4: return "track=" + (1 + rnd.Next(10));
            case 5: return "g=" + (20 + rnd.Next(41));
            case 6: return "turn=" + rnd.Next(2);
            case 7: return "hdg=" + (rnd.Next(5) == 0 ? new[] { 0, 90, 180, 270, 360 }[rnd.Next(5)] : rnd.Next(361));
            case 8: return "vrp=" + (1 + rnd.Next(5));
            case 9: return "zoom=" + (rnd.Next(4) == 0 ? new[] { 0, 5, 9, 10, 90, 91, 95, 100 }[rnd.Next(8)] : rnd.Next(101));
            case 10: return "wp=" + (3 + rnd.Next(23));
            case 11: return "oa2=" + rnd.Next(2);
            case 12: case 13: case 14: return "click=" + Clicks[rnd.Next(Clicks.Length)];
            case 15: return "sel=" + new[] { "pnlSelections_Up", "pnlSelections_Middle", "pnlSelections_Down" }[rnd.Next(3)] + ":" + (rnd.Next(3) == 0 ? "R" : "L");
            case 16: return "vis=" + rnd.Next(2);
            case 17: return "camp=" + (1 + rnd.Next(24)) + "," + (500000 + rnd.Next(2000000)) + "," + (500000 + rnd.Next(2000000)) + "," + rnd.Next(5000);
            case 18: return "camp=" + (1 + rnd.Next(24)) + ",0,0,0";
            case 19: return rnd.Next(2) == 0 ? "getcoords" : "stpt";
            case 20: return "src=" + new[] { "Camp", "TE", "Both", "None" }[rnd.Next(4)];
            case 21: return "flow=" + rnd.Next(2);
            case 22: return "coord=" + new[] { "old1", "old2", "new1", "new2", "new3" }[rnd.Next(5)];
            default: return "terrain=" + rnd.Next(3);
        }
    }

    /** Free-form chains: repeats, reversals, the page hidden and shown, a target replaced or taken away. */
    private static void Sequences(List<List<string>> cases)
    {
        var rnd = new Random(271828);
        // a few written out by hand
        cases.Add(new List<string> { StdIni(), "load", "dive=45", "rel=100", "dive=10", "flow=1" });
        cases.Add(new List<string> { StdIni(), "load", "dive=10", "rel=12", "dive=14", "flow=1" });
        cases.Add(new List<string> { StdIni(), "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "getcoords", "click=pnlRefDown", "camp=6,0,0,0", "getcoords", "wp=9", "flow=1" });
        cases.Add(new List<string> { StdIni(), "load", "prec=1", "src=Camp", "terrain=1", "camp=8,2000000,1500000,5000", "wp=9", "getcoords", "ingr=2", "terrain=0", "getcoords", "ingr=4" });
        cases.Add(new List<string> { StdIni(), "load", "speed=301", "speed=301", "speed=549", "speed=548" });
        cases.Add(new List<string> { StdIni(), "load", "vis=0", "dive=35", "click=pnlRefDown", "zoom=77", "vis=1" });
        cases.Add(new List<string> { StdIni(), "load", "sel=pnlSelections_Up:L", "sel=pnlSelections_Middle:L", "sel=pnlSelections_Down:L", "sel=pnlSelections_Down:R", "sel=pnlSelections_Middle:R", "sel=pnlSelections_Up:R" });
        cases.Add(new List<string> { StdIni(), "load", "click=chbShowPPT", "click=chbShowPPTNr", "click=chbShowPPT", "zoom=0", "zoom=100" });
        for (int i = 0; i < 1100; i++)
        {
            var ops = new List<string>();
            if (rnd.Next(10) == 0) ops.Add("ver=0");
            ops.Add(StdIni());
            ops.Add("load");
            if (rnd.Next(2) == 0) { ops.Add("prec=1"); ops.Add("src=Camp"); }
            int n = 4 + rnd.Next(22);
            string last = null;
            for (int k = 0; k < n; k++)
            {
                // repeat-biased: a third of the time the same kind of op again
                string op = last != null && rnd.Next(3) == 0 ? Retarget(last, rnd) : RandomOp(rnd);
                ops.Add(op);
                last = op;
            }
            cases.Add(ops);
        }
    }

    private static string Retarget(string op, Random rnd)
    {
        var probe = RandomOp(rnd);
        for (int tries = 0; tries < 40 && probe.Split('=')[0] != op.Split('=')[0]; tries++) probe = RandomOp(rnd);
        return probe;
    }


    // ------------------------------------------------------------------------------------------------ the verifier's cases
    // Folded in from its harness (PopupVerify.cs) so that the reference is one file: hand-written sequences, the IP on
    // every side of the target at every distance, a harder Setup.ini, the flight table's grid path, long chains.

    private static T Pick<T>(Random r, params T[] a) => a[r.Next(a.Length)];

    private static readonly string[] Clicks2 = {
        "lblN", "lblE", "lblS", "lblW", "lblN360", "pnlRefUp", "pnlRefDown", "pnlDED_Ref_Up", "pnlDED_Ref_Down",
        "pnlProfile_Up", "pnlProfile_Down", "pnlProfile2_Up", "pnlProfile2_Down", "pnlBomb_Up", "pnlBomb_Down",
        "chbShowPPT", "chbShowPPTNr",
    };

    private static float HardCoord(Random r)
    {
        switch (r.Next(10))
        {
            case 0: return 0f;
            case 1: return (float)(-r.NextDouble() * 200000);
            case 2: return (float)(10000000 + r.NextDouble() * 90000000);    // exponent form when printed
            case 3: return (float)(r.Next(3400000)) + 0.5f;
            case 4: return 3358699.5f;
            case 5: return (float)(r.NextDouble() * 3);
            default: return (float)(r.NextDouble() * 3400000);
        }
    }

    /** A harder vocabulary than Popup.RandomOp; flightCount tracks the flight table's waypoint count (0 = none). */
    private static string HardOp(Random rnd, ref int flightCount)
    {
        switch (rnd.Next(36))
        {
            case 0: return "ingr=" + Pick(rnd, 1, 5, 1, 5, 2, 3, 4);
            case 1: return "dive=" + Pick(rnd, 10, 45, 11, 14, 15, 16, 19, 20, 24, 25, 29, 30, 34, 35, 40, 44, 10 + rnd.Next(36));
            case 2: return "speed=" + Pick(rnd, 300, 301, 302, 303, 304, 305, 306, 309, 311, 549, 550, 548, 547, 300 + rnd.Next(251));
            case 3: return "rel=" + Pick(rnd, 1, 2, 3, 4, 5, 9, 10, 11, 12, 14, 15, 19, 20, 29, 30, 31, 39, 40, 41, 49, 50, 51, 59, 60, 79, 80, 81, 99, 100, 1 + rnd.Next(100));
            case 4: return "track=" + Pick(rnd, 1, 5, 6, 10, 1 + rnd.Next(10));
            case 5: return "g=" + Pick(rnd, 20, 39, 40, 41, 60, 21, 59, 20 + rnd.Next(41));
            case 6: return "turn=" + rnd.Next(2);
            case 7: return "hdg=" + Pick(rnd, 0, 1, 89, 90, 91, 179, 180, 181, 269, 270, 271, 359, 360, rnd.Next(361));
            case 8: return "vrp=" + Pick(rnd, 1, 5, 1 + rnd.Next(5));
            case 9: return "zoom=" + Pick(rnd, 0, 1, 9, 10, 11, 29, 30, 31, 89, 90, 91, 99, 100, rnd.Next(101));
            case 10: return "wp=" + Pick(rnd, 3, 4, 24, 25, 3 + rnd.Next(23));
            case 11: return "oa2=" + rnd.Next(2);
            case 12: case 13: case 14: return "click=" + Clicks2[rnd.Next(Clicks2.Length)];
            case 15: return "sel=" + Pick(rnd, "pnlSelections_Up", "pnlSelections_Middle", "pnlSelections_Down") + ":" + Pick(rnd, "L", "R");
            case 16: return "vis=" + rnd.Next(2);
            case 17: return "camp=" + rnd.Next(25) + "," + F(HardCoord(rnd)) + "," + F(HardCoord(rnd)) + "," + F(Pick(rnd, 0f, 0.5f, 1.5f, 2.5f, -2.5f, -1457f, 45000f, 123456789f, (float)rnd.Next(5000)));
            case 18: return "te=" + rnd.Next(25) + "," + F(HardCoord(rnd)) + "," + F(HardCoord(rnd)) + "," + F(Pick(rnd, 0f, 0.5f, 3.5f, -0.5f, 20000f, (float)rnd.Next(5000)));
            case 19: return Pick(rnd, "camp", "te") + "=" + rnd.Next(25) + "," + Pick(rnd, "0,0,0", "1000000,0,100", "0,1000000,100");
            case 20: return Pick(rnd, "getcoords", "stpt", "camptecall", "getcoords");
            case 21: return "src=" + Pick(rnd, "Camp", "TE", "Both", "None", "X", "null", "camp");
            case 22: return "campte=" + rnd.Next(2);
            case 23: return "prec=" + rnd.Next(2);
            case 24: return "flow=" + rnd.Next(2);
            case 25: return "draw=" + rnd.Next(2);
            case 26: return "coord=" + Pick(rnd, "old1", "old2", "new1", "new2", "new3");
            case 27: return "terrain=" + rnd.Next(3);
            case 28: return "mainloaded=" + Pick(rnd, 0, 1, 1);
            case 29:
            {
                int count = Pick(rnd, 0, 2, 5, 24, 25, 30);
                flightCount = count;
                return "flight=" + Pick(rnd, 0, 1, 2, -1) + "," + rnd.Next(3) + "," + count;
            }
            case 30:
                if (flightCount > 0)
                    return "fwp=" + rnd.Next(flightCount) + "," + Pick(rnd, 0, 1, 1023, 32767, -1, -32768, rnd.Next(1100)) + "," + Pick(rnd, 0, 1, 1023, 32767, -32768, rnd.Next(1100)) + "," + Pick(rnd, 0, -1, 32767, -32768, rnd.Next(3000));
                return "flightnr=" + Pick(rnd, 0, 1, 3);
            case 31: return "flightnr=" + Pick(rnd, 0, 1, 3);
            case 32: return "click=" + Pick(rnd, "btnCamp", "btnTE");
            case 33: return "click=" + Pick(rnd, "pnlRefDown", "pnlRefUp");
            case 34: return "dive=" + Pick(rnd, 10, 15, 20, 25, 30, 35, 40, 45);
            default: return "rel=" + Pick(rnd, 3, 5, 10, 20, 30, 40, 50, 80, 100);
        }
    }

    private static void Tgt(List<string> ops, string tbl, int wp, float tn, float te, float tz, float ipn, float ipe, float ipz)
    {
        ops.Add(tbl + "=" + (wp - 1) + "," + F(tn) + "," + F(te) + "," + F(tz));
        ops.Add(tbl + "=" + (wp - 2) + "," + F(ipn) + "," + F(ipe) + "," + F(ipz));
    }

    /** Written out by hand: every one moves one input more than once, or changes the order of things. */
    private static void Handcrafted(List<List<string>> cases)
    {
        var S = StdIni();
        var hand = new List<string[]>
        {
            new[] { S, "load", "dive=45", "rel=100", "dive=10", "dive=11", "rel=12", "dive=14", "rel=100", "dive=15", "flow=1" },
            new[] { S, "load", "dive=45", "rel=100", "dive=44", "dive=40", "dive=41", "rel=1", "dive=45" },
            new[] { S, "load", "rel=49", "dive=40", "track=10", "rel=50", "track=10", "rel=49", "track=10", "rel=50" },
            new[] { S, "load", "track=10", "rel=50", "rel=49", "track=7", "rel=60", "rel=30" },
            new[] { S, "load", "speed=301", "speed=302", "speed=303", "speed=304", "speed=306" },
            new[] { S, "load", "speed=549", "speed=548", "speed=541" },
            new[] { S, "load", "speed=300", "speed=550", "speed=309" },
            new[] { S, "load", "g=40", "g=41", "g=39" },
            new[] { S, "load", "g=41", "g=40" },
            new[] { S, "load", "g=20", "g=60", "rel=100", "dive=45", "g=20" },
            new[] { S, "load", "ingr=1", "ingr=5", "ingr=1" },
            new[] { S, "load", "zoom=0" }, new[] { S, "load", "zoom=1" }, new[] { S, "load", "zoom=9" }, new[] { S, "load", "zoom=10" },
            new[] { S, "load", "zoom=11" }, new[] { S, "load", "zoom=90" }, new[] { S, "load", "zoom=91" }, new[] { S, "load", "zoom=99" },
            new[] { S, "load", "zoom=100" }, new[] { S, "load", "zoom=100", "zoom=0" },
            new[] { S, "load", "click=pnlRefDown", "click=pnlRefDown", "click=pnlRefUp" },
            new[] { S, "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "getcoords", "click=pnlRefDown", "camp=5,1030000,0,0", "getcoords", "flow=1" },
            new[] { S, "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "getcoords", "click=pnlRefDown", "camp=6,1000000,0,300", "getcoords", "camp=6,1000000,1000000,300", "getcoords" },
            new[] { S, "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "click=pnlRefDown", "wp=8", "wp=7", "click=pnlRefDown" },
            new[] { S, "load", "prec=1", "src=Camp", "camp=2,1000000,1000000,300", "camp=1,1030000,1040000,0", "wp=3", "getcoords", "wp=25", "wp=3" },
            new[] { S, "load", "prec=1", "src=Camp", "camp=24,1000000,1000000,300", "camp=23,1030000,1040000,0", "wp=25", "getcoords", "click=pnlRefDown", "wp=24" },
            new[] { S, "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "vis=0", "getcoords", "dive=35", "click=pnlRefDown", "vis=1" },
            new[] { S, "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "vis=0", "flow=1", "vis=0", "vis=1", "vis=1" },
            new[] { S, "load", "vis=0", "flow=1" },
            new[] { S, "load", "vis=0", "draw=1" },
            new[] { S, "load", "vis=0", "sel=pnlSelections_Up:L", "zoom=40", "vis=1" },
            new[] { S, "load", "sel=pnlSelections_Up:L", "vis=0", "click=pnlRefDown", "vis=1" },
            new[] { S, "load", "sel=pnlSelections_Up:L", "vis=0", "flow=1" },
            new[] { S, "load", "sel=pnlSelections_Up:L", "vis=0", "draw=0", "zoom=50", "click=chbShowPPT" },
            new[] { S, "load", "draw=1" },
            new[] { S, "load", "draw=0" },
            new[] { S, "load", "mainloaded=0", "sel=pnlSelections_Up:L", "draw=1" },
            new[] { S, "load", "sel=pnlSelections_Up:L", "sel=pnlSelections_Middle:L", "sel=pnlSelections_Down:L", "sel=pnlSelections_Down:L", "sel=pnlSelections_Down:R" },
            new[] { S, "load", "sel=pnlSelections_Middle:R", "sel=pnlSelections_Middle:R", "sel=pnlSelections_Up:R", "sel=pnlSelections_Up:L" },
            new[] { S, "load", "sel=pnlSelections_Down:R" },
            new[] { S, "load", "sel=pnlSelections_Up:R" },
            // before the page is loaded (the page exists before fclsMain shows it)
            new[] { "wp=7", S, "load" },
            new[] { "dive=25", "rel=40", S, "load" },
            new[] { "click=pnlRefDown", "click=pnlProfile_Up", "click=pnlBomb_Up", S, "load" },
            new[] { "sel=pnlSelections_Up:L", S, "load" },
            new[] { "zoom=50", S, "load" },
            new[] { "click=chbShowPPT", S, "load" },
            new[] { "oa2=1", S, "load" },
            new[] { "hdg=200", "turn=0", "speed=333", S, "load" },
            new[] { "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "getcoords", S, "load" },
            new[] { "vis=0", S, "load", "dive=25", "vis=1" },
            new[] { "vis=0", S, "load", "vis=1" },
            new[] { "flow=1", S, "load" },
            new[] { "draw=1", S, "load" },
            // loaded twice
            new[] { S, "load", "dive=20", "load" },
            // OA2 from the ini, indeterminate, then moved
            new[] { "ini=OA2atAO:2|Ref:False|Profile:True", "load", "oa2=1", "oa2=0", "oa2=1" },
            new[] { "ini=OA2atAO:2", "load", "oa2=0" },
            new[] { "ini=OA2atAO:2", "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "getcoords", "click=pnlRefDown", "oa2=0" },
        };
        // every heading edge with each turn, each profile and two dive angles, with a target and an IP, VIP mode
        foreach (var hdg in new[] { 0, 1, 179, 180, 181, 359, 360 })
            foreach (var turn in new[] { 0, 1 })
                foreach (var prof in new[] { "pnlProfile_Up", "pnlProfile_Down" })
                    foreach (var dive in new[] { 10, 45 })
                    {
                        var ops = new List<string> { S, "load", "prec=1", "src=Camp" };
                        Tgt(ops, "camp", 9, 1500000, 1600000, 250, 1550000, 1550000, 0);
                        ops.Add("wp=9"); ops.Add("getcoords"); ops.Add("click=pnlRefDown");
                        ops.Add("dive=" + dive); ops.Add("turn=" + turn); ops.Add("click=" + prof); ops.Add("hdg=" + hdg); ops.Add("oa2=1");
                        hand.Add(ops.ToArray());
                    }
        foreach (var h in hand) cases.Add(h.ToList());
    }

    /** IPs on the axes of the target, on it, a foot from it, at 45 degrees, and very far; every switch. */
    private static void EdgeGeometry(List<List<string>> cases)
    {
        var rnd = new Random(6061);
        var targets = new[] { new[] { 1000000f, 1000000f }, new[] { 3300000f, 50000f }, new[] { 50000f, 3300000f }, new[] { 12345.678f, 23456.789f }, new[] { 1234567.1f, 2345678.9f } };
        var dists = new[] { 0f, 1f, 2f, 6076f, 30000f, 200000f, 2000000f };
        var dirs = new[] { new[] { 1, 0 }, new[] { -1, 0 }, new[] { 0, 1 }, new[] { 0, -1 }, new[] { 1, 1 }, new[] { -1, -1 }, new[] { 1, -1 }, new[] { -1, 1 } };
        foreach (var t in targets) foreach (var d in dists) foreach (var dir in dirs)
        {
            if (d == 0f && (dir[0] != 1 || dir[1] != 0)) continue;
            if (rnd.Next(100) >= 55) continue;
            var ops = new List<string>();
            if (rnd.Next(8) == 0) ops.Add("ver=0");
            ops.Add(StdIni()); ops.Add("load");
            if (rnd.Next(2) == 0) ops.Add("coord=" + Pick(rnd, "old1", "old2", "new1", "new2", "new3"));
            ops.Add("prec=1");
            bool te = rnd.Next(3) == 0;
            ops.Add("src=" + (te ? "TE" : "Camp"));
            int wp = Pick(rnd, 3, 4, 13, 24, 25);
            Tgt(ops, te ? "te" : "camp", wp, t[0], t[1], Pick(rnd, 0f, 0.5f, 1.5f, 2.5f, 4000f, -300f), t[0] + dir[0] * d, t[1] + dir[1] * d, Pick(rnd, 0f, 9000f));
            ops.Add("wp=" + wp); ops.Add(Pick(rnd, "getcoords", "camptecall", "stpt"));
            ops.Add("dive=" + Pick(rnd, 10, 14, 15, 25, 29, 30, 44, 45));
            ops.Add("rel=" + Pick(rnd, 1, 12, 14, 30, 35, 40, 50, 80, 100));
            ops.Add("g=" + Pick(rnd, 20, 40, 41, 60));
            ops.Add("speed=" + Pick(rnd, 300, 301, 450, 549, 550));
            ops.Add("turn=" + rnd.Next(2));
            ops.Add("hdg=" + Pick(rnd, 0, 90, 180, 270, 360, rnd.Next(361)));
            ops.Add("click=" + Pick(rnd, "pnlProfile_Up", "pnlProfile_Down"));
            ops.Add("click=" + Pick(rnd, "pnlBomb_Up", "pnlBomb_Down"));
            ops.Add("click=" + Pick(rnd, "pnlRefDown", "pnlRefDown", "pnlRefUp"));
            ops.Add("oa2=" + rnd.Next(2));
            if (rnd.Next(2) == 0) ops.Add("sel=pnlSelections_Up:L");
            if (rnd.Next(3) == 0) ops.Add("zoom=" + Pick(rnd, 0, 100, rnd.Next(101)));
            ops.Add("vrp=" + (1 + rnd.Next(5)));
            // then the same inputs moved again
            ops.Add("dive=" + Pick(rnd, 10, 45, 20)); ops.Add("hdg=" + rnd.Next(361)); ops.Add("turn=" + rnd.Next(2));
            cases.Add(ops);
        }
    }

    /** A harder Setup.ini: boundaries, rounding halves, case, quotes, spaces, hex, exponents, overflow. */
    private static void HardIni(List<List<string>> cases)
    {
        var rnd = new Random(1234);
        var vocab = new Dictionary<string, string[]> {
            ["Ref"] = new[] { "True", "False", "1", "0", "-1", "\"True\"", "'False'", "TRUE", "t", "", "0.0", "0.5", "&H0", "1e-9" },
            ["Profile"] = new[] { "True", "False", "2", "-0", "\"1\"", "", "no", "0x1" },
            ["Bomb"] = new[] { "True", "False", "0", "1", "1.5", "", "Nothing" },
            ["IngressAlt"] = new[] { "100", "150", "250", "350", "449.99", "450", "499", "500", "549.9", "550", "550.01", "99", "99.99", "50", "-0", "1e2", "5E2", "&H1F4", "\"300\"", "300 ", "0.0001", "1,00", "1e400", "-1e400" },
            ["DiveAngle"] = new[] { "10", "45", "9.99", "10.5", "11.5", "44.5", "45.4", "45.5", "45.01", "\"20\"", "2e1", "&H2D", "(10)", "15.4999", "0x10" },
            ["CAS"] = new[] { "300", "550", "299.5", "300.4", "302", "303", "549", "549.5", "550.4", "550.5", "&H226", "5.5e2", "301" },
            ["ReleaseHeight"] = new[] { "100", "150", "250", "350", "10000", "10049", "10050", "99", "99.9", "4050", "4150", "8000", "7999", "1e4", "\"5000\"", "5000.5", "" },
            ["TrackingTime"] = new[] { "1", "10", "0.5", "1.5", "2.5", "9.5", "10.4", "10.5", "&HA", "6" },
            ["PullingGs"] = new[] { "2", "6", "2.05", "2.15", "2.25", "2.35", "4", "4.05", "4.15", "5.95", "6.04", "6.05", "6.5", "59.9", "60", "1.95", "1.99", "&H3", "3e0" },
            ["Turn"] = new[] { "0", "1", "True", "False", "-0", "0.4", "0.5", "1.4", "1.5", "&H0", "1e0", "\"1\"" },
            ["AttackHdg"] = new[] { "0", "360", "359.5", "360.4", "360.5", "0.5", "-0.4", "-0.5", "-0.6", "180", "&H168", "3.6e2", "1e-300" },
            ["VIPtoPUP"] = new[] { "1", "5", "0.5", "0.6", "1.5", "4.5", "5.4", "5.5", "&H5", "2" },
            ["OA2atAO"] = new[] { "0", "1", "2", "3", "-1", "1.5", "2.5", "0.5", "-0.5", "True", "&H2", "1e0", "" },
            ["Waypoint"] = new[] { "3", "25", "23", "23.9", "23.99999", "24", "3.5", "4.5", "5.5", "2.99", "3.00001", "0.0001", "1e-5", "22.5", "0.5", "-3", "&H17", "1e1", "\"7\"", "7 ", "12.25", "3.000", "4.50" },
        };
        var keys = new[] { "Ref", "Profile", "Bomb", "IngressAlt", "DiveAngle", "CAS", "ReleaseHeight", "TrackingTime", "PullingGs", "Turn", "AttackHdg", "VIPtoPUP", "OA2atAO", "Waypoint" };
        var std = new Dictionary<string, string> { ["Ref"] = "False", ["Profile"] = "True", ["Bomb"] = "True", ["IngressAlt"] = "300", ["DiveAngle"] = "30", ["CAS"] = "450", ["ReleaseHeight"] = "5000", ["TrackingTime"] = "5", ["PullingGs"] = "3", ["Turn"] = "1", ["AttackHdg"] = "0", ["VIPtoPUP"] = "3", ["OA2atAO"] = "0", ["Waypoint"] = "5" };
        // one key at a time off the standard set, every value of its vocabulary
        foreach (var k in keys)
            foreach (var v in vocab[k])
            {
                var kv = keys.Select(key => key + ":" + (key == k ? v : std[key])).ToList();
                var ops = new List<string> { "ini=" + string.Join("|", kv), "load" };
                if (k == "DiveAngle" || k == "ReleaseHeight" || k == "TrackingTime") ops.Add("rel=" + Pick(rnd, 1, 50, 100));
                if (k == "Waypoint" || k == "OA2atAO" || k == "Ref")
                {
                    ops.Add("prec=1"); ops.Add("src=Camp");
                    for (int i = 1; i < 25; i++) ops.Add("camp=" + i + "," + F(1000000 + 7000 * i) + "," + F(1200000 - 5000 * i) + "," + (i * 37));
                    ops.Add("getcoords"); ops.Add("click=pnlRefDown"); ops.Add("sel=pnlSelections_Up:L");
                }
                cases.Add(ops);
            }
        // lower-case keys, and dive angles that do not set the release height's range
        cases.Add(new List<string> { "ini=diveangle:45|RELEASEHEIGHT:10000|cas:550|ingressalt:500", "load" });
        cases.Add(new List<string> { "ini=DiveAngle:10|ReleaseHeight:4000", "load" });
        cases.Add(new List<string> { "ini=DiveAngle:11|ReleaseHeight:10000", "load" });
        cases.Add(new List<string> { "ini=DiveAngle:12|ReleaseHeight:250", "load" });
        // everything random
        for (int i = 0; i < 160; i++)
        {
            var kv = new List<string>();
            foreach (var k in keys) { if (rnd.Next(100) < 10) continue; kv.Add(k + ":" + vocab[k][rnd.Next(vocab[k].Length)]); }
            var ops = new List<string> { "ini=" + string.Join("|", kv), "load" };
            int flightCount = 0;
            for (int m = rnd.Next(8); m > 0; m--) ops.Add(HardOp(rnd, ref flightCount));
            cases.Add(ops);
        }
    }

    /** The campaign flight table's grid path, at its limits (Precision off, or a source that finds nothing). */
    private static void FlightGrid(List<List<string>> cases)
    {
        var rnd = new Random(777);
        int[] gs = { 0, 1, -1, 1023, 1024, 32767, -32768, 500, 12 };
        for (int i = 0; i < 180; i++)
        {
            var ops = new List<string> { StdIni(), "load" };
            if (rnd.Next(2) == 0) ops.Add("coord=" + Pick(rnd, "old1", "old2", "new1", "new2", "new3"));
            if (rnd.Next(3) == 0) ops.Add("terrain=" + rnd.Next(3));
            int wp = Pick(rnd, 3, 4, 24, 25, 3 + rnd.Next(23));
            int count = Pick(rnd, 30, 25, 24, wp, wp - 1, 30);
            int sel = rnd.Next(3);
            ops.Add("flight=" + Pick(rnd, 1, 2, -5) + "," + sel + "," + count);
            if (wp - 1 < count) ops.Add("fwp=" + (wp - 1) + "," + Pick(rnd, gs) + "," + Pick(rnd, gs) + "," + Pick(rnd, gs));
            if (wp - 2 < count && rnd.Next(4) > 0) ops.Add("fwp=" + (wp - 2) + "," + Pick(rnd, gs) + "," + Pick(rnd, gs) + "," + Pick(rnd, gs));
            ops.Add("prec=" + rnd.Next(2));
            ops.Add("src=" + Pick(rnd, "Camp", "TE", "Both", "None", "X", "null"));
            ops.Add("campte=" + rnd.Next(2));
            ops.Add("wp=" + wp);
            ops.Add(Pick(rnd, "getcoords", "stpt", "camptecall", "click=btnCamp", "click=btnTE"));
            ops.Add("click=pnlRefDown");
            int flightCount = count;
            for (int m = rnd.Next(6); m > 0; m--)
            {
                var op = HardOp(rnd, ref flightCount);
                if (op.StartsWith("fwp=") && int.Parse(op.Substring(4).Split(',')[0]) >= flightCount) continue;
                ops.Add(op);
            }
            ops.Add("getcoords");
            cases.Add(ops);
        }
    }

    /** Long chains: 30 to 70 ops of the harder vocabulary, repeats biased, the page hidden and shown. */
    private static void LongChains(List<List<string>> cases, int n)
    {
        var rnd = new Random(99991);
        for (int i = 0; i < n; i++)
        {
            var ops = new List<string>();
            if (rnd.Next(8) == 0) ops.Add("ver=0");
            int flightCount = 0;
            // sometimes something happens before the load
            for (int m = rnd.Next(10) == 0 ? 1 + rnd.Next(3) : 0; m > 0; m--)
            {
                var op = HardOp(rnd, ref flightCount);
                if (op.StartsWith("fwp=")) continue;
                ops.Add(op);
            }
            ops.Add(StdIni());
            ops.Add("load");
            if (rnd.Next(3) > 0) { ops.Add("prec=1"); ops.Add("src=" + Pick(rnd, "Camp", "Camp", "TE", "Both")); }
            if (rnd.Next(2) == 0) for (int k = 0; k < 25; k++) if (rnd.Next(3) > 0) ops.Add(Pick(rnd, "camp", "te") + "=" + k + "," + F((float)(200000 + rnd.NextDouble() * 3000000)) + "," + F((float)(200000 + rnd.NextDouble() * 3000000)) + "," + rnd.Next(5000));
            int len = 30 + rnd.Next(41);
            string last = null;
            for (int k = 0; k < len; k++)
            {
                string op;
                if (last != null && rnd.Next(3) == 0)
                {
                    string kind = last.Split('=')[0];
                    int fc = flightCount; op = HardOp(rnd, ref fc);
                    for (int tries = 0; tries < 60 && op.Split('=')[0] != kind; tries++) { fc = flightCount; op = HardOp(rnd, ref fc); }
                    flightCount = fc;   // only the op that is kept may change what the flight table holds
                }
                else op = HardOp(rnd, ref flightCount);
                if (op.StartsWith("fwp=") && int.Parse(op.Substring(4).Split(',')[0]) >= flightCount) continue;
                ops.Add(op);
                last = op;
            }
            cases.Add(ops);
        }
    }

    

    /** The verifier's direct samples (PopupVerify.cs): harder strings, bearings, distances, edges. */
    private static void MoreGeometry(StringBuilder geo, Control probe)
    {
        var mBearing = type.GetMethod("Bearing");
        var mDistance = type.GetMethod("Distance");
        var mN = type.GetMethod("NewPos_N");
        var mE = type.GetMethod("NewPos_E");
        var mScale = type.GetMethod("ScalePointToMap", Any);
        var mF2R = type.GetMethod("FeetToRad");
        var mR2F = type.GetMethod("RadToFeet");
        var gr = new Random(8675309);
        string FS(float f) => (string)conversions.GetMethod("ToString", new[] { typeof(float) }).Invoke(null, new object[] { f });
        string DS(double d) => (string)conversions.GetMethod("ToString", new[] { typeof(double) }).Invoke(null, new object[] { d });
        float Hard() => gr.Next(12) switch
        {
            0 => 0f,
            1 => -(float)(gr.NextDouble() * 500000),
            2 => (float)(1e7 + gr.NextDouble() * 9e7),
            3 => (float)gr.Next(4000000) + 0.5f,
            4 => (float)(gr.NextDouble() * 5),
            5 => 3358699.5f,
            _ => (float)(gr.NextDouble() * 3400000),
        };
        for (int k = 0; k < 12000; k++)
        {
            float bn = Hard(), be = Hard();
            float en, ee;
            switch (gr.Next(8))
            {
                case 0: en = bn; ee = be; break;                                   // the same point
                case 1: en = bn; ee = be + (float)(gr.NextDouble() * 50000 - 25000); break;   // due east / west
                case 2: en = bn + (float)(gr.NextDouble() * 50000 - 25000); ee = be; break;   // due north / south
                case 3: { float dd = (float)(gr.NextDouble() * 40000 - 20000); en = bn + dd; ee = be + dd; break; }   // 45 degrees
                case 4: en = BitConverter.ToSingle(BitConverter.GetBytes(BitConverter.ToInt32(BitConverter.GetBytes(bn), 0) + 1), 0); ee = be; break;   // one ulp
                default: en = Hard(); ee = Hard(); break;
            }
            // as the page hands them over: a float's VB string, or (the PUP points) a double's
            string sbn = FS(bn), sbe = FS(be), sen, see;
            if (gr.Next(3) == 0) { sen = DS(en + gr.NextDouble()); see = DS(ee - gr.NextDouble()); }
            else { sen = FS(en); see = FS(ee); }
            if (gr.Next(200) == 0) sbn = "0.000000";
            geo.AppendLine(string.Join("\t", "bearing", sbn, sbe, sen, see, "", Try(() => R((float)mBearing.Invoke(probe, new object[] { sbn, sbe, sen, see })))));
            geo.AppendLine(string.Join("\t", "distance", sbn, sbe, sen, see, "", Try(() => I((int)mDistance.Invoke(probe, new object[] { sbn, sbe, sen, see })))));
            float brg = gr.Next(6) switch
            {
                0 => -(float)(gr.NextDouble() * 10),
                1 => (float)(2 * Math.PI + gr.NextDouble() * 10),
                2 => (float)(gr.Next(8) * Math.PI / 4),
                3 => 0f,
                _ => (float)(gr.NextDouble() * 2 * Math.PI),
            };
            int dd2 = gr.Next(8) switch { 0 => 0, 1 => -gr.Next(3000000), 2 => int.MaxValue, 3 => int.MinValue, 4 => 1, _ => gr.Next(5000000) };
            geo.AppendLine(string.Join("\t", "newpos", R(bn), R(be), R(brg), I(dd2), "", Try(() => R((float)mN.Invoke(probe, new object[] { bn, be, brg, dd2 })) + "," + R((float)mE.Invoke(probe, new object[] { bn, be, brg, dd2 })))));
        }
        for (int k = 0; k < 4000; k++)
        {
            double ul = gr.NextDouble() * 3400000, ue = gr.NextDouble() * 3400000;
            double n = gr.Next(6) switch { 0 => 0, 1 => -gr.NextDouble() * 1e6, 2 => 1e12 * gr.NextDouble(), _ => gr.NextDouble() * 3400000 };
            double e = gr.Next(6) switch { 0 => 0, 1 => -gr.NextDouble() * 1e6, 2 => 1e12 * gr.NextDouble(), _ => gr.NextDouble() * 3400000 };
            double s = new[] { 0.0087, 0.00087, 0.000870000012218952, 0.0108750002831221, 0.00108750002831221, 1.0, 435.0 / 50000.0 }[gr.Next(7)];
            geo.AppendLine(string.Join("\t", "scale", D(ul), D(ue), D(n), D(e), D(s), Try(() => { var p = (System.Drawing.Point)mScale.Invoke(probe, new object[] { ul, ue, n, e, s }); return I(p.X) + "," + I(p.Y); })));
            int f = gr.Next(5) switch { 0 => int.MaxValue - gr.Next(10), 1 => int.MinValue + gr.Next(10), 2 => -gr.Next(1000000), _ => gr.Next(4000000) };
            geo.AppendLine(string.Join("\t", "feettorad", I(f), "", "", "", "", Try(() => D((double)mF2R.Invoke(probe, new object[] { f })))));
            double r = gr.Next(6) switch { 0 => -gr.NextDouble(), 1 => 1e3 * gr.NextDouble(), 2 => 0.0, 3 => double.NaN, _ => gr.NextDouble() * 0.5 };
            geo.AppendLine(string.Join("\t", "radtofeet", D(r), "", "", "", "", Try(() => I((int)mR2F.Invoke(probe, new object[] { r })))));
        }
        // clsCoordinates.FeetToCoordsBoth at and beyond the edges, under every preset
        var ct = type.Assembly.GetType("WeaponDeliveryPlanner.clsCoordinates", true);
        var presets = new List<string> { "zero" }; presets.AddRange(Coords.Keys);
        foreach (var name in presets)
        {
            var co = Activator.CreateInstance(ct);
            double campW = 3358699.5, campH = 3358699.5;
            if (name != "zero")
            {
                var cd = Coords[name];
                campW = cd.CampW; campH = cd.CampH;
                ct.GetProperty("FALCON_ORIGIN_LAT").SetValue(co, cd.Lat); ct.GetProperty("FALCON_ORIGIN_LONG").SetValue(co, cd.Lon);
                ct.GetProperty("CampW").SetValue(co, cd.CampW); ct.GetProperty("CampH").SetValue(co, cd.CampH);
                ct.GetProperty("EnableNewTerrain").SetValue(co, cd.NewTerrain); ct.GetProperty("TransverseMercatorMeta").SetValue(co, Meta(cd));
            }
            var m = ct.GetMethod("FeetToCoordsBoth");
            var edges = new[] { 0.0, 0.5, 1.0, -1.0, -0.5, campW, campW - 0.5, campW + 0.5, campH, campH + 1, -3000000, 7000000, 12345678, 1e9 };
            for (int k = 0; k < 2500; k++)
            {
                double fn = (double)(float)(gr.NextDouble() * campH * 1.2 - campH * 0.1), fe = (double)(float)(gr.NextDouble() * campW * 1.2 - campW * 0.1);
                if (gr.Next(4) == 0) fn = edges[gr.Next(edges.Length)];
                if (gr.Next(4) == 0) fe = edges[gr.Next(edges.Length)];
                geo.AppendLine(string.Join("\t", "coords", name, D(fn), D(fe), "", "", Try(() => (string)m.Invoke(co, new object[] { fn, fe }))));
            }
        }
        // fclsMain.ReadNewTerrainElvLoc at its edges
        var mt = main.GetType();
        var mTerr = mt.GetMethod("ReadNewTerrainElvLoc", Any);
        mt.GetField("terraindir", Any).SetValue(main, terrainDir);
        foreach (var camp in new[] { 3358699.5, 2000000.0, 1.0 })
        {
            mt.GetField("CampW", Any).SetValue(main, camp); mt.GetField("CampH", Any).SetValue(main, camp);
            var edges = new[] { 0f, -0f, 1f, -1f, (float)camp, (float)camp + 1f, (float)(camp * 2), -(float)camp, (float)(camp / 65.0), -1e30f, 1e30f, float.NaN };
            for (int k = 0; k < 2000; k++)
            {
                float fn = (float)(gr.NextDouble() * camp * 1.4 - camp * 0.2), fe = (float)(gr.NextDouble() * camp * 1.4 - camp * 0.2);
                if (gr.Next(3) == 0) fn = edges[gr.Next(edges.Length)];
                if (gr.Next(3) == 0) fe = edges[gr.Next(edges.Length)];
                geo.AppendLine(string.Join("\t", "terrain", D(camp), R(fn), R(fe), "", "", Try(() => I((int)mTerr.Invoke(main, new object[] { fn, fe })))));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ more cases
    // The page's life before and around its Load: hidden when the form appears (the Load waits for the page to be
    // shown), shown twice, a Setup.ini that changes before the Load runs or is missing altogether, a key written twice.
    private static void Lifecycle(List<List<string>> cases)
    {
        var rnd = new Random(1789);
        var S = StdIni();
        var hand = new List<string[]>
        {
            new[] { "vis=0", S, "load", "vis=1" },
            new[] { "vis=0", S, "load", "dive=25", "vis=1" },
            new[] { "vis=0", S, "load", "sel=pnlSelections_Up:L", "vis=1" },
            new[] { "vis=0", S, "load", "sel=pnlSelections_Up:L", "sel=pnlSelections_Middle:L", "vis=1", "sel=pnlSelections_Down:R" },
            new[] { "vis=0", S, "load", "zoom=50", "draw=0", "vis=1" },
            new[] { "vis=0", S, "load", "draw=1", "vis=1" },
            new[] { "vis=0", S, "load", "flow=1", "vis=1" },
            new[] { "vis=0", S, "load", "click=pnlRefDown", "click=pnlProfile_Up", "vis=1" },
            new[] { "vis=0", S, "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "getcoords", "vis=1" },
            new[] { "vis=0", S, "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "vis=1", "click=pnlRefDown" },
            new[] { "vis=0", "ini=DiveAngle:20|CAS:500", "load", "ini=DiveAngle:40|CAS:350|ReleaseHeight:7000", "vis=1" },
            new[] { "vis=0", "ini=Turn:5", "load", "vis=1" },
            new[] { "vis=0", "ini=Turn:5", "load", "vis=1", "vis=1", "dive=20" },
            new[] { "vis=0", "ini=Turn:5", "load", "vis=1", "vis=0", "vis=1" },
            new[] { "vis=0", "ini=Turn:5", "load", "ini=Turn:1", "vis=1" },
            new[] { "vis=0", "ini=OA2atAO:2", "load", "vis=1", "oa2=0" },
            new[] { "vis=0", "ini=OA2atAO:2", "load", "vis=1", "vis=0", "vis=1" },
            new[] { "vis=0", "vis=1", S, "load" },
            new[] { "vis=1", "vis=0", "vis=1", S, "load", "dive=35" },
            new[] { "vis=0", "vis=0", S, "load", "vis=0", "vis=1", "vis=1" },
            new[] { S, "load", "load" },
            new[] { S, "load", "vis=0", "load", "vis=1" },
            new[] { "vis=0", S, "load", "load", "vis=1" },
            new[] { S, "load", "dive=20", "load", "dive=25" },
            new[] { "ini=Turn:5", "load", "load" },
            new[] { "ini=Turn:5", "load", "vis=0", "vis=1" },
            new[] { "noini", "load" },
            new[] { "noini", "load", "dive=25", "rel=40", "click=pnlRefDown" },
            new[] { S, "noini", "load", "sel=pnlSelections_Up:L" },
            new[] { "noini", "vis=0", "load", "vis=1" },
            new[] { "ini=DiveAngle:20|DiveAngle:40|CAS:500", "load" },
            new[] { "ini=Waypoint:7|Waypoint:9", "load" },
            new[] { "ini=Ref:True|Ref:False|Turn:0|Turn:1", "load" },
            new[] { "ini=CAS:abc|CAS:500", "load" },
            new[] { "ini=Turn:1|Turn:7", "load" },
            new[] { "ini=Turn:7|Turn:1", "load" },
            new[] { "ini=IngressAlt:&H-1", "load" },
            new[] { "ini=IngressAlt:&H-1", "load", "dive=20", "speed=500" },
            new[] { "ini=CAS:&H 1", "load" }, new[] { "ini=CAS:&H+1", "load" }, new[] { "ini=CAS:&HG", "load" }, new[] { "ini=CAS:&O8", "load" },
            new[] { "ini=CAS:&H0x1C2", "load" }, new[] { "ini=CAS:&HFFFFFFFFFFFFFFFFF", "load" }, new[] { "ini=CAS:&O-1", "load" },
            new[] { "ini=Turn:∞", "load" }, new[] { "ini=CAS:∞", "load" }, new[] { "ini=IngressAlt:-∞", "load" }, new[] { "ini=Turn:NaN", "load" },
            new[] { "ini=Waypoint:&H-1", "load" }, new[] { "ini=Ref:&H-1", "load" }, new[] { "ini=OA2atAO:&H-1", "load" },
            new[] { "ini=Waypoint:\"&HA \"", "load" }, new[] { "ini=Waypoint:&X5", "load" }, new[] { "ini=CAS:&Q1", "load" },
            new[] { "vis=0", "ini=IngressAlt:&H-1", "load", "vis=1", "vis=0", "vis=1" },
            // Save to DTC, with and without a TE mission's cartridge, before and after a plan
            new[] { S, "load", "click=btnSaveDTC" },
            new[] { S, "load", "mdtc=1", "click=btnSaveDTC" },
            new[] { S, "load", "prec=1", "src=Camp", "camp=6,1000000,1000000,300", "camp=5,1030000,1040000,0", "wp=7", "getcoords", "click=pnlRefDown", "click=btnSaveDTC", "mdtc=1", "click=btnSaveDTC" },
            new[] { "click=btnSaveDTC", S, "load" },
            new[] { "ver=0", S, "load", "click=btnSaveDTC" },
        };
        foreach (var h in hand) cases.Add(h.ToList());
        // hidden when the form appears, some life, then shown, then some more
        var mid = new[] { "click=btnSaveDTC", "mdtc=1", "sel=pnlSelections_Up:L", "sel=pnlSelections_Middle:L", "sel=pnlSelections_Down:R", "zoom=40", "draw=0", "draw=1", "flow=1", "flow=0", "click=pnlRefDown", "click=chbShowPPT", "dive=35", "rel=60", "wp=9", "getcoords", "stpt", "camptecall", "oa2=1", "hdg=200" };
        for (int i = 0; i < 120; i++)
        {
            var ops = new List<string>();
            if (rnd.Next(2) == 0) ops.Add("vis=0");
            ops.Add(S); ops.Add("load");
            if (rnd.Next(2) == 0) { ops.Add("prec=1"); ops.Add("src=Camp"); for (int k = 1; k < 25; k++) ops.Add("camp=" + k + "," + F(1000000 + 7000 * k) + "," + F(1200000 - 5000 * k) + "," + (k * 37)); }
            for (int m = rnd.Next(5); m > 0; m--) ops.Add(mid[rnd.Next(mid.Length)]);
            ops.Add(rnd.Next(3) == 0 ? "load" : "vis=" + rnd.Next(2));
            for (int m = rnd.Next(5); m > 0; m--) ops.Add(rnd.Next(3) == 0 ? "vis=" + rnd.Next(2) : mid[rnd.Next(mid.Length)]);
            if (rnd.Next(2) == 0) ops.Add("vis=1");
            cases.Add(ops);
        }
    }

    /** The waypoint box as a user works it: a fraction set or typed, the arrows, text it cannot read. */
    private static void Waypoints(List<List<string>> cases)
    {
        var rnd = new Random(4242);
        var S = StdIni();
        var tg = new List<string> { "prec=1", "src=Camp" };
        for (int i = 1; i < 25; i++) tg.Add("camp=" + i + "," + F(1000000 + 7000 * i) + "," + F(1200000 - 5000 * i) + "," + (i * 37));
        var decs = new[] { "4.5", "5.5", "3.25", "24.5", "3.5", "3", "25", "2", "26", "3.0", "5.50", "12.75", "23.999", "24.0001", "0", "-5", "7.49999", "7.5", "8.5" };
        var texts = new[] { "4.5", " 7 ", "7", "1,0", "abc", "-", "", "30", "2", "4.5e1", "$5", "(5)", "5-", "+6", "12.", ".5", "6.50", "1,2,3", "24.9", "3.1" };
        foreach (var v in decs)
        {
            var ops = new List<string> { S, "load" }; ops.AddRange(tg); ops.Add("wpdec=" + v); ops.Add("click=pnlRefDown"); cases.Add(ops);
            ops = new List<string> { S, "load" }; ops.AddRange(tg); ops.Add("wpdec=" + v); ops.Add("wpup"); ops.Add("click=pnlRefDown"); cases.Add(ops);
            ops = new List<string> { S, "load" }; ops.AddRange(tg); ops.Add("wpdec=" + v); ops.Add("wpdown"); ops.Add("wpdown"); cases.Add(ops);
        }
        foreach (var v in texts)
        {
            var ops = new List<string> { S, "load" }; ops.AddRange(tg); ops.Add("wptext=" + v); ops.Add("click=pnlRefDown"); cases.Add(ops);
            ops = new List<string> { S, "load" }; ops.AddRange(tg); ops.Add("wpdec=4.5"); ops.Add("wptext=" + v); ops.Add("wpup"); cases.Add(ops);
        }
        cases.Add(new List<string> { S, "load", "wpup", "wpup" });
        cases.Add(new List<string> { S, "load", "wp=25", "wpup" });
        cases.Add(new List<string> { S, "load", "wp=3", "wpdown" });
        cases.Add(new List<string> { "wpdec=4.5", S, "load" });
        cases.Add(new List<string> { "wpup", "wpup", S, "load", "wpup" });
        cases.Add(new List<string> { "ini=Waypoint:4.5", "load", "wpup", "wpdown", "wpdown" });
        cases.Add(new List<string> { "ini=Waypoint:23.99999", "load", "wpup", "wpup" });
        cases.Add(new List<string> { "ini=Waypoint:3.00000000000000000000000001", "load", "wpup", "wpdown", "wpdown" });
        for (int i = 0; i < 80; i++)
        {
            var ops = new List<string> { S, "load" };
            if (rnd.Next(3) > 0) ops.AddRange(tg);
            for (int m = 3 + rnd.Next(8); m > 0; m--)
            {
                switch (rnd.Next(7))
                {
                    case 0: ops.Add("wpdec=" + decs[rnd.Next(decs.Length)]); break;
                    case 1: ops.Add("wptext=" + texts[rnd.Next(texts.Length)]); break;
                    case 2: case 3: ops.Add("wpup"); break;
                    case 4: ops.Add("wpdown"); break;
                    case 5: ops.Add("click=" + Pick(rnd, "pnlRefDown", "pnlRefUp", "pnlProfile_Up", "pnlProfile_Down")); break;
                    default: ops.Add("wp=" + (3 + rnd.Next(23))); break;
                }
            }
            cases.Add(ops);
        }
    }

    // ------------------------------------------------------------------------------------------------ the map
    // Draw() paints over a crop of fclsMain's theater bitmap and hands the picture to picSatView. What it draws is
    // recorded as it is drawn — Harmony prefixes on the System.Drawing calls Draw() makes (0Harmony.dll, from the
    // Lib.Harmony package, loaded at run time from POPUP_HARMONY so that this file needs no reference to it) — and
    // what picSatView holds is the list as it stood when the picture was handed over.

    private static readonly List<string> drawLog = new List<string>();
    private static string lastPicture = "";
    private static bool captureOn;
    private static int msgBoxes;
    private static bool OnMsgBox() { msgBoxes++; return false; }
    private static readonly List<string> saveLog = new List<string>();
    private static bool OnProfiles(object __instance) { saveLog.Add("Profiles(" + (string)__instance.GetType().GetField("strProfile", Any).GetValue(__instance) + ")"); return false; }
    private static bool OnSaveCallsign(object[] __args) { saveLog.Add("SaveCallsign_DTC(" + __args[0] + ")"); return false; }
    private static bool OnSaveTE(object[] __args) { saveLog.Add("SaveTE_DTC(" + __args[0] + ")"); return false; }

    private static void InstallCapture()
    {
        var path = Environment.GetEnvironmentVariable("POPUP_HARMONY");
        if (string.IsNullOrEmpty(path) || !File.Exists(path)) { Console.Error.WriteLine("POPUP_HARMONY not set: the map is not captured"); return; }
        var hasm = Assembly.LoadFrom(Path.GetFullPath(path));
        var harmonyType = hasm.GetType("HarmonyLib.Harmony", true);
        var hmType = hasm.GetType("HarmonyLib.HarmonyMethod", true);
        var harmony = Activator.CreateInstance(harmonyType, "wdpref.popup.map");
        var patch = harmonyType.GetMethod("Patch", new[] { typeof(MethodBase), hmType, hmType, hmType, hmType });
        void Pre(MethodBase orig, string mine)
        {
            if (orig == null) throw new Exception("nothing to patch for " + mine);
            var hm = Activator.CreateInstance(hmType, typeof(Popup).GetMethod(mine, BindingFlags.Static | BindingFlags.NonPublic));
            patch.Invoke(harmony, new object[] { orig, hm, null, null, null });
        }
        var g = typeof(Graphics);
        var i4 = new[] { typeof(Pen), typeof(int), typeof(int), typeof(int), typeof(int) };
        Pre(g.GetMethod("DrawLine", i4), nameof(OnLine));
        Pre(g.GetMethod("DrawRectangle", i4), nameof(OnRect));
        Pre(g.GetMethod("DrawEllipse", i4), nameof(OnEllipse));
        Pre(g.GetMethod("DrawPie", new[] { typeof(Pen), typeof(int), typeof(int), typeof(int), typeof(int), typeof(int), typeof(int) }), nameof(OnPie));
        Pre(g.GetMethod("DrawPolygon", new[] { typeof(Pen), typeof(Point[]) }), nameof(OnPolygon));
        Pre(g.GetMethod("DrawString", new[] { typeof(string), typeof(Font), typeof(Brush), typeof(float), typeof(float) }), nameof(OnString));
        Pre(typeof(Bitmap).GetMethod("Clone", new[] { typeof(Rectangle), typeof(PixelFormat) }), nameof(OnClone));
        Pre(typeof(PictureBox).GetProperty("Image").GetSetMethod(), nameof(OnImage));
        // a message box (Setup's for a missing Setup.ini, or any other) is counted and answered at once
        var inter = Type.GetType("Microsoft.VisualBasic.Interaction, Microsoft.VisualBasic, Version=10.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a", true);
        foreach (var m in inter.GetMethods().Where(m => m.Name == "MsgBox")) Pre(m, nameof(OnMsgBox));
        foreach (var m in typeof(MessageBox).GetMethods(BindingFlags.Public | BindingFlags.Static).Where(m => m.Name == "Show")) Pre(m, nameof(OnMsgBox));
        // Save to DTC hands over to the DTC page and the main form, which write the pilot's cartridge: recorded, not run
        Pre(dtc.GetType().GetMethod("Profiles", Any, null, Type.EmptyTypes, null), nameof(OnProfiles));
        Pre(main.GetType().GetMethod("SaveCallsign_DTC", Any), nameof(OnSaveCallsign));
        Pre(main.GetType().GetMethod("SaveTE_DTC", Any), nameof(OnSaveTE));
        captureOn = true;
        Console.WriteLine("map capture installed");
    }

    private static string PenS(object p) { var pen = (Pen)p; return pen.Color.Name + "," + pen.DashStyle; }
    private static void OnLine(object[] __args) => drawLog.Add("L:" + I((int)__args[1]) + "," + I((int)__args[2]) + "," + I((int)__args[3]) + "," + I((int)__args[4]) + "," + PenS(__args[0]));
    private static void OnRect(object[] __args) => drawLog.Add("R:" + I((int)__args[1]) + "," + I((int)__args[2]) + "," + I((int)__args[3]) + "," + I((int)__args[4]) + "," + PenS(__args[0]));
    private static void OnEllipse(object[] __args) => drawLog.Add("E:" + I((int)__args[1]) + "," + I((int)__args[2]) + "," + I((int)__args[3]) + "," + I((int)__args[4]) + "," + PenS(__args[0]));
    private static void OnPie(object[] __args) => drawLog.Add("P:" + I((int)__args[1]) + "," + I((int)__args[2]) + "," + I((int)__args[3]) + "," + I((int)__args[4]) + "," + I((int)__args[5]) + "," + I((int)__args[6]) + "," + PenS(__args[0]));
    private static void OnPolygon(object[] __args) => drawLog.Add("G:" + string.Join(";", ((Point[])__args[1]).Select(p => I(p.X) + "," + I(p.Y))) + "," + PenS(__args[0]));
    private static void OnString(object[] __args)
    {
        var f = (Font)__args[1];
        var fam = f.FontFamily.Equals(FontFamily.GenericSansSerif) ? "GenericSansSerif" : f.FontFamily.Name;
        drawLog.Add("T:" + Clean((string)__args[0]).Replace(";", "\\;") + "@" + R((float)__args[3]) + "," + R((float)__args[4]) + "," + ((SolidBrush)__args[2]).Color.Name + "," + fam + "," + R(f.Size));
    }
    private static void OnClone(object[] __args) { drawLog.Clear(); var r = (Rectangle)__args[0]; drawLog.Add("crop:" + I(r.X) + "," + I(r.Y) + "," + I(r.Width) + "," + I(r.Height)); }
    private static void OnImage(object __instance, object[] __args)
    {
        if (((Control)__instance).Name != "picSatView") return;
        var img = (Image)__args[0];
        lastPicture = img == null ? "<none>" : "size:" + I(img.Width) + "x" + I(img.Height) + ";" + string.Join(";", drawLog);
    }

    // ------------------------------------------------------------------------------------------------ x87 samples
    // How .NET's x86 JIT evaluates Math.Sin/Cos/Tan/Atan inside an expression: the extended-precision result of the
    // x87 instruction, used before it is rounded. Each is an expression of the shape WDP uses, compiled here at run
    // time as an optimised assembly of its own (csc /optimize+): WDP is a release build and its JIT expands these calls
    // into fsin/fcos/fptan/fpatan and keeps the result on the x87 stack, while this harness is usually built for
    // debugging, and a DynamicMethod's calls to Math are not expanded at all — both store the value as a double first,
    // which is the very difference being measured.
    private static Func<double, double, double> XSinMul, XCosMul, XTanMul, XAtanMul, XTanDiv, XAtanSub, XSin2;
    private static Func<double, float> XAtanF;

    private static void MakeX87()
    {
        const string src = @"
using System;
public static class X87 {
    public static double SinMul(double x, double d) { return Math.Sin(x) * d; }
    public static double CosMul(double x, double d) { return Math.Cos(x) * d; }
    public static double TanMul(double x, double d) { return Math.Tan(x) * d; }
    public static double AtanMul(double x, double d) { return Math.Atan(x) * d; }
    public static double TanDiv(double a, double x) { return a / Math.Tan(x); }
    public static double AtanSub(double x, double d) { return Math.Atan(x) - d; }
    public static double Sin(double x, double unused) { return Math.Sin(x); }
    public static float AtanF(double x) { return (float)Math.Atan(x); }
}";
        var provider = new Microsoft.CSharp.CSharpCodeProvider();
        var cp = new System.CodeDom.Compiler.CompilerParameters { GenerateInMemory = true, CompilerOptions = "/optimize+ /platform:x86" };
        var res = provider.CompileAssemblyFromSource(cp, src);
        if (res.Errors.HasErrors) throw new Exception("x87 probe did not compile: " + res.Errors[0]);
        var t = res.CompiledAssembly.GetType("X87");
        Func<double, double, double> F(string n) => (Func<double, double, double>)Delegate.CreateDelegate(typeof(Func<double, double, double>), t.GetMethod(n));
        XSinMul = F("SinMul"); XCosMul = F("CosMul"); XTanMul = F("TanMul"); XAtanMul = F("AtanMul");
        XTanDiv = F("TanDiv"); XAtanSub = F("AtanSub"); XSin2 = F("Sin");
        XAtanF = (Func<double, float>)Delegate.CreateDelegate(typeof(Func<double, float>), t.GetMethod("AtanF"));
    }

    private static double XSin(double x) => XSin2(x, 0);

    private static string H(double d) => BitConverter.DoubleToInt64Bits(d).ToString("X16");
    private static string HF(float f) => BitConverter.ToInt32(BitConverter.GetBytes(f), 0).ToString("X8");

    private static void X87Samples(StringBuilder geo)
    {
        MakeX87();
        var r = new Random(87);
        for (int k = 0; k < 30000; k++)
        {
            double x;
            switch (r.Next(6))
            {
                case 0: x = (double)(float)(r.Next(-8, 9) * Math.PI / 4); break;                    // a float on an axis
                case 1: x = (double)((float)r.Next(10, 56) * 0.01745329f); break;                   // a dive or climb angle
                case 2: x = r.NextDouble() * 20 - 10; break;
                case 3: x = (double)(float)(r.NextDouble() * 2 * Math.PI); break;
                case 4: x = (double)BitConverter.ToSingle(BitConverter.GetBytes(BitConverter.ToInt32(BitConverter.GetBytes((float)(r.Next(1, 5) * Math.PI / 2)), 0) + r.Next(-3, 4)), 0); break;
                default: x = r.NextDouble() * 1.5; break;
            }
            double d = r.Next(4) == 0 ? r.NextDouble() * 1e7 : (double)r.Next(-3000000, 5000000);
            if (r.Next(10) == 0) d = r.Next(2) == 0 ? int.MaxValue : int.MinValue;
            double q = r.Next(3) == 0 ? r.NextDouble() * 50 : r.NextDouble() * 3;
            geo.AppendLine(string.Join("\t", "x87", H(x), H(d), H(q), "", "",
                H(XSinMul(x, d)) + "," + H(XCosMul(x, d)) + "," + H(XTanMul(x, d)) + "," + H(XAtanMul(q, d)) + "," + H(XTanDiv(d, x)) + "," + H(XAtanSub(q, x)) + "," + HF(XAtanF(q)) + "," + H(XSin(x))));
        }
    }

    // ------------------------------------------------------------------------------------------------ direct samples

    private static void Geometry(string path)
    {
        var geo = new StringBuilder();
        geo.AppendLine("kind\ta\tb\tc\td\te\tresult");
        var probe = (Control)Activator.CreateInstance(type);
        var mBearing = type.GetMethod("Bearing");
        var mDistance = type.GetMethod("Distance");
        var mN = type.GetMethod("NewPos_N");
        var mE = type.GetMethod("NewPos_E");
        var mScale = type.GetMethod("ScalePointToMap", Any);
        var mF2R = type.GetMethod("FeetToRad");
        var mR2F = type.GetMethod("RadToFeet");
        var gr = new Random(4242);
        for (int k = 0; k < 20000; k++)
        {
            float bn = (float)(100000 + gr.NextDouble() * 3000000), be = (float)(100000 + gr.NextDouble() * 3000000);
            if (k % 50 == 0) { bn = 12345678f + gr.Next(1000); be = 9999999f; }
            double d = gr.Next(10) == 0 ? gr.NextDouble() * 3 : gr.NextDouble() * 400000;
            double a = gr.NextDouble() * 2 * Math.PI;
            if (gr.Next(20) == 0) a = gr.Next(4) * Math.PI / 2;
            float en = (float)(bn + Math.Cos(a) * d), ee = (float)(be + Math.Sin(a) * d);
            if (gr.Next(50) == 0) en = bn;
            if (gr.Next(50) == 0) ee = be;
            string sbn = bn.ToString(), sbe = be.ToString(), sen = en.ToString(), see = ee.ToString();
            geo.AppendLine(string.Join("\t", "bearing", sbn, sbe, sen, see, "", Try(() => R((float)mBearing.Invoke(probe, new object[] { sbn, sbe, sen, see })))));
            geo.AppendLine(string.Join("\t", "distance", sbn, sbe, sen, see, "", Try(() => I((int)mDistance.Invoke(probe, new object[] { sbn, sbe, sen, see })))));
            float brg = (float)(gr.NextDouble() * 2 * Math.PI);
            int dd = gr.Next(10) == 0 ? gr.Next(3) : gr.Next(2000000);
            geo.AppendLine(string.Join("\t", "newpos", R(bn), R(be), R(brg), I(dd), "", Try(() => R((float)mN.Invoke(probe, new object[] { bn, be, brg, dd })) + "," + R((float)mE.Invoke(probe, new object[] { bn, be, brg, dd })))));
        }
        for (int k = 0; k < 5000; k++)
        {
            double ul = gr.NextDouble() * 3400000, ue = gr.NextDouble() * 3400000, n = gr.Next(20) == 0 ? 0 : gr.NextDouble() * 3400000, e = gr.Next(20) == 0 ? 0 : gr.NextDouble() * 3400000;
            double s = new[] { 0.00870000012218952, 0.0087, 0.00174, 0.0000870000012218952, 0.5 }[gr.Next(5)] * (gr.Next(5) == 0 ? 1000 : 1);
            geo.AppendLine(string.Join("\t", "scale", D(ul), D(ue), D(n), D(e), D(s), Try(() => { var p = (Point)mScale.Invoke(probe, new object[] { ul, ue, n, e, s }); return I(p.X) + "," + I(p.Y); })));
            int f = gr.Next(3000000) - 100000;
            geo.AppendLine(string.Join("\t", "feettorad", I(f), "", "", "", "", Try(() => D((double)mF2R.Invoke(probe, new object[] { f })))));
            double r = gr.NextDouble() * (gr.Next(10) == 0 ? 10 : 0.2);
            geo.AppendLine(string.Join("\t", "radtofeet", D(r), "", "", "", "", Try(() => I((int)mR2F.Invoke(probe, new object[] { r })))));
        }
        // clsCoordinates.FeetToCoordsBoth under every preset (objC, as SetCoordData fills it)
        var ct = type.Assembly.GetType("WeaponDeliveryPlanner.clsCoordinates", true);
        var presets = new List<string> { "zero" }; presets.AddRange(Coords.Keys);
        foreach (var name in presets)
        {
            var co = Activator.CreateInstance(ct);
            if (name != "zero")
            {
                var cd = Coords[name];
                ct.GetProperty("FALCON_ORIGIN_LAT").SetValue(co, cd.Lat); ct.GetProperty("FALCON_ORIGIN_LONG").SetValue(co, cd.Lon);
                ct.GetProperty("CampW").SetValue(co, cd.CampW); ct.GetProperty("CampH").SetValue(co, cd.CampH);
                ct.GetProperty("EnableNewTerrain").SetValue(co, cd.NewTerrain); ct.GetProperty("TransverseMercatorMeta").SetValue(co, Meta(cd));
            }
            var m = ct.GetMethod("FeetToCoordsBoth");
            for (int k = 0; k < 3000; k++)
            {
                double fn = (double)(float)(gr.NextDouble() * 3500000 - 50000), fe = (double)(float)(gr.NextDouble() * 3500000 - 50000);
                if (k % 97 == 0) fn = 0; if (k % 89 == 0) fe = 0;
                if (k % 61 == 0) fn = 3358699.5; if (k % 67 == 0) fe = 3358699.5;
                geo.AppendLine(string.Join("\t", "coords", name, D(fn), D(fe), "", "", Try(() => (string)m.Invoke(co, new object[] { fn, fe }))));
            }
        }
        // fclsMain.ReadNewTerrainElvLoc against the synthetic Heightmap.raw
        var mt = main.GetType();
        var mTerr = mt.GetMethod("ReadNewTerrainElvLoc", Any);
        mt.GetField("terraindir", Any).SetValue(main, terrainDir);
        foreach (var camp in new[] { 3358699.5, 2000000.0 })
        {
            mt.GetField("CampW", Any).SetValue(main, camp); mt.GetField("CampH", Any).SetValue(main, camp);
            for (int k = 0; k < 3000; k++)
            {
                float fn = (float)(gr.NextDouble() * camp * 1.1 - camp * 0.05), fe = (float)(gr.NextDouble() * camp * 1.1 - camp * 0.05);
                geo.AppendLine(string.Join("\t", "terrain", D(camp), R(fn), R(fe), "", "", Try(() => I((int)mTerr.Invoke(main, new object[] { fn, fe })))));
            }
        }
        // VB's own conversions of the strings a Setup.ini can hold
        var vocab = new[] { "", " ", "0", "1", "-1", "2", "5", "3.5", "4.5", "5.0", "5.50", "23.99", "1e1", "1E+2", "5e0", "&HA", "&h1e", "&O17", "&H", "abc", " 30 ", "3,00", "1,000", "300.5", "(300)", "$300", "-$5", "5-", "+5", "True", "False", "true", "FALSE", " True ", "yes", "0.0", "1.5", "0.5", "2.5", "-0.5", ".5", "5.", "1e400", "-1e400", "NaN", "Infinity", "1e-400", "12345678901234567890", "2147483647", "2147483648", "-2147483648.5", "79228162514264337593543950335", "0.0000000000000000000000000001", "1.23456789012345678901234567890", "12.5", "13.5", "457.5", "4.25", "4.35", "6.05", "180.5", "181.5", "&HFFFFFFFF", "&H7FFFFFFF", "&H80000000", "&HFFFFFFFFFFFFFFFF", "1d", "1f", "0x10", "  5", "5 " }
            .Concat(new[] { "", "0", "-0", "+0", "00", "007", "1e308", "1e309", "-1e309", "4.9e-324", "1e-325", "2147483647.5", "2147483646.5", "-2147483648.4999", "-2147483649",
            "&H80000000", "&HFFFFFFFF", "&H7FFFFFFF", "&H100000000", "&hff", "&O777", "&O", "&B101", "&", "&H", "&H-1", " &HA", "&HA ",
            ".", "-", "+", "+-5", "--5", "-+5", "1.5e", "e5", "1e+", "1,5", ",5", "5,", "1,,5", "1,000.5", "1.000,5", "1 000",
            "$", "$5", "5$", "-$5", "$-5", "($5)", "(", "()", "(5", "5)", "(-5)", "5-", "5+", "-5-", "1d", "1D", "1f", "1m",
            " 5", "5 ", "５", "٥", "True", "False", "tRuE", "yes", "no", "on", "#TRUE#", "Nothing", "NaN", "nan", "-NaN", "Infinity", "-Infinity", "INF", "∞",
            "3.00001", "23.99999", "0.0001", "1e-5", "2.5", "3.5", "-2.5", "0.5", "-0.5", "1.5E1", "1.5E+1", "15E-1", "0x1F", "1_0", "1'000",
            "4.05", "4.15", "2.05", "2.15", "2.35", "6.05", "359.5", "360.5", "-0.6", "549.5", "10049", "10050", "99.99" })
            .Concat(new[] { "&X5", "&H0x10", "&H+5", "&H-0", "&O-1", "&H 1", "&HG", "&O8", "&H0x", "&H+", "&H1.5", "&h-a", "&o0x1", "&Q1", "-∞", " ∞ ", "\u00a0NaN\u00a0", "\u3000&H5", "\t&H5", "&HFFFFFFFFFFFFFFFFF", "&HFFFFFFFFFFFFFFFF0", "&O1777777777777777777777", "&O2000000000000000000000", "-0.0", "-0e5", "(0)", "0-", "-1e-400", "-1e-330", "-4.9e-324", "-2e-324", "-1e-320", "-1e-50", "-1e-310" })
            .Distinct().ToArray();
        foreach (var s in vocab)
        {
            string Conv(string name, Type ret) => Try(() =>
            {
                var mm = conversions.GetMethod(name, new[] { typeof(string) });
                var o = mm.Invoke(null, new object[] { s });
                return o is double dv ? DZ(dv) : o is float fv ? RZ(fv) : o is decimal dec ? dec.ToString(CultureInfo.InvariantCulture) : Convert.ToString(o, CultureInfo.InvariantCulture);
            });
            var isn = Try(() => ((bool)versioned.GetMethod("IsNumeric").Invoke(null, new object[] { s })) ? "1" : "0");
            geo.AppendLine(string.Join("\t", "vb", Esc(s), isn, Conv("ToDouble", null), Conv("ToInteger", null), Conv("ToBoolean", null), Conv("ToDecimal", null) + "|" + Conv("ToSingle", null)));
        }
        MoreGeometry(geo, probe);
        X87Samples(geo);
        probe.Dispose();
        File.WriteAllText(path, geo.ToString());
        Console.WriteLine("wrote the direct samples to " + path);
    }

    private static string Esc(string s) => "[" + s.Replace(" ", "\\u00a0").Replace("\t", "\\t") + "]";

    private static string Try(Func<string> f)
    {
        try { return f(); }
        catch (TargetInvocationException e) { return "ERR:" + e.InnerException?.GetType().Name; }
        catch (Exception e) { return "ERR:" + e.GetType().Name; }
    }

    private static string I(int v) => v.ToString(CultureInfo.InvariantCulture);
    private static string R(float v) => v.ToString("R", CultureInfo.InvariantCulture);
    private static string D(double v) => v.ToString("R", CultureInfo.InvariantCulture);
    /** A zero with its sign: .NET Framework prints -0.0 as "0", which would hide which one a conversion gave. */
    private static string DZ(double v) => v == 0 && 1 / v < 0 ? "-0" : D(v);
    private static string RZ(float v) => v == 0 && 1 / v < 0 ? "-0" : R(v);
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

    /** A handler, as a click or a scroll runs it: an exception inside is the user's error dialog, not the end. */
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

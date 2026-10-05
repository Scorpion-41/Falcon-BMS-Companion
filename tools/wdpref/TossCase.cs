using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

// Drives the real TOSS page (cntTOSS) through SEQUENCES, as Hadb.cs drives HADB: the page on a form of its own, off
// screen, as it sits on fclsMain; "load" shows the form (its Load event runs Setup() and the first flow); fclsMain's
// steerpoint tables, flight table and the DataCard's Precision flag are set by the case's own ops, by reflection, so a
// case can put the page on a real mission's steerpoints the way fclsMain puts it there. Toss.cs is the slider grid
// the Kotlin check (--wdptosstest) replays; this one is for end-to-end cases (a save's flight, a target steerpoint, the
// VIP reference) that the app's own harness replays through the page's wiring.
//
// The cases come from TOSS_CASES=<file>, one a line: "op;op;...". Ops:
//   ini=K:V|K:V... ([TOSS]) noini load  ingrspd|ingrh|g|turn|oa2|relang|relspd|rel|aoff|hdg|zoom=<pos> (the slider, then
//   its Scroll)  wp=<n>  click=<control>  sel=<pnlSelections_Up|Middle|Down>:L|R  prec=0|1  src=Camp|TE|Both|None
//   campte=0|1  camp=i,N,E,Z / te=i,N,E,Z  flight=nr,sel,count  fwp=i,gx,gy,gz  getcoords  camptecall  stpt  flow=0|1
// Pictures come from a WDP Pictures folder copied beside the output (TOSS_PICTURES=<folder>), Setup.ini is written into
// the scratch program folder, and Save to DTC / Save Map are never run.
//
//   wdpref page TossCase <WeaponDeliveryPlanner.exe> <out.tsv>
internal static class TossCase
{
    private const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

    internal static readonly string[] Labels = {
        "lblIngrCasVal", "lblIngrHeightVal", "lblGVal", "lblTurnVal", "lblOa2ToPupVal", "lblRelAngleVal",
        "lblRelCasVal", "lblReleaseHeightVal", "lblAngleOff", "lblAttackHdgVal", "lblApproachHedVal",
        "lblTASval", "lblBombrangeVal", "lblBombrangeNmVal", "lblMAPVal", "lblTargetHUDval",
        "lblDEDvip_1", "lblDEDvip_2", "lblDEDpup_1", "lblDEDpup_2", "lblDEDOA1_1", "lblDEDOA2_1",
        "lblVIPwp", "lblVIPbrg", "lblVIPrng", "lblVIPelv", "lblVIPnm",
        "lblPUPwp", "lblPUPbrg", "lblPUPrng", "lblPUPelv", "lblPUPnm",
        "lblOA1wp", "lblOA1brg", "lblOA1rng", "lblOA1elv", "lblOA1nm",
        "lblOA2wp", "lblOA2brg", "lblOA2rng", "lblOA2elv", "lblOA2nm",
        "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E", "lblIP_elv", "lblZoom", "lblVRP",
    };

    internal static readonly string[] Shown = {
        "pnlRefUp", "pnlRefDown", "pnlRef_Up", "pnlRef_Down", "pnlDED_Ref_Up", "pnlDED_Ref_Down",
        "pnlBlocked_1", "pnlBlocked_2", "pnlBlocked_3",
        "lblTGT_N", "lblTGT_E", "lblTGT_elv", "lblIP_N", "lblIP_E", "lblIP_elv",
    };

    internal static readonly string[] Sliders = {
        "trbIngrSpd", "trbIngrHeight", "trbG", "trbTurn", "trbOa2ToPup", "trbReleaseAngle", "trbReleaseSpd",
        "trbReleaseHeight", "trbAngleOff", "trbHeading", "trbZoom",
    };

    private static readonly string[] Offsets = { "VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2" };
    private static readonly string[] OffsetParts = { "Stpt", "Bearing", "Range", "Elv" };
    private static readonly string[] StateBools = { "blnLoadedFlag", "blnGotTGT", "blnDoVIP", "blnRef", "blnCampTE" };

    private static int errors;
    private static Type type, campType, teType, flightType, wpType;
    private static object main, dataCard;
    private static string progDir;

    public static int Run(string exePath, string outPath)
    {
        var dir = Path.GetDirectoryName(Path.GetFullPath(exePath));
        AppDomain.CurrentDomain.AssemblyResolve += (s, e) =>
        {
            var n = new AssemblyName(e.Name).Name;
            foreach (var ext in new[] { ".dll", ".exe" }) { var p = Path.Combine(dir, n + ext); if (File.Exists(p)) return Assembly.LoadFrom(p); }
            return null;
        };
        Thread.CurrentThread.CurrentCulture = new CultureInfo("en-US");
        Thread.CurrentThread.CurrentCulture.NumberFormat.NumberDecimalSeparator = ".";
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (sender, e) => { errors++; Console.Error.WriteLine("window message: " + e.Exception.GetType().Name + " " + e.Exception.Message); };

        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        type = asm.GetType("WeaponDeliveryPlanner.cntTOSS", throwOnError: true);
        var myProject = asm.GetType("WeaponDeliveryPlanner.My.MyProject", throwOnError: true);
        var forms = myProject.GetProperty("Forms", BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public).GetValue(null, null);
        main = forms.GetType().GetProperty("fclsMain", Any).GetValue(forms, null);
        dataCard = Control(main.GetType(), main, "cntDataCard");
        var mt = main.GetType();
        campType = mt.GetField("tblCampSTPT", Any).FieldType.GetElementType();
        teType = mt.GetField("tblMissionSTPT", Any).FieldType.GetElementType();
        flightType = mt.GetField("FlightTable", Any).FieldType.GetElementType();
        wpType = flightType.GetField("waypoints", Any).FieldType.GetElementType();

        progDir = Path.GetFullPath(outPath + ".prog");
        Directory.CreateDirectory(Path.Combine(progDir, "Pictures"));
        var pics = Environment.GetEnvironmentVariable("TOSS_PICTURES");
        if (!string.IsNullOrEmpty(pics) && Directory.Exists(pics))
            foreach (var f in Directory.GetFiles(pics, "TOSS*")) File.Copy(f, Path.Combine(progDir, "Pictures", Path.GetFileName(f)), true);

        var cases = new List<List<string>>();
        foreach (var line in File.ReadAllLines(Environment.GetEnvironmentVariable("TOSS_CASES")))
            if (line.Trim().Length > 0 && !line.StartsWith("#")) cases.Add(line.Trim().Split(';').ToList());

        var head = new List<string> { "case", "ops" };
        head.AddRange(Labels);
        head.Add("lblTargetHUDval.back");
        head.AddRange(Shown.Select(s => "vis_" + s));
        foreach (var s in Sliders) { head.Add("sld_" + s); head.Add("sld_" + s + ".min"); head.Add("sld_" + s + ".max"); }
        head.Add("numWaypoint");
        head.Add("nav_Modesel");
        foreach (var o in Offsets) foreach (var p in OffsetParts) head.Add("nav_" + o + "_" + p);
        head.AddRange(StateBools.Select(s => "st_" + s));
        foreach (var s in new[] { "decOrig_TGT", "decOrig_IP" }) { head.Add("st_" + s + ".X"); head.Add("st_" + s + ".Y"); head.Add("st_" + s + ".Z"); }
        head.Add("errors");

        using (var w = new StreamWriter(outPath, false, new UTF8Encoding(false)))
        {
            w.WriteLine(string.Join("\t", head));
            for (int i = 0; i < cases.Count; i++)
            {
                var row = RunCase(cases[i]);
                w.WriteLine(i.ToString(CultureInfo.InvariantCulture) + "\t" + string.Join(";", cases[i]) + "\t" + string.Join("\t", row));
            }
        }
        Console.WriteLine("wrote " + cases.Count + " TOSS cases to " + outPath);
        return 0;
    }

    private static List<string> RunCase(List<string> ops)
    {
        errors = 0;
        ResetMain();
        var c = (Control)Activator.CreateInstance(type);
        Set(c, "ProgramPath", progDir);
        Set(c, "blnVersion", true);
        var host = new Form { StartPosition = FormStartPosition.Manual, Location = new Point(-32000, -32000), ShowInTaskbar = false, FormBorderStyle = FormBorderStyle.None, Size = new Size(1300, 800) };
        host.Controls.Add(c);
        foreach (var op in ops)
        {
            try { Apply(c, op); }
            catch (Exception e) { errors++; var x = e.InnerException ?? e; Console.Error.WriteLine("op " + op + " escaped: " + x.GetType().Name + " " + x.Message); }
        }
        var row = Snapshot(c);
        try { host.Dispose(); } catch (Exception) { }
        try { c.Dispose(); } catch (Exception) { }
        return row;
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
        mt.GetField("blnLoaded", Any).SetValue(main, false);   // Draw() (the map) returns at once
        var navF = mt.GetField("TOSSNavOffsets", Any);
        navF.SetValue(main, Activator.CreateInstance(navF.FieldType));
        File.WriteAllText(Path.Combine(progDir, "Setup.ini"), "[TOSS]\r\n");
    }

    private static void Apply(Control c, string op)
    {
        int eq = op.IndexOf('=');
        string k = eq < 0 ? op : op.Substring(0, eq), v = eq < 0 ? "" : op.Substring(eq + 1);
        var mt = main.GetType();
        switch (k)
        {
            case "ini":
            {
                var sb = new StringBuilder("[TOSS]\r\n");
                if (v.Length > 0) foreach (var kv in v.Split('|')) { int i = kv.IndexOf(':'); sb.Append(kv.Substring(0, i)).Append('=').Append(kv.Substring(i + 1)).Append("\r\n"); }
                File.WriteAllText(Path.Combine(progDir, "Setup.ini"), sb.ToString());
                break;
            }
            case "noini": File.Delete(Path.Combine(progDir, "Setup.ini")); break;
            case "load": ((Form)c.Parent).Show(); break;
            case "ingrspd": Slide(c, "trbIngrSpd", v, "trbIngrSpd_Scroll"); break;
            case "ingrh": Slide(c, "trbIngrHeight", v, "trbIngrHeight_Scroll"); break;
            case "g": Slide(c, "trbG", v, "trbG_Scroll"); break;
            case "turn": Slide(c, "trbTurn", v, "trbTurn_Scroll"); break;
            case "oa2": Slide(c, "trbOa2ToPup", v, "trbOa2ToPup_Scroll"); break;
            case "relang": Slide(c, "trbReleaseAngle", v, "trbRelAngle_Scroll"); break;
            case "relspd": Slide(c, "trbReleaseSpd", v, "trbRelSpd_Scroll"); break;
            case "rel": Slide(c, "trbReleaseHeight", v, "trbReleaseHeight_Scroll"); break;
            case "aoff": Slide(c, "trbAngleOff", v, "trbAngleOff_Scroll"); break;
            case "hdg": Slide(c, "trbHeading", v, "trbHeading_Scroll"); break;
            case "zoom": Slide(c, "trbZoom", v, "trbZoom_Scroll"); break;
            case "wp":
            {
                var num = (NumericUpDown)Control(type, c, "numWaypoint");
                decimal d = decimal.Parse(v, CultureInfo.InvariantCulture);
                d = Math.Max(num.Minimum, Math.Min(num.Maximum, d));
                num.Value = d;
                break;
            }
            case "click": Call(c, v + "_Click", null, EventArgs.Empty); break;
            case "sel":
            {
                var parts = v.Split(':');
                var e = new MouseEventArgs(parts[1] == "R" ? MouseButtons.Right : MouseButtons.Left, 1, 0, 0, 0);
                Call(c, parts[0] + "_MouseClick", null, e);
                break;
            }
            case "prec": dataCard.GetType().GetField("Precision", Any).SetValue(dataCard, v == "1"); break;
            case "src": Set(c, "strDTC", v == "null" ? null : v); break;
            case "campte": Set(c, "blnCampTE", v == "1"); break;
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
                Call(c, "SetCoordData");
                break;
            }
            case "getcoords": Call(c, "Get_Coords"); break;
            case "camptecall": Call(c, "CampTE"); break;
            case "stpt": Call(c, "STPTChange"); break;
            case "flow": Call(c, "ProgramFlow", v == "1"); break;
            default: throw new Exception("unknown op " + op);
        }
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

    private static void Slide(Control c, string name, string v, string handler)
    {
        var tb = (TrackBar)Control(type, c, name);
        int n = int.Parse(v, CultureInfo.InvariantCulture);
        tb.Value = Math.Max(tb.Minimum, Math.Min(tb.Maximum, n));
        Call(c, handler, null, EventArgs.Empty);
    }

    private static List<string> Snapshot(Control c)
    {
        var t = type;
        var row = new List<string>();
        foreach (var l in Labels) { var lb = Control(t, c, l) as Label; row.Add(lb == null ? "<none>" : Clean(lb.Text)); }
        row.Add(((Label)Control(t, c, "lblTargetHUDval")).BackColor.Name);
        foreach (var s in Shown) { var ctl = Control(t, c, s) as Control; row.Add(ctl == null ? "<none>" : (StateVisible(ctl) ? "shown" : "hidden")); }
        foreach (var s in Sliders) { var tb = (TrackBar)Control(t, c, s); row.Add(I(tb.Value)); row.Add(I(tb.Minimum)); row.Add(I(tb.Maximum)); }
        row.Add(((NumericUpDown)Control(t, c, "numWaypoint")).Value.ToString(CultureInfo.InvariantCulture));
        var nav = main.GetType().GetField("TOSSNavOffsets", Any).GetValue(main);
        row.Add(Convert.ToString(nav.GetType().GetField("Modesel").GetValue(nav), CultureInfo.InvariantCulture));
        foreach (var o in Offsets)
        {
            var off = nav.GetType().GetField(o).GetValue(nav);
            foreach (var p in OffsetParts)
            {
                var v = off.GetType().GetField(p).GetValue(off);
                row.Add(v is float f ? f.ToString("R", CultureInfo.InvariantCulture) : Convert.ToString(v, CultureInfo.InvariantCulture));
            }
        }
        foreach (var s in StateBools) { var f = t.GetField(s, Any); row.Add(f == null ? "<none>" : ((bool)f.GetValue(c) ? "1" : "0")); }
        foreach (var s in new[] { "decOrig_TGT", "decOrig_IP" })
        {
            var f = t.GetField(s, Any);
            var p = f?.GetValue(c);
            foreach (var axis in new[] { "X", "Y", "Z" })
            {
                var af = p?.GetType().GetField(axis);
                row.Add(af == null ? "<none>" : Convert.ToString(af.GetValue(p), CultureInfo.InvariantCulture));
            }
        }
        row.Add(I(errors));
        return row;
    }

    private static bool StateVisible(Control c)
    {
        var m = typeof(Control).GetMethod("GetState", BindingFlags.NonPublic | BindingFlags.Instance);
        return (bool)m.Invoke(c, new object[] { 2 });
    }

    private static string I(int v) => v.ToString(CultureInfo.InvariantCulture);
    private static string Clean(string s) => (s ?? "").Replace("\t", " ").Replace("\r", " ").Replace("\n", " ");

    private static void Set(object o, string field, object value)
    {
        var f = type.GetField(field, Any);
        if (f != null) { f.SetValue(o, value); return; }
        var p = type.GetProperty(field, Any);
        if (p == null) throw new Exception("no field " + field);
        p.SetValue(o, value, null);
    }

    private static void Call(object o, string method, params object[] args)
    {
        var m = type.GetMethod(method, Any);
        if (m == null) throw new Exception("no method " + method);
        m.Invoke(o, args);
    }

    private static object Control(Type t, object o, string name)
    {
        var p = t.GetProperty(name, Any);
        if (p != null) return p.GetValue(o, null);
        return t.GetField("_" + name, Any)?.GetValue(o) ?? t.GetField(name, Any)?.GetValue(o);
    }
}

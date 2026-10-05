using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

// What the real Weapon Delivery Planner does with a pilot's cartridge (UserConfig<callsign>.ini), written down so the
// Kotlin port (DtcIni, DtcCartridge/DtcLoad, DtcSave, DtcPlan) can be checked against the program rather than against a
// reading of it.
//
//   wdpref page Dtc <WeaponDeliveryPlanner.exe> <out.tsv>       (run from WDP's own folder)
//
// Every cartridge here is synthetic, generated from a seed and written into a scratch folder beside <out.tsv>;
// nothing is read from or written to a Falcon BMS install. One TSV, one row per case, the kind in the first column:
//
//   ini   Windows' own profile API (GetPrivateProfileStringW / WritePrivateProfileStringW, the calls clsIni makes)
//         over random small files and one operation: what WDP's reads return and what its writes leave in the file.
//   init  the model a fresh fclsMain holds before any cartridge is read.
//   cart  one cartridge through the real classes, in the order WDP runs them: clsLoadDTC.LoadCallsign over the text
//         (every table and structure it fills is written down: m_*), then random edits to that model (in_mut, set by
//         reflection exactly where WDP's page handlers put them), the page's six "include" boxes and the HARM radio
//         buttons (what SaveCallsign_HARM's ApplyHarm reads), then the twenty-five clsSaveDTC calls of
//         fclsMain.SaveCallsign_DTC, in its order, over the same file: out_text is the whole file afterwards, byte for
//         byte, and s_* the model after it (ApplyHarm writes into it).
//
// The rows are one session: like WDP, a load keeps what the cartridge does not hold (HARM values that are not
// numbers, the second OA2 offset, a steerpoint's target name), so each row starts from the model the previous row
// left, and the Kotlin test replays them in order on one model.
//
// What is set on fclsMain by reflection, and why (nothing is shown):
//   Build, MinorPart                 which BMS the campaign is from (the reader's Modesel, the writer's branches)
//   FALCON_ORIGIN_LAT/LONG, CampW/H, g_bEnableNewTerrain, TransverseMercatorMeta   the theater (lat/lon strings)
//   PPT                              the theater's ppt.ini table (GetSAMName)
//   cntDTC.chbCmdsIncl … chbIntLghtIncl, rbnHAS … rbnTbl0   the page's boxes the writer reads
internal static class Dtc
{
    const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;

    [DllImport("kernel32", CharSet = CharSet.Unicode, EntryPoint = "WritePrivateProfileStringW")]
    static extern int WritePP(string s, string k, string v, string f);
    [DllImport("kernel32", CharSet = CharSet.Unicode, EntryPoint = "GetPrivateProfileStringW")]
    static extern int GetPP(string s, string k, string d, StringBuilder r, int n, string f);

    static Random rng;
    static int R(int lo, int hi) => rng.Next(lo, hi + 1);
    static bool P(double p) => rng.NextDouble() < p;
    static T Pick<T>(params T[] a) => a[rng.Next(a.Length)];

    /** TSV-safe: backslash escapes for the four characters that would break a row; null is \0. */
    internal static string Esc(string s)
    {
        if (s == null) return "\\0";
        var sb = new StringBuilder(s.Length + 8);
        foreach (var c in s)
        {
            switch (c)
            {
                case '\\': sb.Append("\\\\"); break;
                case '\r': sb.Append("\\r"); break;
                case '\n': sb.Append("\\n"); break;
                case '\t': sb.Append("\\t"); break;
                default: sb.Append(c); break;
            }
        }
        return sb.ToString();
    }

    static readonly List<string> Columns = new List<string>();
    static readonly HashSet<string> ColumnSet = new HashSet<string>();
    static readonly List<Dictionary<string, string>> Rows = new List<Dictionary<string, string>>();

    static void Row(Dictionary<string, string> r)
    {
        foreach (var k in r.Keys) if (ColumnSet.Add(k)) Columns.Add(k);
        Rows.Add(r);
    }

    public static int Run(string exePath, string outPath)
    {
        Thread.CurrentThread.CurrentCulture = new CultureInfo("en-US");
        Thread.CurrentThread.CurrentUICulture = new CultureInfo("en-US");
        var scratch = Path.GetFullPath(outPath + ".files");
        Directory.CreateDirectory(scratch);
        ColumnSet.Add("kind"); Columns.Add("kind");

        IniRows(Path.Combine(scratch, "ini.ini"));
        MaskRows();
        Setup(exePath);
        var init = new Dictionary<string, string> { ["kind"] = "init" };
        DumpModel(init, "m_");
        Row(init);
        PageControls();
        PageSetup();
        CartRows(Path.Combine(scratch, "UserConfigSynthetic.ini"));

        var sb = new StringBuilder();
        sb.Append(string.Join("\t", Columns)).Append("\r\n");
        foreach (var r in Rows)
        {
            for (int i = 0; i < Columns.Count; i++)
            {
                if (i > 0) sb.Append('\t');
                if (r.TryGetValue(Columns[i], out var v)) sb.Append(v);
            }
            sb.Append("\r\n");
        }
        File.WriteAllText(outPath, sb.ToString(), new UTF8Encoding(false));
        Console.WriteLine("wrote " + Rows.Count + " rows, " + Columns.Count + " columns to " + outPath);
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ ini

    static string RandomIni()
    {
        string[] secs = { "S", "T", "STPT", "ews", " S ", "s", "EWS", "Radio" };
        string[] keys = { "a", "b", "B", "b x", "k1", "k2", "target_0", " b " };
        string Eol()
        {
            double x = rng.NextDouble();
            return x < 0.85 ? "\r\n" : x < 0.95 ? "\n" : "\r";
        }
        var sb = new StringBuilder();
        int n = R(0, 14);
        for (int i = 0; i < n; i++)
        {
            double x = rng.NextDouble();
            string line;
            if (x < 0.22) line = (P(0.1) ? "  " : "") + "[" + Pick(secs) + "]" + (P(0.05) ? " junk" : "");
            else if (x < 0.60) line = (P(0.1) ? Pick(" ", "\t", "  ") : "") + Pick(keys) + (P(0.2) ? Pick(" ", "\t ") : "") + "=" + (P(0.2) ? Pick(" ", "\t") : "") + Pick("1", "2", "x y", "", "\"q\"", "'s'", "v;c", "  ") + (P(0.15) ? Pick(" ", "\t") : "");
            else if (x < 0.72) line = "";
            else if (x < 0.76) line = Pick(" ", "\t", "  ");
            else if (x < 0.86) line = (P(0.2) ? "  " : "") + ";" + Pick("c", "a=1", "b=2", "");
            else if (x < 0.93) line = Pick("noeq", "#c", "b", "x y");
            else line = Pick("#c=1", "=v", "[S", "[T");
            sb.Append(line);
            if (i < n - 1 || P(0.85)) sb.Append(Eol());
            else if (P(0.2)) sb.Append(Pick(" ", "  ", "\t"));
        }
        return sb.ToString();
    }

    static void IniRows(string f)
    {
        string[] secs = { "S", "T", "STPT", "s", "EWS", "Radio", "NEW" };
        string[] keys = { "a", "b", "B", "b x", "k1", "k2", "target_0", "new" };
        for (int row = 0; row < 6000; row++)
        {
            rng = new Random(1000 + row * 31);
            var start = RandomIni();
            File.WriteAllText(f, start, Encoding.Default);
            var r = new Dictionary<string, string> { ["kind"] = "ini", ["in_text"] = Esc(start) };
            var sec = Pick(secs);
            var key = Pick(keys);
            string op;
            double x = rng.NextDouble();
            if (x < 0.30)
            {
                op = "R";
                var b = new StringBuilder(1024);
                int got = GetPP(sec, key, "", b, 1024, f);
                r["out_text"] = Esc(b.ToString(0, got));
            }
            else
            {
                string val = null;
                if (x < 0.75) { op = "W"; val = Pick("9", "new value", "", "0.000000", "a=b", " sp "); WritePP(sec, key, val, f); }
                else if (x < 0.90) { op = "DK"; WritePP(sec, key, null, f); }
                else { op = "DS"; WritePP(sec, null, null, f); }
                r["in_val"] = Esc(val);
                r["out_text"] = Esc(File.ReadAllText(f, Encoding.Default));
            }
            r["in_op"] = op;
            r["in_sec"] = Esc(sec);
            r["in_key"] = Esc(key);
            // Out of scope: a tiny file Windows guesses is UTF-16 (IsTextUnicode, e.g. the three bytes "x y") and then
            // appends to in UTF-16. A cartridge BMS or WDP writes never looks like that, and the port does not model
            // the guess; such cases are counted and left out rather than compared.
            if (r["out_text"].IndexOf((char)0) >= 0) { unicodeGuesses++; continue; }
            Row(r);
        }
        Console.WriteLine("ini: left out " + unicodeGuesses + " file(s) Windows took for UTF-16");
    }
    static int unicodeGuesses;

    // ------------------------------------------------------------------------------------------------ the program

    static Assembly asm;
    static object main, dtc;
    static Type mainT, tmType;

    static object Get(object o, string name)
    {
        var t = o.GetType();
        var f = t.GetField(name, Any);
        if (f != null) return f.GetValue(o);
        var p = t.GetProperty(name, Any);
        return p.GetValue(o, null);
    }

    static void Set(object o, string name, object v)
    {
        var t = o.GetType();
        var f = t.GetField(name, Any);
        if (f != null) { f.SetValue(o, v); return; }
        t.GetProperty(name, Any).SetValue(o, v, null);
    }

    static void Setup(string exePath)
    {
        var full = Path.GetFullPath(exePath);
        asm = Assembly.LoadFrom(full);
        var myProject = asm.GetType("WeaponDeliveryPlanner.My.MyProject", true);
        var forms = myProject.GetProperty("Forms", Any).GetValue(null, null);
        main = forms.GetType().GetProperty("fclsMain", Any).GetValue(forms, null);
        mainT = main.GetType();
        dtc = Get(main, "cntDTC");
        tmType = mainT.GetField("TransverseMercatorMeta", Any).FieldType;
        Set(main, "blnVersion", true);
        try { Set(dtc, "blnVersion", true); } catch { }
    }

    // the theaters: the same presets as the Pop-up harness (and the Kotlin test's table)
    sealed class Coord
    {
        public double Lat, Lon, CampW = 3358699.5, CampH = 3358699.5;
        public bool NewTerrain;
        public double Meridian, OffsetX, OffsetY;
        public uint Size;
        public float HeightmapSize, MeterRes, FtToGrid, GridToFt, GridOffset;
    }

    static readonly Dictionary<string, Coord> Coords = new Dictionary<string, Coord>
    {
        ["none"] = new Coord { CampW = 0, CampH = 0 },
        ["old1"] = new Coord { Lat = 34.0, Lon = 124.0 },
        ["old2"] = new Coord { Lat = -53.5, Lon = -62.0, CampW = 2000000.0, CampH = 2500000.0 },
        ["new1"] = new Coord { NewTerrain = true, Meridian = 127.5, OffsetX = -512000.0, OffsetY = 3700000.0, Size = 1024000u, HeightmapSize = 16384f, MeterRes = 62.5f, FtToGrid = 1f / (62.5f * 3.27998f), GridToFt = 62.5f * 3.27998f, GridOffset = 8192f },
        ["new2"] = new Coord { NewTerrain = true, Meridian = 22.0, OffsetX = -400000.0, OffsetY = 4300000.0, Size = 1024000u, HeightmapSize = 1024f, MeterRes = 1000f, FtToGrid = 1f / 3279.98f, GridToFt = 3279.98f, GridOffset = 512f },
    };

    static void SetTheater(Coord cd)
    {
        Set(main, "FALCON_ORIGIN_LAT", cd.Lat);
        Set(main, "FALCON_ORIGIN_LONG", cd.Lon);
        Set(main, "CampW", cd.CampW);
        Set(main, "CampH", cd.CampH);
        Set(main, "g_bEnableNewTerrain", cd.NewTerrain);
        object tm = Activator.CreateInstance(tmType);
        void S(string n, object val) { var f = tmType.GetField(n); f.SetValue(tm, Convert.ChangeType(val, f.FieldType, CultureInfo.InvariantCulture)); }
        S("Meridian", cd.Meridian); S("offsetX", cd.OffsetX); S("offsetY", cd.OffsetY); S("theaterSizeInMeters", cd.Size);
        S("HEIGHTMAP_SIZE", cd.HeightmapSize); S("METER_RES", cd.MeterRes); S("FT_TO_GRID", cd.FtToGrid); S("GRID_TO_FT", cd.GridToFt); S("GRID_OFFSET", cd.GridOffset);
        Set(main, "TransverseMercatorMeta", tm);
    }

    static void SetPpt(List<KeyValuePair<string, string>> table)
    {
        var ft = mainT.GetField("PPT", Any);
        var et = ft.FieldType.GetElementType();
        var arr = Array.CreateInstance(et, table.Count);
        for (int i = 0; i < table.Count; i++)
        {
            var e = Activator.CreateInstance(et);
            et.GetField("Code").SetValue(e, table[i].Key);
            et.GetField("Name").SetValue(e, table[i].Value);
            et.GetField("Range").SetValue(e, 10.0);
            arr.SetValue(e, i);
        }
        ft.SetValue(main, arr);
    }

    // ------------------------------------------------------------------------------------------------ dumps

    static string F(float f) => float.IsNaN(f) ? "NaN" : BitConverter.ToInt32(BitConverter.GetBytes(f), 0).ToString("x8");
    static string V(object v)
    {
        switch (v)
        {
            case null: return "\\0";
            case float f: return F(f);
            case string s: return Esc(s);
            case bool b: return b ? "1" : "0";
            case Enum e: return Convert.ToInt64(e).ToString(CultureInfo.InvariantCulture);
            default: return Convert.ToString(v, CultureInfo.InvariantCulture);
        }
    }

    /** Named fields of a boxed struct, in this order, joined with "|". */
    static string Fields(object o, params string[] names)
    {
        var sb = new StringBuilder();
        var t = o.GetType();
        for (int i = 0; i < names.Length; i++)
        {
            if (i > 0) sb.Append('|');
            sb.Append(V(t.GetField(names[i]).GetValue(o)));
        }
        return sb.ToString();
    }

    static string ArrayOf(object arr, Func<object, string> each)
    {
        if (arr == null) return "null";
        var sb = new StringBuilder();
        var a = (Array)arr;
        sb.Append(a.Length).Append(':');
        for (int i = 0; i < a.Length; i++)
        {
            if (i > 0) sb.Append(';');
            sb.Append(each(a.GetValue(i)));
        }
        return sb.ToString();
    }

    static readonly string[] StptF = { "FalconX", "FalconY", "FalconZ", "North", "East", "Action", "Target" };
    static readonly string[] TgtF = { "FalconX", "FalconY", "FalconZ", "North", "East", "Action", "Target" };
    static readonly string[] PptF = { "Name", "FalconX", "FalconY", "FalconZ", "FalconRNG", "Code", "North", "East" };
    static readonly string[] LineF = { "FalconX", "FalconY", "FalconZ", "North", "East" };
    static readonly string[] ProgF = { "ChaffBQ", "ChaffBI", "ChaffSQ", "ChaffSI", "FlareBQ", "FlareBI", "FlareSQ", "FlareSI", "Comment" };
    static readonly string[] MfdF = { "Left", "Center", "Right", "Csel" };
    static readonly string[] OffF = { "Stpt", "Bearing", "Range", "Elv" };
    static readonly string[] TimeF = { "Mode1_Code", "Mode3A_Code", "Mode4_Key", "TimeCriteria" };
    static readonly string[] PosF = { "Mode1", "Mode2", "Mode3A", "Mode4", "ModeC", "ModeS", "WayPoint", "Direction" };
    static readonly string[] ThrF = { "Threat_0", "Threat_1", "Threat_2", "Threat_3", "Threat_4" };
    static readonly string[] BombF = { "Submode", "Fuze", "SGL_PAIR", "Release_Spacing", "Release_Pulse", "Release_Angle", "C1_AD1", "C1_AD2", "C2_AD", "C2_BA" };

    static void DumpModel(Dictionary<string, string> r, string p)
    {
        r[p + "stpt"] = ArrayOf(Get(main, "tblCampSTPT"), e => Fields(e, StptF));
        r[p + "tgt"] = ArrayOf(Get(main, "tblCampTgt"), e => Fields(e, TgtF));
        r[p + "ppt"] = ArrayOf(Get(main, "tblCampPPT"), e => Fields(e, PptF));
        r[p + "line"] = ArrayOf(Get(main, "tblCampLine"), e => Fields(e, LineF));
        r[p + "open"] = ArrayOf(Get(main, "tblCampOpen"), e => Fields(e, StptF));
        r[p + "hpn"] = ArrayOf(Get(main, "tblCampHpn"), e => Fields(e, StptF));
        var ews = Get(main, "CampEWS");
        r[p + "ews"] = Fields(ews, "Reqjam", "Reqctr", "Bingo", "Fdbk", "FlareBingo", "ChaffBingo", "ModeSelection", "NumberSelection")
            + "#" + ArrayOf(Get(ews, "Program"), e => Fields(e, ProgF));
        var mfd = Get(main, "CampMFD");
        r[p + "mfd"] = string.Join("#", new[] { "A_G", "A_A", "NAV", "MSL", "DGF", "S_J" }.Select(n => ArrayOf(Get(mfd, n), e => Fields(e, MfdF))));
        var radio = Get(main, "CampRadio");
        r[p + "radio"] = string.Join("#", new[] { "UHF", "VHF", "UHFcomment", "VHFcomment" }.Select(n => ArrayOf(Get(radio, n), V)));
        r[p + "comm"] = Fields(Get(main, "CampComm"), "Comm1", "Comm2", "TACANChannel", "ILSFrequency", "ILSCRS", "TACANBand", "TACANDomain", "Comm1_Comment", "Comm2_Comment");
        var nav = Get(main, "CampNavOffsets");
        r[p + "nav"] = V(Get(nav, "Modesel")) + "#" + string.Join("#", new[] { "VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2" }.Select(n => Fields(Get(nav, n), OffF)));
        r[p + "hud"] = Fields(Get(main, "CampHud"), "Color", "Scales", "Brightness", "FPM", "DED", "Velocity", "Alt", "SymWheelPos");
        r[p + "icp"] = Fields(Get(main, "CampICP"), "MasterMode", "AlowAGL", "AlowMSL", "AlowTFAdv", "ManualWingspan", "BingoFuel");
        var iff = Get(main, "CampIff");
        r[p + "iff"] = Fields(iff, "Mode1_On", "Mode2_On", "Mode3A_On", "Mode4_On", "ModeC_On", "ModeS_On", "Mode1_Code", "Mode2_Code", "Mode3A_Code", "Mode4_Key", "AutoChange")
            + "#" + ArrayOf(Get(iff, "timeSettings"), e => Fields(e, TimeF)) + "#" + ArrayOf(Get(iff, "posSettings"), e => Fields(e, PosF));
        r[p + "misc"] = string.Join("|", V(Get(Get(main, "CampBullsEye"), "BullseyeInfoOnMFD")), V(Get(Get(main, "CampView"), "WideView")),
            V(Get(Get(main, "CampOTW"), "Mode")), V(Get(Get(main, "CampWeapons"), "MasterArm")), V(Get(Get(main, "CampSnsr"), "Ralt")),
            V(Get(Get(main, "CampLightning"), "DED")));
        var harm = Get(main, "CampHarm");
        r[p + "harm"] = ArrayOf(Get(harm, "Table"), e => Fields(e, ThrF)) + "#" + Fields(harm, "Mode", "SubMode", "TER");
        r[p + "laser"] = Fields(Get(main, "CampLaser"), "LaserST", "LaserCode", "LSTCode");
        var agb = Get(main, "CampAGB");
        r[p + "fcc"] = Fields(Get(main, "CampAIM"), "SpotScan", "TD_BP", "TargetSize") + "#" + Fields(Get(main, "CampAGM"), "Mav_AutoPwr", "Mav_AutoPwrDir", "Mav_AutoPwrWpt")
            + "#" + Fields(Get(agb, "Profile1"), BombF) + "#" + Fields(Get(agb, "Profile2"), BombF);
        r[p + "incl"] = string.Join("", new[] { "chbCmdsIncl", "chbHudIncl", "chbViewsIncl", "chbMasterArmIncl", "chbSnsrPowerIncl", "chbIntLghtIncl" }
            .Select(n => ((System.Windows.Forms.CheckBox)Get(dtc, n)).CheckState == System.Windows.Forms.CheckState.Checked ? "1" : "0"));
    }

    static IEnumerable<TOut> Select<TIn, TOut>(this IEnumerable<TIn> e, Func<TIn, TOut> f) { foreach (var x in e) yield return f(x); }

    // ------------------------------------------------------------------------------------------------ edits by path

    /** Sets "A.B.3.C" on fclsMain: fields and array elements, writing boxed structs back on the way out. */
    static bool SetPath(string path, object value)
    {
        var parts = path.Split('.');
        try { Set(main, parts[0], SetIn(Get(main, parts[0]), parts, 1, value)); return true; }
        catch (NullReferenceException) { return false; }   // a table the program does not hold (null): no such edit
        catch (IndexOutOfRangeException) { return false; }
    }

    static object SetIn(object container, string[] parts, int i, object value)
    {
        if (i == parts.Length) return value;
        if (container is Array arr)
        {
            int idx = int.Parse(parts[i], CultureInfo.InvariantCulture);
            arr.SetValue(SetIn(arr.GetValue(idx), parts, i + 1, value), idx);
            return arr;
        }
        var f = container.GetType().GetField(parts[i], Any);
        object inner = f.GetValue(container);
        object nv = i + 1 == parts.Length ? ConvertTo(value, f.FieldType) : SetIn(inner, parts, i + 1, value);
        f.SetValue(container, nv);
        return container;
    }

    static object ConvertTo(object v, Type t)
    {
        if (v == null) return null;
        if (t.IsEnum) return Enum.ToObject(t, v);
        return System.Convert.ChangeType(v, t, CultureInfo.InvariantCulture);
    }

    // ------------------------------------------------------------------------------------------------ masks
    // What a MaskedTextBox's Text reads after Text is set, for every mask the DTC page uses (and the COMM dialog's).

    static readonly string[][] MaskKinds = {
        new[] { "0", "", "" }, new[] { "00", "", "" }, new[] { "000", "", "" }, new[] { "0000", "", "" }, new[] { "00000", "", "" },
        new[] { "0.000", "", "" }, new[] { "100.00", "", "" }, new[] { "000.000", "", "" },
        new[] { "00.00", "0", "IncludePromptAndLiterals" }, new[] { "0000", "0", "IncludePromptAndLiterals" },
    };

    static readonly string[] MaskInputs = {
        "", "0", "5", "9", "10", "42", "99", "100", "300", "1000", "1688", "2888", "12345", "123456", "0000", "00", "05",
        "0.5", "1.5", "2.500", "3.865", "12.345", "0.125", "1,000", "-1", "-5", " 7", "7 ", " ", "abc", "1a2", "1.2.3",
        ".5", "5.", "05.50", "10.00", "99.99", "100.5", "109.00", "110.30", "0900", "251.000", "251", "2510", "30.0",
        "3.0", "0.000", "1E3", "NaN", "65536", "1.23456", "12.3", "123.4", "1234.5", "00.00", "0012", "999999",
    };

    static void MaskRows()
    {
        foreach (var mk in MaskKinds)
        {
            foreach (var input in MaskInputs)
            {
                var box = new System.Windows.Forms.MaskedTextBox();
                box.Culture = new CultureInfo("en-US");
                box.Mask = mk[0];
                if (mk[1] != "") box.PromptChar = mk[1][0];
                if (mk[2] != "") box.TextMaskFormat = (System.Windows.Forms.MaskFormat)Enum.Parse(typeof(System.Windows.Forms.MaskFormat), mk[2]);
                box.Text = input;
                var r = new Dictionary<string, string> { ["kind"] = "mask", ["in_mask"] = Esc(mk[0]), ["in_prompt"] = Esc(mk[1]), ["in_fmt"] = mk[2], ["in_text"] = Esc(input) };
                r["out_text"] = Esc(box.Text);
                Row(r);
                box.Dispose();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the page
    // The DTC page itself (cntDTC): its Load, the cartridge read through its own GetCampFile, the pilot's edits through
    // its own handlers, and every control's state written down. What the page hands to other pages (the DataCard, the
    // attack pages, the main form's map) is recorded as not run: Harmony prefixes skip those calls, and message boxes
    // are answered at once.

    static object harmony; static MethodInfo patchM; static Type hmType;
    static void Pre(MethodBase orig, string mine)
    {
        if (orig == null) throw new Exception("nothing to patch for " + mine);
        var hm = Activator.CreateInstance(hmType, typeof(Dtc).GetMethod(mine, BindingFlags.Static | BindingFlags.NonPublic));
        patchM.Invoke(harmony, new object[] { orig, hm, null, null, null });
    }
    static void Post(MethodBase orig, string mine)
    {
        var hm = Activator.CreateInstance(hmType, typeof(Dtc).GetMethod(mine, BindingFlags.Static | BindingFlags.NonPublic));
        patchM.Invoke(harmony, new object[] { orig, null, hm, null, null });
    }

    static int msgBoxes;
    static bool OnMsgBox() { msgBoxes++; return false; }
    static bool Skip() => false;
    static string pendingFreq = "";
    static bool OnShowDialog(System.Windows.Forms.Form __instance, ref System.Windows.Forms.DialogResult __result)
    {
        if (__instance.GetType().Name != "fclsChangeCOMM") return true;
        int nr = (int)Get(dtc, "intCommNr");
        var box = new System.Windows.Forms.MaskedTextBox { Culture = new CultureInfo("en-US"), Mask = "000.000" };
        box.Text = pendingFreq;
        if (nr < 25) Set(dtc, "strUHF", box.Text); else if (nr > 25) Set(dtc, "strVHF", box.Text);
        __instance.DialogResult = System.Windows.Forms.DialogResult.OK;
        __result = System.Windows.Forms.DialogResult.OK;
        return false;
    }
    static Dictionary<string, string> curRow;
    static void OnLoadCallsign(object __result, ref int LoadError)
    {
        if (curRow == null) return;
        curRow["m_loaded"] = V(__result);
        curRow["m_err"] = LoadError.ToString(CultureInfo.InvariantCulture);
        DumpModel(curRow, "m_");
    }

    static void InstallHarmony()
    {
        var path = Environment.GetEnvironmentVariable("WDPREF_HARMONY") ?? Environment.GetEnvironmentVariable("POPUP_HARMONY");
        if (string.IsNullOrEmpty(path) || !File.Exists(path)) throw new Exception("WDPREF_HARMONY must name 0Harmony.dll (Lib.Harmony, lib/net472)");
        var hasm = Assembly.LoadFrom(Path.GetFullPath(path));
        var harmonyType = hasm.GetType("HarmonyLib.Harmony", true);
        hmType = hasm.GetType("HarmonyLib.HarmonyMethod", true);
        harmony = Activator.CreateInstance(harmonyType, "wdpref.dtc");
        patchM = harmonyType.GetMethod("Patch", new[] { typeof(MethodBase), hmType, hmType, hmType, hmType });
        var inter = Type.GetType("Microsoft.VisualBasic.Interaction, Microsoft.VisualBasic, Version=10.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a", true);
        foreach (var m in inter.GetMethods()) if (m.Name == "MsgBox") Pre(m, nameof(OnMsgBox));
        foreach (var m in typeof(System.Windows.Forms.MessageBox).GetMethods(BindingFlags.Public | BindingFlags.Static)) if (m.Name == "Show") Pre(m, nameof(OnMsgBox));
        Pre(typeof(System.Windows.Forms.Form).GetMethod("ShowDialog", Type.EmptyTypes), nameof(OnShowDialog));
        // what the page hands to other pages: not run
        object card = Get(main, "cntDataCard");
        foreach (var n in new[] { "FillDataCard", "FillCommCard", "CheckCallsignSaved", "CheckMissionSaved", "FillArrRWY", "FillAltnRWY" })
            Pre(card.GetType().GetMethod(n, Any), nameof(Skip));
        Pre(mainT.GetMethod("Check_Saved_Status", Any, null, Type.EmptyTypes, null), nameof(Skip));
        foreach (var m in mainT.GetMethods(Any)) if (m.Name == "Draw" && m.DeclaringType == mainT) Pre(m, nameof(Skip));
        foreach (var page in new[] { "cntPopUp", "cntHADB", "cntTOSS" })
        {
            object p = Get(main, page);
            Pre(p.GetType().GetMethod("CampTE", Any), nameof(Skip));
            Pre(p.GetType().GetMethod("Get_Coords", Any), nameof(Skip));
        }
        object perf = Get(main, "cntPerformance");
        foreach (var m in perf.GetType().GetMethods(Any)) if (m.Name == "Database" && m.DeclaringType == perf.GetType()) Pre(m, nameof(Skip));
        Post(asm.GetType("WeaponDeliveryPlanner.clsLoadDTC", true).GetMethod("LoadCallsign"), nameof(OnLoadCallsign));
    }

    static MethodInfo getState = typeof(System.Windows.Forms.Control).GetMethod("GetState", Any, null, new[] { typeof(int) }, null);
    static bool OwnState(System.Windows.Forms.Control c, int bit) => (bool)getState.Invoke(c, new object[] { bit });
    static void Raise(System.Windows.Forms.Control c, string on)
    {
        var m = c.GetType().GetMethod(on, Any, null, new[] { typeof(EventArgs) }, null);
        m.Invoke(c, new object[] { EventArgs.Empty });
    }

    static string Argb(System.Drawing.Color c) => c.ToArgb().ToString("x8");

    /** Every control's state, in designer order: name.P=value (P: T text, V visible, E enabled, F fore, B back, C check, I index, L items, N value, G grid). */
    static List<KeyValuePair<string, string>> PageDump()
    {
        var list = new List<KeyValuePair<string, string>>();
        void Add(string k, string v) => list.Add(new KeyValuePair<string, string>(k, v));
        if (allControls == null)
        {
            // fixed once, in designer order: the page reorders its own collections (BringToFront) as it runs
            allControls = new List<System.Windows.Forms.Control>();
            void Collect(System.Windows.Forms.Control parent) { foreach (System.Windows.Forms.Control k in parent.Controls) { allControls.Add(k); Collect(k); } }
            Collect((System.Windows.Forms.Control)dtc);
        }
        foreach (var c in allControls)
        {
                var n = c.Name;
                if (!string.IsNullOrEmpty(n))
                {
                    if (c is System.Windows.Forms.NumericUpDown nud) Add(n + ".N", nud.Value.ToString(CultureInfo.InvariantCulture));
                    else if (c is System.Windows.Forms.DataGridView g)
                    {
                        var sb = new StringBuilder();
                        foreach (System.Windows.Forms.DataGridViewRow row in g.Rows)
                        {
                            if (row.IsNewRow) continue;
                            if (sb.Length > 0) sb.Append('\u0002');
                            for (int i = 0; i < row.Cells.Count; i++) { if (i > 0) sb.Append('\u0003'); sb.Append(Esc(row.Cells[i].Value == null ? "\u0004" : Convert.ToString(row.Cells[i].Value, CultureInfo.CurrentCulture))); }
                        }
                        Add(n + ".G", sb.ToString());
                    }
                    else if (!(c is System.Windows.Forms.TabControl)) Add(n + ".T", Esc(c.Text));
                    Add(n + ".V", OwnState(c, 0x2) ? "1" : "0");
                    Add(n + ".E", OwnState(c, 0x4) ? "1" : "0");
                    Add(n + ".F", Argb(c.ForeColor));
                    Add(n + ".B", Argb(c.BackColor));
                    if (c is System.Windows.Forms.CheckBox cb) Add(n + ".C", ((int)cb.CheckState).ToString());
                    if (c is System.Windows.Forms.RadioButton rb) Add(n + ".C", rb.Checked ? "1" : "0");
                    if (c is System.Windows.Forms.ComboBox co)
                    {
                        Add(n + ".I", co.SelectedIndex.ToString());
                        var sb = new StringBuilder();
                        foreach (var it in co.Items) { if (sb.Length > 0) sb.Append('\u0002'); sb.Append(Esc(Convert.ToString(it))); }
                        Add(n + ".L", sb.ToString());
                    }
                }
        }
        return list;
    }

    static List<KeyValuePair<string, string>> baseline;
    static List<System.Windows.Forms.Control> allControls;
    static string PageDiff()
    {
        var now = PageDump();
        var sb = new StringBuilder();
        for (int i = 0; i < now.Count; i++)
        {
            if (now[i].Key != baseline[i].Key) throw new Exception("page dump changed shape at " + now[i].Key);
            if (now[i].Value == baseline[i].Value) continue;
            sb.Append(now[i].Key).Append('=').Append(now[i].Value).Append('\u0001');
        }
        return sb.ToString();
    }

    static void PageSetup()
    {
        InstallHarmony();
        Set(main, "blnLoaded", true);
        Set(dtc, "blnLoaded", true);
        Set(main, "MinorPart", PageMinor); Set(main, "Build", PageBuild);
        SetTheater(Coords["none"]);
        SetPpt(new List<KeyValuePair<string, string>>());
        dtc.GetType().GetMethod("cntDTC_Load", Any).Invoke(dtc, new object[] { dtc, EventArgs.Empty });
        baseline = PageDump();
        var r = new Dictionary<string, string> { ["kind"] = "pbase" };
        var sb = new StringBuilder();
        foreach (var kv in baseline) sb.Append(kv.Key).Append('=').Append(kv.Value).Append('\u0001');
        r["p"] = sb.ToString();
        r["p_path"] = Esc((string)Get(dtc, "ProgramPath"));
        r["p_msg"] = msgBoxes.ToString();
        DumpModel(r, "b_");
        Row(r);
    }
    const int PageMinor = 38, PageBuild = 30000;

    // the controls a pilot works on this page, by what they take
    static readonly List<string> ClickNames = new List<string>();
    static readonly List<string> TextNames = new List<string>();
    static readonly List<string> SelNames = new List<string>();
    static readonly List<string> NumNames = new List<string>();
    static void PageControls()
    {
        ClickNames.AddRange(new[] { "chbREQJAM", "chbREQCTR", "chbFeedback", "chbBingo", "chbCmdsIncl", "btnClear_EWS", "chbBullseye", "btnClear",
            "chbMode1", "chbMode2", "chbMode3A", "chbMode4", "chbModeC", "chbModeS", "btnNone", "btnPopUp", "btnHADB", "btnTOSS",
            "pnlRefLeft", "pnlRefCenter", "pnlRefRight", "chbHudIncl", "chbViewsIncl", "chbMasterArmIncl", "chbSnsrPowerIncl", "chbIntLghtIncl",
            "rbnHAS", "rbnPOS", "rbnPB", "rbnEom", "rbnRuk", "rbnTbl1", "rbnTbl2", "rbnTbl3", "rbnTbl0",
            "btnClearStpt", "btnClearLines", "btnClearTgt", "btnClearPPT", "btnClearOpen", "btnClearHpn", "tabDTC", "tabNavOffsets" });
        foreach (var m in new[] { "AG", "AA", "NAV", "MSL", "DGFT", "SJ" })
            for (int i = 1; i <= 4; i++)
                foreach (var s in new[] { "Left", "Center", "Right" }) { ClickNames.Add("chb" + m + "_MFD" + i + "_" + s); SelNames.Add("cbo" + m + i + "_" + s); }
        for (int i = 1; i <= 6; i++)
        {
            foreach (var q in new[] { "BQ", "BI", "SQ", "SI" }) { TextNames.Add("mxt" + q + "_C" + i); TextNames.Add("mxt" + q + "_F" + i); }
            TextNames.Add("txtComment" + i);
        }
        for (int i = 1; i <= 20; i++) { TextNames.Add("txtUHF_" + i); TextNames.Add("txtVHF_" + i); ClickNames.Add("btnUHF_" + i); ClickNames.Add("btnVHF_" + i); }
        TextNames.AddRange(new[] { "mxtBingo_Flare", "mxtBingo_Chaff", "txtComm1_Comment", "txtComm2_Comment", "mxtTACAN", "mxtILS_FREQ", "mxtILS_CRS",
            "mxtALOW_AGL", "mxtALOW_MSL", "mxtALOW_TF", "mxtWingSpan", "mxtBingo", "mxtLaser", "mxtLaserCode", "mxtLaserLST" });
        foreach (var p in new[] { "P1", "P2" }) foreach (var f in new[] { "Spacing", "Pulse", "Angle", "C1_AD1", "C1_AD2", "C2_AD", "C2_BA" }) TextNames.Add("mxt" + p + "_" + f);
        SelNames.AddRange(new[] { "cboMode", "cboProgram", "cboPreset_1", "cboPreset_2", "cboTACAN", "cboTACANFunc", "cboColor", "cboSymWheelPos", "cboScales",
            "cboFPM", "cboDED", "cboVelocity", "cboAlt", "cboMasterMode", "cboMasterArm", "cboWideView", "cboStartView", "cboRalt", "cboDedBrt",
            "cboAIM9_Spot_Scan", "cboAIM9_TD_BP", "cboAIM120", "cboPower", "cboPowerDir", "cboPowerWpt",
            "cboP1_SubMode", "cboP1_Fuze", "cboP1_SGL_PAIR", "cboP2_SubMode", "cboP2_Fuze", "cboP2_SGL_PAIR" });
        for (int t = 1; t <= 3; t++) for (int h = 1; h <= 5; h++) { SelNames.Add("cboTbl" + t + "_Thr" + h); NumNames.Add("numTbl" + t + "_Thr" + h); }
        NumNames.AddRange(new[] { "numMode1_D1", "numMode1_D2", "numMode2_D1", "numMode2_D2", "numMode2_D3", "numMode2_D4", "numMode3A_D1", "numMode3A_D2", "numMode3A_D3", "numMode3A_D4" });
        foreach (var o in new[] { "VIP", "VIPPUP", "VIPOA1", "VIPOA2", "VRP", "VRPPUP", "VRPOA1", "VRPOA2" })
            foreach (var f in new[] { "STPTnr", "BRG_VAL", "RNG_VAL", "ELV_VAL" }) NumNames.Add("num" + o + "_" + f);
    }

    /** Whether the pilot could reach the control: enabled, and shown along with everything it sits in. */
    static bool Reachable(System.Windows.Forms.Control c)
    {
        if (!c.Enabled) return false;
        // a tab page that is not the selected one is hidden, but the pilot opens it first: tabs do not count
        for (var p = c; p != null && p != dtc; p = p.Parent) if (!(p is System.Windows.Forms.TabPage) && !OwnState(p, 0x2)) return false;
        return true;
    }

    static string TypedValue(string name)
    {
        if (name.StartsWith("txt")) return Pick("", "Slap", "Tower", "x y", "1", "UHF Preset 3", "a;b");
        switch (R(0, 12))
        {
            case 0: return "";
            case 1: return R(0, 9).ToString();
            case 2: return R(10, 99).ToString();
            case 3: return R(100, 999).ToString();
            case 4: return R(1000, 9999).ToString();
            case 5: return R(10000, 99999).ToString();
            case 6: return R(0, 9) + "." + R(0, 999).ToString("000");
            case 7: return R(0, 99).ToString("00") + "." + R(0, 99).ToString("00");
            case 8: return R(108, 118) + "." + R(0, 99).ToString("00");
            case 9: return Pick("1111", "1688", "2888", "1110", "2889", "0");
            case 10: return Pick("0.5", "1.250", "12.3", "5.", ".5");
            case 11: return R(0, 360).ToString();
            default: return R(0, 99).ToString();
        }
    }

    /** One random edit through the page's own handlers, or null when the control could not be reached. */
    static string lastOp;
    static string ApplyRandomOp()
    {
        int k = R(0, 9);
        if (k <= 2)
        {
            var n = Pick(ClickNames.ToArray());
            var c = (System.Windows.Forms.Control)Get(dtc, n);
            if (!Reachable(c)) return null;
            if (n.StartsWith("btnUHF_") || n.StartsWith("btnVHF_"))
            {
                pendingFreq = Pick("251.000", "243.000", "297.500", "138.050", "121.500", "3", "", "123", "12345", "118.1", "225.975");
                lastOp = "comm:" + n + "=" + Esc(pendingFreq);
                Raise(c, "OnClick");
                return lastOp;
            }
            lastOp = "click:" + n;
            Raise(c, "OnClick");
            return lastOp;
        }
        if (k <= 5)
        {
            var n = Pick(TextNames.ToArray());
            var c = (System.Windows.Forms.Control)Get(dtc, n);
            if (!Reachable(c)) return null;
            var v = TypedValue(n);
            lastOp = "text:" + n + "=" + Esc(v);
            c.Text = v;
            Raise(c, "OnLeave");
            return lastOp;
        }
        if (k <= 7)
        {
            var n = Pick(SelNames.ToArray());
            var c = (System.Windows.Forms.ComboBox)Get(dtc, n);
            if (!Reachable(c) || c.Items.Count == 0) return null;
            int i = R(0, c.Items.Count - 1);
            lastOp = "sel:" + n + "=" + i;
            c.SelectedIndex = i;
            Raise(c, "OnLeave");
            return lastOp;
        }
        {
            var n = Pick(NumNames.ToArray());
            var c = (System.Windows.Forms.NumericUpDown)Get(dtc, n);
            if (!Reachable(c)) return null;
            decimal max = c.Maximum;
            decimal v = c.DecimalPlaces > 0 && P(0.5) ? Math.Round((decimal)(rng.NextDouble() * (double)Math.Min(max, 400m)), 1) : R(0, (int)Math.Min(max, P(0.5) ? 30m : max));
            lastOp = "num:" + n + "=" + v.ToString(CultureInfo.InvariantCulture);
            c.Value = v;
            Raise(c, "OnLeave");
            return lastOp;
        }
    }

    // ------------------------------------------------------------------------------------------------ cartridges

    static string Coordinate() => P(0.1) ? "0.000000" : (rng.NextDouble() * 3300000.0).ToString("0.000000", CultureInfo.InvariantCulture);

    /** A value, usually the plausible one, sometimes one of the strings that take VB's odd paths. */
    static string Odd(string normal)
    {
        if (!P(0.08)) return normal;
        return Pick("", "abc", "-1", "1.5", "2.5", "300", "&H10", "1e3", " 7 ", "\"5\"", "65536", "NaN", "-0", "0.4", "1,000", "(3)", "$5", "7-", "0x10", "3.0", "255", "0", "-32769", "40000", "1E+40");
    }

    static string Name() => Pick("Not set", "-1", "", "      EW Site Puryu-gogae 1 JLP-40 Radar P-37", "Bridge 1", "Airbase (ZYPD) Rwy Thr 02", "a;b", "x, y");

    static string RandomCartridge(out List<KeyValuePair<string, string>> pptTable)
    {
        var secs = new List<KeyValuePair<string, List<string>>>();
        List<string> Sec(string name) { var l = new List<string>(); secs.Add(new KeyValuePair<string, List<string>>(name, l)); return l; }
        string K(string key) => P(0.02) ? key.ToUpperInvariant() : key;

        var ews = Sec(P(0.03) ? "ews" : "EWS");
        foreach (var k in new[] { "Reqjam", "Reqctr", "Bingo", "Fdbk" }) if (P(0.9)) ews.Add(K(k) + "=" + Odd(Pick("0", "1", "true", "TRUE", "yes")));
        if (P(0.9)) ews.Add("Flare Bingo=" + Odd(R(0, 30).ToString()));
        if (P(0.9)) ews.Add("Chaff Bingo=" + Odd(R(0, 30).ToString()));
        for (int n = 0; n < 6; n++)
        {
            foreach (var k in new[] { "Chaff BQ", "Chaff BI", "Chaff SQ", "Chaff SI", "Flare BQ", "Flare BI", "Flare SQ", "Flare SI" })
                if (P(0.95)) ews.Add("PGM " + n + " " + k + "=" + Odd(k.EndsWith("I") ? R(0, 5000).ToString() : R(0, 10).ToString()));
            if (P(0.8)) ews.Add("PGM " + n + " Comment=" + Pick("", "Defensive", "Slap", "MAN 1", "  spaced  "));
        }
        if (P(0.6)) ews.Add("Mode Selection=" + Odd(R(0, 4).ToString()));
        if (P(0.6)) ews.Add("Number Selection=" + Odd(R(0, 5).ToString()));

        var mfd = Sec("MFD");
        for (int d = 0; d < 4; d++) for (int m = 0; m < 6; m++)
            {
                if (P(0.1)) continue;
                for (int p = 0; p < 3; p++) mfd.Add("Display" + d + "-" + m + "-" + p + "=" + Odd(R(0, 15).ToString()));
                mfd.Add("Display" + d + "-" + m + "-csel=" + Odd(R(0, 3).ToString()));
            }
        if (P(0.9)) Sec("Bullseye").Add("BullseyeInfoOnMFD=" + Odd(Pick("0", "1")));

        var iff = Sec("IFF");
        if (P(0.9))
        {
            foreach (var k in new[] { "Mode1 On", "Mode2 On", "Mode3A On", "Mode4 On", "ModeC On", "ModeS On" }) iff.Add(k + "=" + Odd(R(0, 1).ToString()));
            iff.Add("Mode1 Code=" + Odd(R(0, 73).ToString()));
            iff.Add("Mode2 Code=" + Odd(R(0, 7777).ToString()));
            iff.Add("Mode3A Code=" + Odd(R(0, 7777).ToString("0000")));
            iff.Add("Mode4 Key=" + Odd(R(-1, 1).ToString()));
            iff.Add("AutoChange=" + Odd(R(0, 3).ToString()));
            for (int i = 0; i < 12; i++)
            {
                if (P(0.1)) continue;
                iff.Add("TIME " + i + " Mode1 Code=" + Odd(R(0, 73).ToString()));
                iff.Add("TIME " + i + " Mode3A Code=" + Odd(R(0, 7777).ToString("0000")));
                iff.Add("TIME " + i + " Mode4 Key=" + Odd(R(-1, 1).ToString()));
                iff.Add("TIME " + i + " Criteria=" + (P(0.05) ? Pick("-1", "1.5", "2.5", "18446744073709551615", "1e2", "") : R(0, 2400).ToString("0000")));
            }
            for (int j = 0; j < 2; j++)
            {
                foreach (var k in new[] { "Mode1", "Mode2", "Mode3A", "Mode4", "ModeC", "ModeS" }) iff.Add("POS " + j + " " + k + "=" + Odd(R(-1, 7777).ToString()));
                iff.Add("POS " + j + " WayPoint=" + Odd(Pick(" 5", "0", R(1, 24).ToString())));
                iff.Add("POS " + j + " Direction=" + Odd(Pick("0", "2", "4", "6", "8")));
            }
        }

        var harm = Sec("HARM");
        if (P(0.85))
        {
            for (int n = 0; n < 3; n++) for (int k = 0; k < 5; k++) harm.Add("THREAT " + n + " " + k + "=" + Odd(R(0, 1200).ToString("0000")));
            harm.Add("MODE=" + Odd(R(0, 3).ToString()));
            harm.Add("SUBMODE=" + Odd(R(0, 3).ToString()));
            harm.Add("TER=" + Odd(R(-1, 3).ToString()));
        }
        var link = Sec("LINK16");
        for (int i = 0; i < R(0, 4); i++) link.Add("FILE_A_VOICE_GROUP_" + (char)('A' + i) + "_CHANNEL=000");

        var stpt = Sec(P(0.03) ? "stpt" : "STPT");
        for (int i = 0; i < 26; i++)
        {
            if (P(0.08) || (i >= 24 && P(0.7))) continue;
            string line;
            if (P(0.4)) line = "0.000000, 0.000000, 0.000000, -1, Not set";
            else line = Odd(Coordinate()) + ", " + Odd(Coordinate()) + ", " + Odd((-rng.NextDouble() * 9000).ToString("0.000000", CultureInfo.InvariantCulture)) + ", " + Odd(Pick("-1", "0", "1", "7", "14", "15", "17", "18", "2")) + ", " + Name();
            if (P(0.03)) line = Pick("1.0", "1.0, 2.0", "1.0, 2.0, 3.0", ",5, 6, 7, 8, x", " , , , , ");
            stpt.Add(K("target_" + i) + "=" + line);
        }
        for (int i = 0; i < 16; i++)
        {
            if (P(0.1)) continue;
            string line = P(0.5) ? "0.000000, 0.000000, 0.000000, 0.000000," : Odd(Coordinate()) + ", " + Odd(Coordinate()) + ", " + Odd("0.000000") + ", " + Odd(Pick("0.000000", "25000.000000", "12345.67", "60761.15")) + ", " + Pick("SA2", "SA6", "S", "", "ZZ", "SA2 ");
            if (P(0.03)) line = Pick("1.0", ", , ,", "1,2");
            stpt.Add("ppt_" + i + "=" + line);
        }
        for (int i = 0; i < 24; i++)
        {
            if (P(0.1)) continue;
            stpt.Add("lineSTPT_" + i + "=" + (P(0.5) ? "0.000000, 0.000000, 0.000000" : Odd(Coordinate()) + ", " + Odd(Coordinate()) + ", " + Odd("0.000000")));
        }
        for (int i = 0; i < 100; i++)
        {
            if (P(0.1)) continue;
            stpt.Add("wpntarget_" + i + "=" + (P(0.7) ? "0.000000, 0.000000, 0.000000, -1, Not set" : Odd(Coordinate()) + ", " + Odd(Coordinate()) + ", " + Odd("-120.500000") + ", " + Odd(Pick("-1", "1", "22")) + ", " + Name()));
        }
        for (int i = 80; i < 100; i++)
        {
            if (P(0.15)) continue;
            stpt.Add("target_" + i + "=" + (P(0.5) ? "0.000000, 0.000000, 0.000000, -1, Not set" : Odd(Coordinate()) + ", " + Odd(Coordinate()) + ", " + Odd("-33.000000") + ", " + Odd(Pick("-1", "1", "0")) + ", " + Name()));
        }

        var radio = Sec("Radio");
        if (P(0.9))
        {
            for (int i = 1; i <= 20; i++) if (P(0.97)) radio.Add("UHF_" + i + "=" + (P(0.01) ? Pick("abc", "", " ") : Odd(R(225000, 399975).ToString())));
            for (int i = 1; i <= 20; i++) if (P(0.97)) radio.Add("VHF_" + i + "=" + (P(0.01) ? Pick("abc", "", " ") : Odd(R(116000, 151975).ToString())));
            for (int i = 1; i <= 20; i++) if (P(0.9)) radio.Add("UHF_COMMENT_" + i + "=" + Pick("", "Tower", "(open)", "UHF_" + i));
            for (int i = 1; i <= 20; i++) if (P(0.9)) radio.Add("VHF_COMMENT_" + i + "=" + Pick("", "Ground", "(open)"));
            for (int i = 1; i <= 4; i++) if (P(0.5)) radio.Add("ILS_" + i + "=" + R(10800, 11195));
        }
        var comms = Sec("COMMS");
        if (P(0.9))
        {
            comms.Add("Comm1=" + Odd(R(0, 21).ToString()));
            comms.Add("Comm1_Comment=" + Pick("(open)", "", "Tower"));
            comms.Add("Comm2=" + Odd(R(0, 21).ToString()));
            comms.Add("Comm2_Comment=" + Pick("(open)", "", "Ground"));
            if (P(0.7)) comms.Add("TACAN Channel=" + Odd(R(1, 126).ToString()));
            if (P(0.7)) comms.Add("TACAN Band=" + Odd(R(0, 1).ToString()));
            if (P(0.7)) comms.Add("TACAN Domain=" + Odd(R(0, 1).ToString()));
            if (P(0.7)) comms.Add("ILS Frequency=" + Odd(R(10000, 11195).ToString()));
            if (P(0.7)) comms.Add("ILS CRS=" + Odd(R(0, 360).ToString()));
        }
        var mapPop = Sec("MAP_POP");
        for (int i = 0; i < R(0, 5); i++) mapPop.Add("MapOpt_" + i + "=" + R(0, 1));

        // what WDP itself adds (BMS's own cartridges usually lack them)
        if (P(0.6))
        {
            var nav = Sec("NAV OFFSETS");
            nav.Add("Modesel=" + Odd(Pick("none", "VIP", "vrp", "0", "1", "2", "3", "4", "5", "1.5")));
            foreach (var k in new[] { "VIP", "VIPPUP", "VRP", "VRPPUP" })
                if (P(0.9)) nav.Add(k + "=" + Odd(R(0, 24).ToString()) + "," + Odd(Pick("0", "12.5", "359.9", "45")) + "," + Odd(R(0, 60000).ToString()) + "," + Odd(R(0, 2000).ToString()));
            int s1 = R(0, 23);
            if (P(0.8)) nav.Add("OA1-" + s1 + "=" + Odd(Pick("0", "90.2", "181")) + "," + Odd(R(0, 9000).ToString()) + "," + Odd(R(0, 500).ToString()));
            if (P(0.8)) nav.Add("OA2-" + s1 + "=" + Odd(Pick("0", "10.2", "271")) + "," + Odd(R(0, 9000).ToString()) + "," + Odd(R(0, 500).ToString()));
            if (P(0.6)) nav.Add("OA1-" + (s1 + 1) + "=" + Odd(Pick("5", "90.25", "18")) + "," + Odd(R(0, 9000).ToString()) + "," + Odd(R(0, 500).ToString()));
            if (P(0.6)) nav.Add("OA2-" + (s1 + 1) + "=" + Odd(Pick("7", "91.35", "38")) + "," + Odd(R(0, 9000).ToString()) + "," + Odd(R(0, 500).ToString()));
            if (P(0.3)) nav.Add("OA1-" + R(0, 23) + "=1,2,3");
        }
        if (P(0.5))
        {
            var hud = Sec("Hud");
            foreach (var k in new[] { "Scales", "Brightness", "FPM", "DED", "Velocity", "Alt", "SymWheelPos" }) if (P(0.8)) hud.Add(k + "=" + Odd(R(0, 3).ToString()));
        }
        if (P(0.5))
        {
            var icp = Sec("ICP");
            icp.Add("MasterMode=" + Odd(R(0, 3).ToString()));
            icp.Add("Alow AGL=" + Odd(Pick("300.000000", "250.5", "1234.567")));
            icp.Add("Alow MSL=" + Odd(R(0, 20000).ToString()));
            icp.Add("Alow TFAdv=" + Odd(R(0, 1000).ToString()));
            icp.Add("Manual Wingspan=" + Odd(Pick("35.000000", "32.8", "0.1")));
            icp.Add("Bingo_Fuel=" + Odd(Pick("1500.000000", "2000", "1234.5678")));
        }
        if (P(0.4)) Sec("Cockpit View").Add("WideView=" + Odd(R(0, 2).ToString()));
        if (P(0.4)) Sec("OTW").Add("Mode=" + Odd(R(0, 2).ToString()));
        if (P(0.4)) Sec("Weapons").Add("MasterArm=" + Odd(R(0, 2).ToString()));
        if (P(0.5))
        {
            var laser = Sec("Laser");
            laser.Add("LaserST=" + Odd(R(0, 30).ToString()));
            laser.Add("LaserTGP=" + Odd(R(1111, 1788).ToString()));
            laser.Add("LaserLST=" + Odd(R(1111, 1788).ToString()));
        }
        if (P(0.5))
        {
            var aim = Sec("FCC_AIM");
            aim.Add("AIM-9_Spot/Scan=" + Odd(R(0, 1).ToString())); aim.Add("AIM-9_TD/BP=" + Odd(R(0, 1).ToString())); aim.Add("AIM120_TargetSize=" + Odd(R(0, 2).ToString()));
            var agm = Sec("FCC_AGM");
            agm.Add("Maverick_AutoPwr=" + Odd(R(0, 1).ToString())); agm.Add("Maverick_AutoPwrDir=" + Odd(R(0, 3).ToString())); agm.Add("Maverick_AutoPwrWpt=" + Odd(R(1, 24).ToString()));
            var agb = Sec("FCC_AGB");
            for (int p = 1; p <= 2; p++)
            {
                agb.Add("Profile" + p + "_Submode=" + Odd(R(0, 9).ToString()));
                agb.Add("Profile" + p + "_Fuze=" + Odd(R(0, 3).ToString()));
                agb.Add("Profile" + p + "_SGL/PAIR=" + Odd(R(0, 1).ToString()));
                agb.Add("Profile" + p + "_Release_Spacing=" + Odd(R(10, 990).ToString()));
                agb.Add("Profile" + p + "_Release_Pulse=" + Odd(R(0, 8).ToString()));
                agb.Add("Profile" + p + "_Release_Angle=" + Odd(R(0, 90).ToString()));
                agb.Add("Profile" + p + "_C1_AD1=" + Odd(Pick("400.000000", "3.25", "0.1")));
                agb.Add("Profile" + p + "_C1_AD2=" + Odd(Pick("600.000000", "6.125")));
                agb.Add("Profile" + p + "_C2_AD=" + Odd(Pick("150.000000", "1.5")));
                agb.Add("Profile" + p + "_C2_BA=" + Odd(R(0, 3000).ToString()));
            }
        }
        if (P(0.4)) Sec("SNSR_PWR").Add("RALT=" + Odd(R(0, 2).ToString()));
        if (P(0.4)) Sec("INT_LIGHTING").Add("DED=" + Odd(R(0, 5).ToString()));

        // shuffle some sections, drop a few
        for (int i = secs.Count - 1; i > 0; i--) if (P(0.15)) { int j = R(0, i); var t = secs[i]; secs[i] = secs[j]; secs[j] = t; }
        var sb = new StringBuilder();
        string eol = P(0.95) ? "\r\n" : "\n";
        foreach (var s in secs)
        {
            if (P(0.03)) continue;
            sb.Append('[').Append(s.Key).Append(']').Append(eol);
            foreach (var l in s.Value)
            {
                if (P(0.005)) sb.Append(";comment").Append(eol);
                if (P(0.003)) sb.Append(eol);
                sb.Append(l).Append(eol);
            }
        }
        var text = sb.ToString();
        if (P(0.05) && text.Length > 2) text = text.Substring(0, text.Length - eol.Length);

        pptTable = new List<KeyValuePair<string, string>>();
        if (P(0.8)) pptTable.Add(new KeyValuePair<string, string>("SA2", "SA-2 Guideline"));
        if (P(0.8)) pptTable.Add(new KeyValuePair<string, string>("SA6", "SA-6 Gainful"));
        if (P(0.3)) pptTable.Add(new KeyValuePair<string, string>("S", "never used"));
        if (P(0.3)) pptTable.Add(new KeyValuePair<string, string>("", "empty code"));
        return text;
    }

    // ------------------------------------------------------------------------------------------------ edits

    sealed class Mut { public string Path; public object Value; public string Enc; }

    static string EncF(float f) => "f:" + F(f);

    static float RandFloat()
    {
        switch (R(0, 9))
        {
            case 0: return 0f;
            case 1: return -0f;
            case 2: return float.NaN;
            case 3: return (float)(rng.NextDouble() * 3300000.0);
            case 4: return (float)(-rng.NextDouble() * 9000.0);
            case 5: return (float)Math.Round(rng.NextDouble() * 100000.0, 3);
            case 6: return 1e-7f * R(1, 9);
            case 7: return 123456.789f;
            case 8: return (float)(rng.NextDouble() * 360.0);
            default: return (float)(R(-5, 5) * 0.05);
        }
    }

    static List<Mut> RandomMutations()
    {
        var list = new List<Mut>();
        int n = P(0.3) ? 0 : R(1, 25);
        for (int i = 0; i < n; i++)
        {
            string path; object v; string enc;
            int kind = R(0, 21);
            float fl = RandFloat();
            int iv = Pick(0, 1, -1, 5, 255, 7777, 65535, int.MaxValue, int.MinValue, R(0, 3000), R(-3000, 0));
            string sv = Pick("", "Tower", null, "a b", "x;y");
            string[] xyz = { "FalconX", "FalconY", "FalconZ" };
            switch (kind)
            {
                case 0: path = "tblCampSTPT." + R(0, 24) + "." + Pick(xyz); v = fl; enc = EncF(fl); break;
                case 1: path = "tblCampSTPT." + R(0, 24) + ".Action"; v = iv; enc = "i:" + iv; break;
                case 2: path = "tblCampPPT." + R(0, 14) + "." + Pick("FalconX", "FalconY", "FalconZ", "FalconRNG"); v = fl; enc = EncF(fl); break;
                case 3: path = "tblCampPPT." + R(0, 14) + ".Code"; v = sv; enc = "s:" + Esc(sv); break;
                case 4: path = "tblCampLine." + R(0, 23) + "." + Pick(xyz); v = fl; enc = EncF(fl); break;
                case 5: path = "tblCampTgt." + R(0, 100) + "." + Pick(xyz); v = fl; enc = EncF(fl); break;
                case 6: path = "tblCampTgt." + R(0, 100) + (P(0.5) ? ".Action" : ".Target"); if (path.EndsWith("Action")) { v = iv; enc = "i:" + iv; } else { v = sv; enc = "s:" + Esc(sv); } break;
                case 7: path = Pick("tblCampOpen." + R(0, 8), "tblCampHpn." + R(0, 9)) + "." + Pick("FalconX", "FalconY", "FalconZ", "Action", "Target");
                    if (path.EndsWith("Action")) { v = iv; enc = "i:" + iv; } else if (path.EndsWith("Target")) { v = sv; enc = "s:" + Esc(sv); } else { v = fl; enc = EncF(fl); }
                    break;
                case 8: path = "CampEWS.Program." + R(0, 5) + "." + Pick("ChaffBQ", "ChaffBI", "ChaffSQ", "ChaffSI", "FlareBQ", "FlareBI", "FlareSQ", "FlareSI", "Comment");
                    if (path.EndsWith("Comment")) { v = sv; enc = "s:" + Esc(sv); } else { v = iv; enc = "i:" + iv; }
                    break;
                case 9: path = "CampEWS." + Pick("Reqjam", "Reqctr", "Bingo", "Fdbk", "FlareBingo", "ChaffBingo", "ModeSelection", "NumberSelection");
                    if (path.EndsWith("Bingo") && !path.EndsWith(".Bingo")) { int b = R(0, 255); v = (byte)b; enc = "i:" + b; }
                    else if (path.EndsWith("Selection")) { v = iv; enc = "i:" + iv; }
                    else { bool b = P(0.5); v = b; enc = "b:" + (b ? "1" : "0"); }
                    break;
                case 10: { int b = R(0, 255); path = "CampMFD." + Pick("A_G", "A_A", "NAV", "MSL", "DGF", "S_J") + "." + R(0, 3) + "." + Pick("Left", "Center", "Right", "Csel"); v = (byte)b; enc = "i:" + b; break; }
                case 11: path = "CampRadio." + Pick("UHF", "VHF", "UHFcomment", "VHFcomment") + "." + R(0, 20);
                    if (path.Contains("comment")) { v = sv; enc = "s:" + Esc(sv); } else { v = iv; enc = "i:" + iv; }
                    break;
                case 12: path = "CampComm." + Pick("Comm1", "Comm2", "TACANChannel", "ILSFrequency", "ILSCRS", "TACANBand", "TACANDomain", "Comm1_Comment", "Comm2_Comment");
                    if (path.Contains("Comment")) { v = sv; enc = "s:" + Esc(sv); } else { v = iv; enc = "i:" + iv; }
                    break;
                case 13: path = "CampNavOffsets." + Pick("VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2") + "." + Pick("Stpt", "Bearing", "Range", "Elv");
                    if (path.EndsWith("Bearing")) { v = fl; enc = EncF(fl); } else if (path.EndsWith("Stpt")) { int s = R(-1, 25); v = s; enc = "i:" + s; } else { v = iv; enc = "i:" + iv; }
                    if (P(0.1)) { path = "CampNavOffsets.Modesel"; int s = R(-1, 4); v = s; enc = "i:" + s; }
                    break;
                case 14: path = "CampHud." + Pick("Color", "Scales", "Brightness", "FPM", "DED", "Velocity", "Alt", "SymWheelPos"); v = iv; enc = "i:" + iv; break;
                case 15: path = "CampICP." + Pick("MasterMode", "AlowAGL", "AlowMSL", "AlowTFAdv", "ManualWingspan", "BingoFuel");
                    if (path.EndsWith("AGL") || path.EndsWith("span") || path.EndsWith("Fuel")) { v = fl; enc = EncF(fl); } else { v = iv; enc = "i:" + iv; }
                    break;
                case 16:
                {
                    int s = R(-32768, 32767); int b = R(0, 255);
                    switch (R(0, 3))
                    {
                        case 0: path = "CampIff." + Pick("Mode1_On", "Mode2_On", "Mode3A_On", "Mode4_On", "ModeC_On", "ModeS_On", "AutoChange"); v = (byte)b; enc = "i:" + b; break;
                        case 1: path = "CampIff." + Pick("Mode1_Code", "Mode2_Code", "Mode3A_Code", "Mode4_Key"); v = (short)s; enc = "i:" + s; break;
                        case 2:
                            path = "CampIff.timeSettings." + R(0, 11) + "." + Pick("Mode1_Code", "Mode3A_Code", "Mode4_Key", "TimeCriteria");
                            if (path.EndsWith("Mode1_Code")) { v = (byte)b; enc = "i:" + b; }
                            else if (path.EndsWith("Mode3A_Code")) { v = (short)s; enc = "i:" + s; }
                            else if (path.EndsWith("Mode4_Key")) { v = iv; enc = "i:" + iv; }
                            else { ulong u = P(0.2) ? ulong.MaxValue : (ulong)R(0, 100000); v = u; enc = "u:" + u.ToString(CultureInfo.InvariantCulture); }
                            break;
                        default:
                            path = "CampIff.posSettings." + R(0, 1) + "." + Pick("Mode1", "Mode2", "Mode3A", "Mode4", "ModeC", "ModeS", "WayPoint", "Direction");
                            if (path.EndsWith("WayPoint")) { v = iv; enc = "i:" + iv; } else if (path.EndsWith("Direction")) { v = (byte)b; enc = "i:" + b; } else { v = (short)s; enc = "i:" + s; }
                            break;
                    }
                    break;
                }
                case 17: path = Pick("CampView.WideView", "CampOTW.Mode", "CampWeapons.MasterArm", "CampSnsr.Ralt", "CampLightning.DED", "CampLaser.LaserST", "CampAIM.SpotScan", "CampAIM.TD_BP", "CampAIM.TargetSize", "CampAGM.Mav_AutoPwr", "CampAGM.Mav_AutoPwrDir", "CampAGM.Mav_AutoPwrWpt"); v = iv; enc = "i:" + iv; break;
                case 18: { int s = R(-32768, 32767); path = "CampLaser." + Pick("LaserCode", "LSTCode"); v = (short)s; enc = "i:" + s; break; }
                case 19: path = "CampHarm.Table." + R(0, 2) + "." + Pick("Threat_0", "Threat_1", "Threat_2", "Threat_3", "Threat_4"); v = iv; enc = "i:" + iv; break;
                case 20: path = "CampAGB." + Pick("Profile1", "Profile2") + "." + Pick(BombF);
                    if (path.Contains("_AD")) { v = fl; enc = EncF(fl); } else { v = iv; enc = "i:" + iv; }
                    break;
                default:
                    path = Pick("CampEWS.Program", "CampRadio.UHF", "CampRadio.VHFcomment", "CampIff.timeSettings", "CampHarm.Table", "CampMFD.S_J", "CampMFD.A_G");
                    v = null; enc = "null";
                    if (!P(0.15)) continue;
                    break;
            }
            list.Add(new Mut { Path = path, Value = v, Enc = enc });
        }
        return list;
    }

    static readonly string[] InclNames = { "chbCmdsIncl", "chbHudIncl", "chbViewsIncl", "chbMasterArmIncl", "chbSnsrPowerIncl", "chbIntLghtIncl" };
    static readonly string[] RbnNames = { "rbnHAS", "rbnPOS", "rbnPB", "rbnEom", "rbnRuk", "rbnTbl1", "rbnTbl2", "rbnTbl3", "rbnTbl0" };
    static readonly string[] SaveCalls = {
        "SaveCallsign_STPT", "SaveCallsign_PPT", "SaveCallsign_Line", "SaveCallsign_WpnTarget", "SaveCallsign_OpenSTPT",
        "SaveCallsign_HarpoonSTPT", "SaveCallsign_EWS", "SaveCallsign_MFD", "SaveCallsign_Bullseye", "SaveCallsign_Radio",
        "SaveCallsign_Comm", "SaveCallsign_NavOffsets", "SaveCallsign_HUD", "SaveCallsign_ICP", "SaveCallsign_IFF",
        "SaveCallsign_View", "SaveCallsign_OTW", "SaveCallsign_Weapons", "SaveCallsign_HARM", "SaveCallsign_Laser",
        "SaveCallsign_AIM", "SaveCallsign_AGM", "SaveCallsign_AGB", "SaveCallsign_SnsrPwr", "SaveCallsign_IntLight" };

    static readonly int[][] Versions = { new[] { 38, 17950 }, new[] { 36, 23170 }, new[] { 36, 23169 }, new[] { 36, 22326 }, new[] { 35, 22326 },
        new[] { 34, 16500 }, new[] { 33, 16499 }, new[] { 33, 14300 }, new[] { 33, 14299 }, new[] { 38, 25688 }, new[] { 38, 25687 }, new[] { 41, 30000 } };

    static void CartRows(string file)
    {
        var loadT = asm.GetType("WeaponDeliveryPlanner.clsLoadDTC", true);
        var saveT = asm.GetType("WeaponDeliveryPlanner.clsSaveDTC", true);
        var loadCallsign = loadT.GetMethod("LoadCallsign");
        string[] theaters = { "none", "old1", "old2", "new1", "new2" };
        int rows = int.TryParse(Environment.GetEnvironmentVariable("WDPREF_DTC_ROWS"), out var nr) ? nr : 1200;
        var clock = System.Diagnostics.Stopwatch.StartNew();
        for (int row = 0; row < rows; row++)
        {
            if (row % 50 == 0) Console.WriteLine("cart " + row + "/" + rows + "  " + clock.Elapsed.TotalSeconds.ToString("0") + " s");
            rng = new Random(424242 + row * 977);
            var r = new Dictionary<string, string> { ["kind"] = "cart" };
            var text = RandomCartridge(out var pptTable);
            var ver = Pick(Versions);
            var th = Pick(theaters);
            r["in_text"] = Esc(text);
            r["in_minor"] = ver[0].ToString(); r["in_build"] = ver[1].ToString();
            r["in_theater"] = th;
            var pptEnc = new StringBuilder();
            foreach (var kv in pptTable) pptEnc.Append(Esc(kv.Key)).Append('=').Append(Esc(kv.Value)).Append(';');
            r["in_ppt"] = pptEnc.ToString();

            File.WriteAllText(file, text, Encoding.Default);
            Set(main, "MinorPart", ver[0]); Set(main, "Build", ver[1]);
            SetTheater(Coords[th]);
            dtc.GetType().GetMethod("SetCoordData", Any).Invoke(dtc, null);   // as fclsMain does when a theater is set
            SetPpt(pptTable);
            // the cartridge is read the way the page reads it (btnGetCampFile -> GetCampFile), and the model is
            // written down twice: straight after clsLoadDTC.LoadCallsign (m_, by a Harmony postfix) and after the page
            // has filled itself from it (g_), which its own handlers can change
            Set(main, "strLastCallsignFile", file); Set(main, "strCallsignFile", file);
            r["in_file"] = Esc(file);
            Set(dtc, "blnLoadCampOrTE", true);
            curRow = r;
            try { dtc.GetType().GetMethod("GetCampFile", Any).Invoke(dtc, null); r["p_exc"] = ""; }
            catch (TargetInvocationException e) { r["p_exc"] = e.InnerException.GetType().Name; }
            curRow = null;
            r["p_load"] = PageDiff();
            DumpModel(r, "g_");

            // the pilot edits the page through its own handlers
            var ui = new StringBuilder();
            int nops = P(0.25) ? 0 : R(1, 12);
            for (int o = 0; o < nops; o++)
            {
                string op;
                try { op = ApplyRandomOp(); }
                catch (TargetInvocationException e) { op = lastOp + "!" + e.InnerException.GetType().Name; }
                catch (Exception e) { op = lastOp + "!" + e.GetType().Name; }
                if (op != null) ui.Append(op).Append((char)1);
            }
            r["in_ui"] = ui.ToString();
            r["p_ui"] = PageDiff();
            DumpModel(r, "u_");

            // edits, the page's boxes, the HARM radios, the version the save runs under
            var muts = RandomMutations();
            var menc = new StringBuilder();
            foreach (var mu in muts)
            {
                if (!SetPath(mu.Path, mu.Value)) continue;
                menc.Append(mu.Path).Append('=').Append(mu.Enc).Append('\u0001');
            }
            r["in_mut"] = menc.ToString();
            var incl = new StringBuilder();
            foreach (var n in InclNames)
            {
                bool on = P(0.5);
                ((System.Windows.Forms.CheckBox)Get(dtc, n)).CheckState = on ? System.Windows.Forms.CheckState.Checked : System.Windows.Forms.CheckState.Unchecked;
                incl.Append(on ? '1' : '0');
            }
            r["in_incl"] = incl.ToString();
            foreach (var group in new[] { new[] { 0, 1 }, new[] { 2, 3, 4 }, new[] { 5, 6, 7, 8 } })
            {
                int pick = R(-1, group.Length - 1);
                foreach (var gi in group) ((System.Windows.Forms.RadioButton)Get(dtc, RbnNames[gi])).Checked = false;
                if (pick >= 0) ((System.Windows.Forms.RadioButton)Get(dtc, RbnNames[group[pick]])).Checked = true;
            }
            var rb = new StringBuilder();
            foreach (var n in RbnNames) rb.Append(((System.Windows.Forms.RadioButton)Get(dtc, n)).Checked ? '1' : '0');
            r["in_rbn"] = rb.ToString();
            var sver = P(0.5) ? ver : Pick(Versions);
            r["in_sminor"] = sver[0].ToString(); r["in_sbuild"] = sver[1].ToString();
            Set(main, "MinorPart", sver[0]); Set(main, "Build", sver[1]);

            var saver = Activator.CreateInstance(saveT);
            string threw = "";
            foreach (var call in SaveCalls)
            {
                try { saveT.GetMethod(call).Invoke(saver, new object[] { file }); }
                catch (TargetInvocationException e) { threw = call + ":" + e.InnerException.GetType().Name; break; }
            }
            r["s_threw"] = threw == "" ? "0" : "1";
            r["s_where"] = threw;
            r["out_text"] = Esc(File.ReadAllText(file, Encoding.Default));
            DumpModel(r, "s_");
            // what SaveCallsign_DTC does to the page afterwards (its TE-mission half is not run: the app writes no mission)
            if (threw == "")
            {
                Set(dtc, "blnCallsignSaved", true);
                Set(dtc, "blnCallsignDtcLoaded", true);
                try { dtc.GetType().GetMethod("Check_DTC", Any).Invoke(dtc, null); } catch (TargetInvocationException e) { r["p_saveexc"] = e.InnerException.GetType().Name; }
            }
            r["p_save"] = PageDiff();
            Row(r);
        }
    }
}

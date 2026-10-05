using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

// Drives the real DataCard page (cntDataCard) and writes down what it shows.
//
// The DataCard is filled from the campaign: WDP's main form (fclsMain) holds the flight table, the package, the
// airports and the attack profile, and the card's Fill* methods copy and format them. This harness builds the
// real fclsMain (without showing it; its constructor only lays out controls), puts a synthetic campaign into the
// fields those methods read — flights with waypoints, the package, three airports, the radio map, the attack
// profile on the DTC page, the transition level — and runs the card through the same sequence WDP runs when a
// flight is selected (SelectNewFlight, then OpenFile's tail), then presses the page's own buttons and edits its
// fields through their own handlers. Every label and text box the card fills is copied out, one row per mission.
//
// Besides the card's own labels, each row writes down the ten cartridge boxes on the DTC page that the weapon boxes'
// Leave handlers write back to (MaskedTextBoxes: "dtc.txtWpn_*") and its six weapon-mode lists, the card's radio
// buttons' Enabled, the elevation labels' ForeColor, the tanker and weapon-mode lists' SelectedIndex, what VB's own
// runtime answers for four odd strings (IsNumeric, ToDouble, ToInteger, ToSingle, ToShort: "in_vbnum"), because what
// WDP calls a number is VB's ("&H1F400", "$250", "(300)" and "7-" are numbers, ",5" and "- 7" are not), and six
// direct calls to the card's own Distance and Heading over the whole short range ("in_geo").
//
// After the page's own sequence, "in_steps" presses more of it: the attack-profile buttons (cntDTC.Profiles copies
// the Pop-up/HADB/TOSS pages' *NavOffsets, set here at random, NaN bearings included; the map drawn after it is not
// the card's), the pilot seats (SelectPilotSeat; the loadout form and performance page after the plan are not the
// card's), the formation dialog's answer (the lines after ShowDialog), a route spinner moved without its Click, the
// tanker lists' own buttons and rows, the weapon-mode lists, boxes typed and not left, Leave and KeyUp handlers, the
// card's Fill* methods. An exception raised after a handler's card part (the map, the loadout form, the performance
// page reading campaign tables this harness does not build) is counted, not taken as WDP stopping: only an exception
// inside the card's own methods counts (FromCard).
//
// A row on which WDP stops with an unhandled exception is written as it stood, with the stage it stopped in
// ("in_threw"): the port must stop in the same stage and leave the same card, which the next row starts from.
//
// What is set on fclsMain by reflection, and why (nothing is shown):
//   blnVersion (and on every page that has it)  the program runs under BMS, not Allied Force
//   FlightTable, SelFlightNr        the flights and ours (BMSUtils.Flight/Waypoint built through their ctors)
//   Build, MinorPart, intFileVer     which BMS the campaign is from (branches in FillAptLabels/FillAutoFlight)
//   blnLoaded, blnMissionLoaded      "a campaign is open" (every Fill* checks)
//   blnSelectingFlight               true during the SelectNewFlight part, false afterwards, as WDP does
//   FltRadio                         the theater's flight radio map (FillAutoPackages)
//   AirportTable, numAirports, intSelApt   one airport, for the transition level FillCommCard reads
//   CampNavOffsets.Modesel, CampLaser      the cartridge's nav-offset mode and laser codes
//   cntDTC.strProfile + its num*_VAL spinners, and the Pop-up/TOSS/HADB pages' fields  (FillAttackType)
//   cntPerformance.lblMilP_Val.Text  (FillDataCard copies it)
//   tblTankerTrack/TankerNR, tblAwacsTrack/AwacsNR, tblJSTARTrack/JstarNR, SelFlightArrStpt   (FillTanker/Awacs/JSTAR)
//   CampEWS.Program (six empty programs, which the EWS boxes' Leave handlers write their comments into)
//   PopUpNavOffsets, HADBNavOffsets, TOSSNavOffsets   what those pages worked out (the profile buttons copy it)
//   cntDTC.blnLoaded                 the DTC page has loaded (FillNAVOFFSETSdata refills its spinners only then)
//   cntDTC.cboP1_SubMode … cboP2_SGL_PAIR   the cartridge's weapon modes, set while blnLoaded is false
//   blnLoaded is false around FillAptLabels only, so its CheckChartButtons (chart buttons, no label) does not look
//   the airport up in a theater database that is not there
// Everything the card itself holds (Packages, tblApt, strNames, strMission, the EWS and weapon strings, its
// toggles) is set on the cntDataCard instance, exactly where WDP's own loaders put it.
//
// One fclsMain for the whole run, as in WDP: each row starts with the card's ClearDatacard, and every toggle the
// row depends on is set explicitly, so the rows do not depend on one another beyond what the port can replay.
//
// WDP stops with an unhandled exception in situations this harness creates on purpose, now and then: a package
// flight missing from a non-empty radio map, a non-ASCII callsign VB's LCase does not fold to the map's spelling, a
// fuel figure that overflows CalculateFuel or RoundUp, a count of more than 255 waypoints, a take-off earlier than
// the taxi time, a laser code past a Short, a NaN bearing into the DTC spinners, and (rarely) the conversions VB's
// IsNumeric says yes to and the handler's conversion then refuses. A second-tanker index left over from a previous
// mission is not created (FirstTanker/SecondTanker are reset to the constructor's -1 on every row).
// Message boxes (CreateFlightplan's "Create Flightplan", the laser range warnings) are closed by a watchdog.
//
//   wdpref page DataCard <WeaponDeliveryPlanner.exe> <out.tsv>       (run from WDP's own folder)
internal static class DataCard
{
    const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;

    // every control the card fills, in the order they are written to the TSV
    static readonly List<string> Outputs = BuildOutputs();

    static List<string> BuildOutputs()
    {
        var l = new List<string>();
        for (int i = 1; i <= 24; i++)
            foreach (var p in new[] { "lblAction", "lblTOS", "lblHdg", "lblDist", "txtKias", "lblAlt", "txtFuel", "txtFormation" })
                l.Add(p + i);
        l.Add("lblFormation");
        l.AddRange(new[] {
            "lblMission", "lblBrfMission", "lblLead", "lblWing1", "lblElement", "lblWing4", "lblPackage1", "lblPackage2", "lblBrfPackage",
            "lblCallsign1", "lblBrfCallsign", "lblMilP",
        });
        for (int i = 1; i <= 5; i++)
            l.AddRange(new[] { "rbnCallsign" + i, "rbnCallsign" + i + ".text", "lblAC" + i, "txtC" + i + "_UHF", "txtC" + i + "_VHF", "txtIDM_" + i, "txtTCN_" + i, "lblP_Task" + i });
        foreach (var m in new[] { "Lead", "Wing1", "Element", "Wing4" })
            l.AddRange(new[] { "txt" + m + "_IDM", "txt" + m + "_TCN", "txt" + m + "_TO", "txt" + m + "_Lnd", "txt" + m + "_Mode23" });
        l.AddRange(new[] { "txtLead_Lsr", "txtWing1_Lsr", "txtElement_lsr", "txtWing4_Lsr" });
        foreach (var a in new[] { "Dep", "Arr", "Altn" })
            l.AddRange(new[] { "lbl" + a + "Name", "lbl" + a + "TCN", "lbl" + a + "Elv", "lbl" + a + "RWY", "lbl" + a + "ILS" });
        l.AddRange(new[] { "lblDEP_UHF", "lblARR_UHF", "lblALTN_UHF", "lblDEP_VHF", "lblARR_VHF", "lblALTN_VHF", "txtRwy1", "txtRwy2" });
        l.AddRange(new[] { "txtExtra1", "txtExtra2", "txtExtra3", "txtExtra4", "txtExtra5" });
        l.AddRange(new[] { "txtEws1", "txtEws2", "txtEws3", "txtEws4", "txtEws5", "txtEws6", "txtALOW", "txtMSL", "txtBingo" });
        l.AddRange(new[] {
            "txtWpn_SubMode1", "txtWpn_Fuse1", "txtWpn_ArmDly1", "txtWpn_BA1", "txtWpn_RelAngle1", "txtWpn_SGLPAIR1", "txtWpn_Ripple1", "txtWpn_Space1", "txtLaserLST1", "txtLaserCode1",
            "txtWpn_SubMode2", "txtWpn_Fuse2", "txtWpn_ArmDly2", "txtWpn_BA2", "txtWpn_RelAngle2", "txtWpn_SGLPAIR2", "txtWpn_Ripple2", "txtWpn_Space2", "txtLaserLST2", "txtLaserCode2",
        });
        l.AddRange(new[] {
            "lblVipVrp", "txtWpn_AttackType",
            "lblVIPbrg", "lblVIPrng", "lblVIPelv", "lblPUPbrg", "lblPUPrng", "lblPUPelv", "lblOA1brg", "lblOA1rng", "lblOA1elv", "lblOA2brg", "lblOA2rng", "lblOA2elv",
            "lblPullHdg", "lblIngrHgt", "lblIngrSpd", "lblRelHgt", "lblRelSpd", "lblAttHed", "lblDA", "lblClimb", "lblTurn", "lblTGTHUD",
        });
        l.AddRange(new[] { "lblAtisType", "lblAtis1", "lblAtis2" });
        foreach (var t in new[] { "txtTanker1", "txtTanker2", "txtAWACS", "txtJSTAR" })
            l.AddRange(new[] { t, t + "_Notes", t + "_TCN", t + "_Loc", t + "_UHF" });
        l.AddRange(new[] { "cboTanker1.items", "cboTanker2.items" });
        l.Add("lblCommPackage");
        for (int i = 1; i <= 5; i++)
            l.AddRange(new[] {
                "lblCommCallsign" + i, "lblCommAc" + i, "lblCommTask" + i, "lblCommTO" + i, "lblCommTaxi" + i, "txtCommHoldPt" + i, "txtCommHoldAlt" + i,
                "lblCommCalls" + i, "lblCommFreq" + i, "lblCommTcn" + i, "txtCommTransAlt" + i, "lblCommPushTime" + i, "lblCommPushAlt" + i, "lblCommTot" + i,
            });
        for (int i = 1; i <= 9; i++)
            l.AddRange(new[] { "numRouteStpt" + i, "lblCommFunc" + i, "lblCommName" + i, "lblCommLat" + i, "lblCommLon" + i });
        l.AddRange(new[] { "lblLat", "lblLon", "txtStdQnh", "txtTransitLvl", "lblRwyTaxiTime1", "lblRwyTaxiTime2", "numTaxi1", "numTaxi2" });
        // the cartridge's boxes on the DTC page that the weapon boxes' Leave handlers write back to
        foreach (var w in new[] { "ArmDly", "BA", "RelAngle", "Ripple", "Space" }) for (int i = 1; i <= 2; i++) l.Add("dtc.txtWpn_" + w + i);
        // the radio buttons: which are enabled, and the pilot seats; the elevations colour; the tanker lists selection
        for (int i = 1; i <= 5; i++) l.Add("rbnCallsign" + i + ".enabled");
        foreach (var s in new[] { "rbnLead", "rbnWing", "rbnElmLead", "rbnElmWing" }) { l.Add(s); l.Add(s + ".enabled"); }
        l.AddRange(new[] { "lblDepElv.fore", "lblArrElv.fore", "lblAltnElv.fore", "cboTanker1.sel", "cboTanker2.sel" });
        // the weapon-mode lists, the card's and the DTC page's
        foreach (var c in new[] { "cboSubMode1", "cboFuze1", "cboSGL_PAIR1", "cboSubMode2", "cboFuze2", "cboSGL_PAIR2" }) l.Add(c + ".sel");
        foreach (var c in new[] { "cboP1_SubMode", "cboP1_Fuze", "cboP1_SGL_PAIR", "cboP2_SubMode", "cboP2_Fuze", "cboP2_SGL_PAIR" }) l.Add("dtc." + c + ".sel");
        return l;
    }

    // ------------------------------------------------------------------ reflection helpers
    static object Get(object o, string name)
    {
        var t = o.GetType();
        var p = t.GetProperty(name, Any);
        if (p != null) return p.GetValue(o, null);
        var f = t.GetField(name, Any);
        if (f == null) throw new Exception("no member " + name + " on " + t.Name);
        return f.GetValue(o);
    }

    static void Set(object o, string name, object v)
    {
        var t = o.GetType();
        var f = t.GetField(name, Any);
        if (f != null) { f.SetValue(o, Convert.ChangeType(v, f.FieldType, CultureInfo.InvariantCulture)); return; }
        var p = t.GetProperty(name, Any);
        if (p == null) throw new Exception("no member " + name + " on " + t.Name);
        p.SetValue(o, v, null);
    }

    static void SetRaw(object o, string name, object v)
    {
        var t = o.GetType();
        var f = t.GetField(name, Any);
        if (f != null) { f.SetValue(o, v); return; }
        t.GetProperty(name, Any).SetValue(o, v, null);
    }

    static object Call(object o, string m, params object[] a)
    {
        var mi = o.GetType().GetMethods(Any).First(x => x.Name == m && x.GetParameters().Length == a.Length);
        try { return mi.Invoke(o, a); }
        catch (TargetInvocationException e) { throw new Exception(m + ": " + e.InnerException.GetType().Name + " " + e.InnerException.Message, e.InnerException); }
    }

    static void Click(object o, string handler) { Call(o, handler, null, EventArgs.Empty); }

    // the weapon box on the card -> its cartridge box on the DTC page (mxtP<n>_<suffix>)
    static readonly Dictionary<string, string> WpnMxt = new Dictionary<string, string> { { "ArmDly", "C1_AD1" }, { "BA", "C2_BA" }, { "RelAngle", "Angle" }, { "Ripple", "Pulse" }, { "Space", "Spacing" } };
    static object Dtc;

    static string Text(object dc, string name)
    {
        if (name.StartsWith("dtc.cbo")) return ((ComboBox)Get(Dtc, name.Substring(4, name.Length - 8))).SelectedIndex.ToString(CultureInfo.InvariantCulture);
        if (name.StartsWith("dtc.txtWpn_"))
        {
            var k = name.Substring(11, name.Length - 12);
            return ((Control)Get(Dtc, "mxtP" + name.Substring(name.Length - 1) + "_" + WpnMxt[k])).Text;
        }
        if (name.EndsWith(".text")) return ((Control)Get(dc, name.Substring(0, name.Length - 5))).Text;
        if (name.EndsWith(".enabled")) return ((Control)Get(dc, name.Substring(0, name.Length - 8))).Enabled.ToString();
        if (name.EndsWith(".fore")) return ((Control)Get(dc, name.Substring(0, name.Length - 5))).ForeColor.Name;
        if (name.EndsWith(".sel")) return ((ComboBox)Get(dc, name.Substring(0, name.Length - 4))).SelectedIndex.ToString(CultureInfo.InvariantCulture);
        if (name.EndsWith(".items")) return string.Join("~", ((ComboBox)Get(dc, name.Substring(0, name.Length - 6))).Items.Cast<object>().Select(x => Convert.ToString(x)));
        var c = Get(dc, name);
        if (c is RadioButton rb) return rb.Checked ? "checked" : "unchecked";
        if (c is NumericUpDown nu) return nu.Value.ToString(CultureInfo.InvariantCulture);
        return ((Control)c).Text;
    }

    // set a field of the element i of a struct array held in field `arr` of `owner`
    static void SetStruct(object owner, string arr, int i, Dictionary<string, object> values)
    {
        var a = (Array)Get(owner, arr);
        var el = a.GetValue(i);
        foreach (var kv in values)
        {
            var f = el.GetType().GetField(kv.Key, Any);
            f.SetValue(el, kv.Value == null ? null : Convert.ChangeType(kv.Value, f.FieldType, CultureInfo.InvariantCulture));
        }
        a.SetValue(el, i);
    }

    static void TrySet(object o, string name, object v) { try { Set(o, name, v); } catch { } }

    // ------------------------------------------------------------------ VB's own idea of a number
    // The card reads what a pilot typed through Versioned.IsNumeric and Conversions.To*. These are the runtime's own,
    // called directly: to write down what they answer (the in_vbnum column, which the port's DataCardNet must match),
    // and to keep the harness away from the inputs on which WDP itself stops with an unhandled exception.
    static MethodInfo vbIsNumeric, vbToInteger, vbToShort, vbToDouble, vbToSingle;
    static void LoadVb()
    {
        var vb = Assembly.Load("Microsoft.VisualBasic, Version=10.0.0.0, Culture=neutral, PublicKeyToken=b03f5f7f11d50a3a");
        vbIsNumeric = vb.GetType("Microsoft.VisualBasic.CompilerServices.Versioned").GetMethod("IsNumeric", new[] { typeof(object) });
        var conv = vb.GetType("Microsoft.VisualBasic.CompilerServices.Conversions");
        vbToInteger = conv.GetMethod("ToInteger", new[] { typeof(string) });
        vbToShort = conv.GetMethod("ToShort", new[] { typeof(string) });
        vbToDouble = conv.GetMethod("ToDouble", new[] { typeof(string) });
        vbToSingle = conv.GetMethod("ToSingle", new[] { typeof(string) });
    }
    static string VbCall(MethodInfo m, string s)
    {
        try
        {
            var v = m.Invoke(null, new object[] { s });
            if (v is double d) return d.ToString("R", CultureInfo.InvariantCulture);
            if (v is float f) return f.ToString("R", CultureInfo.InvariantCulture);
            return Convert.ToString(v, CultureInfo.InvariantCulture);
        }
        catch (TargetInvocationException e) { return "!" + e.InnerException.GetType().Name; }
    }
    static bool VbNum(string s) => VbCall(vbIsNumeric, s) == "True";
    static bool VbNumSafe(string s) => !VbCall(vbIsNumeric, s).StartsWith("!");
    static bool Ok(MethodInfo m, string s) => !VbCall(m, s).StartsWith("!");
    static bool VbInt(string s) => VbNum(s) && Ok(vbToInteger, s);
    static bool VbShort(string s) => VbNum(s) && Ok(vbToShort, s);
    static bool VbDbl(string s) => VbNum(s) && Ok(vbToDouble, s);
    static bool VbSng(string s) => VbNum(s) && Ok(vbToSingle, s);

    // non-printing characters and the replay's separators written as \uXXXX
    static string Esc(string s)
    {
        var sb = new StringBuilder();
        foreach (var c in s)
        {
            if (c < 32 || c > 126 || c == '\\' || c == '|' || c == '~' || c == '=' || c == ';' || c == ':' || c == ',') sb.Append("\\u").Append(((int)c).ToString("x4"));
            else sb.Append(c);
        }
        return sb.ToString();
    }

    // what VB accepts as a number and a plain reading does not, and the other way round
    static readonly string[] OddNumbers = {
        "&H10", "&H1F", "&O17", "&h7FF", "$250", "$1,200.50", "(300)", "(2.5)", "1e3", "1.5E+2", "2E-1", " 7 ", "7-", "7+", "- 7", "+ 7",
        "1,500", ",5", "5,", "2.5", "3.5", "-0", "+12", "0x10", "1 000", "１２", "12e", "e5", ".", "-", "..5", "5..", "Infinity", "-Infinity",
        "NaN", "1.", ".5", "00012", "1,2,3", "12 ", " 12", "12 ", "£5", "€5", "%5", "5%", "1d", "1f", "1.0e1.0", "TRUE", "&H", "&HFFFFFFFF",
        "&H+5", "&H0x10", " &H10", "　&H10", "&H10 ", "&O8", "∞", "-∞", " NaN ", "1e309", "4e38", "2147483648", "32768", "$-5", "-$5", "$ 5", "5 $",
        "($5)", "(-5)", "1,e5", "1e0001", "1e-400", "4\u0000", "\u00004", "\t5\r", "\u000B5\u000C", "12Y", "1,,2", "&H1F400", "(1111)", "&H457", "2888.4",
    };
    // pieces for random strings, in the same spirit
    static readonly string[] NumAtoms = { "1", "2", "5", "9", "0", "12", "007", ".", ",", "e", "E", "+", "-", "(", ")", "$", " ", " ", "&H", "&O", "&h",
        "F", "a", "x", "NaN", "∞", "\t", "\u0000", "3.5", "1e3", "&", "o", "H" };
    static string OddNumber() => P(0.5) ? Pick(OddNumbers) : string.Concat(Enumerable.Range(0, R(1, 5)).Select(_ => Pick(NumAtoms)));
    // one to type into a box: no control characters (the TSV and the replay's separators stay intact)
    static string OddEdit()
    {
        while (true) { var s = OddNumber(); if (s.All(c => c >= 32)) return s; }
    }

    // ------------------------------------------------------------------ message boxes never stop the run
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc cb, IntPtr l);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
    delegate bool EnumWindowsProc(IntPtr h, IntPtr l);
    static volatile int boxes;

    static void Watchdog()
    {
        uint me = (uint)System.Diagnostics.Process.GetCurrentProcess().Id;
        while (true)
        {
            Thread.Sleep(200);
            EnumWindows((h, l) =>
            {
                GetWindowThreadProcessId(h, out uint pid);
                if (pid != me || !IsWindowVisible(h)) return true;
                var sb = new StringBuilder(64);
                GetClassName(h, sb, 64);
                if (sb.ToString() == "#32770") { boxes++; PostMessage(h, 0x0010, IntPtr.Zero, IntPtr.Zero); }
                return true;
            }, IntPtr.Zero);
        }
    }

    // ------------------------------------------------------------------ random inputs
    static Random rng;
    static int R(int lo, int hi) => rng.Next(lo, hi + 1);
    static bool P(double p) => rng.NextDouble() < p;
    static T Pick<T>(params T[] xs) => xs[rng.Next(xs.Length)];
    static string Word(int min, int max)
    {
        const string cs = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 -./";
        int n = R(min, max);
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.Append(cs[rng.Next(cs.Length)]);
        return sb.ToString();
    }
    static string F(float v) => v.ToString("R", CultureInfo.InvariantCulture);
    static string D(double v) => v.ToString("R", CultureInfo.InvariantCulture);

    static readonly string[] Callsigns = { "Viper 1", "Cowboy 2", "Falcon 3", "Hawg 4", "Ouranos 5", "Rider 5", "Texaco 1", "Dragnet 5", "Uzi 6", "Chevy 7" };
    static readonly string[] Types = { "F-16C-52", "F-16CM-50", "F-4E", "F-15E", "A-10C", "F/A-18C", "Mirage 2000" };
    static readonly string[] Tasks = { "Strike", "SEAD", "Escort", "Sweep", "BARCAP", "DEAD", "CAS", "OCA" };
    static readonly string[] Airports = { "Kunsan", "Osan AB", "Larissa", "Nea Anchialos", "Souda", "Araxos", "Tirana", "Skopje" };

    // where the row is: the stage an unhandled exception in WDP stops it in (in_threw), and the row's inputs so far
    static string Stage = "";
    static Dictionary<string, string> CurInp;
    static void St(string s) { Stage = s; }
    // the harness-only exceptions after a handler's card part, by where they were raised
    static readonly Dictionary<string, int> Tails = new Dictionary<string, int>();
    static void Tail(Exception ex)
    {
        var st = ((ex.InnerException ?? ex).StackTrace ?? "").Split((char)10);
        var key = Stage + " " + (ex.InnerException ?? ex).GetType().Name + " " + (st.FirstOrDefault(l => l.Contains("WeaponDeliveryPlanner.")) ?? st.FirstOrDefault() ?? "").Trim();
        key = System.Text.RegularExpressions.Regex.Replace(key, "^Step[0-9]+", "Step");
        Tails[key] = Tails.TryGetValue(key, out var n) ? n + 1 : 1;
    }

    // an exception that came out of what a handler does after the card's own part (the map, the loadout form, the
    // performance page): those read campaign tables this harness does not build, so they are not WDP stopping on
    // the card. Only exceptions raised inside the named card methods count.
    static bool FromCard(Exception ex, params string[] cardMethods)
    {
        var st = (ex.InnerException ?? ex).StackTrace ?? "";
        // the frame that threw is the first line
        var first = st.Split('\n').FirstOrDefault() ?? "";
        foreach (var m in cardMethods) if (st.Contains("cntDataCard." + m + "(") || st.Contains("cntDTC." + m + "(")) return true;
        return false;
    }

    // the weapon-mode lists: the DTC page's, the card's, the boxes that open them and the buttons that take them
    static readonly string[] DtcModeLists = { "cboP1_SubMode", "cboP1_Fuze", "cboP1_SGL_PAIR", "cboP2_SubMode", "cboP2_Fuze", "cboP2_SGL_PAIR" };
    static readonly string[] CardModeLists = { "cboSubMode1", "cboFuze1", "cboSGL_PAIR1", "cboSubMode2", "cboFuze2", "cboSGL_PAIR2" };
    static readonly string[] WpnBoxClick = { "txtWpn_Mode1_Click", "txtWpn_Fuse1_Click", "txtWpn_SGLPAIR1_Click", "txtWpn_Mode2_Click", "txtWpn_Fuse2_Click", "txtWpn_SGLPAIR2_Click" };
    static readonly string[] WpnTakeClick = { "btnMode1_Click", "btnFuse1_Click", "btnSGL_PAIR1_Click", "btnMode2_Click", "btnFuse2_Click", "btnSGL_PAIR2_Click" };

    static readonly string[] NonAscii = { "İzmir 2", "ΟΥΡΑΝΟΣ 1", "Straße 3", "\u212Aelvin 4", "σίγμα 6", "ÆGIR 8" };
    static string RadioName(string cs)
    {
        switch (cs)
        {
            case "İzmir 2": return Pick("izmir2", "İzmir 2", "IZMIR2", "izmir 2", "İZMİR2", "i̇zmir2");
            case "ΟΥΡΑΝΟΣ 1": return Pick("ουρανοσ1", "ΟΥΡΑΝΟΣ1", "ουρανοσ 1", "ΟυρανοΣ1", "ουρανος1");
            case "Straße 3": return Pick("straße3", "STRAßE 3", "Straße3", "STRASSE3");
            case "\u212Aelvin 4": return Pick("kelvin4", "Kelvin 4", "\u212Aelvin4");
            case "σίγμα 6": return Pick("ΣΊΓΜΑ6", "σίγμα6", "ΣΙΓΜΑ 6");
            case "ÆGIR 8": return Pick("ægir8", "AEGIR8", "ÆGIR 8");
        }
        return P(0.3) ? cs.Replace(" ", "").ToLowerInvariant() : P(0.1) ? cs.ToUpperInvariant() : cs;
    }

    public static int Run(string exePath, string outPath)
    {
        Thread.CurrentThread.CurrentCulture = new CultureInfo("en-US");
        Thread.CurrentThread.CurrentUICulture = new CultureInfo("en-US");
        new Thread(Watchdog) { IsBackground = true }.Start();

        var full = Path.GetFullPath(exePath);
        var asm = Assembly.LoadFrom(full);
        var utils = Assembly.LoadFrom(Path.Combine(Path.GetDirectoryName(full), "BMSUtils.dll"));
        var dbwdp = Assembly.LoadFrom(Path.Combine(Path.GetDirectoryName(full), "dbwdp.dll"));
        var myProject = asm.GetType("WeaponDeliveryPlanner.My.MyProject", true);
        var forms = myProject.GetProperty("Forms", Any).GetValue(null, null);
        var main = forms.GetType().GetProperty("fclsMain", Any).GetValue(forms, null);
        var dc = Get(main, "cntDataCard");
        var dtc = Get(main, "cntDTC");
        Dtc = dtc;
        var popup = Get(main, "cntPopUp");
        var toss = Get(main, "cntTOSS");
        var hadb = Get(main, "cntHADB");
        var perf = Get(main, "cntPerformance");
        var flightT = utils.GetType("BMSUtils.Flight", true);
        var wpT = utils.GetType("BMSUtils.Waypoint", true);
        var fltRadT = asm.GetType("WeaponDeliveryPlanner.fclsMain+FltRad", true);
        var aptT = dbwdp.GetType("dbwdp.DatabaseStructs+Airport", true);
        LoadVb();
        // the program runs under BMS, not Allied Force
        Set(main, "blnVersion", true);
        foreach (var o in new[] { dc, dtc, popup, toss, hadb, perf }) TrySet(o, "blnVersion", true);
        // the DTC page has been loaded (its Load sets this; FillNAVOFFSETSdata only refills its spinners then)
        Set(dtc, "blnLoaded", true);

        var inCols = new List<string> {
            "in_row", "in_build", "in_minor", "in_sel", "in_flights", "in_apts", "in_altnFuel", "in_swingFlpn", "in_swingClicks", "in_fuelEdits",
            "in_packages", "in_selFltInPack", "in_names", "in_mission", "in_pkgName", "in_fltRadio", "in_milP", "in_ews", "in_wpn", "in_laser",
            "in_profile", "in_modesel", "in_popRef", "in_tossRef", "in_dtcNums", "in_pages", "in_setRange", "in_rangeClicks",
            "in_atis", "in_atisClicks", "in_kmsm", "in_kmsmClicks", "in_tl", "in_taxiTime", "in_taxi", "in_swing", "in_latClicks", "in_routeEdits",
            "in_arrStpt", "in_tankers", "in_awacs", "in_jstar", "in_tankerPick", "in_latlon", "in_fieldEdits", "in_cut", "in_boxes", "in_vbnum", "in_dtcInit", "in_taxi2",
            "in_threw", "in_keep", "in_nav", "in_dtcLoaded", "in_steps", "in_geo", "in_dtcCbo",
        };
        var sb = new StringBuilder();
        sb.Append(string.Join("\t", inCols));
        foreach (var o in Outputs) sb.Append('\t').Append(o);
        sb.AppendLine();

        // the cartridge's EWS programs, whose comments the EWS boxes' Leave handlers write back
        var ews0 = Get(main, "CampEWS");
        var ewsProgT = asm.GetType("WeaponDeliveryPlanner.fclsMain+EWS_Program", true);
        ews0.GetType().GetField("Program").SetValue(ews0, Array.CreateInstance(ewsProgT, 6));
        SetRaw(main, "CampEWS", ews0);

        string[] seatNames = { "rbnLead", "rbnWing", "rbnElmLead", "rbnElmWing" };
        string[] offNames = { "VIP", "VIPPUP", "VRP", "VRPPUP", "OA1_1", "OA2_1", "OA1_2", "OA2_2" };
        string[] formations = { "Spread", "Wedge", "Trail", "Ladder", "Stack", "ResCell", "Box", "Arrowhead", "Fluid", "Vic", "EchelonRight", "Finger 4", "LineAbreast", "EchelonLeft", "Diamond" };
        int tails = 0;

        const int Rows = 2400;
        int written = 0, crashed = 0;
        var errors = new StringBuilder();
        for (int row = 0; row < Rows; row++)
        {
            rng = new Random(9173 + row * 7919);
            var inp = new Dictionary<string, string>();
            CurInp = inp; Stage = "";
            inp["in_row"] = row.ToString(CultureInfo.InvariantCulture);
            int before = boxes;
            try
            {
            // ---------------------------------------------------------------- the campaign
            var ver = Pick(new[] { 38, 17950 }, new[] { 38, 17837 }, new[] { 34, 16501 }, new[] { 34, 16500 }, new[] { 33, 13500 }, new[] { 38, 18100 }, new[] { 36, 12345 }, new[] { 35, 12345 });
            int minor = ver[0], build = ver[1];
            inp["in_build"] = build.ToString(); inp["in_minor"] = minor.ToString();
            int nFlights = R(1, 5);
            int sel = R(0, nFlights - 1);
            inp["in_sel"] = sel.ToString();
            var flights = Array.CreateInstance(flightT, nFlights);
            var flightEnc = new List<string>();
            for (int k = 0; k < nFlights; k++)
            {
                var f = Activator.CreateInstance(flightT, true);
                int nWp = k == sel ? (P(0.1) ? R(1, 3) : (P(0.15) ? R(20, 24) : P(0.02) ? R(25, 27) : R(4, 16))) : R(2, 12);
                var wps = Array.CreateInstance(wpT, nWp);
                var enc = new List<string>();
                int gx = R(20, 900), gy = R(20, 900);
                bool far = P(0.02);
                long t = P(0.2) ? R(0, 3) * 86400000L + R(0, 86399999) : R(3, 20) * 3600000L + R(0, 3599) * 1000L + (P(0.3) ? R(0, 999) : 0);
                bool landed = false;
                for (int i = 0; i < nWp; i++)
                {
                    var w = Activator.CreateInstance(wpT, true);
                    int act;
                    if (i == 0) act = P(0.9) ? 1 : Pick(0, 2, 8);
                    else if (landed) act = P(0.7) ? 7 : Pick(0, 27);
                    else if (i == nWp - 1) act = P(0.8) ? 7 : Pick(0, 4, 8);
                    else if (P(0.02)) { act = 7; }
                    else act = P(0.55) ? 0 : Pick(2, 3, 4, 5, 6, 8, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 28, 29, 30, 31, 45);
                    if (act == 7) landed = true;
                    int px = gx, py = gy;
                    if (i > 0)
                    {
                        if (P(0.03)) { gx = 0; gy = 0; }
                        else if (far && P(0.5)) { gx = Pick(-32768, 32767, -20000, 30000); gy = Pick(0, 32767, -32768, 25000); }
                        else if (P(0.06)) { /* same cell */ }
                        else if (P(0.06)) { gx += R(-40, 40); }
                        else if (P(0.06)) { gy += R(-40, 40); }
                        else { gx += R(-70, 70); gy += R(-70, 70); }
                        if (!far) { if (gx < 0) gx = -gx; if (gy < 0) gy = -gy; }
                    }
                    int gz = i == 0 ? (P(0.5) ? 0 : R(0, 80)) : Pick(0, 30, 50, 51, 200, 250, 251, 500, 1500, 2500, 2501, 3500, R(0, 4500), R(0, 4500), R(0, 4500));
                    // TasToIas (inside CreateFlightplan) goes NaN above ~93,195 ft and the plan stops there
                    if (P(0.02)) gz = Pick(9319, 9320, 14544, 14545, 20000, 32767, R(9000, 32767), -1, -100, -32768, R(-500, -1));
                    uint arrive, depart;
                    if (i > 0)
                    {
                        if (P(0.04)) t -= R(0, 600000);
                        else if (P(0.05)) { }
                        else if (P(0.65))
                        {
                            // flown at a real speed: the leg's length at 250-650 kt, to the second or the millisecond
                            double ft = Math.Sqrt((double)(gx - px) * (gx - px) + (double)(gy - py) * (gy - py)) * 3279.98;
                            double ms = ft / 6076.12 / R(250, 650) * 3600000.0;
                            t += P(0.5) ? (long)Math.Round(ms / 1000.0) * 1000L : (long)ms + R(0, 999);
                        }
                        else t += P(0.1) ? R(1000, 30000) : R(60000, 1500000) + (P(0.3) ? R(0, 999) : 0);
                        if (t < 0) t = 0;
                    }
                    arrive = (uint)t;
                    long dep = t;
                    if ((act == 8 && P(0.6)) || P(0.05)) dep = t + R(1000, 1200000);
                    if (P(0.02)) dep = t - R(0, 30000);
                    if (dep < 0) dep = 0;
                    depart = (uint)dep;
                    if (landed && act == 27 && P(0.5)) { arrive = 0; depart = 0; }
                    t = Math.Max(t, dep);
                    int form = R(0, 255);
                    Set(w, "GridX", (short)gx); Set(w, "GridY", (short)gy); Set(w, "GridZ", (short)gz);
                    Set(w, "Arrive", arrive); Set(w, "Depart", depart); Set(w, "Action", (byte)act); Set(w, "Formation", (byte)form);
                    wps.SetValue(w, i);
                    enc.Add(string.Join(",", (short)gx, (short)gy, (short)gz, arrive, depart, act, form));
                }
                int nAc = R(1, 4);
                var ps = new byte[4];
                for (int j = 0; j < 4; j++) ps[j] = (byte)(j < nAc ? R(1, 9) : 0);
                if (P(0.1)) ps[R(0, 3)] = (byte)R(0, 5);
                var lc = new int[4];
                for (int j = 0; j < 4; j++) lc[j] = R(1111, 1788);
                // the flight's own count, now and then not the array's length (past 255, FillFlightplan's checked
                // byte cast stops WDP)
                int numWp = k == sel && P(0.006) ? Pick(R(256, 400), nWp + 1, Math.Max(0, nWp - 1)) : nWp;
                SetRaw(f, "waypoints", wps); Set(f, "numWaypoints", (ushort)numWp);
                SetRaw(f, "plane_stats", ps); SetRaw(f, "laserCode", lc);
                flights.SetValue(f, k);
                flightEnc.Add(string.Join(",", ps) + ";" + string.Join(",", lc) + ";" + string.Join(":", enc) + (numWp != nWp ? ";" + numWp : ""));
            }
            inp["in_flights"] = string.Join("|", flightEnc);
            SetRaw(main, "FlightTable", flights);
            Set(main, "SelFlightNr", sel);
            Set(main, "Build", build); Set(main, "MinorPart", minor); Set(main, "intFileVer", 108);
            // the DTC page's weapon-mode lists as the cartridge left them (set while no campaign is open, so their
            // SelectedIndexChanged, which fills the card, does nothing the row's own strings do not replace)
            Set(main, "blnLoaded", false);
            var dtcCbo = new List<string>();
            for (int i = 0; i < 6; i++)
            {
                var cbo = (ComboBox)Get(dtc, DtcModeLists[i]);
                cbo.SelectedIndex = R(-1, cbo.Items.Count - 1);
                dtcCbo.Add(cbo.SelectedIndex.ToString(CultureInfo.InvariantCulture));
            }
            inp["in_dtcCbo"] = string.Join(",", dtcCbo);
            Set(main, "blnLoaded", true); Set(main, "blnMissionLoaded", true);

            // the theater's flight radio map
            bool radio = P(0.6);
            // the package: one entry per flight, in flight order, the rest empty
            var pk = new List<Dictionary<string, object>>();
            var used = new HashSet<string>();
            for (int k = 0; k < nFlights; k++)
            {
                string cs;
                do { cs = P(0.12) ? Pick(NonAscii) : Pick(Callsigns); } while (!used.Add(cs));
                var f = flights.GetValue(k);
                var w0 = ((Array)Get(f, "waypoints")).GetValue(0);
                int nw = ((Array)Get(f, "waypoints")).Length;
                int push = P(0.8) ? R(0, Math.Max(0, nw - 1)) : R(0, nw + 2);
                int tgt = P(0.8) ? R(0, Math.Max(0, nw - 1)) : R(0, nw + 2);
                uint to = (uint)Get(w0, "Depart");
                if (to < 700000 && P(0.9)) to = 0;
                if (to != 0 && to < 700000) to = 700000;
                // a take-off before the taxi time: FillCommCard's checked (uint)(TakeOffTime - TaxiTime) stops WDP
                if (P(0.005)) to = (uint)R(1, 350000);
                var d = new Dictionary<string, object> {
                    { "Callsign", P(0.05) ? "" : cs }, { "AC_Nr", P(0.08) ? 0 : R(1, 4) }, { "AC_Type", Pick(Types) }, { "Task", Pick(Tasks) },
                    { "FltNr", P(0.05) ? R(nFlights, nFlights + 2) : k }, { "TakeOffTime", to }, { "PushStpt", (byte)push }, { "TargetStpt", (byte)tgt }, { "F16", P(0.3) },
                };
                pk.Add(d);
            }
            var radios = new List<string>();
            if (radio)
            {
                foreach (var d in pk)
                {
                    var cs = (string)d["Callsign"];
                    // a flight missing from a non-empty radio map makes FillAutoPackages index FltRadio[-1] and stop
                    if (P(0.01)) continue;
                    var name = RadioName(cs);
                    string uhf = R(225000, 399975).ToString(), vhf = R(30000, 150975).ToString();
                    // the radio map as VB reads it: hex, currency, parentheses, exponents
                    if (P(0.1)) { var o = OddEdit(); if (VbDbl(o)) vhf = o; }
                    if (P(0.1)) { var o = OddEdit(); if (VbDbl(o)) uhf = o; }
                    radios.Add(name + "~" + uhf + "~" + vhf);
                }
                // the support tracks' entries (FillTanker/FillAwacs/FillJSTAR look them up)
                if (P(0.4))
                    foreach (var nm in new[] { "Texaco 1", "Arco 2", "Shell 3", "Dragnet 5", "Magic 1", "ΑΣ 7", "Σ 8" })
                        if (P(0.5)) radios.Add((nm == "ΑΣ 7" ? Pick("ασ7", "ας 7", "ΑΣ 7") : nm == "Σ 8" ? Pick("σ8", "ς8") : P(0.3) ? nm.Replace(" ", "").ToLowerInvariant() : nm) + "~" + R(225000, 399975) + "~" + R(30000, 150975));
            }
            if (radio && radios.Count == 0) radio = false;
            inp["in_fltRadio"] = radio ? string.Join("|", radios) : "";
            var fr = Array.CreateInstance(fltRadT, radio ? radios.Count : 0);
            for (int i = 0; i < fr.Length; i++)
            {
                var p = radios[i].Split('~');
                var e = Activator.CreateInstance(fltRadT);
                fltRadT.GetField("Callsign").SetValue(e, p[0]);
                fltRadT.GetField("Uhf").SetValue(e, p[1]);
                fltRadT.GetField("Vhf").SetValue(e, p[2]);
                fr.SetValue(e, i);
            }
            SetRaw(main, "FltRadio", fr);

            // one airport, for the transition level
            int tl = Pick(0, 0, 50, 70, 100, 180, 245);
            inp["in_tl"] = tl.ToString();
            var at = Array.CreateInstance(aptT, 1);
            var ap = Activator.CreateInstance(aptT);
            aptT.GetField("id").SetValue(ap, (short)5);
            aptT.GetField("TL").SetValue(ap, (byte)tl);
            at.SetValue(ap, 0);
            SetRaw(main, "AirportTable", at);
            Set(main, "numAirports", 1);
            Set(main, "intSelApt", 5);

            // laser codes (ClearFlight and FillWeaponProfile read them)
            var laser = Get(main, "CampLaser");
            short lst = (short)R(1111, 1788), lcode = (short)R(1111, 1788);
            laser.GetType().GetField("LSTCode").SetValue(laser, lst);
            laser.GetType().GetField("LaserCode").SetValue(laser, lcode);
            SetRaw(main, "CampLaser", laser);
            inp["in_laser"] = lst + "," + lcode;
            var nav = Get(main, "CampNavOffsets");
            nav.GetType().GetField("Modesel").SetValue(nav, 0);
            SetRaw(main, "CampNavOffsets", nav);
            // what the Pop-up, HADB and TOSS pages last worked out (the profile buttons copy it into the cartridge)
            var navEnc = new List<string>();
            foreach (var src in new[] { "PopUpNavOffsets", "HADBNavOffsets", "TOSSNavOffsets" })
            {
                var no = Get(main, src);
                int ms = Pick(0, 1, 2, 1, 2, 3, -1);
                no.GetType().GetField("Modesel").SetValue(no, ms);
                var parts = new List<string> { ms.ToString() };
                foreach (var on in offNames)
                {
                    var fo = no.GetType().GetField(on);
                    var o = fo.GetValue(no);
                    int stpt = R(0, 30);
                    float brg = P(0.003) ? float.NaN : Pick(R(0, 3600) / 10f, R(0, 360000) / 1000f, R(0, 3600000) / 10000f, 359.95f, 0.05f, 0.15f, 0.25f, 0.35f, 2.675f, -1f, -0.04f, 360.04f, 361f, 999999.5f, 1234567f, 123.45f, 1e-8f);
                    int rngv = Pick(R(0, 999999), R(0, 60000), 0, -5, 1000000, 6076, int.MaxValue);
                    int elv = Pick(R(0, 30000), 0, -1, 999999, 1000000);
                    o.GetType().GetField("Stpt").SetValue(o, stpt); o.GetType().GetField("Bearing").SetValue(o, brg);
                    o.GetType().GetField("Range").SetValue(o, rngv); o.GetType().GetField("Elv").SetValue(o, elv);
                    fo.SetValue(no, o);
                    parts.Add(stpt + "," + F(brg) + "," + rngv + "," + elv);
                }
                SetRaw(main, src, no);
                navEnc.Add(string.Join(",", parts));
            }
            inp["in_nav"] = string.Join("|", navEnc);
            inp["in_dtcLoaded"] = "1";

            // ---------------------------------------------------------------- the card's own toggles, explicitly
            bool atis = P(0.5), kmsm = P(0.5), swing = P(0.5), swingFlpn = P(0.5), keep = P(0.25);
            int setRange = R(0, 2);
            int altnFuel = P(0.02) ? 2147483000 : Pick(1000, 1000, 0, 800, 1500, R(0, 3000));
            int taxiTime = Pick(360000, 360000, 360000, 0, 700000);
            inp["in_atis"] = atis ? "1" : "0"; inp["in_kmsm"] = kmsm ? "1" : "0"; inp["in_swing"] = swing ? "1" : "0";
            inp["in_swingFlpn"] = swingFlpn ? "1" : "0"; inp["in_setRange"] = setRange.ToString(); inp["in_altnFuel"] = altnFuel.ToString();
            inp["in_taxiTime"] = taxiTime.ToString(); inp["in_keep"] = keep ? "1" : "0";
            Set(dc, "blnAtis", atis); Set(dc, "blnKmSm", kmsm); Set(dc, "blnSwing", swing); Set(dc, "blnSwingFlpn", swingFlpn);
            Set(dc, "intSetRange", setRange); Set(dc, "AltnFuel", altnFuel); Set(dc, "TaxiTime", taxiTime);
            Set(dc, "blnKeepNames", keep); Set(dc, "blnWth", false); Set(dc, "Precision", false);
            // the chosen tankers outlive a mission in WDP (a second tanker index from the last mission can point past
            // this one's table); every row starts from the constructor's -1
            Set(dc, "FirstTanker", -1); Set(dc, "SecondTanker", -1);
            // the cartridge's weapon boxes, as the DTC page holds them when the row starts (they read back through
            // their masks, and that reading is what is written down)
            var dtcInit = new List<string>();
            foreach (var w in WpnMxt)
                for (int i = 1; i <= 2; i++)
                {
                    var mxt = (Control)Get(dtc, "mxtP" + i + "_" + w.Value);
                    mxt.Text = w.Key == "ArmDly" ? Pick("00.00", "05.25", "10.00", "01.50", R(0, 9999).ToString("0000"), R(0, 99).ToString()) : P(0.2) ? OddEdit() : R(0, 9999).ToString();
                    dtcInit.Add("txtWpn_" + w.Key + i + "=" + mxt.Text);
                }
            inp["in_dtcInit"] = string.Join("|", dtcInit);
            ((NumericUpDown)Get(dc, "numTaxi1")).Value = 6;
            ((NumericUpDown)Get(dc, "numTaxi2")).Value = 6;
            ((Label)Get(dc, "lblRwyTaxiTime1")).Text = "6";
            ((Label)Get(dc, "lblRwyTaxiTime2")).Text = "6";
            ((Label)Get(dc, "lblLat")).Text = swing ? "Brg" : "Latitude";
            ((Label)Get(dc, "lblLon")).Text = swing ? "Dist" : "Longitude";
            ((Label)Get(dc, "lblFormation")).Text = swingFlpn ? "Swing" : "Form";
            ((Label)Get(dc, "lblAtisType")).Text = "Military";
            foreach (var i in Enumerable.Range(1, 5)) ((RadioButton)Get(dc, "rbnCallsign" + i)).Checked = false;
            ((RadioButton)Get(dc, "rbnCallsign1")).Checked = true;
            // FillPackages enables the five radio buttons; SelectNewFlight ends with the pilot in the lead's seat
            foreach (var i in Enumerable.Range(1, 5)) ((RadioButton)Get(dc, "rbnCallsign" + i)).Enabled = true;
            ((RadioButton)Get(dc, "rbnLead")).Checked = true;
            var milP = R(80, 105) + " %";
            ((Label)Get(perf, "lblMilP_Val")).Text = milP;
            inp["in_milP"] = milP;

            // ---------------------------------------------------------------- SelectNewFlight
            Set(main, "blnSelectingFlight", true);
            St("ClearDatacard"); Call(dc, "ClearDatacard");
            St("CreateFlightplan"); Call(dc, "CreateFlightplan", false);
            St("");
            // FillPackages: what it would have put in Packages, from the flight table
            int selFlt = P(0.9) ? sel + 1 : R(0, 6);
            Set(dc, "SelFltInPack", selFlt);
            inp["in_selFltInPack"] = selFlt.ToString();
            for (int k = 0; k < pk.Count; k++) SetStruct(dc, "Packages", k, pk[k]);
            inp["in_packages"] = string.Join("|", pk.Select(d => string.Join("~", d["Callsign"], d["AC_Nr"], d["AC_Type"], d["Task"], d["FltNr"], d["TakeOffTime"], d["PushStpt"], d["TargetStpt"], (bool)d["F16"] ? "1" : "0")));
            St("FillAutoPackages"); Call(dc, "FillAutoPackages");
            // FillAutoFlight hands an F-16 flight's type to the performance page (cboType, TypeChange): its business
            St("FillAutoFlight");
            try { Call(dc, "FillAutoFlight"); } catch (Exception ex) when (!FromCard(ex, "FillAutoFlight", "FillBasicFlight")) { tails++; Tail(ex); }
            St("FillDataCard1"); Call(dc, "FillDataCard");
            St("FillCommCardPackages1"); Call(dc, "FillCommCardPackages");
            St("FillCommCard1"); Call(dc, "FillCommCard");
            St("");
            // Dep/Arr/AltnAirport: the three airports as they land in tblApt
            var apts = new List<string>();
            for (int a = 0; a < 3; a++)
            {
                float Freq() => P(0.3) ? 0f : (float)(R(108000, 399975) / 1000.0);
                var d = new Dictionary<string, object> {
                    { "Name", P(0.05) ? "" : Pick(Airports) }, { "TCN", P(0.1) ? "" : R(1, 126) + Pick("X", "Y") },
                    { "Elv", P(0.05) ? Pick(1e-5f, 0.0001f, 0.00012345f, 1.5e-6f, 12345678f, 1e7f, 9999999f, -0f) : P(0.2) ? (float)(R(0, 30000) / 10.0) : (float)R(0, 4000) }, { "ElvByTerrain", P(0.5) },
                    { "UHF", Freq() }, { "VHF", Freq() }, { "AtisVHF", Freq() }, { "GndUHF", Freq() }, { "AppUHF", Freq() }, { "OpsUHF", Freq() }, { "LsoUHF", Freq() },
                    { "RWY", Pick("09", "27", "33R", "15L", "") }, { "ILS", P(0.4) ? 0f : (float)(R(10800, 11195) / 100.0) },
                };
                SetStruct(dc, "tblApt", a, d);
                apts.Add(string.Join("~", d["Name"], d["TCN"], F((float)d["Elv"]), (bool)d["ElvByTerrain"] ? "1" : "0", F((float)d["UHF"]), F((float)d["VHF"]),
                    F((float)d["AtisVHF"]), F((float)d["GndUHF"]), F((float)d["AppUHF"]), F((float)d["OpsUHF"]), F((float)d["LsoUHF"]), d["RWY"], F((float)d["ILS"])));
            }
            inp["in_apts"] = string.Join("|", apts);
            // FillAptLabels ends in CheckChartButtons, which looks the airport up in the theater database for the
            // chart buttons (no label); with blnLoaded false it only hides them, which is all it can do here
            Set(main, "blnLoaded", false);
            St("FillAptLabels"); Call(dc, "FillAptLabels");
            Set(main, "blnLoaded", true);
            St("FillFlightplan"); Call(dc, "FillFlightplan");
            St("");
            // the support tracks the main form works out from the campaign (Tankers(), Awacs(), JSTAR())
            var tankT = asm.GetType("WeaponDeliveryPlanner.fclsMain+Tanker", true);
            var ownWp = (Array)Get(flights.GetValue(sel), "waypoints");
            int arrStpt = R(0, ownWp.Length - 1);
            Set(main, "SelFlightArrStpt", arrStpt);
            string Tracks(string field, string countField, int n, bool nullFirst)
            {
                var arr = Array.CreateInstance(tankT, Math.Max(n, 1));
                var enc = new List<string>();
                for (int i = 0; i < n; i++)
                {
                    var e = Activator.CreateInstance(tankT);
                    string nm = nullFirst && i == 0 ? null : Pick("Texaco 1", "Arco 2", "Shell 3", "Dragnet 5", "Magic 1", "Darkstar 2", "Axeman 1", "Bonus 4", "Texaco 1", "No Tanker", "", "ΑΣ 7", "Σ 8");
                    string tcn = P(0.15) ? null : P(0.1) ? "" : P(0.15) ? Word(1, 3).Replace(" ", "q") + "X" : R(1, 126) + Pick("X", "Y");
                    if (P(0.1)) tcn = OddEdit() + Pick("X", "Y");
                    // a channel VB calls a number but cannot make an Integer of stops FillTanker (unhandled)
                    if (tcn != null && tcn.Length > 1) { var c = tcn.Substring(0, tcn.Length - 1); if (!VbNumSafe(c) || (VbNum(c) && !VbInt(c))) tcn = "7X"; }
                    uint t1 = (uint)R(0, 30 * 3600) * 1000u, t2 = t1 + (uint)R(0, 6 * 3600) * 1000u;
                    string veh = Pick("KC-135", "KC-10", "E-3", "E-8C", "IL-78"), loc = Word(0, 10);
                    tankT.GetField("Name").SetValue(e, nm); tankT.GetField("Tcn").SetValue(e, tcn); tankT.GetField("VehType").SetValue(e, veh);
                    tankT.GetField("Loc").SetValue(e, loc); tankT.GetField("Time1").SetValue(e, t1); tankT.GetField("Time2").SetValue(e, t2);
                    arr.SetValue(e, i);
                    enc.Add((nm ?? "\u0001") + "~" + (tcn ?? "\u0001") + "~" + veh + "~" + loc + "~" + t1 + "~" + t2);
                }
                SetRaw(main, field, n == 0 && P(0.5) ? null : arr);
                Set(main, countField, n);
                return string.Join("|", enc);
            }
            int nTank = Pick(0, 1, 2, 3, 4, 5), nAwacs = Pick(0, 1, 2), nJstar = Pick(0, 1);
            inp["in_tankers"] = Tracks("tblTankerTrack", "TankerNR", nTank, P(0.05));
            inp["in_awacs"] = Tracks("tblAwacsTrack", "AwacsNR", nAwacs, false);
            inp["in_jstar"] = Tracks("tblJSTARTrack", "JstarNR", nJstar, false);
            inp["in_arrStpt"] = arrStpt.ToString();
            if (nTank != 0) { St("FillTanker"); Call(dc, "FillTanker"); }
            if (nAwacs != 0) { St("FillAwacs"); Call(dc, "FillAwacs"); }
            if (nJstar != 0) { St("FillJSTAR"); Call(dc, "FillJSTAR"); }
            St("");
            // the pilot picks a tanker from each list: the combo's item, then the button that takes it ("k:item")
            var tsel = new List<string>();
            foreach (var k in P(0.2) ? new[] { 1, 2, 1, 2, 2 } : new[] { 1, 2 })
            {
                var cbo = (ComboBox)Get(dc, "cboTanker" + k);
                if (cbo.Items.Count == 0 || P(0.4)) { tsel.Add(""); inp["in_tankerPick"] = string.Join("|", tsel); continue; }
                var item = cbo.Items[R(0, cbo.Items.Count - 1)];
                tsel.Add(k + ":" + Convert.ToString(item));
                inp["in_tankerPick"] = string.Join("|", tsel);
                St("Tanker" + (tsel.Count - 1));
                cbo.SelectedItem = item;
                Click(dc, "btnTanker" + k + "_Click");
                St("");
            }
            inp["in_tankerPick"] = string.Join("|", tsel);
            Set(main, "blnSelectingFlight", false);

            // the flight plan's coordinate strings are the projection's, not the card's: the port takes them as given
            var fp = (Array)Get(dc, "FlightPlan");
            var ll = new List<string>();
            for (int i = 0; i < 24; i++)
            {
                var e = fp.GetValue(i);
                ll.Add((string)e.GetType().GetField("Latitude").GetValue(e) + "~" + (string)e.GetType().GetField("Longtitude").GetValue(e));
            }
            inp["in_latlon"] = string.Join("|", ll);

            // ---------------------------------------------------------------- OpenFile's tail
            var names = new[] { Word(0, 12), Word(0, 12), Word(0, 12), Word(0, 12) };
            var sn = (string[])Get(dc, "strNames");
            for (int i = 0; i < 4; i++) sn[i + 1] = names[i];
            inp["in_names"] = string.Join("~", names);
            var mission = Word(0, 14);
            Set(dc, "strMission", mission); inp["in_mission"] = mission;
            var pkgName = R(1, 9999).ToString();
            Set(main, "SelPackageName", pkgName); inp["in_pkgName"] = pkgName;
            var ews = Enumerable.Range(0, 6).Select(_ => Word(0, 6)).ToArray();
            for (int i = 0; i < 6; i++) Set(dc, "strEws" + (i + 1), ews[i]);
            inp["in_ews"] = string.Join("~", ews);
            var wpnNames = new[] { "strSubMode1", "strFuse1", "strArmDelay1", "strBurstAlt1", "strRelAngle1", "strSglPair1", "strRipple1", "strSpacing1",
                                   "strSubMode2", "strFuse2", "strArmDelay2", "strBurstAlt2", "strRelAngle2", "strSglPair2", "strRipple2", "strSpacing2" };
            var wpn = wpnNames.Select(_ => Word(0, 5)).ToArray();
            for (int i = 0; i < wpnNames.Length; i++) Set(dc, wpnNames[i], wpn[i]);
            inp["in_wpn"] = string.Join("~", wpn);

            // the attack profile, as the DTC page and the planning pages hold it
            var profile = Pick("None", "PopUp", "TOSS", "HADB", "Else", "Else", "PopUp", "TOSS");
            int modesel = R(0, 3);
            bool popRef = P(0.5), tossRef = P(0.5);
            Set(dtc, "strProfile", profile);
            nav = Get(main, "CampNavOffsets");
            nav.GetType().GetField("Modesel").SetValue(nav, modesel);
            SetRaw(main, "CampNavOffsets", nav);
            Set(popup, "blnRef", popRef); Set(toss, "blnRef", tossRef);
            inp["in_profile"] = profile; inp["in_modesel"] = modesel.ToString(); inp["in_popRef"] = popRef ? "1" : "0"; inp["in_tossRef"] = tossRef ? "1" : "0";
            var nums = new List<string>();
            foreach (var mode in new[] { "VIP", "VRP" })
                foreach (var pt in new[] { "", "PUP", "OA1", "OA2" })
                    foreach (var q in new[] { "BRG", "RNG", "ELV" })
                    {
                        var n = (NumericUpDown)Get(dtc, "num" + mode + pt + "_" + q + "_VAL");
                        decimal v = q == "BRG" ? (decimal)(R(0, 3600) / 10.0) : q == "RNG" ? Pick<decimal>(0, R(0, 999999), R(0, 200000), R(1000, 60000)) : R(0, 30000);
                        if (v > n.Maximum) v = n.Maximum;
                        n.Value = v;
                        nums.Add(n.Text);
                    }
            inp["in_dtcNums"] = string.Join("~", nums);
            var pages = new Dictionary<string, string>();
            void PageF(object o, string owner, string field, object v) { Set(o, field, v); pages[owner + "." + field] = Convert.ToString(v, CultureInfo.InvariantCulture); }
            void PageL(object o, string owner, string ctl, string v) { ((Control)Get(o, ctl)).Text = v; pages[owner + "." + ctl] = v; }
            PageF(popup, "pop", "dblPullHeading", P(0.2) ? (double)R(0, 360) : R(0, 360000) / 1000.0 + (P(0.5) ? 0.05 : 0.0));
            PageF(popup, "pop", "intPullDownAlt", R(0, 12000));
            PageF(popup, "pop", "intClimbAngleDeg", R(5, 45));
            PageL(popup, "pop", "lblTurnVal", Pick("Left", "Right", "L 45", "R 30"));
            PageL(popup, "pop", "lblTargetHUDval", Pick("YES", "NO"));
            foreach (var s in new[] { "strIngrHgt", "strIngrSpd", "strRelHgt", "strRelSpd", "strAttHed", "strDA", "strPullingG" })
                PageF(dtc, "dtc", s, R(0, 9000).ToString());
            PageF(toss, "toss", "intAttackHeadingDeg", R(0, 360));
            PageF(toss, "toss", "intIngressHeight", R(100, 2000));
            PageF(toss, "toss", "intIngressCAS", R(300, 550));
            PageF(toss, "toss", "intReleaseHeight", R(100, 9000));
            PageF(toss, "toss", "intReleaseCAS", R(300, 550));
            PageF(toss, "toss", "intReleaseAngleDeg", R(0, 45));
            PageF(toss, "toss", "intPullingGs", R(2, 6));
            PageF(toss, "toss", "strTurn", Pick("Left", "Right"));
            PageL(toss, "toss", "lblTargetHUDval", Pick("YES", "NO"));
            PageF(hadb, "hadb", "intIngressAlt", R(1000, 30000));
            PageF(hadb, "hadb", "intIngressCAS", R(300, 550));
            PageF(hadb, "hadb", "intReleaseHeight", R(1000, 15000));
            PageF(hadb, "hadb", "intCAS", R(300, 550));
            PageF(hadb, "hadb", "intAttackHeadingDeg", R(0, 360));
            PageF(hadb, "hadb", "intDiveAngleDeg", R(10, 60));
            PageF(hadb, "hadb", "intPullingGs", R(2, 6));
            PageF(hadb, "hadb", "strTurnDirection", Pick("Left", "Right"));
            PageL(hadb, "hadb", "lblTargetHUDval", Pick("YES", "NO"));
            inp["in_pages"] = string.Join("|", pages.Select(kv => kv.Key + "=" + kv.Value));

            St("FillDataCard2"); Call(dc, "FillDataCard");
            St("FillCommCardPackages2"); Call(dc, "FillCommCardPackages");
            St("FillCommCard2"); Call(dc, "FillCommCard");
            St("");

            // ---------------------------------------------------------------- the pilot at the page
            int swingClicks = Pick(0, 0, 1, 2);
            inp["in_swingClicks"] = swingClicks.ToString();
            for (int i = 0; i < swingClicks; i++) { St("Swing" + i); Click(dc, "lblFormation_Click"); }
            St("");
            var edits = new List<string>();
            int nEdits = Pick(0, 0, 1, 1, 2, 3);
            int ownWps = ((Array)Get(flights.GetValue(sel), "waypoints")).Length;
            for (int e = 0; e < nEdits; e++)
            {
                int s = P(0.8) ? R(2, Math.Max(2, Math.Min(24, ownWps))) : R(2, 24);
                string kind = Pick("k", "k", "k", "l", "x", "e");
                // typing into a fuel box past the route's last steerpoint + 1 makes CalculateFuel index past the
                // flight's waypoints — an unhandled exception in WDP (kept rare: it ends the row)
                if (kind == "k" && s > ownWps + 1 && P(0.8)) kind = "l";
                string val = kind == "x" ? Word(1, 4).Replace(" ", "q") : kind == "e" ? "" : P(0.03) ? Pick("2147483000", "2147483599", "-2147483000") : R(0, 12000).ToString();
                if (kind == "x" && double.TryParse(val, NumberStyles.Any, CultureInfo.CurrentCulture, out _)) val = "q" + val;
                if (P(0.25)) { val = OddEdit(); kind = VbInt(val) && P(0.6) ? "k" : "l"; if (kind == "k" && s > ownWps + 1) kind = "l"; }
                // IsNumeric throwing, or a yes that ToInteger then refuses, would also stop WDP: kept rare
                if ((!VbNumSafe(val) || (VbNum(val) && !VbInt(val))) && P(0.9)) val = "q" + val;
                if (kind == "k" && !VbInt(val) && P(0.9)) kind = "l";
                edits.Add(s + "=" + kind + "=" + val);
                inp["in_fuelEdits"] = string.Join("|", edits);
                St("Fuel" + e);
                ((TextBox)Get(dc, "txtFuel" + s)).Text = val;
                if (kind == "k") Call(dc, "txtFuel" + s + "_KeyUp", null, null);
                Call(dc, "txtFuel" + s + "_Leave", null, EventArgs.Empty);
                St("");
            }
            inp["in_fuelEdits"] = string.Join("|", edits);

            int rangeClicks = Pick(0, 1, 2, 3);
            inp["in_rangeClicks"] = rangeClicks.ToString();
            for (int i = 0; i < rangeClicks; i++) { St("Range" + i); Click(dc, "btnToggleRange_Click"); }
            int atisClicks = Pick(0, 1, 2);
            inp["in_atisClicks"] = atisClicks.ToString();
            for (int i = 0; i < atisClicks; i++) { St("Atis" + i); Click(dc, "btnATIS_Click"); }
            int kmsmClicks = Pick(0, 1, 2);
            inp["in_kmsmClicks"] = kmsmClicks.ToString();
            for (int i = 0; i < kmsmClicks; i++) { St("KmSm" + i); Click(dc, "btnKmSm_Click"); }
            int taxi = Pick(0, 0, R(4, 10));
            inp["in_taxi"] = taxi.ToString();
            if (taxi != 0) { St("Taxi1"); ((NumericUpDown)Get(dc, "numTaxi1")).Value = taxi; Click(dc, "numTaxi1_Click"); }
            int taxi2 = Pick(0, 0, R(4, 10));
            inp["in_taxi2"] = taxi2.ToString();
            if (taxi2 != 0) { St("Taxi2"); ((NumericUpDown)Get(dc, "numTaxi2")).Value = taxi2; Click(dc, "numTaxi2_Click"); }
            int latClicks = Pick(0, 1, 2);
            inp["in_latClicks"] = latClicks.ToString();
            for (int i = 0; i < latClicks; i++) { St("Lat" + i); Click(dc, "lblLat_Click"); }
            St("");
            var routeEdits = new List<string>();
            int nRoute = Pick(0, 0, 1, 2, 3);
            for (int e = 0; e < nRoute; e++)
            {
                int k = R(1, 9), v = R(0, 24);
                routeEdits.Add(k + "=" + v);
                inp["in_routeEdits"] = string.Join("|", routeEdits);
                St("Route" + e);
                ((NumericUpDown)Get(dc, "numRouteStpt" + k)).Value = v;
                Click(dc, "numRouteStpt" + k + "_Click");
                St("");
            }
            inp["in_routeEdits"] = string.Join("|", routeEdits);

            // typed into the card's own fields, then left: each Leave handler checks and clamps what was typed, or
            // puts back what the cartridge holds (the DTC page's box, given here as it reads). "!box" is typed only.
            var fieldEdits = new List<string>();
            var wpnMxt = new Dictionary<string, string> { { "ArmDly", "C1_AD1" }, { "BA", "C2_BA" }, { "RelAngle", "Angle" }, { "Ripple", "Pulse" }, { "Space", "Spacing" } };
            int nField = Pick(0, 1, 2, 3, 4, 5);
            for (int e = 0; e < nField; e++)
            {
                string kind = Pick("ArmDly", "BA", "RelAngle", "Ripple", "Space", "LaserLST", "LaserCode", "ALOW", "MSL", "Bingo", "Ews", "Typed", "Typed", "TcnLeave", "AnyBox");
                int which = R(1, 2);
                if (kind == "Typed" || kind == "TcnLeave" || kind == "AnyBox")
                {
                    // boxes with only a TextChanged handler (the package's UHF/VHF, IDM; the flight members' boxes),
                    // the two TACAN boxes that also have a Leave handler, and boxes typed into and never left
                    string box;
                    if (kind == "TcnLeave") box = P(0.5) ? "txtLead_TCN" : "txtTCN_" + R(1, 5);
                    else if (kind == "AnyBox")
                        box = Pick("txtKias" + R(1, 24), "txtFormation" + R(1, 24), "txtTGT_Pri", "txtExtra" + R(1, 5), "txtTanker1_UHF", "txtTanker2", "txtAWACS_UHF", "txtCommHoldPt" + R(1, 5),
                            "txtStdQnh", "txtTransitLvl", "txtRwy2", "txtWpn_SubMode1", "txtEws" + R(1, 6), "txtFuel" + R(1, 24), "txtLaserLST2", "txtLaserCode1", "txtWpn_BA" + R(1, 2), "txtALOW", "txtBingo", "txtIDM_" + R(1, 5));
                    else
                    {
                        int k = R(1, 5);
                        string m = Pick("Lead", "Wing1", "Element", "Wing4");
                        box = Pick("txtC" + k + "_UHF", "txtC" + k + "_VHF", "txtC" + k + "_VHF", "txtIDM_" + k,
                            "txt" + m + "_IDM", "txt" + m + "_TO", "txt" + m + "_Lnd", "txt" + m + "_Mode23",
                            m == "Element" ? "txtElement_lsr" : "txt" + m + "_Lsr", m == "Lead" ? "txtWing1_TCN" : "txt" + m + "_TCN");
                    }
                    string typed = P(0.3) ? OddEdit() : P(0.3) ? (R(225000, 399975) / 1000.0).ToString("0.000", CultureInfo.InvariantCulture) : P(0.3) ? R(1, 126) + Pick("X", "Y") : Word(0, 8);
                    fieldEdits.Add((kind == "TcnLeave" ? "" : "!") + box + "=" + typed + "=");
                    inp["in_fieldEdits"] = string.Join("|", fieldEdits);
                    St("Field" + e);
                    ((TextBox)Get(dc, box)).Text = typed;
                    if (kind == "TcnLeave") Call(dc, box + "_Leave", null, EventArgs.Empty);
                    St("");
                    continue;
                }
                // txtLaserCode2_Leave range-checks txtLaserCode1's text (a WDP slip, kept); if that is not a number
                // WDP stops with an unhandled exception (kept rare: it ends the row)
                if (kind == "LaserCode" && which == 2 && !double.TryParse(((Control)Get(dc, "txtLaserCode1")).Text, NumberStyles.Any, CultureInfo.CurrentCulture, out _) && P(0.9)) which = 1;
                string ctl, handler, mxtName = null;
                if (wpnMxt.ContainsKey(kind)) { ctl = "txtWpn_" + kind + which; handler = ctl + "_Leave"; mxtName = "mxtP" + which + "_" + wpnMxt[kind]; }
                else if (kind == "LaserLST" || kind == "LaserCode") { ctl = "txt" + kind + which; handler = ctl + "_Leave"; }
                else if (kind == "Ews") { which = R(1, 6); ctl = "txtEws" + which; handler = ctl + "_Leave"; }
                else { ctl = "txt" + kind; handler = ctl + "_Leave"; }
                string mxtText = "";
                if (mxtName != null)
                {
                    var mxt = (Control)Get(dtc, mxtName);
                    string want = kind == "ArmDly" ? Pick("00.00", "05.25", "10.00", "01.50") : R(0, 400).ToString();
                    mxt.Text = want;
                    mxtText = mxt.Text;
                }
                string val;
                int pick = R(0, 9);
                if (kind == "Ews") val = Word(0, 8);
                else if (pick < 2) val = Word(1, 4).Replace(" ", "q") + "z";   // not a number
                else if (pick == 2) val = "";
                else if (pick == 3) val = "-" + R(1, 99);
                else if (pick == 4) val = (R(0, 99999) / 100.0).ToString(CultureInfo.InvariantCulture);
                else if (pick == 5) val = " " + R(0, 20000) + " ";
                else val = R(0, 12000).ToString();
                if (kind != "Ews" && P(0.3)) val = OddEdit();
                // a laser code past a Short: Conversions.ToShort overflows and WDP stops (kept rare)
                if ((kind == "LaserLST" || kind == "LaserCode") && P(0.03)) val = Pick("40000", "32768", "-40000");
                // WDP converts the cartridge's text back when the typed one is not a number: keep that text numeric
                if (mxtName != null && !double.TryParse(mxtText, NumberStyles.Any, CultureInfo.CurrentCulture, out _) && !VbNum(val))
                    val = R(0, 90).ToString();
                // where WDP itself stops (unhandled): IsNumeric throwing, or a number its own conversion cannot take
                if (kind != "Ews" && P(0.95))
                {
                    if (!VbNumSafe(val)) val = "7";
                    bool num = VbNum(val);
                    if (num && (kind == "ArmDly") && !VbDbl(val)) val = "7";
                    if (num && (kind == "BA" || kind == "RelAngle" || kind == "Space" || kind == "Ripple" || kind == "MSL") && !VbInt(val)) val = "7";
                    if (num && (kind == "ALOW" || kind == "Bingo") && !VbSng(val)) val = "7";
                    if ((kind == "LaserLST" || kind == "LaserCode") && !val.StartsWith("40000") && val != "32768" && val != "-40000")
                    {
                        string check = ctl == "txtLaserCode2" ? ((Control)Get(dc, "txtLaserCode1")).Text : val;
                        if (VbNum(val) && !VbDbl(check))
                        {
                            if (ctl == "txtLaserCode2") { ctl = "txtLaserCode1"; handler = "txtLaserCode1_Leave"; check = val; }
                            if (!VbDbl(val)) { val = "1500"; check = val; }
                        }
                        if (VbNum(val))
                        {
                            double dv = double.Parse(VbCall(vbToDouble, check), CultureInfo.InvariantCulture);
                            if (dv > 0 && dv <= 9999 && !VbShort(val)) val = "1500";
                        }
                    }
                }
                fieldEdits.Add(ctl + "=" + val + "=" + mxtText);
                inp["in_fieldEdits"] = string.Join("|", fieldEdits);
                St("Field" + e);
                ((TextBox)Get(dc, ctl)).Text = val;
                Call(dc, handler, null, EventArgs.Empty);
                St("");
            }
            inp["in_fieldEdits"] = string.Join("|", fieldEdits);
            St("FillDataCard3"); Call(dc, "FillDataCard");
            St("");

            // ---------------------------------------------------------------- more of the page's own controls
            var steps = new List<string>();
            int nSteps = Pick(0, 0, 1, 2, 3, 4, 6, 8);
            for (int e = 0; e < nSteps; e++)
            {
                string op = Pick("prof", "prof", "prof", "seat", "seat", "form", "rv", "rc", "tset", "tidx", "titem", "tbtn", "tbtn", "type", "leave", "key",
                    "fdc", "fcc", "fcp", "atis", "kmsm", "range", "swing", "lat", "taxi", "wmo", "wmo", "wms", "wms", "wmt", "wmt", "wmt");
                string enc;
                Action act;
                switch (op)
                {
                    case "prof":
                    {
                        string pr = Pick("PopUp", "HADB", "TOSS", "None");
                        enc = "prof:" + pr;
                        // cntDTC.Profiles → FillNAVOFFSETSdata → FillDataCard, FillCommCard; then the map (FillMap)
                        act = () => { try { Click(dc, "btn" + pr + "_Click"); } catch (Exception ex) when (!FromCard(ex, "Profiles", "FillNAVOFFSETSdata", "FillDataCard", "FillCommCard", "FillAttackType", "FillWeaponProfile", "FillEws", "SetRouteStpts", "FillFltPlan")) { tails++; Tail(ex); } };
                        break;
                    }
                    case "seat":
                    {
                        int s = R(0, 3);
                        var rb = (RadioButton)Get(dc, seatNames[s]);
                        if (!rb.Enabled) { e--; nSteps--; continue; }
                        enc = "seat:" + s;
                        // the button is checked before its Click; SelectPilotSeat then plans again and hands the flight
                        // to the loadout form and the performance page (not the card's)
                        act = () => { rb.Checked = true; try { Click(dc, seatNames[s] + "_Click"); } catch (Exception ex) when (!FromCard(ex, "CreateFlightplan", "FillFlightplan", "RoundUp")) { tails++; Tail(ex); } };
                        break;
                    }
                    case "form":
                    {
                        int k = R(1, 24); string nm = P(0.8) ? Pick(formations) : Word(0, 6); bool all = P(0.4);
                        enc = "form:" + k + ":" + Esc(nm) + ":" + (all ? "1" : "0");
                        // the formation dialog answered OK (txtFormationN_Click's own lines after ShowDialog)
                        act = () => { ((TextBox)Get(dc, "txtFormation" + k)).Text = nm; if (all) Call(dc, "SetAllFormationsSame", nm); };
                        break;
                    }
                    case "rv":
                    {
                        int k = R(1, 9), v = R(0, 24);
                        enc = "rv:" + k + ":" + v;
                        act = () => { ((NumericUpDown)Get(dc, "numRouteStpt" + k)).Value = v; };
                        break;
                    }
                    case "rc":
                    {
                        int k = R(1, 9), v = R(0, 24);
                        enc = "rc:" + k + ":" + v;
                        act = () => { ((NumericUpDown)Get(dc, "numRouteStpt" + k)).Value = v; Click(dc, "numRouteStpt" + k + "_Click"); };
                        break;
                    }
                    case "tset": { int k = R(1, 2); enc = "tset:" + k; act = () => Click(dc, "btnSetTanker" + k + "_Click"); break; }
                    case "tbtn": { int k = R(1, 2); enc = "tbtn:" + k; act = () => Click(dc, "btnTanker" + k + "_Click"); break; }
                    case "tidx":
                    {
                        int k = R(1, 2); var cbo = (ComboBox)Get(dc, "cboTanker" + k);
                        int i = R(-1, cbo.Items.Count - 1);
                        enc = "tidx:" + k + ":" + i;
                        act = () => cbo.SelectedIndex = i;
                        break;
                    }
                    case "titem":
                    {
                        int k = R(1, 2); var cbo = (ComboBox)Get(dc, "cboTanker" + k);
                        string it = cbo.Items.Count > 0 && P(0.6) ? Convert.ToString(cbo.Items[R(0, cbo.Items.Count - 1)]) : P(0.2) ? null : Pick("Nobody 9", "texaco 1", "No Tanker", "");
                        enc = "titem:" + k + ":" + (it == null ? "\\u0001" : Esc(it));
                        act = () => cbo.SelectedItem = it;
                        break;
                    }
                    case "type":
                    {
                        string box = Pick("txtFuel" + R(2, 24), "txtEws" + R(1, 6), "txtLaserLST1", "txtLaserCode1", "txtLaserCode2", "txtALOW", "txtMSL", "txtBingo", "txtWpn_BA1", "txtWpn_Ripple2",
                            "txtLead_TCN", "txtTCN_" + R(1, 5), "txtC" + R(1, 5) + "_VHF", "txtFormation" + R(1, 24));
                        string v = P(0.4) ? OddEdit() : P(0.5) ? R(0, 9000).ToString() : Word(0, 6);
                        if (!VbNumSafe(v)) v = "7";
                        enc = "type:" + box + ":" + Esc(v);
                        act = () => ((TextBox)Get(dc, box)).Text = v;
                        break;
                    }
                    case "leave":
                    {
                        string box = Pick("txtFuel" + R(2, 24), "txtEws" + R(1, 6), "txtLaserLST1", "txtLaserCode1", "txtALOW", "txtMSL", "txtBingo", "txtLead_TCN", "txtTCN_" + R(1, 5));
                        enc = "leave:" + box;
                        act = () => Call(dc, box + "_Leave", null, EventArgs.Empty);
                        break;
                    }
                    case "key": { int k = R(2, 24); enc = "key:" + k; act = () => Call(dc, "txtFuel" + k + "_KeyUp", null, null); break; }
                    case "wmo": { int i = R(0, 5); enc = "wmo:" + i; act = () => Click(dc, WpnBoxClick[i]); break; }
                    case "wms":
                    {
                        int i = R(0, 5); var cbo = (ComboBox)Get(dc, CardModeLists[i]); int idx = R(-1, cbo.Items.Count - 1);
                        enc = "wms:" + i + ":" + idx;
                        act = () => cbo.SelectedIndex = idx;
                        break;
                    }
                    case "wmt": { int i = R(0, 5); enc = "wmt:" + i; act = () => Click(dc, WpnTakeClick[i]); break; }
                    case "fdc": enc = "fdc"; act = () => Call(dc, "FillDataCard"); break;
                    case "fcc": enc = "fcc"; act = () => Call(dc, "FillCommCard"); break;
                    case "fcp": enc = "fcp"; act = () => Call(dc, "FillCommCardPackages"); break;
                    case "atis": enc = "atis"; act = () => Click(dc, "btnATIS_Click"); break;
                    case "kmsm": enc = "kmsm"; act = () => Click(dc, "btnKmSm_Click"); break;
                    case "range": enc = "range"; act = () => Click(dc, "btnToggleRange_Click"); break;
                    case "swing": enc = "swing"; act = () => Click(dc, "lblFormation_Click"); break;
                    case "lat": enc = "lat"; act = () => Click(dc, "lblLat_Click"); break;
                    default:
                    {
                        int k = R(1, 2), v = R(4, 10);
                        enc = "taxi:" + k + ":" + v;
                        act = () => { ((NumericUpDown)Get(dc, "numTaxi" + k)).Value = v; Click(dc, "numTaxi" + k + "_Click"); };
                        break;
                    }
                }
                steps.Add(enc);
                inp["in_steps"] = string.Join("|", steps);
                St("Step" + (steps.Count - 1));
                act();
                St("");
            }
            inp["in_steps"] = string.Join("|", steps);

            // CutSituation, WDP's line breaker for the briefing page's situation text
            var cuts = new List<string>();
            for (int i = 0; i < 3; i++)
            {
                string s = Word(0, 30);
                while (s.Length < R(0, 200)) s += " " + Word(1, 14);
                if (P(0.2)) s = Word(81, 120).Replace(" ", "x");
                cuts.Add(s + "=" + Call(dc, "CutSituation", s));
            }
            inp["in_cut"] = string.Join("|", cuts);

            // what VB's own runtime answers for a few odd strings: IsNumeric, ToDouble, ToInteger, ToSingle, ToShort
            var vbs = new List<string>();
            for (int i = 0; i < 4; i++)
            {
                string s = i == 0 ? OddNumbers[row % OddNumbers.Length] : OddNumber();
                vbs.Add(string.Join("~", Esc(s), VbCall(vbIsNumeric, s), VbCall(vbToDouble, s), VbCall(vbToInteger, s), VbCall(vbToSingle, s), VbCall(vbToShort, s)));
            }
            inp["in_vbnum"] = string.Join("|", vbs);

            // the card's own Distance and Heading between two cells, called directly, over the whole short range
            // (on legs of tens of thousands of cells the x87's intermediate precision shows in the last digit)
            var geo = new List<string>();
            for (int i = 0; i < 6; i++)
            {
                int x1 = P(0.5) ? R(-32768, 32767) : R(0, 2000), y1 = P(0.5) ? R(-32768, 32767) : R(0, 2000);
                int x2 = P(0.5) ? R(-32768, 32767) : x1 + R(-100, 100), y2 = P(0.5) ? R(-32768, 32767) : y1 + R(-100, 100);
                var a = new System.Drawing.Point(x1, y1); var b = new System.Drawing.Point(x2, y2);
                float dist = (float)Call(dc, "Distance", a, b);
                float hdg = (float)Call(dc, "Heading", a, b);
                geo.Add(x1 + "," + y1 + "," + x2 + "," + y2 + "," + F(dist) + "," + F(hdg));
            }
            inp["in_geo"] = string.Join("|", geo);

            inp["in_boxes"] = (boxes - before).ToString();
            sb.Append(string.Join("\t", inCols.Select(c => inp.TryGetValue(c, out var v) ? v : "")));
            foreach (var o in Outputs) sb.Append('\t').Append(Text(dc, o).Replace("\t", "\\t").Replace("\r", "\\r").Replace("\n", "\\n"));
            sb.AppendLine();
            written++;
            }
            catch (Exception ex)
            {
                errors.AppendLine("row " + row + " (" + Stage + "): " + ex.Message + " @ " + ((ex.InnerException ?? ex).StackTrace ?? "").Split((char)10).FirstOrDefault()?.Trim()); crashed++;
                try { Set(main, "blnSelectingFlight", false); Set(main, "blnLoaded", true); } catch { }
                // the row as it stood when WDP stopped, and where it stopped: the port has to stop there too, and
                // the next row starts from what it left
                if (Stage != "")
                {
                    inp["in_threw"] = Stage;
                    if (!inp.ContainsKey("in_latlon"))
                    {
                        var fp = (Array)Get(dc, "FlightPlan");
                        var ll = new List<string>();
                        for (int i = 0; i < 24; i++) { var e = fp.GetValue(i); ll.Add((string)e.GetType().GetField("Latitude").GetValue(e) + "~" + (string)e.GetType().GetField("Longtitude").GetValue(e)); }
                        inp["in_latlon"] = string.Join("|", ll);
                    }
                    sb.Append(string.Join("\t", inCols.Select(c => inp.TryGetValue(c, out var v) ? v : "")));
                    foreach (var o in Outputs) sb.Append('\t').Append(Text(dc, o).Replace("\t", "\\t").Replace("\r", "\\r").Replace("\n", "\\n"));
                    sb.AppendLine();
                    written++;
                }
                Stage = "";
            }
        }

        File.WriteAllText(outPath, sb.ToString());
        foreach (var kv in Tails.OrderByDescending(x => x.Value)) errors.AppendLine("after the card's part, " + kv.Value + "x: " + kv.Key);
        File.WriteAllText(outPath + ".errors.txt", errors.ToString());
        Console.WriteLine(crashed + " rows on which WDP stopped (written as they stood, with the stage), " + tails + " harness-only exceptions after the card's part");
        Console.WriteLine("wrote " + written + " DataCard rows (" + Outputs.Count + " controls each, " + boxes + " message boxes closed) to " + outPath);
        return 0;
    }
}

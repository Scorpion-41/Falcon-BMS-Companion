using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

// The real Performance page (cntPerformance) given named cases: the reference of the end-to-end comparison.
//
// The cases are written by the app's own check (`--wdppagetest performance <x>.cases.tsv out.txt` writes
// `<x>.cases.tsv.in` when the reference is missing), one per line: `name<TAB>key=value;key=value...`, with what WDP's
// databases put in the page's fields (the engine, the weights, the loadout's weight and drag, the airfield) and what
// the pilot sets (temperature, wind, QNH, pitch and power by their list text, the cruise radio and altitude, taxi fuel,
// the turn calculator's picks). Each case runs on a fresh page the way Performance.cs runs its grid: the databases'
// figures into the fields, the pilot's inputs through each control's own event. Every label the page shows is written
// out, with the engine's labels as WDP's aircraft table writes them and the take-off block the page writes into the
// DataCard.
//
//   wdpref page PerfCase <WeaponDeliveryPlanner.exe> <x.cases.tsv>
//
// Run it from a folder of its own with a copy of WDP's Setup.ini beside it, never from WDP's folder: Setup() reads the
// Setup.ini beside the running program (every input is set explicitly anyway). Nothing is written but the output.
internal static class PerfCase
{
    private const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;
    private static Type type;
    private static object main, dataCard, mainPage, aircraftData;

    internal static readonly string[] Labels = Performance.Labels.Concat(new[] {
        "lblTempStandard", "lblISAdevVal", "lblDensityAlt", "lblTAS", "lblMach", "lblTurnRadius",
        "cboActualTemp", "cboPitch", "cboPower", "lblDrag_Val",
    }).Distinct().ToArray();

    internal static readonly string[] Card = { "lblTOSpec", "lblRotation", "lblRefusal", "lblMilClimb", "lblGrossWgt" };

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
        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        type = asm.GetType("WeaponDeliveryPlanner.cntPerformance", throwOnError: true);
        var myProject = asm.GetType("WeaponDeliveryPlanner.My.MyProject", throwOnError: true);
        var forms = myProject.GetProperty("Forms", BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public).GetValue(null, null);
        main = forms.GetType().GetProperty("fclsMain", Any).GetValue(forms, null);
        dataCard = Control(main, "cntDataCard");
        mainPage = Control(main, "cntPerformance");
        aircraftData = Activator.CreateInstance(asm.GetType("WeaponDeliveryPlanner.clsAircraftData", throwOnError: true));
        // no mission, no flight: the page plans for the jet it is given (DoCalculations skips its F-16 check)
        SetField(main, "SelFlightNr", -1);
        SetField(main, "FlightNR", 0);
        SetField(main, "blnSelectingFlight", false);
        SetField(main, "blnVersion", true);
        SetField(dataCard, "strWeather", new string[7]);

        var sb = new StringBuilder();
        sb.AppendLine("case\tname\tinputs");
        int count = 0;
        foreach (var line in File.ReadAllLines(outPath + ".in"))
        {
            if (line.Trim() == "" || line.StartsWith("#") || !line.Contains("\t")) continue;
            var name = line.Substring(0, line.IndexOf('\t'));
            var spec = line.Substring(line.IndexOf('\t') + 1);
            var inp = spec.Split(';').Where(x => x.Contains("=")).ToDictionary(x => x.Substring(0, x.IndexOf('=')).Trim(), x => x.Substring(x.IndexOf('=') + 1));
            var engineLabels = EngineLabels(inp);
            var c = NewPage();
            string err = "";
            try { Drive(c, inp); } catch (Exception e) { var x = e.InnerException ?? e; err = x.GetType().Name + " " + x.Message; }
            sb.Append("case\t").Append(name).Append('\t').Append(spec).AppendLine();
            foreach (var l in Labels) Row(sb, l, Text(c, l));
            foreach (var l in Performance.Colours) Row(sb, l + ".fore", ((System.Windows.Forms.Control)Control(c, l)).ForeColor.Name);
            foreach (var kv in engineLabels) Row(sb, kv.Key, kv.Value);
            foreach (var l in Card) Row(sb, "card." + l, Text(dataCard, l));
            Row(sb, "card.lblRefusal.fore", ((System.Windows.Forms.Control)Control(dataCard, "lblRefusal")).ForeColor.Name);
            Row(sb, "intGrossWt", Convert.ToString(type.GetField("intGrossWt", Any).GetValue(c), CultureInfo.InvariantCulture));
            Row(sb, "dblTOFactor", ((double)type.GetField("dblTOFactor", Any).GetValue(c)).ToString("R", CultureInfo.InvariantCulture));
            if (err != "") Row(sb, "error", err);
            ((System.Windows.Forms.Control)c).Dispose();
            count++;
        }
        File.WriteAllText(outPath, sb.ToString());
        Console.WriteLine("wrote " + count + " cases to " + outPath);
        return 0;
    }

    private static void Row(StringBuilder sb, string label, string value) => sb.Append('\t').Append(label).Append('\t').Append(value).AppendLine();

    /** The engine's labels as WDP's aircraft table writes them (clsAircraftData.SetGe100 and the rest, Aircraft's tail). */
    private static Dictionary<string, string> EngineLabels(Dictionary<string, string> inp)
    {
        var d = new Dictionary<string, string>();
        var set = new Dictionary<string, string> { { "100", "SetGe100" }, { "129", "SetGe129" }, { "200", "SetPw200" }, { "220", "SetPw220" }, { "229", "SetPw229" } };
        if (!set.TryGetValue(inp["engine"], out var method)) return d;
        Call(aircraftData, method);
        d["lblPowerPlant_val"] = Text(mainPage, "lblPowerPlant_val");
        d["lblMilP_Val"] = Text(mainPage, "lblMilP_Val");
        d["lblDry_Val"] = type.GetField("intEngDry", Any).GetValue(mainPage) + " lbs";
        d["lblMaxPower_Val"] = type.GetField("intEngMax", Any).GetValue(mainPage) + " lbs";
        d["lblEmpty_Val"] = inp["empty"];
        d["lblMax_Val"] = inp["max"];
        return d;
    }

    /** A fresh page, loaded as its Load event loads it (the Setup.ini beside the harness is read, then every input is set). */
    private static object NewPage()
    {
        var c = Activator.CreateInstance(type);
        SetField(c, "blnLoaded", false);
        SetField(c, "blnVersion", true);
        Call(c, "FillCombobox");
        Call(c, "Setup");
        SetField(c, "blnVersion", true);
        SetField(c, "intIntFuel", 7162);
        ((NumericUpDown)Control(c, "numTaxiFuel")).Value = 200m;
        ((ComboBox)Control(c, "cboCAS")).SelectedIndex = 3;
        ((ComboBox)Control(c, "cboAltitude")).SelectedIndex = 21;
        ((ComboBox)Control(c, "cboGs")).SelectedIndex = 2;
        SetField(c, "dblQNH_Hpa", 1013.2);
        SetField(c, "dblQNH_In", 29.92);
        ((TextBox)Control(c, "txtQNH_Hpa")).Text = "1013.2";
        ((TextBox)Control(c, "txtQNH_In")).Text = "29.92";
        SetField(c, "blnLoaded", true);
        return c;
    }

    /** One case: the databases' figures into the fields, then the pilot's inputs through their own events. */
    private static void Drive(object c, Dictionary<string, string> inp)
    {
        SetField(c, "CurrentEngine", short.Parse(inp["engine"]));
        SetField(c, "intEmpty", int.Parse(inp["empty"]));
        SetField(c, "intIntFuel", int.Parse(inp["intFuel"]));
        SetField(c, "intMax", int.Parse(inp["max"]));
        SetField(c, "intLoadout", int.Parse(inp["loadout"]));
        SetField(c, "intDrag", int.Parse(inp["drag"]));
        SetField(c, "intExtFuel", int.Parse(inp["extFuel"]));
        SetField(c, "intElv", int.Parse(inp["elv"]));
        SetField(c, "intTODA", int.Parse(inp["toda"]));
        SetField(c, "intRwyHed", int.Parse(inp["rwyHed"]));
        Call(c, "SetFuel");
        Call(c, "FillLoadoutDrag");
        ((TextBox)Control(c, "txtTemp")).Text = inp["temp"];
        ((TextBox)Control(c, "txtWindDir")).Text = inp["windDir"];
        ((TextBox)Control(c, "txtWindSpd")).Text = inp["windSpd"];
        if (inp["qnhKind"] == "hpa") { ((TextBox)Control(c, "txtQNH_Hpa")).Text = inp["qnh"]; Call(c, "QnhHpa"); }
        else { ((TextBox)Control(c, "txtQNH_In")).Text = inp["qnh"]; Call(c, "QnhInch"); }
        Call(c, "ProgramFlow");
        ((ComboBox)Control(c, "cboPitch")).SelectedItem = inp["pitch"];
        ((ComboBox)Control(c, "cboPower")).SelectedItem = inp["power"];
        ((RadioButton)Control(c, inp["radio"])).Checked = true;
        ((TextBox)Control(c, "txtCruiseAlt")).Text = inp["cruiseAlt"];
        Call(c, "ProgramFlow");
        ((NumericUpDown)Control(c, "numTaxiFuel")).Value = decimal.Parse(inp["taxiFuel"], CultureInfo.InvariantCulture);
        Call(c, "SetFuel");
        Call(c, "Weights");
        Call(c, "ProgramFlow");
        // the turn calculator: worked out once the page is loaded, as fclsMain does (fclsMain.cs l.9188), then the lists' picks
        Call(c, "ProgramflowTurn");
        if (inp.TryGetValue("cas", out var cas) && cas != "") ((ComboBox)Control(c, "cboCAS")).SelectedItem = cas;
        if (inp.TryGetValue("alt", out var alt) && alt != "") ((ComboBox)Control(c, "cboAltitude")).SelectedItem = alt;
        if (inp.TryGetValue("g", out var g) && g != "") ((ComboBox)Control(c, "cboGs")).SelectedItem = g;
        if (inp.TryGetValue("actual", out var actual) && actual != "") ((ComboBox)Control(c, "cboActualTemp")).SelectedItem = actual;
    }

    private static void SetField(object o, string name, object value)
    {
        var f = o.GetType().GetField(name, Any);
        if (f == null) throw new Exception("no field " + name);
        f.SetValue(o, value);
    }

    private static void Call(object o, string method, params object[] args)
    {
        var m = o.GetType().GetMethod(method, Any);
        if (m == null) throw new Exception("no method " + method);
        m.Invoke(o, args);
    }

    private static object Control(object o, string name)
    {
        var p = o.GetType().GetProperty(name, Any);
        return p != null ? p.GetValue(o, null) : o.GetType().GetField("_" + name, Any)?.GetValue(o);
    }

    private static string Text(object o, string name)
    {
        var c = Control(o, name) as System.Windows.Forms.Control;
        return c == null ? "(none)" : c.Text.Replace('\t', ' ').Replace("\r", "").Replace('\n', ' ');
    }
}

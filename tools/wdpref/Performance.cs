using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

// Drives the real Performance page (cntPerformance) and writes down what it shows.
//
// The page is a Windows Forms control whose figures come from its own handlers: every input is set the way a pilot
// sets it — the text typed into a box (its TextChanged runs), a list picked (its SelectedIndexChanged runs), a radio
// ticked, the taxi fuel stepped — and then the page's own ProgramFlow runs, as it does after every change. What the
// page reads from WDP's databases (the aircraft's weights and engine, the loadout's weight and drag, the airfield's
// elevation, runway length and heading) is set straight into its fields, which is where those databases put it. Every
// label, and the boxes the page rewrites, are copied out.
//
// A second section drives the turn calculator on the right: speed, altitude, G and (sometimes) the actual
// temperature picked from their lists.
//
//   wdpref page Performance <WeaponDeliveryPlanner.exe> <out.tsv>       (run from WDP's own folder)
internal static class Performance
{
    private const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

    internal static readonly string[] Inputs = {
        "engine", "empty", "intFuel", "max", "loadout", "drag", "extFuel", "elv", "toda", "rwyHed",
        "temp", "windDir", "windSpd", "qnh", "qnhKind", "pitch", "power", "radio", "cruiseAlt", "taxiFuel",
    };

    internal static readonly string[] Labels = {
        "lblISA_Dev", "lblLoadout_val", "lblGross_Val", "lblTempF", "lblHWC", "lblCWC", "lblBlockFuel_Val", "lblFuel_val",
        "lblScheduleMax_Val", "lblScheduleMil_Val", "lblCruiseCeiling_Val", "lblOptCruiseAlt_val", "lblServiceCeiling_Val",
        "lblOptMach_Val", "lblDistMil_Val", "lblFuelMil_Val", "lblTimeMil_Val", "lblDistMax_Val", "lblFuelMax_Val",
        "lblTimeMax_Val", "lblFactor_Val", "lblLiftOff_Val", "lblRotate_Val", "lblRefusal_Val",
        "txtCruiseAlt", "txtQNH_In", "txtQNH_Hpa", "txtTemp", "txtWindDir", "txtWindSpd",
    };

    internal static readonly string[] Colours = { "lblGross_Val", "lblRefusal_Val" };

    internal static readonly string[] TurnLabels = { "lblTempStandard", "lblISAdevVal", "lblDensityAlt", "lblTAS", "lblMach", "lblTurnRadius", "cboActualTemp" };

    private static readonly string[] Radios = { "radCruiseAlt", "radOptCruiseAlt", "radCruiseCeiling", "radServiceCeiling" };

    private static Type type;
    private static object main, dataCard;

    public static int Run(string exePath, string outPath)
    {
        Thread.CurrentThread.CurrentCulture = new CultureInfo("en-US");
        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        type = asm.GetType("WeaponDeliveryPlanner.cntPerformance", throwOnError: true);
        var myProject = asm.GetType("WeaponDeliveryPlanner.My.MyProject", throwOnError: true);
        var forms = myProject.GetProperty("Forms", BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public).GetValue(null, null);
        main = forms.GetType().GetProperty("fclsMain", Any).GetValue(forms, null);
        dataCard = Control(main, "cntDataCard");
        // no mission, no flight: the page plans for the jet it is given (DoCalculations skips its F-16 check)
        SetField(main, "SelFlightNr", -1);
        SetField(main, "FlightNR", 0);
        SetField(main, "blnSelectingFlight", false);
        SetField(main, "blnVersion", true);
        SetField(dataCard, "strWeather", new string[7]);

        var sb = new StringBuilder();
        sb.Append("section\t").Append(string.Join("\t", Inputs));
        foreach (var l in Labels) sb.Append('\t').Append(l);
        foreach (var l in Colours) sb.Append('\t').Append(l + ".fore");
        sb.Append("\terror");
        sb.AppendLine();

        var rnd = new Random(20260927);
        int rows = 0;
        var engines = new[] { 100, 129, 200, 220, 229 };
        var temps = new[] { "-5", "0", "15", "23", "31", "38", "44", "17.5", "abc", "" };
        var winds = new[] { "0", "90", "200", "359", "400", "-10", "x" };
        var speeds = new[] { "0", "8", "15", "27", "250", "" };
        var qnhsHpa = new[] { "1013.2", "995", "1030", "900", "1060", "x" };
        var qnhsIn = new[] { "29.92", "29.5", "30.21", "26", "32" };
        var cruises = new[] { "25000", "32000", "15000", "9000", "41000", "x" };
        for (int i = 0; i < 700; i++)
        {
            var inp = new Dictionary<string, string>();
            int engine = engines[i % engines.Length];
            inp["engine"] = engine.ToString();
            inp["empty"] = new[] { "19500", "17700", "21234" }[rnd.Next(3)];
            inp["intFuel"] = new[] { "7162", "5898", "10219" }[rnd.Next(3)];
            inp["max"] = new[] { "48000", "37500", "52000" }[rnd.Next(3)];
            inp["loadout"] = new[] { "0", "1500", "3200", "6500", "9800" }[rnd.Next(5)];
            inp["drag"] = new[] { "0", "1", "37", "60", "115", "150", "236", "320", "410" }[rnd.Next(9)];
            inp["extFuel"] = new[] { "0", "2040", "2516", "5032" }[rnd.Next(4)];
            inp["elv"] = new[] { "0", "40", "150", "1500", "2400", "5300" }[rnd.Next(6)];
            inp["toda"] = new[] { "9000", "7000", "12000", "5800", "15500" }[rnd.Next(5)];
            inp["rwyHed"] = new[] { "90", "180", "320", "10" }[rnd.Next(4)];
            inp["temp"] = temps[rnd.Next(temps.Length)];
            inp["windDir"] = winds[rnd.Next(winds.Length)];
            inp["windSpd"] = speeds[rnd.Next(speeds.Length)];
            inp["qnhKind"] = rnd.Next(2) == 0 ? "hpa" : "in";
            inp["qnh"] = inp["qnhKind"] == "hpa" ? qnhsHpa[rnd.Next(qnhsHpa.Length)] : qnhsIn[rnd.Next(qnhsIn.Length)];
            inp["pitch"] = rnd.Next(6).ToString();
            inp["power"] = rnd.Next(2).ToString();
            inp["radio"] = Radios[rnd.Next(4)];
            inp["cruiseAlt"] = cruises[rnd.Next(cruises.Length)];
            inp["taxiFuel"] = new[] { "0", "200", "500", "1000" }[rnd.Next(4)];

            string err = "";
            var c = NewPage();
            try { Drive(c, inp); } catch (Exception e) { var x = e.InnerException ?? e; err = x.GetType().Name + " " + (x.StackTrace ?? "").Split('\n').Select(s => s.Trim()).FirstOrDefault(s => s.Contains("cntPerformance") || s.Contains("cntDataCard") || s.Contains("Performance.")); }
            sb.Append("perf\t").Append(string.Join("\t", Inputs.Select(k => inp[k])));
            foreach (var l in Labels) sb.Append('\t').Append(Text(c, l));
            foreach (var l in Colours) sb.Append('\t').Append(((Control)Control(c, l)).ForeColor.Name);
            sb.Append('\t').Append(err);
            sb.AppendLine();
            rows++;
            ((Control)c).Dispose();
        }

        // the turn calculator: the lists' indices, and the actual temperature's where one is picked (-1: none)
        sb.AppendLine("turnhead\tcas\talt\tg\tactual\t" + string.Join("\t", TurnLabels));
        for (int i = 0; i < 200; i++)
        {
            int cas = rnd.Next(31), alt = rnd.Next(36), g = rnd.Next(9), actual = i % 3 == 0 ? rnd.Next(101) : -1;
            var c = NewPage();
            string err = "";
            try
            {
                ((ComboBox)Control(c, "cboCAS")).SelectedIndex = cas;
                ((ComboBox)Control(c, "cboAltitude")).SelectedIndex = alt;
                ((ComboBox)Control(c, "cboGs")).SelectedIndex = g;
                if (actual >= 0) ((ComboBox)Control(c, "cboActualTemp")).SelectedIndex = actual;
            }
            catch (Exception e) { err = (e.InnerException ?? e).GetType().Name; }
            sb.Append("turn\t").Append(cas).Append('\t').Append(alt).Append('\t').Append(g).Append('\t').Append(actual);
            foreach (var l in TurnLabels) sb.Append('\t').Append(Text(c, l));
            sb.Append('\t').Append(err).AppendLine();
            ((Control)c).Dispose();
        }

        File.WriteAllText(outPath, sb.ToString());
        Console.WriteLine("wrote " + rows + " Performance rows to " + outPath);
        return 0;
    }

    /** A fresh page, loaded as its Load event loads it (WDP's own Setup.ini is read, then every input is set). */
    private static object NewPage()
    {
        var c = Activator.CreateInstance(type);
        SetField(c, "blnLoaded", false);
        SetField(c, "blnVersion", true);
        Call(c, "FillCombobox");
        Call(c, "Setup");
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

    /** One row's inputs, in the order the Kotlin replays them. */
    private static void Drive(object c, Dictionary<string, string> inp)
    {
        // what the aircraft table, the Loadout window and the airport table put in the page's fields
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
        // what the pilot sets, each through its own event
        ((TextBox)Control(c, "txtTemp")).Text = inp["temp"];
        ((TextBox)Control(c, "txtWindDir")).Text = inp["windDir"];
        ((TextBox)Control(c, "txtWindSpd")).Text = inp["windSpd"];
        if (inp["qnhKind"] == "hpa") { ((TextBox)Control(c, "txtQNH_Hpa")).Text = inp["qnh"]; Call(c, "QnhHpa"); }
        else { ((TextBox)Control(c, "txtQNH_In")).Text = inp["qnh"]; Call(c, "QnhInch"); }
        Call(c, "ProgramFlow");
        ((ComboBox)Control(c, "cboPitch")).SelectedIndex = int.Parse(inp["pitch"]);
        ((ComboBox)Control(c, "cboPower")).SelectedIndex = int.Parse(inp["power"]);
        ((RadioButton)Control(c, inp["radio"])).Checked = true;
        ((TextBox)Control(c, "txtCruiseAlt")).Text = inp["cruiseAlt"];
        Call(c, "ProgramFlow");
        ((NumericUpDown)Control(c, "numTaxiFuel")).Value = decimal.Parse(inp["taxiFuel"], CultureInfo.InvariantCulture);
        Call(c, "ProgramFlow");
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
        var c = Control(o, name) as Control;
        return c == null ? "" : c.Text.Replace('\t', ' ').Replace("\r", "").Replace('\n', ' ');
    }
}

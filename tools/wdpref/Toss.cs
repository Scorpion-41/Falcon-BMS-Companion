using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Windows.Forms;

// Drives the real TOSS page and writes down what it shows.
//
// cntTOSS is a Windows Forms control, but it does not need a window to compute: its slider handlers and
// ProgramFlow are public, its labels are readable, and its loaded flag is a private field. So this creates one,
// runs its own Setup the way its Load event would, then for each row of a grid moves the sliders through the same
// handlers a pilot's mouse would and copies every label's text out. The Kotlin port is checked against that.
//
//   wdpref toss <WeaponDeliveryPlanner.exe> <out.tsv>       (run from WDP's own folder)
internal static class Toss
{
    private static readonly string[] Labels = {
        "lblIngrCasVal", "lblIngrHeightVal", "lblGVal", "lblTurnVal", "lblOa2ToPupVal", "lblRelAngleVal",
        "lblRelCasVal", "lblReleaseHeightVal", "lblAngleOff", "lblAttackHdgVal", "lblApproachHedVal",
        "lblTASval", "lblBombrangeVal", "lblBombrangeNmVal", "lblMAPVal", "lblTargetHUDval",
        "lblVIPwp", "lblVIPbrg", "lblVIPrng", "lblVIPelv", "lblVIPnm",
        "lblPUPwp", "lblPUPbrg", "lblPUPrng", "lblPUPelv", "lblPUPnm",
        "lblOA1wp", "lblOA1brg", "lblOA1rng", "lblOA1elv", "lblOA1nm",
        "lblOA2wp", "lblOA2brg", "lblOA2rng", "lblOA2elv", "lblOA2nm",
    };

    public static int Run(string exePath, string outPath)
    {
        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        var type = asm.GetType("WeaponDeliveryPlanner.cntTOSS", throwOnError: true);
        const BindingFlags any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

        var sb = new StringBuilder();
        sb.Append("ingrCas\tingrHeight\tg\tturn\toa2\trelAngle\trelCas\trelHeight\tangleOff\theading\twaypoint\tref");
        foreach (var l in Labels) sb.Append('\t').Append(l);
        sb.AppendLine();

        int rows = 0;
        foreach (var ingrHeight in new[] { 5, 10 })                // ×100 ft
        foreach (var turn in new[] { 1, 0 })                       // Right, Left
        foreach (var g in new[] { 3, 5 })
        foreach (var relAngle in new[] { 20, 30, 45 })
        foreach (var relCas in new[] { 45, 50 })                   // ×10 kt
        foreach (var relHeight in new[] { 30, 40, 55 })            // ×100 ft, inside the derived range
        foreach (var angleOff in new[] { 0, 30, 60 })
        foreach (var heading in new[] { 0, 90, 225 })
        {
            // a fresh control per row, so nothing carries over except what WDP itself carries
            var c = (Control)Activator.CreateInstance(type);
            var t = type;
            // what Load would have done before Setup, and what fclsMain tells the page it is running under
            Set(t, c, "blnLoadedFlag", false);
            Set(t, c, "blnLAT", true);
            Set(t, c, "blnMAT", false);
            Set(t, c, "blnVersion", true);                            // BMS, not Allied Force: FillLabelsBMS
            Call(t, c, "Setup");
            Set(t, c, "blnLoadedFlag", true);
            Set(t, c, "blnRef", false);                               // VRP mode: the figures that need no campaign
            Call(t, c, "ProgramFlow", false);

            // Every input the page has is set here, so nothing depends on whatever the machine's Setup.ini says.
            // The pilot moves the sliders: value, then the handler, exactly as the Scroll event does.
            ((NumericUpDown)Control(t, c, "numWaypoint")).Value = 4; Call(t, c, "STPTChange");
            Slider(t, c, "trbIngrSpd", 45); Call(t, c, "ChangeIngressSpeed");
            Slider(t, c, "trbIngrHeight", ingrHeight); Call(t, c, "ChangeIngressHeight");
            Slider(t, c, "trbG", g); Call(t, c, "ChangePullingG");
            Slider(t, c, "trbTurn", turn); Call(t, c, "ChangeTurn");
            Slider(t, c, "trbOa2ToPup", 3); Call(t, c, "ChangeOa2ToPup");
            Slider(t, c, "trbReleaseAngle", relAngle); Call(t, c, "ChangeRelAngle");
            Slider(t, c, "trbReleaseSpd", relCas); Call(t, c, "ChangeRelSpd");
            Slider(t, c, "trbReleaseHeight", relHeight); Call(t, c, "ChangeRelHeight");
            Slider(t, c, "trbAngleOff", angleOff); Call(t, c, "ChangeAngleOff");
            Slider(t, c, "trbHeading", heading); Call(t, c, "ChangeHeading");
            Call(t, c, "ProgramFlow", true);

            sb.Append(string.Join("\t", new object[] {
                45 * 10, Get(t, c, "trbIngrHeight", "Value"), g, turn == 1 ? "Right" : "Left", 3, relAngle,
                relCas * 10, Get(t, c, "trbReleaseHeight", "Value"), angleOff, heading, 4, "VRP",
            }.Select(o => Convert.ToString(o, CultureInfo.InvariantCulture))));
            foreach (var l in Labels) sb.Append('\t').Append(Text(t, c, l));
            sb.AppendLine();
            rows++;
            c.Dispose();
        }

        File.WriteAllText(outPath, sb.ToString());
        Console.WriteLine("wrote " + rows + " TOSS rows to " + outPath);
        return 0;
    }

    private static void Set(Type t, object o, string field, object value)
    {
        var f = t.GetField(field, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
        if (f == null) throw new Exception("no field " + field);
        f.SetValue(o, value);
    }

    private static void Call(Type t, object o, string method, params object[] args)
    {
        var m = t.GetMethod(method, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
        if (m == null) throw new Exception("no method " + method);
        m.Invoke(o, args);
    }

    private static object Control(Type t, object o, string name)
    {
        var p = t.GetProperty(name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
        return p != null ? p.GetValue(o, null) : t.GetField("_" + name, BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(o);
    }

    private static void Slider(Type t, object o, string name, int value)
    {
        var tb = (TrackBar)Control(t, o, name);
        tb.Value = Math.Max(tb.Minimum, Math.Min(tb.Maximum, value));
    }

    private static object Get(Type t, object o, string name, string prop)
    {
        var c = Control(t, o, name);
        return c.GetType().GetProperty(prop).GetValue(c, null);
    }

    private static string Text(Type t, object o, string name)
    {
        var c = Control(t, o, name) as Label;
        return c == null ? "" : c.Text;
    }
}

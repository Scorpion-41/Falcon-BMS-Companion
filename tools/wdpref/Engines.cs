using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;

// Asks Weapon Delivery Planner's five engine classes for their answers at random points, between the breakpoints
// and past both ends, so the port's tables-plus-interpolation can be checked against the program's own unrolled
// lookups rather than only at the points the tables were sampled at.
//
//   wdpref page Engines <WeaponDeliveryPlanner.exe> <out.tsv>       (run from WDP's own folder)
//
// One line per call: class, method, the arguments (comma separated, invariant culture), the answer ("R" format; a
// string as it is; THREW when the program threw).
internal static class Engines
{
    private static readonly string[] Classes = { "clsGE100", "clsGE129", "clsPW200", "clsPW220", "clsPW229" };

    // the methods the Performance page calls, and the ones they call
    private static readonly string[] Methods = {
        "TakeoffFactor", "OptCruise", "GetOptMach", "CruiseCeiling", "ClimbScheduleMIL", "ClimbScheduleAB",
        "MilFuelIndex", "MilFuelUsed", "MilClimbIndex", "MilClimbDistance", "MilClimbTime",
        "ABFuelIndex", "ABFuelUsed", "ABClimbIndex", "ABClimbDistance", "ABClimbTime",
        "TakeOffSpeed", "RotationSpeed", "AccRefusal", "RwyRefusal", "RefusalWindCorrection", "RefusalSpeed",
    };

    public static int Run(string exePath, string outPath)
    {
        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        var rnd = new Random(4711);
        var sb = new StringBuilder();
        int n = 0;
        foreach (var cls in Classes)
        {
            var type = asm.GetType("WeaponDeliveryPlanner." + cls, true);
            var inst = Activator.CreateInstance(type);
            foreach (var name in Methods)
            {
                var m = type.GetMethod(name, BindingFlags.Public | BindingFlags.Instance);
                if (m == null) continue;
                var ps = m.GetParameters();
                for (int k = 0; k < 400; k++)
                {
                    var args = new object[ps.Length];
                    for (int i = 0; i < ps.Length; i++) args[i] = Pick(rnd, name, ps[i]);
                    string cell;
                    try
                    {
                        var r = m.Invoke(inst, args);
                        cell = r is string s ? s : Convert.ToDouble(r, CultureInfo.InvariantCulture).ToString("R", CultureInfo.InvariantCulture);
                    }
                    catch { cell = "THREW"; }
                    sb.Append(cls).Append('\t').Append(name).Append('\t')
                      .Append(string.Join(",", args.Select(a => Convert.ToString(a, CultureInfo.InvariantCulture))))
                      .Append('\t').AppendLine(cell);
                    n++;
                }
            }
        }
        File.WriteAllText(outPath, sb.ToString());
        Console.WriteLine("wrote " + n + " engine calls to " + outPath);
        return 0;
    }

    /** A value for one argument: mostly the range the page feeds, sometimes a breakpoint, sometimes past the ends. */
    private static object Pick(Random r, string method, ParameterInfo p)
    {
        var t = p.ParameterType;
        double v;
        bool edge = r.Next(8) == 0;
        switch (p.Name)
        {
            case "PressureAlt": v = edge ? r.Next(-4000, 14000) : (r.Next(3) == 0 ? r.Next(-2, 6) * 2000 : r.Next(-1500, 9000)); break;
            case "TempCelcius": v = edge ? r.Next(-60, 70) : (r.Next(4) == 0 ? r.Next(-400, 500) / 10.0 : r.Next(-30, 48)); break;
            case "MilFactor": v = new[] { 1.0, 0.550000011920929, 0.5099999904632568 }[r.Next(3)]; break;
            case "DragIndex": v = edge ? r.Next(-10, 520) : (r.Next(3) == 0 ? r.Next(0, 9) * 50 : r.Next(0, 420)); break;
            case "GrossWeight":
            case "GrossWt":
                if (method == "TakeOffSpeed" || method == "AccRefusal" || method == "RefusalSpeed" || true)
                    v = edge ? r.Next(10000, 60000) : (r.Next(4) == 0 ? r.Next(16, 50) * 1000 + (r.Next(2) == 0 ? 500 : 0) : r.Next(17000, 50000));
                else v = 0;
                break;
            case "CruiseAlt": v = edge ? r.Next(-2000, 60000) : (r.Next(3) == 0 ? r.Next(0, 11) * 5000 : r.Next(0, 51000)); break;
            case "FuelIndex":
            case "ClimbIndex": v = edge ? r.Next(-10, 170) / 10.0 : (r.Next(3) == 0 ? r.Next(0, 31) * 0.5 : Math.Round(r.NextDouble() * 14, 4)); break;
            case "ISA_Dev": v = 0; break;
            case "Pitch": v = r.Next(7, 15); break;
            case "TakeoffSpeed": v = r.Next(100, 230); break;
            case "MAXAB":
            case "AB": v = r.Next(2); break;
            case "TOFactor": v = edge ? r.Next(0, 160) / 10.0 : (r.Next(3) == 0 ? r.Next(2, 16) * 0.5 : Math.Round(0.8 + r.NextDouble() * 7, 3)); break;
            case "Acc": v = edge ? r.Next(-10, 140) : Math.Round(r.NextDouble() * 110, 3); break;
            case "RunwayLenght": v = edge ? r.Next(0, 25000) : (r.Next(3) == 0 ? new[] { 2000, 3000, 4000, 5000, 6000, 7000, 8000, 10000, 12000, 15000, 18000, 20000 }[r.Next(12)] : r.Next(4000, 13000)); break;
            case "HWC": v = r.Next(-30, 45); break;
            case "RefusalSpeed": v = r.Next(0, 220); break;
            default: throw new Exception("no range for " + p.Name);
        }
        if (t == typeof(bool)) return v != 0;
        if (t == typeof(int)) return (int)Math.Round(v);
        if (t == typeof(decimal)) return (decimal)v;
        return v;
    }
}

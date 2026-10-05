using System;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Text;

// Prints what the real Weapon Delivery Planner computes, so the Kotlin port can be checked against it rather than
// against a reading of its source. Loads the shipped assembly and calls into it by reflection: nothing is copied
// out, and the answers are the program's own.
//
//   wdpref <path to WeaponDeliveryPlanner.exe> <out.csv>
internal static class Program
{
    private static int Main(string[] args)
    {
        if (args.Length >= 4 && args[0] == "sample")
        {
            return Sampler.Run(args[1], args[2], args[3]);
        }
        if (args.Length >= 3 && args[0] == "toss")
        {
            return Toss.Run(args[1], args[2]);
        }
        // Any page: `wdpref page Popup <exe> <out>` finds the static class of that name in this assembly and calls
        // its Run(exe, out). A page's reference harness is therefore one new file and no edit here, which is what
        // lets several pages be ported at once without treading on one another.
        if (args.Length >= 4 && args[0] == "page")
        {
            var t = typeof(Program).Assembly.GetType(args[1], throwOnError: false);
            var run = t?.GetMethod("Run", BindingFlags.Public | BindingFlags.Static);
            if (run == null)
            {
                Console.Error.WriteLine("no page harness named " + args[1]);
                return 2;
            }
            return (int)run.Invoke(null, new object[] { args[2], args[3] });
        }

        if (args.Length < 2)
        {
            Console.Error.WriteLine("usage: wdpref <WeaponDeliveryPlanner.exe> <out.csv>");
            return 2;
        }

        var asm = Assembly.LoadFrom(Path.GetFullPath(args[0]));
        var ballistics = asm.GetType("WeaponDeliveryPlanner.clsBallistics", throwOnError: true);
        var bombrange = ballistics.GetMethod("Bombrange", BindingFlags.Public | BindingFlags.Instance);
        var tofField = ballistics.GetField("TOF", BindingFlags.Public | BindingFlags.Instance);

        var meteo = asm.GetType("WeaponDeliveryPlanner.clsMeteo", throwOnError: true);

        var sb = new StringBuilder();
        sb.AppendLine("kind,a,b,c,d,out1,out2");

        // ---- ballistics: the grid a pilot actually plans over
        foreach (var type in new[] { "Low", "High", "Cluster" })
        {
            foreach (var dive in new[] { 0, 5, 10, 15, 20, 30, 45, 60 })
            {
                foreach (var tas in new[] { 350, 400, 450, 500, 550 })
                {
                    foreach (var height in new[] { 500, 1000, 2000, 5000, 10000, 15000 })
                    {
                        var inst = Activator.CreateInstance(ballistics);
                        var range = (double)bombrange.Invoke(inst, new object[] { dive, tas, height, type });
                        var tof = (double)tofField.GetValue(inst);
                        sb.AppendLine(string.Format(CultureInfo.InvariantCulture,
                            "bombrange,{0},{1},{2},{3},{4},{5}", type, dive, tas, height, range, tof));
                    }
                }
            }
        }

        // The atmosphere helpers are plain arithmetic and are ported by reading; they are also
        // unreachable by reflection, because clsMeteo.Inch and clsMeteo.Hpa call each other without end.

        File.WriteAllText(args[1], sb.ToString());
        Console.WriteLine("wrote " + args[1]);
        return 0;
    }

    private static void Set(Type t, object o, string name, object value)
    {
        var f = t.GetField(name, BindingFlags.Public | BindingFlags.Instance);
        if (f != null) { f.SetValue(o, Convert.ChangeType(value, f.FieldType, CultureInfo.InvariantCulture)); return; }
        var p = t.GetProperty(name, BindingFlags.Public | BindingFlags.Instance);
        if (p != null && p.CanWrite) p.SetValue(o, Convert.ChangeType(value, p.PropertyType, CultureInfo.InvariantCulture), null);
    }

    private static string Get(Type t, object o, string name)
    {
        var p = t.GetProperty(name, BindingFlags.Public | BindingFlags.Instance);
        var v = p != null ? p.GetValue(o, null) : t.GetField(name, BindingFlags.Public | BindingFlags.Instance)?.GetValue(o);
        return Convert.ToString(v, CultureInfo.InvariantCulture);
    }
}

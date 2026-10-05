using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;

// Samples Weapon Delivery Planner's engine performance tables out of the program itself.
//
// Those five classes are 90,000 lines of decompiled C#, but they are not 90,000 lines of thought: each method is a
// lookup table with its interpolation unrolled into thousands of else-if branches. The breakpoints are read out of
// the source by tools/breakpoints.mjs; the VALUES at those breakpoints are asked of the real program here. So
// nothing is transcribed by hand, and the port is checked against the same program afterwards.
//
//   wdpref sample <WeaponDeliveryPlanner.exe> <enginespec.tsv> <outdir>
//
// The spec is one line per method:  cls <tab> name <tab> vis <tab> ret <tab> param:type:scale:b1,b2,… <tab> …
internal static class Sampler
{
    public static int Run(string exePath, string specPath, string outDir)
    {
        var asm = Assembly.LoadFrom(Path.GetFullPath(exePath));
        Directory.CreateDirectory(outDir);

        var index = new StringBuilder();
        int tabulated = 0, skipped = 0, points = 0;

        foreach (var line in File.ReadAllLines(specPath))
        {
            if (line.Trim().Length == 0) continue;
            var f = line.Split('\t');
            if (f.Length < 4) continue;
            string cls = f[0], name = f[1], vis = f[2], ret = f[3];
            if (vis != "public" || ret == "string") { skipped++; continue; }

            var type = asm.GetType("WeaponDeliveryPlanner." + cls);
            var method = type?.GetMethod(name, BindingFlags.Public | BindingFlags.Instance);
            if (method == null) { index.AppendLine($"skip\t{cls}.{name}\tno such method"); skipped++; continue; }

            var ps = method.GetParameters();
            var axes = new List<double[]>();
            string why = null;
            for (int i = 4; i < f.Length; i++)
            {
                var g = f[i].Split(':');
                string pname = g[0], ptype = g[1];
                double scale = double.Parse(g[2], CultureInfo.InvariantCulture);
                var breaks = g[3].Length == 0
                    ? new double[0]
                    : g[3].Split(',').Select(v => double.Parse(v, CultureInfo.InvariantCulture)).ToArray();

                if (ptype == "bool") { axes.Add(new double[] { 0, 1 }); continue; }
                if (breaks.Length >= 2) { axes.Add(breaks.Select(b => b * scale).ToArray()); continue; }
                // A parameter the table does not turn on is a correction applied after the lookup — the ISA
                // deviation and the MIL factor both are. Pin it at its neutral value; the port carries the formula.
                if (pname == "MilFactor") { axes.Add(new double[] { 1.0 }); continue; }
                if (pname == "ISA_Dev") { axes.Add(new double[] { 0.0 }); continue; }
                if (breaks.Length == 1) { axes.Add(new double[] { breaks[0] * scale }); continue; }
                why = "untabulated parameter " + pname;
                break;
            }
            if (why != null || axes.Count != ps.Length)
            {
                index.AppendLine($"skip\t{cls}.{name}\t{why ?? "parameter count"}");
                skipped++;
                continue;
            }

            var inst = Activator.CreateInstance(type);
            var sb = new StringBuilder();
            sb.Append("# ").Append(cls).Append('.').Append(name).Append('(')
              .Append(string.Join(", ", ps.Select(p => p.ParameterType.Name + " " + p.Name))).AppendLine(")");
            for (int i = 0; i < axes.Count; i++)
                sb.Append("axis\t").Append(ps[i].Name).Append('\t')
                  .AppendLine(string.Join("\t", axes[i].Select(v => v.ToString("R", CultureInfo.InvariantCulture))));

            var counters = new int[axes.Count];
            var values = new List<string>();
            while (true)
            {
                var args = new object[ps.Length];
                for (int i = 0; i < ps.Length; i++)
                {
                    var t = ps[i].ParameterType;
                    var v = axes[i][counters[i]];
                    args[i] = t == typeof(bool) ? (object)(v != 0) : Convert.ChangeType(v, t, CultureInfo.InvariantCulture);
                }
                string cell;
                try
                {
                    var r = method.Invoke(inst, args);
                    cell = r == null ? "" : Convert.ToDouble(r, CultureInfo.InvariantCulture).ToString("R", CultureInfo.InvariantCulture);
                }
                catch { cell = ""; }
                values.Add(cell);

                int d = axes.Count - 1;
                while (d >= 0 && ++counters[d] >= axes[d].Length) { counters[d] = 0; d--; }
                if (d < 0) break;
            }
            sb.Append("values\t").AppendLine(string.Join("\t", values));

            File.WriteAllText(Path.Combine(outDir, cls + "." + name + ".tsv"), sb.ToString());
            index.AppendLine($"ok\t{cls}.{name}\t{values.Count}");
            points += values.Count;
            tabulated++;
        }

        File.WriteAllText(Path.Combine(outDir, "_index.tsv"), index.ToString());
        Console.WriteLine($"tabulated {tabulated} methods ({points} points), skipped {skipped}");
        return 0;
    }
}

using System.Globalization;

namespace BmsCompanion.Bridge.Bms;

// Parser for the pilot DTC file <BMS>\User\Config\<callsign>.ini (written by "Save" in the DTC UI).
// Format reference: BMS Technical Manual, chapter "User File Structure".

public sealed class Dtc
{
    public long Modified { get; set; }
    /// <summary>Flight plan steerpoints and user TGT STPTs (target_N = steerpoint N+1).</summary>
    public List<DtcPoint> Steerpoints { get; set; } = new();
    public List<DtcPoint> WeaponTargets { get; set; } = new();
    public List<DtcPpt> Ppts { get; set; } = new();
    public List<DtcPoint> Lines { get; set; } = new();
    public List<Preset> Uhf { get; set; } = new();
    public List<Preset> Vhf { get; set; } = new();
    public Dictionary<string, string> Iff { get; set; } = new();
    public string? Bingo { get; set; }
}

public sealed class DtcPoint
{
    public int N { get; set; }
    public double X { get; set; }
    public double Y { get; set; }
    public double AltFt { get; set; }
    /// <summary>-1 = user target steerpoint, otherwise the waypoint action code.</summary>
    public int Action { get; set; }
    public bool IsTarget => Action == -1;
    public string? Name { get; set; }
}

public sealed class DtcPpt
{
    public int N { get; set; }
    public double X { get; set; }
    public double Y { get; set; }
    public double AltFt { get; set; }
    public double RangeNm { get; set; }
    public string? Name { get; set; }
}

public sealed class Preset { public int Ch { get; set; } public string Freq { get; set; } = ""; public string? Comment { get; set; } }

public static class DtcParser
{
    public static Dtc Parse(string path)
    {
        var dtc = new Dtc { Modified = new DateTimeOffset(File.GetLastWriteTimeUtc(path)).ToUnixTimeMilliseconds() };
        var sections = new Dictionary<string, Dictionary<string, string>>(StringComparer.OrdinalIgnoreCase);
        Dictionary<string, string>? cur = null;
        foreach (var raw in File.ReadAllLines(path))
        {
            var line = raw.Trim();
            if (line.StartsWith('[') && line.EndsWith(']'))
            {
                cur = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                sections[line[1..^1]] = cur;
                continue;
            }
            var eq = line.IndexOf('=');
            if (cur == null || eq <= 0) continue;
            cur[line[..eq].Trim()] = line[(eq + 1)..].Trim();
        }

        if (sections.TryGetValue("STPT", out var stpt))
        {
            foreach (var (k, v) in stpt)
            {
                var us = k.LastIndexOf('_');
                if (us < 0 || !int.TryParse(k[(us + 1)..], out var idx)) continue;
                var prefix = k[..us];
                var f = v.Split(',').Select(s => s.Trim()).ToArray();
                double N(int i) => i < f.Length && double.TryParse(f[i], NumberStyles.Float, CultureInfo.InvariantCulture, out var d) ? d : 0;
                bool empty = N(0) == 0 && N(1) == 0;
                if (empty) continue;
                switch (prefix)
                {
                    case "target":
                    case "wpntarget":
                        var p = new DtcPoint
                        {
                            N = idx + 1, X = N(0), Y = N(1), AltFt = Math.Abs(N(2)), Action = (int)N(3),
                            Name = f.Length > 4 && f[4].Length > 0 && f[4] != "Not set" ? string.Join(", ", f.Skip(4)).Trim() : null,
                        };
                        (prefix == "target" ? dtc.Steerpoints : dtc.WeaponTargets).Add(p);
                        break;
                    case "ppt":
                        var r = N(3);
                        dtc.Ppts.Add(new DtcPpt
                        {
                            N = idx + 56, // PPTs are steerpoints 56-70 in the F-16
                            X = N(0), Y = N(1), AltFt = Math.Abs(N(2)),
                            RangeNm = r > 500 ? r / 6076.12 : r,
                            Name = f.Length > 4 && f[4].Length > 0 ? f[4] : null,
                        });
                        break;
                    case "lineSTPT":
                        dtc.Lines.Add(new DtcPoint { N = idx, X = N(0), Y = N(1), AltFt = Math.Abs(N(2)) });
                        break;
                }
            }
            dtc.Steerpoints.Sort((a, b) => a.N.CompareTo(b.N));
            dtc.WeaponTargets.Sort((a, b) => a.N.CompareTo(b.N));
            dtc.Ppts.Sort((a, b) => a.N.CompareTo(b.N));
        }

        if (sections.TryGetValue("Radio", out var radio))
        {
            for (int ch = 1; ch <= 20; ch++)
            {
                if (radio.TryGetValue($"UHF_{ch}", out var u) && int.TryParse(u, out var uk) && uk > 0)
                    dtc.Uhf.Add(new Preset { Ch = ch, Freq = (uk / 1000.0).ToString("0.000", CultureInfo.InvariantCulture), Comment = Comment(radio.GetValueOrDefault($"UHF_COMMENT_{ch}")) });
                if (radio.TryGetValue($"VHF_{ch}", out var vh) && int.TryParse(vh, out var vk) && vk > 0)
                    dtc.Vhf.Add(new Preset { Ch = ch, Freq = (vk / 1000.0).ToString("0.000", CultureInfo.InvariantCulture), Comment = Comment(radio.GetValueOrDefault($"VHF_COMMENT_{ch}")) });
            }
        }

        if (sections.TryGetValue("IFF", out var iff))
        {
            foreach (var key in new[] { "Mode1 Code", "Mode2 Code", "Mode3A Code", "Mode4 Key" })
                if (iff.TryGetValue(key, out var v)) dtc.Iff[key] = v;
        }
        return dtc;
    }

    private static string? Comment(string? c) => c is null or "(open)" ? null : c;
}

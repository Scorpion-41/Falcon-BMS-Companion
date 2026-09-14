using System.Text.RegularExpressions;

namespace BmsCompanion.Bridge.Bms;

// Parser for <BMS>\User\Briefings\briefing.txt (the "Print" button output, text mode).
// Every section is also exported raw (Sections) so the app can still show data if a future BMS changes a layout.
// UPDATING: compare a new briefing.txt against Resources\demo_briefing.txt; section titles are matched in SectionTitles.

public sealed class Briefing
{
    public string? Generated { get; set; }
    public BriefOverview Overview { get; set; } = new();
    public string? Situation { get; set; }
    public List<RosterFlight> Roster { get; set; } = new();
    public List<PackageFlight> Package { get; set; } = new();
    public List<TextBlock> Threats { get; set; } = new();
    public List<BriefSteerpoint> Steerpoints { get; set; } = new();
    public List<CommEntry> Comms { get; set; } = new();
    public List<OrdnanceFlight> Ordnance { get; set; } = new();
    public WeatherTable? Weather { get; set; }
    public List<SupportEntry> Support { get; set; } = new();
    public List<string> Roe { get; set; } = new();
    public List<TextBlock> Emergency { get; set; } = new();
    public string? Alternate { get; set; }
    public List<RawSection> Sections { get; set; } = new();
}

public sealed class BriefOverview
{
    public string? Flight { get; set; }
    public string? Mission { get; set; }
    public string? PackageId { get; set; }
    public string? PackageType { get; set; }
    public string? PackageMission { get; set; }
    public string? TargetArea { get; set; }
    public string? Tot { get; set; }
    public string? Sunrise { get; set; }
    public string? Sunset { get; set; }
}

public sealed class RosterFlight { public string Callsign { get; set; } = ""; public List<string> Pilots { get; set; } = new(); }

public sealed class PackageFlight
{
    public string Callsign { get; set; } = "";
    public string? FlightId { get; set; }
    public bool Primary { get; set; }
    public string? Role { get; set; }
    public string? Aircraft { get; set; }
    public int? Count { get; set; }
    public string? Task { get; set; }
    public string? Takeoff { get; set; }
    public string? Push { get; set; }
    public string? Target { get; set; }
    public string? Iff { get; set; }
}

public sealed class TextBlock { public string? Title { get; set; } public List<string> Lines { get; set; } = new(); }

public sealed class BriefSteerpoint
{
    public int N { get; set; }
    public string? Desc { get; set; }
    public string? Time { get; set; }
    public string? Dist { get; set; }
    public string? Heading { get; set; }
    public string? Cas { get; set; }
    public string? Alt { get; set; }
    public string? Action { get; set; }
    public string? Formation { get; set; }
    public string? Comments { get; set; }
}

public sealed class CommEntry
{
    public string Agency { get; set; } = "";
    public string? Callsign { get; set; }
    public string? Uhf { get; set; }
    public int? UhfCh { get; set; }
    public string? Vhf { get; set; }
    public int? VhfCh { get; set; }
    public string? Notes { get; set; }
    public string? Group { get; set; }
}

public sealed class OrdnanceFlight { public string Flight { get; set; } = ""; public List<OrdnanceAircraft> Aircraft { get; set; } = new(); }
public sealed class OrdnanceAircraft { public string Name { get; set; } = ""; public List<Store> Stores { get; set; } = new(); }
public sealed class Store { public int Qty { get; set; } public string Name { get; set; } = ""; }

public sealed class WeatherTable { public List<string> Columns { get; set; } = new(); public List<WeatherRow> Rows { get; set; } = new(); }
public sealed class WeatherRow { public string Label { get; set; } = ""; public List<string> Values { get; set; } = new(); }

public sealed class SupportEntry { public string Callsign { get; set; } = ""; public string? Role { get; set; } public string? Aircraft { get; set; } public string? Notes { get; set; } }

public sealed class RawSection { public string Title { get; set; } = ""; public List<List<string>> Rows { get; set; } = new(); }

public static class BriefingParser
{
    private static readonly string[] SectionTitles =
    {
        "Mission Overview", "Situation", "Pilot Roster", "Package Elements", "Threat Analysis", "Steerpoints",
        "Comm Ladder", "Iff", "Link 16", "Ordnance", "Weather", "Support", "Rules of Engagement", "Emergency Procedures",
    };

    public static Briefing Parse(string text)
    {
        // With "Append new briefings" the file holds several records: use the last one.
        var recIdx = text.LastIndexOf("BRIEFING RECORD", StringComparison.Ordinal);
        if (recIdx > 0) text = text[recIdx..];
        var lines = text.Replace("\r", "").Split('\n');

        var b = new Briefing();
        var gen = Regex.Match(text, @"BRIEFING RECORD generated at (.+?)\.?\s*$", RegexOptions.Multiline);
        if (gen.Success) b.Generated = gen.Groups[1].Value.Trim();

        // Split into sections: a section header is an un-indented line starting with a known title.
        var sections = new List<(string title, List<string> body)>();
        (string title, List<string> body)? cur = null;
        foreach (var line in lines)
        {
            if (line.StartsWith("END_OF_BRIEFING")) break;
            var title = line.Length > 0 && !char.IsWhiteSpace(line[0]) ? SectionTitles.FirstOrDefault(t => line.StartsWith(t, StringComparison.OrdinalIgnoreCase)) : null;
            if (title != null)
            {
                cur = (title, new List<string>());
                sections.Add(cur.Value);
                // "Package Elements: <TAB>x = Primary Flight" keeps content after the colon; ignore it.
                continue;
            }
            cur?.body.Add(line);
        }

        foreach (var (title, body) in sections)
        {
            var rows = body.Select(Cells).Where(r => r.Count > 0).ToList();
            b.Sections.Add(new RawSection { Title = title, Rows = rows });
            try
            {
                switch (title)
                {
                    case "Mission Overview": ParseOverview(b, rows); break;
                    case "Situation": b.Situation = string.Join("\n\n", rows.Select(r => string.Join(" ", r))); break;
                    case "Pilot Roster": ParseRoster(b, rows); break;
                    case "Package Elements": ParsePackage(b, rows); break;
                    case "Threat Analysis": b.Threats = Blocks(rows); break;
                    case "Steerpoints": ParseSteerpoints(b, rows); break;
                    case "Comm Ladder": ParseComms(b, body); break;
                    case "Ordnance": ParseOrdnance(b, body); break;
                    case "Weather": ParseWeather(b, rows); break;
                    case "Support": ParseSupport(b, rows); break;
                    case "Rules of Engagement": b.Roe = rows.Select(r => string.Join(" ", r)).ToList(); break;
                    case "Emergency Procedures": ParseEmergency(b, rows); break;
                }
            }
            catch (Exception e) { Log.Warn($"Briefing section '{title}' parse failed: {e.Message}"); }
        }
        return b;
    }

    /// <summary>Tab separated cells, trimmed, empty cells removed.</summary>
    private static List<string> Cells(string line) =>
        line.Split('\t').Select(c => c.Trim()).Where(c => c.Length > 0).ToList();

    private static void ParseOverview(Briefing b, List<List<string>> rows)
    {
        var o = b.Overview;
        foreach (var r in rows)
        {
            var key = r[0].TrimEnd(':').Trim();
            var val = r.Count > 1 ? r[1] : null;
            switch (key)
            {
                case "Package #":
                    var m = Regex.Match(val ?? "", @"^(\d+)\s*\((.+)\)");
                    if (m.Success) { o.PackageId = m.Groups[1].Value; o.PackageType = m.Groups[2].Value.Trim(); } else o.PackageId = val;
                    break;
                case "Pkg-Mission": o.PackageMission = val; break;
                case "Target Area": o.TargetArea = val?.TrimEnd('.'); break;
                case "Time on Target": o.Tot = val; break;
                case "Sunrise": o.Sunrise = val; break;
                case "Sunset": o.Sunset = val; break;
                default:
                    if (o.Flight == null && r.Count == 1)
                    {
                        var fm = Regex.Match(r[0], @"^(\S+)\s*\((.+)\)");
                        if (fm.Success) { o.Flight = fm.Groups[1].Value; o.Mission = fm.Groups[2].Value.Trim(); }
                    }
                    break;
            }
        }
    }

    private static void ParseRoster(Briefing b, List<List<string>> rows)
    {
        foreach (var r in rows.Skip(1))
            b.Roster.Add(new RosterFlight { Callsign = r[0], Pilots = r.Skip(1).ToList() });
    }

    private static void ParsePackage(Briefing b, List<List<string>> rows)
    {
        PackageFlight? last = null;
        foreach (var r in rows)
        {
            if (r[0].StartsWith("Callsign:")) continue;
            if (r[0].StartsWith("T/O:"))
            {
                if (last == null) continue;
                foreach (var c in r)
                {
                    if (c.StartsWith("T/O:")) last.Takeoff = c[4..].Trim();
                    else if (c.StartsWith("Push:")) last.Push = c[5..].Trim();
                    else if (c.StartsWith("Tgt:")) last.Target = c[4..].Trim();
                    else if (c.StartsWith("IFF:")) last.Iff = c[4..].Trim();
                }
                continue;
            }
            if (r.Count < 3) continue;
            var f = new PackageFlight { Callsign = r[0] };
            var id = r[1];
            f.Primary = id.Contains("(x");
            f.FlightId = Regex.Match(id, @"\d+").Value;
            f.Role = r.ElementAtOrDefault(2);
            f.Aircraft = r.ElementAtOrDefault(3);
            f.Task = r.ElementAtOrDefault(4);
            var cm = Regex.Match(f.Aircraft ?? "", @"^(\d+)\s+(.+)$");
            if (cm.Success) { f.Count = int.Parse(cm.Groups[1].Value); f.Aircraft = cm.Groups[2].Value.Trim(); }
            b.Package.Add(f);
            last = f;
        }
    }

    private static List<TextBlock> Blocks(List<List<string>> rows)
    {
        var list = new List<TextBlock>();
        TextBlock? cur = null;
        foreach (var r in rows)
        {
            var text = string.Join(" ", r);
            if (text.EndsWith(':') && text.Length < 60 && !text.StartsWith("--"))
            {
                cur = new TextBlock { Title = text.TrimEnd(':') };
                list.Add(cur);
                continue;
            }
            if (cur == null) { cur = new TextBlock(); list.Add(cur); }
            cur.Lines.Add(text.StartsWith("-- ") ? text[3..] : text);
        }
        return list;
    }

    private static void ParseSteerpoints(Briefing b, List<List<string>> rows)
    {
        foreach (var r in rows)
        {
            if (!int.TryParse(r[0], out var n)) continue;
            string? At(int i) { var v = r.ElementAtOrDefault(i); return v is null or "--" ? null : v; }
            b.Steerpoints.Add(new BriefSteerpoint
            {
                N = n, Desc = At(1), Time = At(2), Dist = At(3), Heading = At(4), Cas = At(5), Alt = At(6),
                Action = At(7), Formation = At(8), Comments = At(9),
            });
        }
    }

    private static void ParseComms(Briefing b, List<string> body)
    {
        // Keep the blank-line groups (flight, common, AWACS, departure, arrival, alternate).
        int group = 0;
        foreach (var line in body)
        {
            var r = Cells(line);
            if (r.Count == 0) { group++; continue; }
            if (r[0].StartsWith("Agency:")) continue;
            if (r.Count < 3) continue;
            var e = new CommEntry
            {
                Agency = r[0].TrimEnd(':'),
                Callsign = r[1] == "None" ? null : r[1],
                Notes = r.ElementAtOrDefault(4),
                Group = group.ToString(),
            };
            (e.Uhf, e.UhfCh) = Freq(r.ElementAtOrDefault(2));
            (e.Vhf, e.VhfCh) = Freq(r.ElementAtOrDefault(3));
            b.Comms.Add(e);
        }
    }

    private static (string?, int?) Freq(string? s)
    {
        if (s == null || s == "--") return (null, null);
        var m = Regex.Match(s, @"([\d.]+)\s*MHz\s*(?:\[(\d+)\])?");
        if (!m.Success) return (s, null);
        return (m.Groups[1].Value, m.Groups[2].Success ? int.Parse(m.Groups[2].Value) : null);
    }

    private static void ParseOrdnance(Briefing b, List<string> body)
    {
        OrdnanceFlight? flight = null;
        foreach (var line in body)
        {
            var r = Cells(line);
            if (r.Count == 0 || r[0].StartsWith("Callsign:")) continue;
            if (r.Count >= 2 && r[1].StartsWith("--"))
            {
                // "<flight>  -- Name1 --  -- Name2 --"
                flight = new OrdnanceFlight { Flight = r[0] };
                foreach (var n in r.Skip(1)) flight.Aircraft.Add(new OrdnanceAircraft { Name = n.Trim('-', ' ') });
                b.Ordnance.Add(flight);
                continue;
            }
            if (flight == null) continue;
            // Store columns line up with aircraft columns (split on raw tabs to keep positions).
            var raw = line.Split('\t').Select(c => c.Trim()).Where(c => c.Length > 0).ToList();
            for (int i = 0; i < raw.Count && i < flight.Aircraft.Count; i++)
            {
                var m = Regex.Match(raw[i], @"^(\d+)x\s+(.+)$");
                if (m.Success) flight.Aircraft[i].Stores.Add(new Store { Qty = int.Parse(m.Groups[1].Value), Name = m.Groups[2].Value.Trim() });
                else flight.Aircraft[i].Stores.Add(new Store { Qty = 1, Name = raw[i] });
            }
        }
    }

    private static void ParseWeather(Briefing b, List<List<string>> rows)
    {
        var w = new WeatherTable();
        foreach (var r in rows)
        {
            if (r[0].StartsWith("Conditions")) { w.Columns = r.Skip(1).Select(c => c.TrimEnd(':')).ToList(); continue; }
            w.Rows.Add(new WeatherRow { Label = r[0].TrimEnd(':'), Values = r.Skip(1).ToList() });
        }
        b.Weather = w;
    }

    private static void ParseSupport(Briefing b, List<List<string>> rows)
    {
        foreach (var r in rows)
        {
            if (r[0].StartsWith("Callsign:")) continue;
            if (r.Count == 1 && r[0].StartsWith("No ", StringComparison.OrdinalIgnoreCase)) continue; // "No support flights available."
            var m = Regex.Match(r[0], @"^(\S+)\s*\((.+)\):?$");
            b.Support.Add(new SupportEntry
            {
                Callsign = m.Success ? m.Groups[1].Value : r[0].TrimEnd(':'),
                Role = m.Success ? m.Groups[2].Value : null,
                Aircraft = r.ElementAtOrDefault(1),
                Notes = r.ElementAtOrDefault(2),
            });
        }
    }

    private static void ParseEmergency(Briefing b, List<List<string>> rows)
    {
        b.Emergency = Blocks(rows);
        var alt = b.Emergency.FirstOrDefault(x => x.Title?.StartsWith("Alternate", StringComparison.OrdinalIgnoreCase) == true);
        b.Alternate = alt?.Lines.FirstOrDefault();
    }
}

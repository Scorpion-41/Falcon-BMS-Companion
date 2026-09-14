using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace BmsCompanion.Bridge.Bms;

/// <summary>Locates the BMS installation and the current pilot via the registry (the same keys EZBoards uses).</summary>
public sealed class BmsInstall
{
    public string? BaseDir { get; private set; }
    public string? RegistryVersion { get; private set; }
    public string? Callsign { get; private set; }
    public string? Theater { get; private set; }

    private const string SimsKey = @"SOFTWARE\WOW6432Node\Benchmark Sims";

    /// <summary>Re-reads the registry. <paramref name="overrideDir"/> wins when it points to a BMS folder.</summary>
    public void Refresh(string? overrideDir)
    {
        BaseDir = null; RegistryVersion = null; Callsign = null; Theater = null;
        try
        {
            using var sims = Registry.LocalMachine.OpenSubKey(SimsKey);
            if (sims != null)
            {
                // Pick the newest "Falcon BMS 4.xx" key whose baseDir exists, so a new BMS release needs no code change.
                var keys = sims.GetSubKeyNames()
                    .Where(n => n.StartsWith("Falcon BMS", StringComparison.OrdinalIgnoreCase))
                    .OrderByDescending(n => VersionOf(n));
                foreach (var name in keys)
                {
                    using var k = sims.OpenSubKey(name);
                    var dir = k?.GetValue("baseDir") as string;
                    if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir)) continue;
                    BaseDir = dir.TrimEnd('\\');
                    RegistryVersion = name;
                    Callsign = ReadString(k!.GetValue("PilotCallsign"));
                    Theater = ReadString(k.GetValue("curTheater"));
                    break;
                }
            }
        }
        catch (Exception e) { Log.Warn("Registry read failed: " + e.Message); }

        if (!string.IsNullOrWhiteSpace(overrideDir) && Directory.Exists(overrideDir))
            BaseDir = overrideDir.TrimEnd('\\');
    }

    private static Version VersionOf(string keyName)
    {
        var m = Regex.Match(keyName, @"(\d+)\.(\d+)");
        return m.Success ? new Version(int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value)) : new Version(0, 0);
    }

    private static string? ReadString(object? v) => v switch
    {
        byte[] b => Encoding.ASCII.GetString(b).TrimEnd('\0', ' '),
        string s => s.TrimEnd('\0', ' '),
        _ => null,
    };

    public string? ConfigDir => BaseDir == null ? null : Path.Combine(BaseDir, "User", "Config");

    /// <summary>Briefing folder, honouring g_sBriefingsDirectory from the cfg files.</summary>
    public string? BriefingsDir(string? fromSharedMemory)
    {
        if (!string.IsNullOrWhiteSpace(fromSharedMemory) && Directory.Exists(fromSharedMemory)) return fromSharedMemory;
        if (BaseDir == null) return null;
        foreach (var cfg in new[] { "Falcon BMS User.cfg", "Falcon BMS.cfg" })
        {
            var p = Path.Combine(ConfigDir!, cfg);
            var v = ReadCfgString(p, "g_sBriefingsDirectory");
            if (!string.IsNullOrWhiteSpace(v) && Directory.Exists(v)) return v;
        }
        return Path.Combine(BaseDir, "User", "Briefings");
    }

    /// <summary>Last value of "set name value" in a BMS cfg file (later lines win, like BMS itself).</summary>
    public static string? ReadCfgString(string path, string name)
    {
        try
        {
            if (!File.Exists(path)) return null;
            string? result = null;
            foreach (var raw in File.ReadLines(path))
            {
                var line = raw.Trim();
                if (!line.StartsWith("set ", StringComparison.OrdinalIgnoreCase)) continue;
                var body = line[4..];
                var cut = body.IndexOf("//", StringComparison.Ordinal);
                if (cut >= 0) body = body[..cut];
                body = body.Trim();
                if (!body.StartsWith(name + " ", StringComparison.OrdinalIgnoreCase) && !body.StartsWith(name + "\t", StringComparison.OrdinalIgnoreCase)) continue;
                result = body[name.Length..].Trim().Trim('"');
            }
            return result;
        }
        catch { return null; }
    }

    public string? DefaultEzBoardsDir()
    {
        if (BaseDir == null) return null;
        var d = Path.Combine(BaseDir, "Tools", "EZBoards");
        return File.Exists(Path.Combine(d, "EZBOARDS.BAT")) ? d : null;
    }

    public static bool IsBmsRunning()
    {
        try
        {
            return Process.GetProcessesByName("Falcon BMS").Length > 0;
        }
        catch { return false; }
    }
}

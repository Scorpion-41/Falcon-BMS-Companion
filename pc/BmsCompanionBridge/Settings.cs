using System.Text.Json;

namespace BmsCompanion.Bridge;

/// <summary>User settings, stored in %APPDATA%\BMS Companion\bridge-settings.json (never in the program folder).</summary>
public sealed class Settings
{
    public int Port { get; set; } = 47474;
    public string? BmsDirOverride { get; set; }
    public string? EzBoardsDir { get; set; }
    public bool AutoEzBoardsOnPrint { get; set; }
    public bool TacviewEnabled { get; set; } = true;
    public string TacviewHost { get; set; } = "127.0.0.1";
    public int TacviewPort { get; set; } = 42674;
    public string TacviewPassword { get; set; } = "";
    public bool StartMinimized { get; set; }
    /// <summary>Saved demo setting. Off by default so a first-time user always sees real BMS status.</summary>
    public bool DemoMode { get; set; }

    /// <summary>Demo forced by the --demo command line switch for this run only; never saved.</summary>
    [System.Text.Json.Serialization.JsonIgnore] public bool DemoFromCommandLine { get; set; }

    [System.Text.Json.Serialization.JsonIgnore] public bool DemoActive => DemoMode || DemoFromCommandLine;

    /// <summary>No settings file existed at start-up: show the setup guide.</summary>
    [System.Text.Json.Serialization.JsonIgnore] public bool FirstRun { get; set; }

    public static string Folder => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BMS Companion");
    private static string FilePath => Path.Combine(Folder, "bridge-settings.json");
    private static readonly JsonSerializerOptions Opts = new() { WriteIndented = true };

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath), Opts) ?? new Settings();
        }
        catch (Exception e) { Log.Warn("Settings load failed: " + e.Message); }
        return new Settings { FirstRun = !File.Exists(FilePath) };
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Opts));
        }
        catch (Exception e) { Log.Warn("Settings save failed: " + e.Message); }
    }
}

public static class Log
{
    private static readonly object Lock = new();
    private static readonly LinkedList<string> Lines = new();
    public static event Action<string>? Added;

    public static void Info(string msg) => Add("INFO", msg);
    public static void Warn(string msg) => Add("WARN", msg);

    private static void Add(string level, string msg)
    {
        var line = $"{DateTime.Now:HH:mm:ss} {level} {msg}";
        lock (Lock)
        {
            Lines.AddLast(line);
            while (Lines.Count > 300) Lines.RemoveFirst();
        }
        Added?.Invoke(line);
    }

    public static string[] Snapshot() { lock (Lock) return Lines.ToArray(); }
}

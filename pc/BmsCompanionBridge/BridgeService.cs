using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using BmsCompanion.Bridge.Bms;
using BmsCompanion.Bridge.Demo;
using BmsCompanion.Bridge.EzBoards;
using BmsCompanion.Bridge.Server;

namespace BmsCompanion.Bridge;

/// <summary>Owns all data sources and serves them through the HTTP API.</summary>
public sealed class BridgeService : IDisposable
{
    public static readonly string Version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.0.0";

    public Settings Settings { get; }
    public BmsInstall Install { get; } = new();
    public TacviewClient Tacview { get; } = new();
    public EzBoardsRunner Ez { get; } = new();
    public HttpServer Http { get; }
    private readonly Discovery _discovery = new();
    private DemoSource? _demo;
    private readonly System.Threading.Timer _timer;

    private BmsSnapshot _snap = new();
    private DateTime _snapTime = DateTime.MinValue;
    private readonly object _snapLock = new();

    private Briefing? _briefing;
    private long _briefingMtime;
    private string? _briefingPath;
    private Dtc? _dtc;
    private long _dtcMtime;
    private Board? _board;
    private long _boardKey;
    private readonly SemaphoreSlim _boardGate = new(1, 1);

    public event Action? StatusChanged;

    public static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        NumberHandling = JsonNumberHandling.AllowNamedFloatingPointLiterals,
    };

    public BridgeService(Settings settings)
    {
        Settings = settings;
        Http = new HttpServer(Handle);
        Ez.Completed += _ => { _boardKey = 0; StatusChanged?.Invoke(); };
        _timer = new System.Threading.Timer(_ => Tick(), null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start()
    {
        ApplySettings(restartServer: true);
        _timer.Change(0, 1000);
    }

    /// <summary>Re-applies settings (after the user changed them in the window).</summary>
    public void ApplySettings(bool restartServer)
    {
        Install.Refresh(Settings.BmsDirOverride);
        if (string.IsNullOrWhiteSpace(Settings.EzBoardsDir))
        {
            var auto = Install.DefaultEzBoardsDir();
            if (auto != null) { Settings.EzBoardsDir = auto; Settings.Save(); }
        }
        _demo = Settings.DemoActive ? new DemoSource() : null;
        if (Settings.TacviewEnabled && _demo == null) Tacview.Start(Settings.TacviewHost, Settings.TacviewPort, Settings.TacviewPassword);
        else Tacview.Stop();
        if (restartServer)
        {
            try { Http.Start(Settings.Port); }
            catch (Exception e) { Log.Warn($"Cannot listen on port {Settings.Port}: {e.Message}"); }
            _discovery.Start(() => Settings.Port, Version);
        }
        lock (_filesLock) { _briefing = null; _dtc = null; _briefingMtime = 0; _dtcMtime = 0; _boardKey = 0; }
        StatusChanged?.Invoke();
    }

    private int _tick;
    private void Tick()
    {
        try
        {
            if (++_tick % 10 == 0) Install.Refresh(Settings.BmsDirOverride);
            var changed = RefreshFiles(out bool briefingPrinted);
            if (briefingPrinted && Settings.AutoEzBoardsOnPrint && _demo == null && EzBoardsRunner.IsValidDir(Settings.EzBoardsDir))
            {
                Log.Info("Briefing printed - running EZBoards automatically");
                _ = Ez.GenerateAsync(Settings.EzBoardsDir, auto: true);
            }
            if (changed || _tick % 2 == 0) StatusChanged?.Invoke();
        }
        catch (Exception e) { Log.Warn("Tick: " + e.Message); }
    }

    private BmsSnapshot Snapshot()
    {
        lock (_snapLock)
        {
            if ((DateTime.UtcNow - _snapTime).TotalMilliseconds > 150)
            {
                _snap = SharedMemoryReader.Read();
                _snapTime = DateTime.UtcNow;
            }
            return _snap;
        }
    }

    public string? BriefingPath
    {
        get
        {
            var dir = Install.BriefingsDir(Snapshot().Strings.GetValueOrDefault(StringId.BmsBriefingsDirectory));
            return dir == null ? null : Path.Combine(dir, "briefing.txt");
        }
    }

    public string? CallsignIni
    {
        get
        {
            if (Install.ConfigDir == null || string.IsNullOrWhiteSpace(Install.Callsign)) return null;
            return Path.Combine(Install.ConfigDir, Install.Callsign + ".ini");
        }
    }

    /// <summary>Reloads briefing.txt / callsign.ini when their timestamps change.</summary>
    private readonly object _filesLock = new();

    private bool RefreshFiles(out bool briefingPrinted)
    {
        lock (_filesLock) return RefreshFilesLocked(out briefingPrinted);
    }

    private bool RefreshFilesLocked(out bool briefingPrinted)
    {
        briefingPrinted = false;
        if (_demo != null)
        {
            if (_briefing == null)
            {
                _briefing = BriefingParser.Parse(_demo.BriefingText);
                _briefingMtime = _demo.BriefingModified;
                _dtc = _demo.Dtc();
                _dtcMtime = _dtc.Modified;
                return true;
            }
            return false;
        }

        bool changed = false;
        var bp = BriefingPath;
        long bm = bp != null && File.Exists(bp) ? new DateTimeOffset(File.GetLastWriteTimeUtc(bp)).ToUnixTimeMilliseconds() : 0;
        if (bm != _briefingMtime || bp != _briefingPath)
        {
            bool firstLoad = _briefingMtime == 0 || bp != _briefingPath;
            _briefingPath = bp;
            _briefingMtime = bm;
            _briefing = null;
            if (bm > 0)
            {
                try
                {
                    _briefing = BriefingParser.Parse(ReadShared(bp!));
                    Log.Info($"Briefing loaded ({_briefing.Generated})");
                    if (!firstLoad) briefingPrinted = true;
                }
                catch (Exception e) { Log.Warn("Briefing read failed: " + e.Message); _briefingMtime = 0; }
            }
            changed = true;
        }

        var ini = CallsignIni;
        long im = ini != null && File.Exists(ini) ? new DateTimeOffset(File.GetLastWriteTimeUtc(ini)).ToUnixTimeMilliseconds() : 0;
        if (im != _dtcMtime)
        {
            _dtcMtime = im;
            _dtc = null;
            if (im > 0)
            {
                try { _dtc = DtcParser.Parse(ini!); }
                catch (Exception e) { Log.Warn("DTC read failed: " + e.Message); _dtcMtime = 0; }
            }
            changed = true;
        }
        return changed;
    }

    /// <summary>Reads a file BMS may still have open for writing.</summary>
    private static string ReadShared(string path)
    {
        for (int i = 0; ; i++)
        {
            try
            {
                using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                using var r = new StreamReader(fs);
                return r.ReadToEnd();
            }
            catch (IOException) when (i < 5) { Thread.Sleep(150); }
        }
    }

    private async Task<Board?> BoardAsync()
    {
        long key = _briefingMtime ^ (_dtcMtime << 1) ^ (Settings.EzBoardsDir?.GetHashCode() ?? 0);
        if (_boardKey == key && _board != null) return _board;
        await _boardGate.WaitAsync();
        try
        {
            if (_boardKey == key && _board != null) return _board;
            string? briefing = _briefingPath;
            string? tmp = null;
            if (_demo != null)
            {
                tmp = Path.Combine(Path.GetTempPath(), "bms-companion-demo-briefing.txt");
                // xbrief needs CRLF line endings, like BMS writes them.
                await File.WriteAllTextAsync(tmp, _demo.BriefingText.Replace("\r\n", "\n").Replace("\n", "\r\n"));
                briefing = tmp;
            }
            _board = briefing == null ? null : await EzBoardsRunner.ReadBoardAsync(Settings.EzBoardsDir, briefing, _demo == null ? CallsignIni : null);
            _boardKey = key;
            if (tmp != null) try { File.Delete(tmp); } catch { }
            return _board;
        }
        finally { _boardGate.Release(); }
    }

    // ------------------------------------------------------------------ API

    public InfoDto Info()
    {
        var snap = _demo == null ? Snapshot() : null;
        var info = new InfoDto { Version = Version, Demo = _demo != null };
        info.Bms = new BmsStatusDto
        {
            Installed = Install.BaseDir != null,
            BaseDir = Install.BaseDir,
            RegistryVersion = Install.RegistryVersion,
            Version = snap?.Version,
            Running = _demo != null || (snap?.Available ?? false),
            Flying = _demo != null || (snap?.Flying ?? false),
            Theater = _demo != null ? "Hellas" : snap?.Strings.GetValueOrDefault(StringId.ThrName) ?? Install.Theater,
            Callsign = _demo != null ? "Demo" : Install.Callsign,
            Aircraft = _demo != null ? "F-16C Block 52+" : snap?.Strings.GetValueOrDefault(StringId.AcName),
        };
        info.Tacview = new TacviewStatusDto
        {
            Enabled = Settings.TacviewEnabled || _demo != null,
            Connected = _demo != null || Tacview.Connected,
            State = _demo != null ? "demo" : Tacview.State,
            Objects = _demo != null ? 11 : Tacview.ObjectCount,
        };
        info.Briefing = new BriefingStatusDto { Available = _briefing != null, Modified = _briefingMtime, Generated = _briefing?.Generated, DtcModified = _dtcMtime };
        info.EzBoards = new EzStatusDto
        {
            Configured = EzBoardsRunner.IsValidDir(Settings.EzBoardsDir),
            Path = Settings.EzBoardsDir,
            AutoOnPrint = Settings.AutoEzBoardsOnPrint,
            Running = Ez.Running,
            LastRun = Ez.LastRun,
        };
        return info;
    }

    private async Task<HttpResponse> Handle(HttpRequest req)
    {
        string J(object o) => JsonSerializer.Serialize(o, o.GetType(), Json);
        switch (req.Method, req.Path.TrimEnd('/'))
        {
            case ("GET", ""):
            case ("GET", "/index.html"):
                return HttpResponse.Html(StatusPage());
            case ("GET", "/api/info"):
                return HttpResponse.Json(J(Info()));
            case ("GET", "/api/live"):
            {
                if (_demo != null) return HttpResponse.Json(J(_demo.Live()));
                var snap = Snapshot();
                return HttpResponse.Json(J(snap.Available ? snap.Live : new LiveDto { T = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() }));
            }
            case ("GET", "/api/contacts"):
            {
                if (_demo != null) return HttpResponse.Json(J(_demo.Contacts()));
                var snap = Snapshot();
                return HttpResponse.Json(J(Tacview.Snapshot(snap.Flying ? snap.Live.X : null, snap.Flying ? snap.Live.Y : null)));
            }
            case ("GET", "/api/mission"):
            {
                RefreshFiles(out _);
                var board = await BoardAsync();
                return HttpResponse.Json(J(new
                {
                    version = $"{_briefingMtime}-{_dtcMtime}-{board?.Time ?? 0}",
                    briefingModified = _briefingMtime,
                    briefing = _briefing,
                    dtc = _dtc,
                    board,
                }));
            }
            case ("POST", "/api/ezboards/generate"):
            {
                if (_demo != null && !EzBoardsRunner.IsValidDir(Settings.EzBoardsDir))
                    return HttpResponse.Json(J(new EzRunDto { Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), Ok = true, DurationMs = 800, Message = "Demo mode: kneeboards would be generated now." }));
                if (_demo != null)
                    return HttpResponse.Json(J(new EzRunDto { Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), Ok = true, Message = "Demo mode: EZBoards was not run (it would overwrite your kneeboards with demo data)." }));
                var r = await Ez.GenerateAsync(Settings.EzBoardsDir, auto: false);
                return HttpResponse.Json(J(r), r.Ok ? 200 : 409);
            }
            case ("GET", "/api/ezboards/status"):
                return HttpResponse.Json(J(Info().EzBoards));
            default:
                return HttpResponse.NotFound();
        }
    }

    private string StatusPage()
    {
        var i = Info();
        string Esc(string? s) => System.Net.WebUtility.HtmlEncode(s ?? "—");
        return $@"<!doctype html><html><head><meta name=viewport content='width=device-width'><title>BMS Companion Bridge</title>
<style>body{{background:#0a0f14;color:#dfe8ef;font:15px system-ui;margin:24px}}h1{{color:#ffb547}}td{{padding:4px 12px}}.k{{color:#8aa}}</style></head><body>
<h1>BMS Companion Bridge {Esc(i.Version)}</h1><p>Open the <b>BMS Companion</b> Android app &rarr; Mission to connect.</p><table>
<tr><td class=k>BMS</td><td>{(i.Bms.Running ? "running" : "not running")} {Esc(i.Bms.Version)} · {Esc(i.Bms.Theater)}</td></tr>
<tr><td class=k>Tacview stream</td><td>{Esc(i.Tacview.State)} ({i.Tacview.Objects} objects)</td></tr>
<tr><td class=k>Briefing</td><td>{(i.Briefing.Available ? Esc(i.Briefing.Generated) : "not printed yet")}</td></tr>
<tr><td class=k>EZBoards</td><td>{(i.EzBoards.Configured ? Esc(i.EzBoards.Path) : "not configured")}</td></tr>
</table><p class=k>API: /api/info · /api/live · /api/contacts · /api/mission · POST /api/ezboards/generate</p></body></html>";
    }

    public void Dispose()
    {
        _timer.Dispose();
        Http.Dispose();
        _discovery.Dispose();
        Tacview.Dispose();
    }
}

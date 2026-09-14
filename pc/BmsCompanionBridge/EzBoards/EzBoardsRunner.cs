using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using BmsCompanion.Bridge.Server;

namespace BmsCompanion.Bridge.EzBoards;

/// <summary>Board data extracted from EZBoards' xbrief HTML: the same tables that end up on the kneeboard.</summary>
public sealed class Board
{
    public long Time { get; set; }
    public string Format { get; set; } = "";
    public List<BoardTable> Tables { get; set; } = new();
}

public sealed class BoardTable
{
    public string Title { get; set; } = "";
    public List<string> Header { get; set; } = new();
    public List<BoardRow> Rows { get; set; } = new();
}

public sealed class BoardRow
{
    /// <summary>xbrief row class: ownflight, ownroster, odd, even, …</summary>
    public string? Kind { get; set; }
    public List<string> Cells { get; set; } = new();
}

/// <summary>
/// Runs the EZBoards tool (by "Logic", shipped in &lt;BMS&gt;\Tools\EZBoards) without a console window.
/// EZBOARDS.BAT skips its PAUSE when given any argument and ends with "SUCCESS." / "### ERROR ###".
/// </summary>
public sealed class EzBoardsRunner
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    public bool Running { get; private set; }
    public EzRunDto? LastRun { get; private set; }
    public event Action<EzRunDto>? Completed;

    public static bool IsValidDir(string? dir) => !string.IsNullOrWhiteSpace(dir) && File.Exists(Path.Combine(dir, "EZBOARDS.BAT"));

    public async Task<EzRunDto> GenerateAsync(string? dir, bool auto)
    {
        var result = new EzRunDto { Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), Auto = auto };
        if (!IsValidDir(dir))
        {
            result.Message = "EZBoards folder not set (EZBOARDS.BAT not found). Choose it in the bridge settings.";
            return Finish(result);
        }
        if (!await _gate.WaitAsync(0))
        {
            result.Message = "EZBoards is already running.";
            return result;
        }
        Running = true;
        var sw = Stopwatch.StartNew();
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe",
                // Full path + any argument: EZBOARDS.BAT skips its PAUSE when it gets a parameter.
                Arguments = $"/d /s /c \"\"{Path.Combine(dir!, "EZBOARDS.BAT")}\" companion\"",
                WorkingDirectory = dir!,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                RedirectStandardInput = true,
                StandardOutputEncoding = Encoding.UTF8,
            };
            // The .BAT calls CONFIG.BAT, bin\xbrief.exe etc. relative to its folder; make sure cmd looks there.
            psi.Environment.Remove("NoDefaultCurrentDirectoryInExePath");
            using var p = new Process { StartInfo = psi };
            var log = new List<string>();
            p.OutputDataReceived += (_, e) => { if (e.Data != null) lock (log) log.Add(Clean(e.Data)); };
            p.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (log) log.Add(Clean(e.Data)); };
            p.Start();
            p.StandardInput.Close(); // any stray PAUSE returns immediately
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(90));
            try { await p.WaitForExitAsync(cts.Token); }
            catch (OperationCanceledException)
            {
                try { p.Kill(true); } catch { }
                lock (log) log.Add("Timed out after 90 s.");
            }
            p.WaitForExit(2000);
            List<string> lines;
            lock (log) lines = log.Where(l => l.Length > 0 && !ProgressLine.IsMatch(l)).ToList();
            bool success = p.HasExited && p.ExitCode == 0 && lines.Any(l => l.Contains("SUCCESS."));
            result.Ok = success;
            result.Log = lines.TakeLast(40).ToList();
            result.Message = success
                ? "Kneeboards generated."
                : lines.LastOrDefault(l => l.Contains("Could not find") || l.Contains("ERROR") || l.Contains("error", StringComparison.OrdinalIgnoreCase)) ?? "EZBoards failed (see log).";
        }
        catch (Exception e)
        {
            result.Message = "Could not start EZBoards: " + e.Message;
        }
        finally
        {
            result.DurationMs = sw.ElapsedMilliseconds;
            Running = false;
            _gate.Release();
        }
        return Finish(result);
    }

    private EzRunDto Finish(EzRunDto r)
    {
        LastRun = r;
        Log.Info($"EZBoards: {(r.Ok ? "OK" : "FAILED")} - {r.Message} ({r.DurationMs} ms)");
        Completed?.Invoke(r);
        return r;
    }

    // wkhtmltoimage progress bars ("[=====>   ] 25%") only clutter the log shown in the app
    private static readonly Regex ProgressLine = new(@"^s*[[=> ]*]s*d+%$", RegexOptions.Compiled);
    private static readonly Regex Ansi = new(@"\x1B\[[0-9;]*[A-Za-z]", RegexOptions.Compiled);
    private static string Clean(string s) => Ansi.Replace(s, "").TrimEnd();

    /// <summary>
    /// Runs EZBoards' xbrief.exe directly (read only: output goes to stdout, nothing is written) to get every board
    /// section, including target steerpoints and min-fuel columns, then parses the HTML tables into data.
    /// </summary>
    public static async Task<Board?> ReadBoardAsync(string? dir, string briefingTxt, string? callsignIni, string format = "pcstw")
    {
        if (!IsValidDir(dir)) return null;
        var exe = Path.Combine(dir!, "bin", "xbrief.exe");
        if (!File.Exists(exe) || !File.Exists(briefingTxt)) return null;
        var psi = new ProcessStartInfo
        {
            FileName = exe,
            WorkingDirectory = dir!,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
        };
        psi.ArgumentList.Add("--format"); psi.ArgumentList.Add(format);
        if (File.Exists(Path.Combine(dir!, "Comments.txt"))) { psi.ArgumentList.Add("--comments"); psi.ArgumentList.Add("Comments.txt"); }
        psi.ArgumentList.Add(briefingTxt);
        if (callsignIni != null && File.Exists(callsignIni)) psi.ArgumentList.Add(callsignIni);
        try
        {
            using var p = Process.Start(psi)!;
            var outTask = p.StandardOutput.ReadToEndAsync();
            var errTask = p.StandardError.ReadToEndAsync();
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            await p.WaitForExitAsync(cts.Token);
            var html = await outTask;
            await errTask;
            if (p.ExitCode != 0) return null;
            var board = ParseHtml(html);
            board.Format = format;
            board.Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            return board;
        }
        catch (Exception e)
        {
            Log.Warn("xbrief failed: " + e.Message);
            return null;
        }
    }

    public static Board ParseHtml(string html)
    {
        var board = new Board();
        foreach (Match t in Regex.Matches(html, @"<table[^>]*>(.*?)</table>", RegexOptions.Singleline | RegexOptions.IgnoreCase))
        {
            var table = new BoardTable();
            foreach (Match tr in Regex.Matches(t.Groups[1].Value, @"<tr([^>]*)>(.*?)</tr>", RegexOptions.Singleline | RegexOptions.IgnoreCase))
            {
                var attrs = tr.Groups[1].Value;
                var inner = tr.Groups[2].Value;
                var title = Regex.Match(inner, @"<th[^>]*class=""(?:title|stamp)""[^>]*>(.*?)</th>", RegexOptions.Singleline);
                if (title.Success) { table.Title = Text(title.Groups[1].Value); continue; }
                var cls = Regex.Match(attrs, @"class=""([^""]*)""").Groups[1].Value;
                var ths = Regex.Matches(inner, @"<th[^>]*>(.*?)</th>", RegexOptions.Singleline);
                if (cls == "header" || (ths.Count > 0 && !inner.Contains("<td")))
                {
                    table.Header = ths.Select(m => Text(m.Groups[1].Value).TrimEnd(':')).ToList();
                    continue;
                }
                var cells = Regex.Matches(inner, @"<td[^>]*>(.*?)</td>", RegexOptions.Singleline).Select(m => Text(m.Groups[1].Value)).ToList();
                if (cells.Count > 0) table.Rows.Add(new BoardRow { Kind = cls.Length > 0 ? cls : null, Cells = cells });
            }
            if (table.Title.Length > 0 || table.Rows.Count > 0) board.Tables.Add(table);
        }
        return board;
    }

    private static string Text(string html) => WebUtility.HtmlDecode(Regex.Replace(html, "<[^>]+>", " ")).Replace(' ', ' ').Trim();
}

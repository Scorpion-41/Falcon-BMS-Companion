using System.Globalization;
using System.Net.Sockets;
using System.Text;
using BmsCompanion.Bridge.Server;

namespace BmsCompanion.Bridge.Bms;

/// <summary>
/// Client for the Tacview real-time telemetry stream that BMS 4.37.4+ serves (g_bTacviewRealTime 1, default port 42674).
/// This is the only source of other aircraft positions (the AWACS picture); BMS shared memory has ownship data only.
/// The stream is plain ACMI 2.x text: "id,T=lon|lat|alt|roll|pitch|yaw|u|v|heading,Name=..,Type=..,Coalition=..", "#time", "-id".
/// </summary>
public sealed class TacviewClient : IDisposable
{
    private const double BmsFtPerMeter = 3.27998;   // BMS theater feet (1024 km theater = 3,358,700 ft)
    private const double RealFtPerMeter = 3.28084;
    private const double FtPerNm = 6076.12;

    private sealed class Obj
    {
        public string Id = "";
        public double U, V, AltM, Hdg, PrevU, PrevV, PrevTime, GsKts;
        public bool HasPos;
        public string? Name, Pilot, Group, Coalition, Color, Type, CallSign;
    }

    private readonly Dictionary<string, Obj> _objects = new();
    private readonly object _lock = new();
    private CancellationTokenSource? _cts;
    private Task? _task;
    private double _time;

    public string Host { get; private set; } = "127.0.0.1";
    public int Port { get; private set; } = 42674;
    public string Password { get; private set; } = "";
    public bool Connected { get; private set; }
    public string State { get; private set; } = "off";

    public void Start(string host, int port, string password)
    {
        if (_task != null && host == Host && port == Port && password == Password) return;
        Stop();
        Host = host; Port = port; Password = password;
        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _task = Task.Run(() => Loop(token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        _task = null;
        Connected = false;
        State = "off";
        lock (_lock) _objects.Clear();
    }

    private async Task Loop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            if (!BmsInstall.IsBmsRunning() && Host is "127.0.0.1" or "localhost")
            {
                State = "waiting for BMS";
                await Delay(3000, ct);
                continue;
            }
            try
            {
                // Keep the last failure text while retrying so status displays do not flicker every few seconds.
                if (State is "off" or "waiting for BMS") State = "connecting";
                using var tcp = new TcpClient();
                using (var cts = CancellationTokenSource.CreateLinkedTokenSource(ct))
                {
                    cts.CancelAfter(4000);
                    await tcp.ConnectAsync(Host, Port, cts.Token);
                }
                tcp.NoDelay = true;
                using var stream = tcp.GetStream();
                // Handshake: both sides send a NUL-terminated block of 4 lines; password "0" means none.
                var hello = $"XtraLib.Stream.0\nTacview.RealTimeTelemetry.0\nBMS Companion\n{(string.IsNullOrEmpty(Password) ? "0" : Password)}\0";
                await stream.WriteAsync(Encoding.UTF8.GetBytes(hello), ct);
                var reader = new StreamLineReader(stream);
                var header = await reader.ReadUntilAsync(0, ct);
                if (header == null || !header.StartsWith("XtraLib.Stream.0")) throw new IOException("bad handshake");
                lock (_lock) { _objects.Clear(); _time = 0; }
                Connected = true;
                State = "connected";
                Log.Info($"Tacview connected to {Host}:{Port}");
                while (!ct.IsCancellationRequested)
                {
                    var line = await reader.ReadUntilAsync((byte)'\n', ct);
                    if (line == null) break;
                    ParseLine(line);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (Exception e)
            {
                State = e is SocketException or OperationCanceledException
                    ? "no stream: set g_bTacviewRealTime 1, start ACMI recording"
                    : "error: " + e.Message;
            }
            if (Connected) { Log.Info("Tacview disconnected"); State = "stream ended, retrying"; }
            Connected = false;
            lock (_lock) _objects.Clear();
            await Delay(3000, ct);
        }
    }

    private static async Task Delay(int ms, CancellationToken ct)
    {
        try { await Task.Delay(ms, ct); } catch (OperationCanceledException) { }
    }

    private void ParseLine(string raw)
    {
        var line = raw.TrimEnd('\r');
        if (line.Length == 0) return;
        char c0 = line[0];
        if (c0 == '#')
        {
            if (double.TryParse(line.AsSpan(1), NumberStyles.Float, CultureInfo.InvariantCulture, out var t)) _time = t;
            return;
        }
        if (c0 == '-')
        {
            lock (_lock) _objects.Remove(line[1..]);
            return;
        }
        if (line.StartsWith("FileType") || line.StartsWith("FileVersion")) return;
        int comma = line.IndexOf(',');
        if (comma <= 0) return;
        var id = line[..comma];
        if (id == "0") return; // global properties
        lock (_lock)
        {
            if (!_objects.TryGetValue(id, out var o)) { o = new Obj { Id = id }; _objects[id] = o; }
            foreach (var prop in SplitProps(line, comma + 1))
            {
                int eq = prop.IndexOf('=');
                if (eq <= 0) continue;
                var key = prop[..eq];
                var val = prop[(eq + 1)..];
                switch (key)
                {
                    case "T": ApplyTransform(o, val); break;
                    case "Name": o.Name = val; break;
                    case "Pilot": o.Pilot = val; break;
                    case "Group": o.Group = val; break;
                    case "CallSign": o.CallSign = val; break;
                    case "Coalition": o.Coalition = val; break;
                    case "Color": o.Color = val; break;
                    case "Type": o.Type = val; break;
                }
            }
        }
    }

    /// <summary>Splits on commas that are not escaped with a backslash.</summary>
    private static IEnumerable<string> SplitProps(string line, int start)
    {
        var sb = new StringBuilder();
        for (int i = start; i < line.Length; i++)
        {
            char ch = line[i];
            if (ch == '\\' && i + 1 < line.Length) { sb.Append(line[++i]); continue; }
            if (ch == ',') { yield return sb.ToString(); sb.Clear(); continue; }
            sb.Append(ch);
        }
        if (sb.Length > 0) yield return sb.ToString();
    }

    private void ApplyTransform(Obj o, string val)
    {
        // Empty fields mean "unchanged". Flat-world forms carry U/V: 5 fields (lon|lat|alt|u|v) or 9 fields (... |u|v|heading).
        var f = val.Split('|');
        double? Get(int i) => i < f.Length && f[i].Length > 0 && double.TryParse(f[i], NumberStyles.Float, CultureInfo.InvariantCulture, out var d) ? d : null;
        double? u = null, v = null, alt = Get(2), hdg = null;
        if (f.Length == 5) { u = Get(3); v = Get(4); }
        else if (f.Length >= 9) { u = Get(6); v = Get(7); hdg = Get(8) ?? Get(5); }
        if (alt.HasValue) o.AltM = alt.Value;
        if (hdg.HasValue) o.Hdg = (hdg.Value % 360 + 360) % 360;
        if (u.HasValue || v.HasValue)
        {
            double nu = u ?? o.U, nv = v ?? o.V;
            if (o.HasPos && _time > o.PrevTime + 0.5)
            {
                double dist = Math.Sqrt((nu - o.PrevU) * (nu - o.PrevU) + (nv - o.PrevV) * (nv - o.PrevV));
                o.GsKts = dist / (_time - o.PrevTime) * 1.943844;
                o.PrevU = nu; o.PrevV = nv; o.PrevTime = _time;
            }
            else if (!o.HasPos) { o.PrevU = nu; o.PrevV = nv; o.PrevTime = _time; }
            o.U = nu; o.V = nv; o.HasPos = true;
        }
    }

    public int ObjectCount { get { lock (_lock) return _objects.Count; } }

    /// <summary>Air, sea, missile and bullseye objects in BMS theater feet. Ground clutter is skipped to keep payloads small.</summary>
    public ContactsDto Snapshot(double? ownX, double? ownY)
    {
        var dto = new ContactsDto { T = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), Connected = Connected, State = State };
        lock (_lock)
        {
            ContactDto? own = null;
            double best = 2 * FtPerNm;
            foreach (var o in _objects.Values)
            {
                if (!o.HasPos || o.Type == null) continue;
                var kind = KindOf(o.Type);
                if (kind == null) continue;
                var c = new ContactDto
                {
                    Id = o.Id, Kind = kind,
                    X = o.V * BmsFtPerMeter, Y = o.U * BmsFtPerMeter,
                    AltFt = o.AltM * RealFtPerMeter, Hdg = Math.Round(o.Hdg, 1), GsKts = Math.Round(o.GsKts),
                    Name = o.Name, Pilot = o.Pilot, Group = o.Group ?? o.CallSign, Coalition = o.Coalition, Color = o.Color,
                };
                if (ownX.HasValue && ownY.HasValue && kind is "air" or "heli")
                {
                    double d = Math.Sqrt((c.X - ownX.Value) * (c.X - ownX.Value) + (c.Y - ownY.Value) * (c.Y - ownY.Value));
                    if (d < best) { best = d; own = c; }
                }
                dto.Contacts.Add(c);
            }
            if (own != null) own.Own = true;
            var ownCoalition = own?.Coalition;
            foreach (var c in dto.Contacts)
                c.Friendly = ownCoalition != null && string.Equals(c.Coalition, ownCoalition, StringComparison.OrdinalIgnoreCase);
        }
        return dto;
    }

    private static string? KindOf(string type)
    {
        if (type.Contains("Bullseye")) return "bullseye";
        if (type.Contains("Missile") || type.Contains("Weapon")) return type.Contains("Missile") ? "missile" : null;
        if (type.Contains("Rotorcraft")) return "heli";
        if (type.Contains("Air")) return "air";
        if (type.Contains("Sea") || type.Contains("Watercraft")) return "ship";
        return null;
    }

    public void Dispose() => Stop();

    /// <summary>Minimal buffered reader that splits a byte stream on a delimiter.</summary>
    private sealed class StreamLineReader
    {
        private readonly Stream _s;
        private readonly byte[] _buf = new byte[65536];
        private int _start, _end;
        private readonly MemoryStream _acc = new();

        public StreamLineReader(Stream s) => _s = s;

        public async Task<string?> ReadUntilAsync(byte delim, CancellationToken ct)
        {
            _acc.SetLength(0);
            while (true)
            {
                for (int i = _start; i < _end; i++)
                {
                    if (_buf[i] != delim) continue;
                    _acc.Write(_buf, _start, i - _start);
                    _start = i + 1;
                    return Encoding.UTF8.GetString(_acc.GetBuffer(), 0, (int)_acc.Length);
                }
                _acc.Write(_buf, _start, _end - _start);
                _start = _end = 0;
                int n = await _s.ReadAsync(_buf, ct);
                if (n <= 0) return null;
                _end = n;
            }
        }
    }
}

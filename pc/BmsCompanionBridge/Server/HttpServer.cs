using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace BmsCompanion.Bridge.Server;

public sealed record HttpRequest(string Method, string Path, Dictionary<string, string> Query, Dictionary<string, string> Headers, string RemoteIp);
public sealed record HttpResponse(int Status, string ContentType, byte[] Body)
{
    public static HttpResponse Json(string json, int status = 200) => new(status, "application/json; charset=utf-8", Encoding.UTF8.GetBytes(json));
    public static HttpResponse Html(string html) => new(200, "text/html; charset=utf-8", Encoding.UTF8.GetBytes(html));
    public static HttpResponse NotFound() => Json("{\"error\":\"not found\"}", 404);
}

/// <summary>
/// Tiny dependency-free HTTP/1.1 server (keep-alive + gzip). HttpListener would need an admin URL ACL to listen
/// on the LAN, which is exactly the setup friction this project avoids.
/// </summary>
public sealed class HttpServer : IDisposable
{
    private readonly Func<HttpRequest, Task<HttpResponse>> _handler;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    public int Port { get; private set; }
    public string? LastClient { get; private set; }
    public DateTime LastRequest { get; private set; }

    public HttpServer(Func<HttpRequest, Task<HttpResponse>> handler) => _handler = handler;

    public void Start(int port)
    {
        Stop();
        Port = port;
        _cts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        var ct = _cts.Token;
        _ = Task.Run(async () =>
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var client = await _listener.AcceptTcpClientAsync(ct);
                    _ = Task.Run(() => Serve(client, ct));
                }
                catch (OperationCanceledException) { break; }
                catch (ObjectDisposedException) { break; }
                catch (Exception e) { Log.Warn("Accept failed: " + e.Message); }
            }
        });
        Log.Info($"HTTP server listening on port {port}");
    }

    public void Stop()
    {
        _cts?.Cancel();
        try { _listener?.Stop(); } catch { }
        _listener = null;
    }

    private async Task Serve(TcpClient client, CancellationToken ct)
    {
        using var _ = client;
        client.NoDelay = true;
        var remote = (client.Client.RemoteEndPoint as IPEndPoint)?.Address.MapToIPv4().ToString() ?? "?";
        var stream = client.GetStream();
        var buffer = new byte[16384];
        int filled = 0;
        try
        {
            while (!ct.IsCancellationRequested)
            {
                // Read until end of headers.
                int headerEnd;
                while ((headerEnd = IndexOfHeaderEnd(buffer, filled)) < 0)
                {
                    if (filled == buffer.Length) return;
                    using var idle = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    idle.CancelAfter(TimeSpan.FromSeconds(20));
                    int n = await stream.ReadAsync(buffer.AsMemory(filled), idle.Token);
                    if (n <= 0) return;
                    filled += n;
                }
                var head = Encoding.ASCII.GetString(buffer, 0, headerEnd);
                var lines = head.Split("\r\n");
                var parts = lines[0].Split(' ');
                if (parts.Length < 2) return;
                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var l in lines.Skip(1))
                {
                    var c = l.IndexOf(':');
                    if (c > 0) headers[l[..c].Trim()] = l[(c + 1)..].Trim();
                }
                int consumed = headerEnd + 4;
                int contentLength = headers.TryGetValue("Content-Length", out var cl) && int.TryParse(cl, out var len) ? Math.Clamp(len, 0, 1 << 20) : 0;
                // Discard any body (the API has no request bodies).
                int bodyInBuffer = Math.Min(contentLength, filled - consumed);
                consumed += bodyInBuffer;
                int remaining = contentLength - bodyInBuffer;
                while (remaining > 0)
                {
                    int n = await stream.ReadAsync(new byte[Math.Min(remaining, 8192)], ct);
                    if (n <= 0) return;
                    remaining -= n;
                }
                Buffer.BlockCopy(buffer, consumed, buffer, 0, filled - consumed);
                filled -= consumed;

                var target = parts[1];
                var q = target.IndexOf('?');
                var path = Uri.UnescapeDataString(q >= 0 ? target[..q] : target);
                var query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                if (q >= 0)
                {
                    foreach (var kv in target[(q + 1)..].Split('&', StringSplitOptions.RemoveEmptyEntries))
                    {
                        var e = kv.IndexOf('=');
                        if (e > 0) query[Uri.UnescapeDataString(kv[..e])] = Uri.UnescapeDataString(kv[(e + 1)..]);
                        else query[Uri.UnescapeDataString(kv)] = "";
                    }
                }

                LastClient = remote;
                LastRequest = DateTime.Now;
                HttpResponse resp;
                if (parts[0] == "OPTIONS") resp = new HttpResponse(204, "text/plain", Array.Empty<byte>());
                else
                {
                    try { resp = await _handler(new HttpRequest(parts[0], path, query, headers, remote)); }
                    catch (Exception e)
                    {
                        Log.Warn($"{parts[0]} {path} failed: {e.Message}");
                        resp = HttpResponse.Json($"{{\"error\":{System.Text.Json.JsonSerializer.Serialize(e.Message)}}}", 500);
                    }
                }

                var body = resp.Body;
                bool gzip = body.Length > 1024 && headers.TryGetValue("Accept-Encoding", out var ae) && ae.Contains("gzip");
                if (gzip)
                {
                    using var ms = new MemoryStream();
                    using (var gz = new GZipStream(ms, CompressionLevel.Fastest, true)) gz.Write(body);
                    body = ms.ToArray();
                }
                bool keepAlive = !(headers.TryGetValue("Connection", out var conn) && conn.Equals("close", StringComparison.OrdinalIgnoreCase));
                var sb = new StringBuilder();
                sb.Append($"HTTP/1.1 {resp.Status} {Reason(resp.Status)}\r\n");
                sb.Append($"Content-Type: {resp.ContentType}\r\n");
                sb.Append($"Content-Length: {body.Length}\r\n");
                if (gzip) sb.Append("Content-Encoding: gzip\r\n");
                sb.Append("Cache-Control: no-store\r\n");
                sb.Append("Access-Control-Allow-Origin: *\r\n");
                sb.Append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
                sb.Append(keepAlive ? "Connection: keep-alive\r\n" : "Connection: close\r\n");
                sb.Append("\r\n");
                await stream.WriteAsync(Encoding.ASCII.GetBytes(sb.ToString()), ct);
                if (parts[0] != "HEAD") await stream.WriteAsync(body, ct);
                await stream.FlushAsync(ct);
                if (!keepAlive) return;
            }
        }
        catch (OperationCanceledException) { }
        catch (IOException) { }
        catch (Exception e) { Log.Warn("Connection error: " + e.Message); }
    }

    private static int IndexOfHeaderEnd(byte[] b, int len)
    {
        for (int i = 0; i + 3 < len; i++)
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') return i;
        return -1;
    }

    private static string Reason(int s) => s switch { 200 => "OK", 204 => "No Content", 404 => "Not Found", 409 => "Conflict", 500 => "Internal Server Error", _ => "OK" };

    public void Dispose() => Stop();
}

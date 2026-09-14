using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace BmsCompanion.Bridge.Server;

/// <summary>
/// LAN auto-discovery: the app broadcasts "BMSC_DISCOVER" to UDP port 47475 and the bridge answers
/// with a small JSON describing where its HTTP API is.
/// </summary>
public sealed class Discovery : IDisposable
{
    public const int UdpPort = 47475;
    private const string Probe = "BMSC_DISCOVER";
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;

    public void Start(Func<int> httpPort, string version)
    {
        Stop();
        try
        {
            _udp = new UdpClient(AddressFamily.InterNetwork);
            _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _udp.Client.Bind(new IPEndPoint(IPAddress.Any, UdpPort));
        }
        catch (Exception e)
        {
            Log.Warn($"Discovery disabled (UDP {UdpPort} unavailable): {e.Message}");
            _udp = null;
            return;
        }
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        var udp = _udp;
        _ = Task.Run(async () =>
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var r = await udp.ReceiveAsync(ct);
                    if (!Encoding.ASCII.GetString(r.Buffer).StartsWith(Probe)) continue;
                    var reply = JsonSerializer.Serialize(new
                    {
                        service = "bms-companion",
                        name = Environment.MachineName,
                        port = httpPort(),
                        version,
                        api = Api.Version,
                    });
                    await udp.SendAsync(Encoding.UTF8.GetBytes(reply), r.RemoteEndPoint, ct);
                }
                catch (OperationCanceledException) { break; }
                catch (ObjectDisposedException) { break; }
                catch (SocketException) { /* ICMP resets etc. */ }
            }
        });
    }

    public void Stop()
    {
        _cts?.Cancel();
        try { _udp?.Close(); } catch { }
        _udp = null;
    }

    public void Dispose() => Stop();

    /// <summary>IPv4 addresses of active, non-loopback adapters (shown to the user for manual connection).</summary>
    public static List<string> LocalAddresses()
    {
        var list = new List<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
                var props = ni.GetIPProperties();
                if (props.GatewayAddresses.Count == 0) continue; // skip virtual adapters without a route
                foreach (var ua in props.UnicastAddresses)
                    if (ua.Address.AddressFamily == AddressFamily.InterNetwork) list.Add(ua.Address.ToString());
            }
        }
        catch { }
        return list.Distinct().ToList();
    }
}

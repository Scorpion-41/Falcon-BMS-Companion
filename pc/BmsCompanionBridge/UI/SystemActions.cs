using System.Diagnostics;
using BmsCompanion.Bridge.Server;

namespace BmsCompanion.Bridge.UI;

/// <summary>Small Windows helpers shared by the main window and the setup guide.</summary>
public static class SystemActions
{
    public const string FirewallRuleName = "BMS Companion Bridge";

    /// <summary>Adds two inbound rules limited to the local subnet (HTTP API + discovery). Asks for admin rights.</summary>
    public static bool AddFirewallRules(int port)
    {
        var cmd = $"/c netsh advfirewall firewall delete rule name=\"{FirewallRuleName}\" >nul 2>&1 & " +
                  $"netsh advfirewall firewall add rule name=\"{FirewallRuleName}\" dir=in action=allow protocol=TCP localport={port} remoteip=localsubnet profile=any & " +
                  $"netsh advfirewall firewall add rule name=\"{FirewallRuleName}\" dir=in action=allow protocol=UDP localport={Discovery.UdpPort} remoteip=localsubnet profile=any";
        try
        {
            var p = Process.Start(new ProcessStartInfo("cmd.exe", cmd) { Verb = "runas", UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden });
            p?.WaitForExit(15000);
            bool ok = p?.ExitCode == 0;
            Log.Info(ok ? $"Firewall rules added (TCP {port}, UDP {Discovery.UdpPort}, local subnet)" : "Firewall command finished with code " + p?.ExitCode);
            return ok;
        }
        catch (Exception e)
        {
            Log.Warn("Firewall rule not added: " + e.Message);
            return false;
        }
    }

    /// <summary>True when our named rule exists (a rule created from the Windows prompt is not detected).</summary>
    public static bool FirewallRuleExists()
    {
        try
        {
            var psi = new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{FirewallRuleName}\"")
            {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
            };
            using var p = Process.Start(psi)!;
            var output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(5000);
            return p.ExitCode == 0 && output.Contains(FirewallRuleName);
        }
        catch { return false; }
    }

    /// <summary>EZBoards (xbrief.exe) needs the .NET 8 runtime.</summary>
    public static bool DotNet8RuntimeInstalled()
    {
        foreach (var root in new[] { Environment.GetEnvironmentVariable("DOTNET_ROOT"), Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "dotnet") })
        {
            if (string.IsNullOrEmpty(root)) continue;
            var dir = Path.Combine(root, "shared", "Microsoft.NETCore.App");
            if (Directory.Exists(dir) && Directory.GetDirectories(dir, "8.*").Length > 0) return true;
        }
        return false;
    }

    /// <summary>Opens a URL, folder or file with the default handler.</summary>
    public static void Open(string target)
    {
        try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
        catch (Exception e) { Log.Warn($"Could not open {target}: {e.Message}"); }
    }

    /// <summary>Opens a text file in Notepad (the user edits it; the bridge never writes BMS files).</summary>
    public static void OpenInNotepad(string file)
    {
        try { Process.Start(new ProcessStartInfo("notepad.exe", $"\"{file}\"") { UseShellExecute = true }); }
        catch (Exception e) { Log.Warn($"Could not open {file}: {e.Message}"); }
    }
}

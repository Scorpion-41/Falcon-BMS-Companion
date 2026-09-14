using BmsCompanion.Bridge.UI;

namespace BmsCompanion.Bridge;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--selftest") { SelfTest.Run(args[1]); return; }
        if (args.Length == 2 && args[0] == "--dumpstrings") { SelfTest.DumpStrings(args[1]); return; }
        if (args.Length == 3 && args[0] == "--eztest") { SelfTest.RunEz(args[1], args[2]); return; }
        using var mutex = new Mutex(true, "BMSCompanionBridge.SingleInstance", out bool first);
        if (!first)
        {
            MessageBox.Show("BMS Companion Bridge is already running (check the tray).", "BMS Companion Bridge");
            return;
        }

        var settings = Settings.Load();
        if (args.Contains("--demo")) settings.DemoFromCommandLine = true;
        if (args.Contains("--guide")) settings.FirstRun = true; // open the setup guide at start

        ApplicationConfiguration.Initialize();
        using var svc = new BridgeService(settings);
        Log.Info($"BMS Companion Bridge {BridgeService.Version} starting");
        svc.Start();
        Application.Run(new MainForm(svc));
    }
}

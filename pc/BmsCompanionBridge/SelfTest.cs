using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using BmsCompanion.Bridge.Bms;
using BmsCompanion.Bridge.Demo;
using BmsCompanion.Bridge.EzBoards;

namespace BmsCompanion.Bridge;

/// <summary>Developer check: `BMSCompanionBridge.exe --selftest out.txt` dumps struct sizes and parser output.</summary>
internal static class SelfTest
{
    public static void Run(string outFile)
    {
        var sb = new StringBuilder();
        sb.AppendLine($"sizeof(FlightData)={Unsafe.SizeOf<FlightDataRaw>()} sizeof(FlightData2)={Unsafe.SizeOf<FlightData2Raw>()}");
        var demo = new DemoSource();
        var b = BriefingParser.Parse(demo.BriefingText);
        sb.AppendLine(JsonSerializer.Serialize(b, BridgeService.Json));
        sb.AppendLine(JsonSerializer.Serialize(demo.Live(), BridgeService.Json));
        var np = SharedMemoryReader.ParseNavPoint("NP:56,PT,2910660.5,258538.0,0.0,52.1;PT:\"SA-5\",364567.0,0;");
        sb.AppendLine(JsonSerializer.Serialize(np, BridgeService.Json));
        sb.AppendLine(JsonSerializer.Serialize(SharedMemoryReader.ParseVoice("Ouranos5|PAXX,None,Dragnet5,Larissa,Larissa,Nea Anchialos"), BridgeService.Json));
        var html = Environment.GetEnvironmentVariable("BMSC_TEST_BOARD_HTML");
        if (html != null && File.Exists(html)) sb.AppendLine(JsonSerializer.Serialize(EzBoardsRunner.ParseHtml(File.ReadAllText(html)), BridgeService.Json));
        File.WriteAllText(outFile, sb.ToString());
    }

    /// <summary>`--eztest <EZBoards dir> out.txt`: runs EZBoards exactly like the app button does.</summary>
    public static void RunEz(string dir, string outFile)
    {
        var r = new EzBoardsRunner().GenerateAsync(dir, auto: false).GetAwaiter().GetResult();
        File.WriteAllText(outFile, JsonSerializer.Serialize(r, BridgeService.Json));
    }

    /// <summary>`--dumpstrings out.txt`: raw StringData ids and values from a running BMS (to verify the id table).</summary>
    public static void DumpStrings(string outFile)
    {
        var snap = SharedMemoryReader.Read();
        var sb = new StringBuilder($"available={snap.Available} flying={snap.Flying} version={snap.Version}\n");
        foreach (var kv in snap.Strings.OrderBy(k => (uint)k.Key)) sb.AppendLine($"{(uint)kv.Key,3} {kv.Key,-24} {kv.Value}");
        foreach (var np in snap.NavPointStrings) sb.AppendLine("NP " + np);
        sb.AppendLine($"hsiBits=0x{snap.HsiBits:X8} lightBits=0x{snap.LightBits:X8} pilotsOnline={snap.PilotsOnline} status={string.Join(",", snap.PilotStatus)} ded0={snap.Live.Ded.FirstOrDefault()}");
        File.WriteAllText(outFile, sb.ToString());
    }
}

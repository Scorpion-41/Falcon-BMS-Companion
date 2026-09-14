namespace BmsCompanion.Bridge.Server;

// JSON contract between the bridge and the Android app. Serialized camelCase.
// Keep changes additive; bump ApiVersion only for breaking changes. Documented in docs/PROTOCOL.md.

public static class Api
{
    public const int Version = 1;
}

public sealed class InfoDto
{
    public string App { get; set; } = "BMS Companion Bridge";
    public string Version { get; set; } = "";
    public int Api { get; set; } = Server.Api.Version;
    public string Host { get; set; } = Environment.MachineName;
    public bool Demo { get; set; }
    public BmsStatusDto Bms { get; set; } = new();
    public TacviewStatusDto Tacview { get; set; } = new();
    public BriefingStatusDto Briefing { get; set; } = new();
    public EzStatusDto EzBoards { get; set; } = new();
}

public sealed class BmsStatusDto
{
    public bool Installed { get; set; }
    public string? BaseDir { get; set; }
    public string? RegistryVersion { get; set; }
    public string? Version { get; set; }
    public bool Running { get; set; }
    public bool Flying { get; set; }
    public string? Theater { get; set; }
    public string? Callsign { get; set; }
    public string? Aircraft { get; set; }
}

public sealed class TacviewStatusDto
{
    public bool Enabled { get; set; }
    public bool Connected { get; set; }
    public string State { get; set; } = "off";
    public int Objects { get; set; }
}

public sealed class BriefingStatusDto
{
    public bool Available { get; set; }
    public long Modified { get; set; }
    public string? Generated { get; set; }
    public long DtcModified { get; set; }
}

public sealed class EzStatusDto
{
    public bool Configured { get; set; }
    public string? Path { get; set; }
    public bool AutoOnPrint { get; set; }
    public bool Running { get; set; }
    public EzRunDto? LastRun { get; set; }
}

public sealed class EzRunDto
{
    public long Time { get; set; }
    public bool Ok { get; set; }
    public long DurationMs { get; set; }
    public string Message { get; set; } = "";
    public List<string> Log { get; set; } = new();
    public bool Auto { get; set; }
}

public sealed class LiveDto
{
    public long T { get; set; }
    public bool Flying { get; set; }
    public string? Theater { get; set; }
    public string? Aircraft { get; set; }
    public double X { get; set; }
    public double Y { get; set; }
    public double AltFt { get; set; }
    public double HdgTrue { get; set; }
    public double HdgMag { get; set; }
    public double Kias { get; set; }
    public double Mach { get; set; }
    public double GsKts { get; set; }
    public double VviFpm { get; set; }
    public double GLoad { get; set; }
    public double Aoa { get; set; }
    public double RadarAltFt { get; set; }
    public double FuelInternal { get; set; }
    public double FuelExternal { get; set; }
    public double FuelFlow { get; set; }
    public double Bingo { get; set; }
    public int Chaff { get; set; }
    public int Flares { get; set; }
    public double Gear { get; set; }
    public double SpeedBrake { get; set; }
    public double? BullX { get; set; }
    public double? BullY { get; set; }
    /// <summary>Seconds since midnight, sim time (zulu).</summary>
    public int TimeSec { get; set; }
    public double Lat { get; set; }
    public double Lon { get; set; }
    public string? Tacan { get; set; }
    public double? BeaconBrg { get; set; }
    public double? BeaconNm { get; set; }
    public double DesiredCourse { get; set; }
    public int NavMode { get; set; }
    public int IlsFreq { get; set; }
    public int UhfPreset { get; set; }
    public int UhfFreq { get; set; }
    public List<string> Ded { get; set; } = new();
    public List<RwrDto> Rwr { get; set; } = new();
    public List<NavPointDto> NavPoints { get; set; } = new();
    public VoiceDto? Voice { get; set; }
    public List<PilotDto> Pilots { get; set; } = new();
}

public sealed class RwrDto
{
    public int Sym { get; set; }
    /// <summary>True bearing to the emitter, degrees.</summary>
    public double Brg { get; set; }
    /// <summary>0..1, BMS draws the symbol at radius clamp(lethality, 0.25, 0.8).</summary>
    public double Lethality { get; set; }
    public bool Launch { get; set; }
    public bool Lock { get; set; }
    public bool Selected { get; set; }
    public bool New { get; set; }
}

public sealed class NavPointDto
{
    public int I { get; set; }
    public string Type { get; set; } = "WP";
    public double X { get; set; }
    public double Y { get; set; }
    public double AltFt { get; set; }
    public string? Name { get; set; }
    public double? RangeNm { get; set; }
}

public sealed class VoiceDto
{
    public string? Flight { get; set; }
    public string? Seats { get; set; }
    public string? Tanker { get; set; }
    public string? Awacs { get; set; }
    public string? Departure { get; set; }
    public string? Arrival { get; set; }
    public string? Alternate { get; set; }
}

public sealed class PilotDto
{
    public string Callsign { get; set; } = "";
    public int Status { get; set; }
}

public sealed class ContactDto
{
    public string Id { get; set; } = "";
    /// <summary>air, heli, missile, ship, bullseye, sam</summary>
    public string Kind { get; set; } = "air";
    public double X { get; set; }
    public double Y { get; set; }
    public double AltFt { get; set; }
    public double Hdg { get; set; }
    public double GsKts { get; set; }
    public string? Name { get; set; }
    public string? Pilot { get; set; }
    public string? Group { get; set; }
    public string? Coalition { get; set; }
    public string? Color { get; set; }
    public bool Own { get; set; }
    public bool Friendly { get; set; }
}

public sealed class ContactsDto
{
    public long T { get; set; }
    public bool Connected { get; set; }
    public string State { get; set; } = "off";
    public List<ContactDto> Contacts { get; set; } = new();
}

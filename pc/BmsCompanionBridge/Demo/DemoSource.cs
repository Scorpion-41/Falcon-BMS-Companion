using System.Reflection;
using BmsCompanion.Bridge.Bms;
using BmsCompanion.Bridge.Server;

namespace BmsCompanion.Bridge.Demo;

/// <summary>
/// Synthetic mission (Hellas, SEAD from Larissa towards Tirana) so the app can be tried and developed without BMS running.
/// Everything is generated; no data is read from the BMS install.
/// </summary>
public sealed class DemoSource
{
    private const double FtPerNm = 6076.12;
    private readonly DateTime _start = DateTime.UtcNow;
    private const double TimeScale = 6; // demo time runs 6x faster than real time

    private static readonly (double x, double y, string desc)[] Route =
    {
        (2293126, 962235, "Takeoff"), (2492326, 741035, "Holding Pt"), (2528258, 695069, "Push"), (2705234, 491385, "Pre IP"),
        (2741700, 452298, "IP"), (2901884, 261470, "Grnd Attack"), (2719964, 456458, "Turn Pt"), (2527725, 693932, "Split"),
        (2414037, 829372, "--"), (2293126, 962235, "Land"), (2129284, 1054691, "Alternate"),
    };

    private static readonly (double x, double y) Bullseye = (2480000, 700000);
    private static readonly (double x, double y) SamSite = (2910660, 258538);
    private static readonly (double x, double y) AwacsOrbit = (2230000, 800000);

    public string BriefingText { get; } = LoadResource("demo_briefing.txt");
    public long BriefingModified { get; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private static string LoadResource(string name)
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name);
        if (s == null) return "";
        using var r = new StreamReader(s);
        return r.ReadToEnd();
    }

    private double SimSeconds => (DateTime.UtcNow - _start).TotalSeconds * TimeScale;

    /// <summary>Position along the route (loops), returns point, heading and the index of the next steerpoint.</summary>
    private static (double x, double y, double hdg, int next) Along(double distFt, int lastLeg = 9)
    {
        double total = 0;
        for (int i = 0; i < lastLeg; i++) total += Dist(Route[i], Route[i + 1]);
        distFt %= total;
        for (int i = 0; i < lastLeg; i++)
        {
            var d = Dist(Route[i], Route[i + 1]);
            if (distFt <= d)
            {
                var f = distFt / d;
                var x = Route[i].x + (Route[i + 1].x - Route[i].x) * f;
                var y = Route[i].y + (Route[i + 1].y - Route[i].y) * f;
                var hdg = (Math.Atan2(Route[i + 1].y - Route[i].y, Route[i + 1].x - Route[i].x) * 180 / Math.PI + 360) % 360;
                return (x, y, hdg, i + 2);
            }
            distFt -= d;
        }
        return (Route[0].x, Route[0].y, 0, 2);
    }

    private static double Dist((double x, double y, string) a, (double x, double y, string) b) => Math.Sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y));

    public LiveDto Live()
    {
        double t = SimSeconds;
        const double gsKts = 480;
        var (x, y, hdg, next) = Along(t * gsKts * FtPerNm / 3600);
        double fuelBurn = (t % 2300) * 2.2;
        var live = new LiveDto
        {
            T = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Flying = true,
            Theater = "Hellas",
            Aircraft = "F-16C Block 52+",
            X = x, Y = y, AltFt = 22000 + 400 * Math.Sin(t / 50), HdgTrue = hdg, HdgMag = (hdg - 4 + 360) % 360,
            Kias = 355, Mach = 0.86, GsKts = gsKts, VviFpm = 80 * Math.Cos(t / 50), GLoad = 1.0, Aoa = 3.1, RadarAltFt = 0,
            FuelInternal = Math.Max(1200, 7100 - fuelBurn), FuelExternal = Math.Max(0, 1800 - fuelBurn), FuelFlow = 4800, Bingo = 2400,
            Chaff = 60, Flares = 30, Gear = 0, SpeedBrake = 0,
            BullX = Bullseye.x, BullY = Bullseye.y,
            TimeSec = (int)(3 * 3600 + 43 * 60 + t) % 86400,
            Tacan = "27X", BeaconBrg = 120, BeaconNm = 40, DesiredCourse = 132, NavMode = 2, UhfPreset = 15, UhfFreq = 345950,
            Ded = new List<string>
            {
                $"      STPT  {next,2}  AUTO    ",
                "  LAT  N 40°12.345'     ",
                "  LNG  E 20°51.004'     ",
                "ELEV   22000FT          ",
                $" TOS  {TimeString((int)(3 * 3600 + 58 * 60 + t) % 86400)}          ",
            },
            Voice = new VoiceDto { Flight = "Ouranos5", Seats = "PAXX", Awacs = "Dragnet5", Departure = "Larissa", Arrival = "Larissa", Alternate = "Nea Anchialos" },
        };
        for (int i = 0; i < Route.Length; i++)
            live.NavPoints.Add(new NavPointDto { I = i + 1, Type = "WP", X = Route[i].x, Y = Route[i].y, AltFt = i is 0 or 9 or 10 ? 0 : 22000 });
        live.NavPoints.Add(new NavPointDto { I = 56, Type = "PT", X = SamSite.x, Y = SamSite.y, Name = "SA-5", RangeNm = 60 });
        live.NavPoints.Add(new NavPointDto { I = 57, Type = "PT", X = 2760000, Y = 330000, Name = "SA-6", RangeNm = 14 });
        live.NavPoints.Add(new NavPointDto { I = 26, Type = "MK", X = 2600000, Y = 600000, AltFt = 1200 });

        // RWR: SA-5 site, a MiG-29 CAP and a search radar. Bearings are true, like BMS (the app subtracts heading).
        double Rel(double tx, double ty) => ((Math.Atan2(ty - y, tx - x) * 180 / Math.PI) + 360) % 360;
        live.Rwr.Add(new RwrDto { Sym = 10, Brg = Rel(SamSite.x, SamSite.y), Lethality = 0.7, Lock = (t % 90) > 60 });
        live.Rwr.Add(new RwrDto { Sym = 2, Brg = Rel(2850000, 420000), Lethality = 0.5, New = (t % 40) < 4 });
        live.Rwr.Add(new RwrDto { Sym = 17, Brg = Rel(2980000, 300000), Lethality = 0.2 });
        return live;
    }

    public ContactsDto Contacts()
    {
        double t = SimSeconds;
        var own = Live();
        var dto = new ContactsDto { T = own.T, Connected = true, State = "demo" };
        void Add(string id, string kind, double x, double y, double alt, double hdg, double gs, string name, string? group, string coal, bool friendly, bool isOwn = false) =>
            dto.Contacts.Add(new ContactDto
            {
                Id = id, Kind = kind, X = x, Y = y, AltFt = alt, Hdg = Math.Round(hdg, 1), GsKts = gs, Name = name, Group = group,
                Coalition = coal, Color = friendly ? "Blue" : "Red", Friendly = friendly, Own = isOwn,
            });

        Add("1", "air", own.X, own.Y, own.AltFt, own.HdgTrue, own.GsKts, "F-16CM-52", "Ouranos5", "Greece", true, true);
        var wing = Along(Math.Max(0, t * 480 * FtPerNm / 3600 - 1.5 * FtPerNm));
        Add("2", "air", wing.x + 2500, wing.y - 2500, 21500, wing.hdg, 480, "F-16CM-52", "Ouranos5", "Greece", true);
        var rider = Along(Math.Max(0, t * 470 * FtPerNm / 3600 - 9 * FtPerNm));
        Add("3", "air", rider.x - 6000, rider.y + 4000, 20000, rider.hdg, 470, "F-4E", "Rider5", "Greece", true);
        Add("4", "air", rider.x - 8500, rider.y + 6500, 20500, rider.hdg, 470, "F-4E", "Rider5", "Greece", true);
        double a = t / 400;
        Add("5", "air", AwacsOrbit.x + Math.Cos(a) * 15 * FtPerNm, AwacsOrbit.y + Math.Sin(a) * 25 * FtPerNm, 30000, (a * 180 / Math.PI + 90) % 360, 320, "EMB-145H", "Dragnet5", "Greece", true);
        double m = t / 260;
        Add("10", "air", 2850000 + Math.Cos(m) * 12 * FtPerNm, 420000 + Math.Sin(m) * 20 * FtPerNm, 26000, (m * 180 / Math.PI + 90) % 360, 450, "MiG-29A", "Falcon2", "Albania", false);
        Add("11", "air", 2850000 + Math.Cos(m) * 12 * FtPerNm - 8000, 420000 + Math.Sin(m) * 20 * FtPerNm + 6000, 25000, (m * 180 / Math.PI + 90) % 360, 450, "MiG-29A", "Falcon2", "Albania", false);
        Add("12", "heli", 2560000, 640000 + (t % 600) * 200, 800, 90, 110, "UH-60", "Pedro1", "Greece", true);
        Add("20", "ship", 2400000, 330000, 0, 150, 12, "Elli class frigate", null, "Greece", true);
        // hostile ejected crew close to ownship: the Picture list must still show the MiGs first
        Add("40", "crew", own.X + 4 * FtPerNm, own.Y - 3 * FtPerNm, 0, 0, 0, "Ejected Crew", null, "Albania", false);
        if ((t % 120) < 25)
        {
            double f = (t % 120) / 25;
            Add("30", "missile", SamSite.x + (own.X - SamSite.x) * f * 0.6, SamSite.y + (own.Y - SamSite.y) * f * 0.6, 5000 + 30000 * f, 0, 1800, "SA-5 Gammon", null, "Albania", false);
        }
        Add("90", "bullseye", Bullseye.x, Bullseye.y, 0, 0, 0, "Bullseye", null, "Greece", true);
        return dto;
    }

    public Dtc Dtc()
    {
        var d = new Dtc { Modified = BriefingModified };
        for (int i = 0; i < Route.Length; i++)
            d.Steerpoints.Add(new DtcPoint { N = i + 1, X = Route[i].x, Y = Route[i].y, AltFt = i is 0 or 9 or 10 ? 0 : 22000, Action = i == 5 ? -1 : 1, Name = i == 5 ? "SA-5 launchers, 4 nm S of Tirana" : null });
        d.Ppts.Add(new DtcPpt { N = 56, X = SamSite.x, Y = SamSite.y, RangeNm = 60, Name = "SA-5" });
        d.Ppts.Add(new DtcPpt { N = 57, X = 2760000, Y = 330000, RangeNm = 14, Name = "SA-6" });
        string[] uhf = { "382.500|Base ops", "264.500|DEP Ground", "362.400|DEP Tower", "265.150|DEP Approach", "342.275|AWACS Check-in", "399.125|Tactical", "265.150|ARR Approach", "362.400|ARR Tower", "264.500|ARR Ground", "342.600|ALT Approach", "364.400|ALT Tower", "340.300|ALT Ground", "348.350|", "339.750|Advisory", "345.950|Intra Flight 1", "251.000|Intra Flight 2" };
        for (int i = 0; i < uhf.Length; i++)
        {
            var p = uhf[i].Split('|');
            d.Uhf.Add(new Preset { Ch = i + 1, Freq = p[0], Comment = p[1].Length > 0 ? p[1] : null });
        }
        d.Vhf.Add(new Preset { Ch = 2, Freq = "123.250", Comment = "DEP ATIS" });
        d.Vhf.Add(new Preset { Ch = 3, Freq = "120.550", Comment = "DEP Tower" });
        d.Vhf.Add(new Preset { Ch = 14, Freq = "119.500", Comment = "UNICOM" });
        d.Vhf.Add(new Preset { Ch = 15, Freq = "52.450", Comment = "Flight-1" });
        d.Iff["Mode1 Code"] = "60"; d.Iff["Mode2 Code"] = "1404"; d.Iff["Mode3A Code"] = "7504"; d.Iff["Mode4 Key"] = "1";
        return d;
    }

    private static string TimeString(int s) => $"{s / 3600:00}:{s / 60 % 60:00}:{s % 60:00}";
}

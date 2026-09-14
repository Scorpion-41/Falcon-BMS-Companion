using System.Runtime.InteropServices;

namespace BmsCompanion.Bridge.Bms;

// Mirrors of the structs in <BMS>\Tools\SharedMem\FlightData.h (BMS 4.38: FlightData v118, FlightData2 v23,
// StringData v5). Fields must stay in header order; C# sequential layout + fixed buffers reproduce the
// MSVC default alignment because the structs only contain 1/2/4-byte types.
//
// UPDATING FOR A NEW BMS VERSION: diff the new FlightData.h against these structs. BMS only ever appends
// fields at the end, so usually you just add the new trailing fields here. See docs/UPDATING.md.

[StructLayout(LayoutKind.Sequential)]
public unsafe struct FlightDataRaw
{
    public float x, y, z, xDot, yDot, zDot;
    public float alpha, beta, gamma, pitch, roll, yaw;
    public float mach, kias, vt, gs, windOffset, nozzlePos;
    public float internalFuel, externalFuel, fuelFlow, rpm, ftit, gearPos, speedBrake, epuFuel, oilPressure;
    public uint lightBits;
    public float headPitch, headRoll, headYaw;
    public uint lightBits2, lightBits3;
    public float ChaffCount, FlareCount;
    public float NoseGearPos, LeftGearPos, RightGearPos;
    public float AdiIlsHorPos, AdiIlsVerPos;
    public int courseState, headingState, totalStates;
    public float courseDeviation, desiredCourse, distanceToBeacon, bearingToBeacon, currentHeading, desiredHeading;
    public float deviationLimit, halfDeviationLimit, localizerCourse, airbaseX, airbaseY, totalValues;
    public float TrimPitch, TrimRoll, TrimYaw;
    public uint hsiBits;
    public fixed byte DEDLines[5 * 26];
    public fixed byte Invert[5 * 26];
    public fixed byte PFLLines[5 * 26];
    public fixed byte PFLInvert[5 * 26];
    public int UFCTChan, AUXTChan;
    public int RwrObjectCount;
    public fixed int RWRsymbol[40];
    public fixed float bearing[40];
    public fixed uint missileActivity[40];
    public fixed uint missileLaunch[40];
    public fixed uint selected[40];
    public fixed float lethality[40];
    public fixed uint newDetection[40];
    public float fwd, aft, total;
    public int VersionNum;
    public float headX, headY, headZ;
    public int MainPower;
}

[StructLayout(LayoutKind.Sequential)]
public unsafe struct FlightData2Raw
{
    public float nozzlePos2, rpm2, ftit2, oilPressure2;
    public byte navMode;
    public float AAUZ;
    public fixed byte tacanInfo[2];
    public int AltCalReading;
    public uint altBits, powerBits, blinkBits;
    public int cmdsMode;
    public int uhf_panel_preset, uhf_panel_frequency;
    public float cabinAlt, hydPressureA, hydPressureB;
    public int currentTime;
    public short vehicleACD;
    public int VersionNum;
    public float fuelFlow2;
    public fixed byte RwrInfo[512];
    public float lefPos, tefPos, vtolPos;
    public byte pilotsOnline;
    public fixed byte pilotsCallsign[32 * 12];
    public fixed byte pilotsStatus[32];
    public float bumpIntensity;
    public float latitude, longitude;
    public fixed ushort RTT_size[2];
    public fixed ushort RTT_area[7 * 4];
    public byte iffBackupMode1Digit1, iffBackupMode1Digit2, iffBackupMode3ADigit1, iffBackupMode3ADigit2;
    public byte instrLight;
    public uint bettyBits, miscBits;
    public float RALT, bingoFuel, caraAlow, bullseyeX, bullseyeY;
    public int BMSVersionMajor, BMSVersionMinor, BMSVersionMicro, BMSBuildNumber;
    public uint StringAreaSize, StringAreaTime, DrawingAreaSize;
    public float turnRate;
    public byte floodConsole;
    public float magDeviationSystem, magDeviationReal;
    public fixed uint ecmBits[5];
    public byte ecmOper;
    public fixed byte RWRjammingStatus[40];
    public int radio2_preset, radio2_frequency;
    public byte iffTransponderActiveCode1;
    public short iffTransponderActiveCode2, iffTransponderActiveCode3A, iffTransponderActiveCodeC, iffTransponderActiveCode4;
    public int tacan_ils_frequency;
    public int desired_RTT_FPS;
    public float sideSlipdeg, gsMax, gsMin;
}

/// <summary>StringData identifiers (enum StringIdentifier in FlightData.h). New IDs are only appended.</summary>
public enum StringId : uint
{
    // Verified against a running BMS 4.38.1 with --dumpstrings.
    BmsExe = 0, KeyFile = 1, BmsBasedir = 2, BmsBinDirectory = 3, BmsDataDirectory = 4, BmsUIArtDirectory = 5,
    BmsUserDirectory = 6, BmsAcmiDirectory = 7, BmsBriefingsDirectory = 8, BmsConfigDirectory = 9, BmsLogsDirectory = 10,
    BmsPatchDirectory = 11, BmsPictureDirectory = 12,
    ThrName = 13, ThrCampaigndir = 14, ThrTerraindir = 15, ThrArtdir = 16, ThrMoviedir = 17, ThrUisounddir = 18,
    ThrObjectdir = 19, Thr3ddatadir = 20, ThrMisctexdir = 21, ThrSounddir = 22, ThrTacrefdir = 23, ThrSplashdir = 24,
    ThrCockpitdir = 25, ThrSimdatadir = 26, ThrSubtitlesdir = 27, ThrTacrefpicsdir = 28,
    AcName = 29, AcNCTR = 30, ButtonsFile = 31, CockpitFile = 32, NavPoint = 33, ThrTerrdatadir = 34, VoiceHelpers = 35,
}

public static class SharedMemNames
{
    public const string FlightData = "FalconSharedMemoryArea";
    public const string FlightData2 = "FalconSharedMemoryArea2";
    public const string Strings = "FalconSharedMemoryAreaString";

    public const uint HsiFlying = 0x80000000;
}

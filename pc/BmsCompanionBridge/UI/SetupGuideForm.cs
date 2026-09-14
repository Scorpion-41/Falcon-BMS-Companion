using System.Drawing;
using BmsCompanion.Bridge.Bms;
using BmsCompanion.Bridge.EzBoards;
using BmsCompanion.Bridge.Server;

namespace BmsCompanion.Bridge.UI;

/// <summary>
/// Step-by-step setup guide with live checks. Opens automatically on first launch and from the main window / tray.
/// It only reads BMS files; changes to BMS config are made by the user (buttons open the right file or folder).
/// </summary>
public sealed class SetupGuideForm : Form
{
    private static readonly Color Bg = Color.FromArgb(10, 15, 20);
    private static readonly Color Surface = Color.FromArgb(20, 28, 36);
    private static readonly Color TextC = Color.FromArgb(223, 232, 239);
    private static readonly Color Dim = Color.FromArgb(150, 170, 180);
    private static readonly Color Amber = Color.FromArgb(255, 181, 71);
    private static readonly Color Green = Color.FromArgb(91, 227, 138);
    private static readonly Color Red = Color.FromArgb(255, 107, 107);
    private static readonly Color Code = Color.FromArgb(124, 255, 154);

    private enum State { Ok, Todo, Problem, Info }

    private sealed class Step
    {
        public Label Icon = null!;
        public Label Status = null!;
        public readonly List<Label> Wrapping = new();
    }

    private readonly BridgeService _svc;
    private readonly FlowLayoutPanel _list;
    private readonly Label _summary;
    private readonly Step _sBms, _sNetwork, _sApp, _sBriefing, _sDtc, _sLive, _sTacview, _sEz;
    private bool _firewallRule;
    private DateTime _firewallChecked = DateTime.MinValue;

    public SetupGuideForm(BridgeService svc, Icon? icon)
    {
        _svc = svc;
        Text = "BMS Companion Bridge: Setup guide";
        if (icon != null) Icon = icon;
        BackColor = Bg; ForeColor = TextC;
        Font = new Font("Segoe UI", 10f);
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(LogicalToDeviceUnits(620), LogicalToDeviceUnits(500));
        Size = new Size(LogicalToDeviceUnits(820), LogicalToDeviceUnits(900));

        var header = new Panel { Dock = DockStyle.Top, Height = LogicalToDeviceUnits(92), BackColor = Bg, Padding = new Padding(20, 14, 20, 6) };
        var title = new Label { Text = "SETUP GUIDE", ForeColor = Amber, Font = new Font("Segoe UI Semibold", 16f), AutoSize = true, Location = new Point(18, 12) };
        _summary = new Label { ForeColor = Dim, AutoSize = true, Location = new Point(20, 50) };
        header.Controls.Add(title);
        header.Controls.Add(_summary);

        var footer = new FlowLayoutPanel { Dock = DockStyle.Bottom, AutoSize = true, BackColor = Surface, Padding = new Padding(16, 10, 16, 10), FlowDirection = FlowDirection.RightToLeft };
        var close = MakeButton("Close", Amber, Bg);
        close.Click += (_, _) => Close();
        var refresh = MakeButton("Re-check", Surface, TextC);
        refresh.Click += (_, _) => { _firewallChecked = DateTime.MinValue; RefreshChecks(); };
        footer.Controls.Add(close);
        footer.Controls.Add(refresh);
        footer.Controls.Add(new Label { Text = "Checks refresh every few seconds while this window is open.", ForeColor = Dim, AutoSize = true, Margin = new Padding(0, 8, 16, 0) });

        _list = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, BackColor = Bg, Padding = new Padding(16, 4, 16, 16) };
        Controls.Add(_list);
        Controls.Add(footer);
        Controls.Add(header);

        var cfgDir = () => _svc.Install.ConfigDir;
        var userCfg = () => cfgDir() is { } d ? Path.Combine(d, "Falcon BMS User.cfg") : null;

        _sBms = AddStep(1, "Falcon BMS is found",
            "The bridge finds BMS through the registry (the same way EZBoards does). If you have several installs or a portable copy, choose the BMS folder.",
            null,
            ("Choose BMS folder…", () => PickFolder("Select your Falcon BMS folder", p => { _svc.Settings.BmsDirOverride = p; })));

        _sNetwork = AddStep(2, "Allow the bridge on your network",
            "The app connects to this PC over your local network: TCP 47474 for data and UDP 47475 for auto-discovery. Allow it when Windows asks (private networks), or add the rules here (asks for admin rights once, limited to your local subnet). Keep this bridge running while you fly; closing the window only hides it to the tray (right-click the tray icon to exit).",
            null,
            ("Allow through Windows Firewall", () => { SystemActions.AddFirewallRules(_svc.Settings.Port); _firewallChecked = DateTime.MinValue; RefreshChecks(); }));

        _sApp = AddStep(3, "Connect the BMS Companion app",
            "Put the phone or tablet on the same Wi-Fi/LAN as this PC (guest Wi-Fi networks isolate devices and will not work). In the app open Mission → Setup → Find bridge. If nothing is found (some routers block broadcasts), type the address shown above and port 47474, then Connect. The app header turns green (LINKED). It only polls while the Mission screen is open, and its ☀ button keeps the screen awake.",
            null);

        _sBriefing = AddStep(4, "Export the briefing from BMS",
            "In the BMS Launcher open CONFIG → General → Briefing / Debriefing: tick \"Briefing Output to File\" (enables the PRINT button) and untick \"HTML Briefings\" (the bridge and EZBoards read the text file). \"Append New Briefings\" is optional; the newest briefing is always used. Then, for every mission, open the Briefing screen and press PRINT (top right): BMS writes User\\Briefings\\briefing.txt and the app updates within a second. If you redirect briefings with g_sBriefingsDirectory, the bridge follows it while BMS is running.",
            "set g_nPrintToFile 1\r\nset g_bBriefHTML 0",
            ("Open Briefings folder", () => { if (_svc.BriefingPath is { } bp) SystemActions.Open(Path.GetDirectoryName(bp)!); }));

        _sDtc = AddStep(5, "Save the DTC",
            "In the DTC page set your target steerpoints, PPTs and comm plan, then press SAVE. That writes User\\Config\\<callsign>.ini, which gives the app steerpoint positions, targets, threat rings and radio presets before you enter 3D (in 3D they also come live from shared memory).",
            null);

        _sLive = AddStep(6, "Live flight data",
            "Nothing to configure: BMS always publishes shared memory. Position, fuel, RWR, DED, bullseye and steerpoints stream to the app as soon as you are in the 3D world.",
            null);

        _sTacview = AddStep(7, "AWACS picture (other aircraft)",
            "Other aircraft come from BMS's built-in Tacview real-time telemetry (the Tacview program is not needed). Add the lines below to \"Falcon BMS User.cfg\" and restart BMS. The stream only runs while ACMI recording is on: start it in 3D (default key F) or enable recording in the Launcher. Multiplayer: the host must allow it (g_bMPTacviewRtAllowedByServer 1). If you set g_sTacviewPassword, enter the same password in the bridge settings. In the app, the Hostiles chip on the map switches between the full picture and friendlies only.",
            "set g_bTacviewRealTime 1\r\nset g_bTacviewAcmi 1",
            ("Copy lines", () => { try { Clipboard.SetText("set g_bTacviewRealTime 1\r\nset g_bTacviewAcmi 1\r\n"); } catch { } }),
            ("Open Falcon BMS User.cfg", () => { if (userCfg() is { } f) { if (File.Exists(f)) SystemActions.OpenInNotepad(f); else SystemActions.Open(Path.GetDirectoryName(f)!); } }));

        _sEz = AddStep(8, "EZBoards kneeboards (optional)",
            "EZBoards (by Logic) ships with BMS in Tools\\EZBoards and needs the .NET 8 runtime. The bridge finds it automatically; if you keep it elsewhere, choose the folder. Workflow: plan → save DTC → PRINT → tap Boards → Generate kneeboards in the app. The console runs hidden on this PC and the app shows success or the error log. Tick \"Run EZBoards automatically when the briefing is printed\" in the bridge settings to make PRINT alone enough. To see kneeboards in the cockpit, enable the 3D pilot model (Setup → Graphics → Pilot Model). Which pages go on which kneeboard is set in EZBoards' own CONFIG_USER.BAT.",
            null,
            ("Choose EZBoards folder…", () => PickFolder("Select the EZBoards folder (contains EZBOARDS.BAT)", p =>
            {
                if (EzBoardsRunner.IsValidDir(p)) _svc.Settings.EzBoardsDir = p;
                else MessageBox.Show(this, "That folder does not contain EZBOARDS.BAT.", "EZBoards folder", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            })),
            ("Get .NET 8 runtime", () => SystemActions.Open("https://dotnet.microsoft.com/en-us/download/dotnet/8.0")));

        var trouble = AddStep(9, "Troubleshooting",
            "• \"Timed out\" / NO LINK in the app: is this bridge running, is it allowed through the firewall (step 2), and is the device on the same network (not guest Wi-Fi)? Try typing the IP manually.\r\n" +
            "• \"Connection refused\": nothing listens on the port. The bridge is closed or uses another port (see App port in the settings).\r\n" +
            "• Briefing tab empty: PRINT not pressed, HTML Briefings still on, or the briefing folder is redirected (start BMS so the bridge can read the real path).\r\n" +
            "• Steerpoints missing from the map before 3D: save the DTC. In 3D they come from shared memory.\r\n" +
            "• No AWACS feed: g_bTacviewRealTime 1, ACMI recording running, correct port/password, multiplayer host allows it.\r\n" +
            "• EZBoards fails: open the log on the app's Boards tab. Usual causes: .NET 8 runtime missing, briefing not printed, wrong EZBoards folder.\r\n" +
            "• Wrong map in the app: it uses the theater BMS reports. Add-on theaters that reuse a base map show that map.\r\n" +
            "• Demo data showing: untick Demo mode in the bridge settings and press Save & apply.",
            null);
        trouble.Icon.Text = "ℹ";
        trouble.Icon.ForeColor = Dim;
        trouble.Status.Visible = false;

        AddNote("Just want to look around? Tick \"Demo mode\" in the bridge settings to feed the app a synthetic mission without BMS. Remember to untick it before flying.");

        _list.SizeChanged += (_, _) => Rewrap();
        Shown += (_, _) => { Rewrap(); RefreshChecks(); };
        var timer = new System.Windows.Forms.Timer { Interval = 2500 };
        timer.Tick += (_, _) => RefreshChecks();
        timer.Start();
        FormClosed += (_, _) => timer.Dispose();
    }

    // ------------------------------------------------------------------ checks

    private void RefreshChecks()
    {
        if (IsDisposed) return;
        var info = _svc.Info();
        var install = _svc.Install;
        int done = 0, total = 0;
        void Set(Step s, State st, string text, bool counts = true)
        {
            s.Icon.Text = st switch { State.Ok => "✔", State.Problem => "✖", State.Todo => "●", _ => "ℹ" };
            s.Icon.ForeColor = st switch { State.Ok => Green, State.Problem => Red, State.Todo => Amber, _ => Dim };
            s.Status.Text = text;
            s.Status.ForeColor = st switch { State.Ok => Green, State.Problem => Red, State.Todo => Amber, _ => Dim };
            if (counts) { total++; if (st == State.Ok) done++; }
        }

        // 1 BMS
        if (install.BaseDir != null) Set(_sBms, State.Ok, $"Found: {install.BaseDir}" + (install.RegistryVersion != null ? $" ({install.RegistryVersion})" : ""));
        else Set(_sBms, State.Problem, "Falcon BMS was not found. Choose the BMS folder.");

        // 2 network
        if ((DateTime.Now - _firewallChecked).TotalSeconds > 15)
        {
            _firewallChecked = DateTime.Now;
            _ = Task.Run(() => { _firewallRule = SystemActions.FirewallRuleExists(); });
        }
        bool appSeen = _svc.Http.LastRequest > DateTime.Now.AddMinutes(-1) && _svc.Http.LastClient is not ("127.0.0.1" or "::1");
        if (_firewallRule) Set(_sNetwork, State.Ok, "Firewall rules are in place.");
        else if (appSeen) Set(_sNetwork, State.Ok, "The app reached the bridge, so the network is fine.");
        else Set(_sNetwork, State.Todo, "No firewall rule found yet. Fine if you allowed the Windows prompt; otherwise press the button.");

        // 3 app
        var ips = Discovery.LocalAddresses();
        var addr = ips.Count > 0 ? string.Join("   or   ", ips.Select(ip => $"{ip}  port {_svc.Settings.Port}")) : $"port {_svc.Settings.Port}";
        if (appSeen) Set(_sApp, State.Ok, $"Connected: {_svc.Http.LastClient} at {_svc.Http.LastRequest:HH:mm:ss}. Address: {addr}");
        else Set(_sApp, State.Todo, $"Waiting for the app… Address to enter: {addr}");

        // 4 briefing
        string? PrintToFile = CfgValue("g_nPrintToFile"), briefHtml = CfgValue("g_bBriefHTML");
        bool printOn = PrintToFile == null || (int.TryParse(PrintToFile, out var ptf) && ptf != 0);
        bool htmlOn = briefHtml == "1";
        if (info.Demo) Set(_sBriefing, State.Info, "Demo mode: using the demo briefing.", counts: false);
        else if (htmlOn) Set(_sBriefing, State.Problem, "HTML Briefings is ON: untick it in the Launcher (CONFIG → General).");
        else if (PrintToFile != null && !printOn) Set(_sBriefing, State.Problem, "Briefing Output to File is OFF: tick it in the Launcher (CONFIG → General).");
        else if (info.Briefing.Available) Set(_sBriefing, State.Ok, $"Briefing found (printed {info.Briefing.Generated}). Press PRINT again after changing the mission.");
        else Set(_sBriefing, State.Todo, "Settings look right. No briefing printed yet: press PRINT on the BMS Briefing screen.");

        // 5 DTC
        if (info.Demo) Set(_sDtc, State.Info, "Demo mode: using demo steerpoints.", counts: false);
        else if (info.Briefing.DtcModified > 0) Set(_sDtc, State.Ok, $"DTC file found (saved {DateTimeOffset.FromUnixTimeMilliseconds(info.Briefing.DtcModified).LocalDateTime:g}).");
        else if (install.Callsign == null) Set(_sDtc, State.Todo, "Pilot callsign not known yet (log into BMS once).");
        else Set(_sDtc, State.Todo, $"No DTC saved yet for \"{install.Callsign}\": press SAVE in the DTC page.");

        // 6 live
        if (info.Demo) Set(_sLive, State.Info, "Demo mode is on.", counts: false);
        else if (info.Bms.Flying) Set(_sLive, State.Ok, $"Receiving live data ({info.Bms.Aircraft ?? "aircraft"}, {info.Bms.Theater}).");
        else if (info.Bms.Running) Set(_sLive, State.Info, "BMS is running (in the UI). Live data starts when you enter 3D.", counts: false);
        else Set(_sLive, State.Info, "Start Falcon BMS. Nothing else to do here.", counts: false);

        // 7 tacview
        var rt = CfgValue("g_bTacviewRealTime");
        var acmi = CfgValue("g_bTacviewAcmi");
        if (info.Demo) Set(_sTacview, State.Info, "Demo mode: showing demo traffic.", counts: false);
        else if (!_svc.Settings.TacviewEnabled) Set(_sTacview, State.Todo, "Reading the Tacview stream is switched off in the bridge settings.");
        else if (info.Tacview.Connected) Set(_sTacview, State.Ok, $"Connected to the AWACS feed ({info.Tacview.Objects} objects).");
        else if (rt != "1") Set(_sTacview, State.Todo, "g_bTacviewRealTime is not set to 1 yet: add the lines below, then restart BMS.");
        else if (acmi == "0") Set(_sTacview, State.Problem, "g_bTacviewAcmi is 0: set it to 1 (the real-time stream needs ACMI recording).");
        else if (info.Bms.Flying) Set(_sTacview, State.Todo, "Config is right. Start ACMI recording in 3D (default key F) to begin the stream.");
        else Set(_sTacview, State.Ok, "Config is right. The feed connects in 3D while ACMI recording is on.");

        // 8 EZBoards
        bool ezDir = EzBoardsRunner.IsValidDir(_svc.Settings.EzBoardsDir);
        bool net8 = SystemActions.DotNet8RuntimeInstalled();
        var last = _svc.Ez.LastRun;
        if (!ezDir) Set(_sEz, State.Todo, "EZBoards folder not set (optional).");
        else if (!net8) Set(_sEz, State.Problem, $"Folder OK, but the .NET 8 runtime was not found. EZBoards will not run without it.");
        else if (last is { Ok: false }) Set(_sEz, State.Problem, $"Last run failed: {last.Message}");
        else Set(_sEz, State.Ok, $"Ready: {_svc.Settings.EzBoardsDir}" + (last is { Ok: true } ? $" (last run OK at {DateTimeOffset.FromUnixTimeMilliseconds(last.Time).LocalDateTime:HH:mm:ss})" : ""));

        _summary.Text = info.Demo
            ? "Demo mode is ON: the app shows a synthetic mission. Untick it in the settings to use real BMS data."
            : $"{done} of {total} steps done. Follow the steps below once; afterwards the bridge just runs in the tray.";
        _summary.ForeColor = info.Demo ? Amber : (done == total ? Green : Dim);
    }

    /// <summary>Effective cfg value: Falcon BMS User.cfg overrides Falcon BMS.cfg, later lines win.</summary>
    private string? CfgValue(string name)
    {
        var dir = _svc.Install.ConfigDir;
        if (dir == null) return null;
        return BmsInstall.ReadCfgString(Path.Combine(dir, "Falcon BMS User.cfg"), name)
               ?? BmsInstall.ReadCfgString(Path.Combine(dir, "Falcon BMS.cfg"), name);
    }

    // ------------------------------------------------------------------ layout

    private Step AddStep(int n, string title, string text, string? code, params (string label, Action action)[] actions)
    {
        var step = new Step();
        var card = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, BackColor = Surface, Padding = new Padding(12, 10, 14, 12), Margin = new Padding(0, 0, 0, 10) };
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(40)));
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        step.Icon = new Label { Text = "●", Font = new Font("Segoe UI Symbol", 16f, FontStyle.Bold), ForeColor = Dim, AutoSize = true, Margin = new Padding(0, 0, 6, 0) };
        card.Controls.Add(step.Icon, 0, 0);
        card.SetRowSpan(step.Icon, 4);

        var col = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true, Margin = new Padding(0) };
        col.Controls.Add(new Label { Text = $"{n}. {title}", Font = new Font("Segoe UI Semibold", 11.5f), ForeColor = TextC, AutoSize = true, Margin = new Padding(0, 2, 0, 2) });
        step.Status = new Label { AutoSize = true, Font = new Font("Segoe UI Semibold", 9.5f), Margin = new Padding(0, 0, 0, 6) };
        step.Wrapping.Add(step.Status);
        col.Controls.Add(step.Status);
        var desc = new Label { Text = text, ForeColor = Dim, AutoSize = true, Margin = new Padding(0, 0, 0, 6) };
        step.Wrapping.Add(desc);
        col.Controls.Add(desc);
        if (code != null)
        {
            var box = new Label { Text = code, Font = new Font("Consolas", 10f), ForeColor = Code, BackColor = Color.FromArgb(5, 9, 12), AutoSize = true, Padding = new Padding(8, 6, 8, 6), Margin = new Padding(0, 0, 0, 8) };
            col.Controls.Add(box);
        }
        if (actions.Length > 0)
        {
            var buttons = new FlowLayoutPanel { AutoSize = true, WrapContents = true, Margin = new Padding(0) };
            foreach (var (label, action) in actions)
            {
                var b = MakeButton(label, Color.FromArgb(30, 40, 50), TextC);
                b.Click += (_, _) => { action(); RefreshChecks(); };
                buttons.Controls.Add(b);
            }
            col.Controls.Add(buttons);
        }
        card.Controls.Add(col, 1, 0);
        card.Tag = step;
        _list.Controls.Add(card);
        return step;
    }

    private void AddNote(string text)
    {
        var note = new Label { Text = text, ForeColor = Dim, AutoSize = true, Margin = new Padding(4, 4, 0, 10) };
        note.Tag = "note";
        _list.Controls.Add(note);
    }

    /// <summary>Labels only wrap when given a maximum width; keep it in sync with the window width.</summary>
    private void Rewrap()
    {
        int width = _list.ClientSize.Width - _list.Padding.Horizontal - SystemInformation.VerticalScrollBarWidth - 4;
        foreach (Control c in _list.Controls)
        {
            if (c is TableLayoutPanel card && card.Tag is Step s)
            {
                card.MinimumSize = card.MaximumSize = new Size(width, 0); // equal-width cards that still grow in height
                int textWidth = width - card.Padding.Horizontal - LogicalToDeviceUnits(46);
                foreach (var l in s.Wrapping) l.MaximumSize = new Size(Math.Max(200, textWidth), 0);
            }
            else if (c is Label l) l.MaximumSize = new Size(Math.Max(200, width), 0);
        }
    }

    private void PickFolder(string description, Action<string> apply)
    {
        using var dlg = new FolderBrowserDialog { Description = description, UseDescriptionForTitle = true, ShowNewFolderButton = false };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        apply(dlg.SelectedPath);
        _svc.Settings.Save();
        _svc.ApplySettings(restartServer: false);
    }

    private static Button MakeButton(string text, Color bg, Color fg)
    {
        var b = new Button { Text = text, AutoSize = true, BackColor = bg, ForeColor = fg, FlatStyle = FlatStyle.Flat, Padding = new Padding(8, 3, 8, 3), Margin = new Padding(0, 0, 8, 4) };
        b.FlatAppearance.BorderColor = Color.FromArgb(55, 70, 82);
        return b;
    }
}

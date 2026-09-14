using System.Diagnostics;
using System.Drawing;
using System.Reflection;
using BmsCompanion.Bridge.EzBoards;
using BmsCompanion.Bridge.Server;

namespace BmsCompanion.Bridge.UI;

/// <summary>Status + settings window. Closing it hides to the tray; the bridge keeps running.</summary>
public sealed class MainForm : Form
{
    private static readonly Color Bg = Color.FromArgb(10, 15, 20);
    private static readonly Color Surface = Color.FromArgb(20, 28, 36);
    private static readonly Color TextC = Color.FromArgb(223, 232, 239);
    private static readonly Color Dim = Color.FromArgb(138, 160, 170);
    private static readonly Color Amber = Color.FromArgb(255, 181, 71);
    private static readonly Color Green = Color.FromArgb(91, 227, 138);
    private static readonly Color Red = Color.FromArgb(255, 107, 107);

    private readonly BridgeService _svc;
    private readonly NotifyIcon _tray;
    private bool _exiting;

    private readonly Label _lblServer = Value(), _lblClient = Value(), _lblBms = Value(), _lblTacview = Value(), _lblBriefing = Value(), _lblEz = Value();
    private readonly Panel _dotServer = Dot(), _dotBms = Dot(), _dotTacview = Dot(), _dotBriefing = Dot(), _dotEz = Dot(), _dotClient = Dot();
    private readonly TextBox _txtEz = Input(), _txtBms = Input(), _txtTvHost = Input(), _txtTvPass = Input();
    private readonly NumericUpDown _numPort = Num(1024, 65535), _numTvPort = Num(1, 65535);
    private readonly CheckBox _chkAuto = Check("Run EZBoards automatically when the briefing is printed in BMS");
    private readonly CheckBox _chkTacview = Check("Read the Tacview real-time stream (AWACS picture: other aircraft)");
    private readonly CheckBox _chkMin = Check("Start minimized to the tray");
    private readonly CheckBox _chkDemo = Check("Demo mode (fake mission, no BMS needed)");
    private readonly TextBox _log = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, BackColor = Surface, ForeColor = Dim, BorderStyle = BorderStyle.None, Font = new Font("Consolas", 8.5f), Dock = DockStyle.Fill };
    private readonly Button _btnGenerate;

    public MainForm(BridgeService svc)
    {
        _svc = svc;
        Text = "BMS Companion Bridge " + BridgeService.Version;
        Icon = LoadIcon();
        BackColor = Bg; ForeColor = TextC;
        Font = new Font("Segoe UI", 9.5f);
        // Sizes are in 96-dpi units; scale them for high-DPI screens (PerMonitorV2).
        MinimumSize = new Size(LogicalToDeviceUnits(640), LogicalToDeviceUnits(520));
        Size = new Size(LogicalToDeviceUnits(860), LogicalToDeviceUnits(800));
        StartPosition = FormStartPosition.CenterScreen;

        var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, Padding = new Padding(16), BackColor = Bg };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        Controls.Add(root);

        var title = new Label { Text = "BMS COMPANION BRIDGE", ForeColor = Amber, Font = new Font("Segoe UI Semibold", 15f), AutoSize = true, Margin = new Padding(0, 0, 0, 2) };
        var sub = new Label { Text = "Keep this running while you fly. Open the BMS Companion app → Mission → Connect.", ForeColor = Dim, AutoSize = true, Margin = new Padding(0, 0, 0, 10) };
        var head = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false };
        head.Controls.Add(title); head.Controls.Add(sub);
        root.Controls.Add(head);

        // ---- status
        var status = Group("STATUS");
        var st = Grid(3);
        AddStatus(st, "App link", _dotServer, _lblServer);
        AddStatus(st, "Last app request", _dotClient, _lblClient);
        AddStatus(st, "Falcon BMS", _dotBms, _lblBms);
        AddStatus(st, "Tacview stream", _dotTacview, _lblTacview);
        AddStatus(st, "Briefing", _dotBriefing, _lblBriefing);
        AddStatus(st, "EZBoards", _dotEz, _lblEz);
        status.Controls.Add(st);
        root.Controls.Add(status);

        // ---- settings
        var set = Group("SETTINGS");
        var g = Grid(3);
        AddRow(g, "EZBoards folder", _txtEz, BrowseButton(_txtEz, "Select the EZBoards folder (contains EZBOARDS.BAT)"));
        AddRow(g, "BMS folder (optional)", _txtBms, BrowseButton(_txtBms, "Select the Falcon BMS folder (only if auto-detect fails)"));
        AddRow(g, "", _chkAuto, null);
        AddRow(g, "", _chkTacview, null);
        var tv = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0) };
        _txtTvHost.Width = 140; _txtTvPass.Width = 120; _txtTvPass.UseSystemPasswordChar = true;
        tv.Controls.Add(Small("Host")); tv.Controls.Add(_txtTvHost); tv.Controls.Add(Small("Port")); tv.Controls.Add(_numTvPort); tv.Controls.Add(Small("Password")); tv.Controls.Add(_txtTvPass);
        AddRow(g, "Tacview", tv, null);
        AddRow(g, "App port (TCP)", _numPort, Small("+ UDP 47475 (discovery)"));
        AddRow(g, "", _chkMin, null);
        AddRow(g, "", _chkDemo, null);
        set.Controls.Add(g);

        var buttons = new FlowLayoutPanel { AutoSize = true, WrapContents = true, Dock = DockStyle.Bottom, Margin = new Padding(0, 8, 0, 0) };
        var save = Btn("Save && apply", Amber, Bg);
        save.Click += (_, _) => SaveSettings();
        _btnGenerate = Btn("Generate kneeboards now", Surface, TextC);
        _btnGenerate.Click += async (_, _) => await Generate();
        var fw = Btn("Allow through Windows Firewall", Surface, TextC);
        fw.Click += (_, _) => SystemActions.AddFirewallRules(_svc.Settings.Port);
        var guide = Btn("Setup guide", Surface, Amber);
        guide.Click += (_, _) => ShowGuide();
        var web = Btn("Open status page", Surface, TextC);
        web.Click += (_, _) => Open($"http://127.0.0.1:{_svc.Settings.Port}/");
        buttons.Controls.AddRange(new Control[] { save, guide, _btnGenerate, fw, web });
        set.Controls.Add(buttons);
        root.Controls.Add(set);

        var logBox = Group("LOG");
        logBox.Dock = DockStyle.Fill;
        logBox.Controls.Add(_log);
        root.Controls.Add(logBox);

        LoadSettings();

        _tray = new NotifyIcon { Icon = Icon, Text = "BMS Companion Bridge", Visible = true };
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => ShowWindow());
        menu.Items.Add("Setup guide", null, (_, _) => { ShowWindow(); ShowGuide(); });
        menu.Items.Add("Generate kneeboards", null, async (_, _) => await Generate());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => { _exiting = true; Close(); });
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => ShowWindow();

        foreach (var l in Log.Snapshot()) _log.AppendText(l + Environment.NewLine);
        Log.Added += line => BeginInvokeSafe(() => { _log.AppendText(line + Environment.NewLine); });
        _svc.StatusChanged += () => BeginInvokeSafe(RefreshStatus);
        _svc.Ez.Completed += r => BeginInvokeSafe(() =>
            _tray.ShowBalloonTip(3000, r.Ok ? "Kneeboards generated" : "EZBoards failed", r.Message, r.Ok ? ToolTipIcon.Info : ToolTipIcon.Warning));
        var timer = new System.Windows.Forms.Timer { Interval = 1000 };
        timer.Tick += (_, _) => RefreshStatus();
        timer.Start();

        if (_svc.Settings.StartMinimized && !_svc.Settings.FirstRun) // the guide needs a visible window
        {
            WindowState = FormWindowState.Minimized;
            ShowInTaskbar = false;
            Load += (_, _) => Hide();
        }
        Shown += (_, _) =>
        {
            ActiveControl = null; // don't start with the path text selected
            if (_svc.Settings.FirstRun) BeginInvoke(ShowGuide);
        };
        RefreshStatus();
    }

    private void BeginInvokeSafe(Action a)
    {
        if (IsDisposed || !IsHandleCreated) return;
        try { BeginInvoke(a); } catch { }
    }

    private void ShowWindow()
    {
        Show();
        ShowInTaskbar = true;
        WindowState = FormWindowState.Normal;
        Activate();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!_exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            _tray.ShowBalloonTip(2000, "BMS Companion Bridge", "Still running in the tray. Right-click the icon to exit.", ToolTipIcon.Info);
            return;
        }
        _tray.Visible = false;
        base.OnFormClosing(e);
    }

    private void LoadSettings()
    {
        var s = _svc.Settings;
        _txtEz.Text = s.EzBoardsDir ?? "";
        _txtBms.Text = s.BmsDirOverride ?? "";
        _chkAuto.Checked = s.AutoEzBoardsOnPrint;
        _chkTacview.Checked = s.TacviewEnabled;
        _txtTvHost.Text = s.TacviewHost;
        _numTvPort.Value = s.TacviewPort;
        _txtTvPass.Text = s.TacviewPassword;
        _numPort.Value = s.Port;
        _chkMin.Checked = s.StartMinimized;
        _chkDemo.Checked = s.DemoMode;
        _chkDemo.Text = s.DemoFromCommandLine ? "Demo mode (forced on by --demo for this run)" : "Demo mode (fake mission, no BMS needed)";
    }

    private void SaveSettings()
    {
        var s = _svc.Settings;
        var ez = _txtEz.Text.Trim();
        if (ez.Length > 0 && !EzBoardsRunner.IsValidDir(ez))
        {
            MessageBox.Show(this, "That folder does not contain EZBOARDS.BAT.\nPick the EZBoards folder (for example <BMS>\\Tools\\EZBoards).", "EZBoards folder", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        int oldPort = s.Port;
        s.EzBoardsDir = ez.Length > 0 ? ez : null;
        s.BmsDirOverride = _txtBms.Text.Trim().Length > 0 ? _txtBms.Text.Trim() : null;
        s.AutoEzBoardsOnPrint = _chkAuto.Checked;
        s.TacviewEnabled = _chkTacview.Checked;
        s.TacviewHost = _txtTvHost.Text.Trim().Length > 0 ? _txtTvHost.Text.Trim() : "127.0.0.1";
        s.TacviewPort = (int)_numTvPort.Value;
        s.TacviewPassword = _txtTvPass.Text;
        s.Port = (int)_numPort.Value;
        s.StartMinimized = _chkMin.Checked;
        s.DemoMode = _chkDemo.Checked;
        s.Save();
        _svc.ApplySettings(restartServer: oldPort != s.Port);
        LoadSettings();
        Log.Info("Settings saved");
    }

    private async Task Generate()
    {
        _btnGenerate.Enabled = false;
        try { await _svc.Ez.GenerateAsync(_svc.Settings.EzBoardsDir, auto: false); }
        finally { _btnGenerate.Enabled = true; RefreshStatus(); }
    }

    private void RefreshStatus()
    {
        var i = _svc.Info();
        var ips = Discovery.LocalAddresses();
        SetStatus(_dotServer, _lblServer, true, ips.Count > 0 ? string.Join("   ", ips.Select(ip => $"{ip}:{_svc.Settings.Port}")) : $"port {_svc.Settings.Port}");
        var last = _svc.Http.LastRequest;
        bool recent = last > DateTime.Now.AddSeconds(-10);
        SetStatus(_dotClient, _lblClient, recent ? true : null, last == default ? "no app connected yet" : $"{_svc.Http.LastClient} at {last:HH:mm:ss}");
        SetStatus(_dotBms, _lblBms, i.Bms.Running ? true : i.Bms.Installed ? null : false,
            i.Demo ? "DEMO MODE" :
            i.Bms.Running ? $"running {i.Bms.Version} · {i.Bms.Theater} · {(i.Bms.Flying ? "in 3D" : "in UI")}" :
            i.Bms.Installed ? $"not running ({i.Bms.RegistryVersion}, {i.Bms.BaseDir})" : "not found - set the BMS folder");
        SetStatus(_dotTacview, _lblTacview, i.Tacview.Connected ? true : i.Tacview.Enabled ? null : false,
            i.Tacview.Connected ? $"connected · {i.Tacview.Objects} objects" : i.Tacview.State);
        SetStatus(_dotBriefing, _lblBriefing, i.Briefing.Available ? true : null,
            i.Briefing.Available ? $"printed {i.Briefing.Generated}" : "not printed yet (Briefing → PRINT in BMS)");
        var lr = i.EzBoards.LastRun;
        SetStatus(_dotEz, _lblEz, i.EzBoards.Configured ? (lr == null ? true : lr.Ok) : null,
            !i.EzBoards.Configured ? "folder not set" :
            i.EzBoards.Running ? "generating…" :
            lr == null ? "ready" : $"{(lr.Ok ? "OK" : "FAILED")} at {DateTimeOffset.FromUnixTimeMilliseconds(lr.Time).LocalDateTime:HH:mm:ss} · {lr.Message}");
    }

    private static void SetStatus(Panel dot, Label lbl, bool? ok, string text)
    {
        dot.BackColor = ok == true ? Green : ok == false ? Red : Amber;
        lbl.Text = text;
    }

    private SetupGuideForm? _guide;

    /// <summary>Opens (or focuses) the setup guide; settings changed there are reflected here when it closes.</summary>
    private void ShowGuide()
    {
        if (_guide is { IsDisposed: false }) { _guide.Activate(); return; }
        _guide = new SetupGuideForm(_svc, Icon);
        _guide.FormClosed += (_, _) => { LoadSettings(); RefreshStatus(); };
        _guide.Show(this);
    }

    private static void Open(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
    }

    private static Icon LoadIcon()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("app.ico");
        return s != null ? new Icon(s) : SystemIcons.Application;
    }

    // ---- small UI helpers
    private static Label Value() => new() { AutoSize = true, ForeColor = TextC, Margin = new Padding(0, 4, 0, 4) };
    private static Panel Dot() => new() { Width = 10, Height = 10, Margin = new Padding(0, 8, 8, 0), BackColor = Color.Gray };
    private static TextBox Input() => new() { BackColor = Surface, ForeColor = TextC, BorderStyle = BorderStyle.FixedSingle, Dock = DockStyle.Fill, Margin = new Padding(0, 3, 6, 3) };
    private static NumericUpDown Num(int min, int max) => new() { Minimum = min, Maximum = max, Width = 80, BackColor = Surface, ForeColor = TextC, BorderStyle = BorderStyle.FixedSingle, Margin = new Padding(0, 3, 6, 3) };
    private static CheckBox Check(string text) => new() { Text = text, AutoSize = true, ForeColor = TextC, Margin = new Padding(0, 3, 0, 3) };
    private static Label Small(string t) => new() { Text = t, AutoSize = true, ForeColor = Dim, Margin = new Padding(0, 6, 6, 0) };

    private static Button Btn(string text, Color bg, Color fg)
    {
        var b = new Button { Text = text, AutoSize = true, BackColor = bg, ForeColor = fg, FlatStyle = FlatStyle.Flat, Padding = new Padding(8, 3, 8, 3), Margin = new Padding(0, 0, 8, 6) };
        b.FlatAppearance.BorderColor = Color.FromArgb(50, 64, 76);
        return b;
    }

    private static GroupBox Group(string title) => new()
    {
        Text = title, ForeColor = Amber, AutoSize = true, Dock = DockStyle.Fill, Padding = new Padding(10, 6, 10, 8), Margin = new Padding(0, 0, 0, 10),
        Font = new Font("Segoe UI Semibold", 9f),
    };

    private static TableLayoutPanel Grid(int cols)
    {
        var t = new TableLayoutPanel { ColumnCount = cols, AutoSize = true, Dock = DockStyle.Top, Font = new Font("Segoe UI", 9.5f) };
        t.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150));
        t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        if (cols > 2) t.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        return t;
    }

    private static void AddStatus(TableLayoutPanel t, string label, Panel dot, Label value)
    {
        var row = t.RowCount++;
        t.Controls.Add(new Label { Text = label, ForeColor = Dim, AutoSize = true, Margin = new Padding(0, 4, 0, 4) }, 0, row);
        var flow = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0) };
        flow.Controls.Add(dot); flow.Controls.Add(value);
        t.Controls.Add(flow, 1, row);
        t.SetColumnSpan(flow, 2);
    }

    private static void AddRow(TableLayoutPanel t, string label, Control c, Control? extra)
    {
        var row = t.RowCount++;
        t.Controls.Add(new Label { Text = label, ForeColor = Dim, AutoSize = true, Margin = new Padding(0, 6, 0, 0) }, 0, row);
        t.Controls.Add(c, 1, row);
        if (extra != null) t.Controls.Add(extra, 2, row); else t.SetColumnSpan(c, 2);
    }

    private Button BrowseButton(TextBox target, string description)
    {
        var b = Btn("Browse…", Surface, TextC);
        b.Margin = new Padding(0, 1, 0, 1);
        b.Click += (_, _) =>
        {
            using var dlg = new FolderBrowserDialog { Description = description, UseDescriptionForTitle = true, ShowNewFolderButton = false };
            if (Directory.Exists(target.Text)) dlg.InitialDirectory = target.Text;
            if (dlg.ShowDialog(this) == DialogResult.OK) target.Text = dlg.SelectedPath;
        };
        return b;
    }
}

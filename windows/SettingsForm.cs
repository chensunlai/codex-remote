namespace CodexRemote.Windows;

internal sealed class SettingsForm : Form
{
    private readonly TextBox gateway = new() { Dock = DockStyle.Fill };
    private readonly TextBox token = new() { Dock = DockStyle.Fill, UseSystemPasswordChar = true };
    private readonly TextBox name = new() { Dock = DockStyle.Fill };
    private readonly ComboBox codex = new()
    {
        Dock = DockStyle.Fill, DropDownStyle = ComboBoxStyle.DropDown,
        AutoCompleteMode = AutoCompleteMode.SuggestAppend, AutoCompleteSource = AutoCompleteSource.ListItems,
        DropDownWidth = 700
    };
    private readonly Label discovery = new()
    {
        Text = "从检测结果中选择，或输入 EXE、CMD、BAT 的完整路径。",
        AutoSize = true, MaximumSize = new Size(560, 0), ForeColor = SystemColors.GrayText
    };
    private readonly CheckBox startup = new() { AutoSize = true, Text = "登录 Windows 后自动启动" };
    private readonly Label status = new() { AutoSize = true, MaximumSize = new Size(560, 0) };
    private readonly Button save = new() { Text = "保存并连接", AutoSize = true };
    private readonly Func<AgentSettings, bool, Task<bool>> apply;
    public event Action? UpdateRequested;
    internal bool AllowClose;

    public SettingsForm(Icon icon, Func<AgentSettings, bool, Task<bool>> apply)
    {
        this.apply = apply;
        Text = "Codex Remote · 连接设置";
        Icon = icon;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(640, 620);
        MinimumSize = new Size(600, 420);
        AutoScroll = true;
        Font = new Font("Segoe UI", 10);
        BackColor = SystemColors.Window;
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Top, Padding = new Padding(28), ColumnCount = 2, RowCount = 13,
            AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var intro = new Label
        {
            Text = "连接本机已登录的 Codex", AutoSize = true,
            Font = new Font(Font.FontFamily, 15, FontStyle.Bold), Margin = new Padding(0, 0, 0, 6)
        };
        layout.Controls.Add(intro, 0, 0);
        layout.SetColumnSpan(intro, 2);
        var description = new Label
        {
            Text = "关闭此窗口后仍在右下角托盘运行。", AutoSize = true,
            ForeColor = SystemColors.GrayText, Margin = new Padding(0, 0, 0, 18)
        };
        layout.Controls.Add(description, 0, 1);
        layout.SetColumnSpan(description, 2);
        AddField(layout, "Gateway 地址（逗号分隔，按先后顺序连接）", gateway, 2);
        AddField(layout, "访问令牌", token, 4);
        AddField(layout, "服务器名称", name, 6);
        AddField(layout, "Codex 可执行文件", codex, 8, span: false);
        var browse = new Button { Text = "浏览…", AutoSize = true, Margin = new Padding(8, 0, 0, 14) };
        browse.Click += (_, _) => BrowseCodex();
        layout.Controls.Add(browse, 1, 9);
        discovery.Margin = new Padding(0, 0, 0, 10);
        layout.Controls.Add(discovery, 0, 10);
        layout.SetColumnSpan(discovery, 2);
        startup.Margin = new Padding(0, 4, 0, 12);
        layout.Controls.Add(startup, 0, 11);
        layout.SetColumnSpan(startup, 2);
        var footer = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, AutoSize = true, Margin = Padding.Empty };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        status.Margin = new Padding(0, 6, 14, 0);
        footer.Controls.Add(status, 0, 0);
        var update = new Button { Name = "checkUpdates", Text = "检查更新", AutoSize = true };
        update.Click += (_, _) => UpdateRequested?.Invoke();
        footer.Controls.Add(update, 1, 0);
        save.Click += async (_, _) => await SaveAsync();
        footer.Controls.Add(save, 2, 0);
        layout.Controls.Add(footer, 0, 12);
        layout.SetColumnSpan(footer, 2);
        Controls.Add(layout);
        AcceptButton = save;
        FormClosing += (_, e) =>
        {
            if (!AllowClose && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
            }
        };
    }

    protected override void OnLoad(EventArgs e)
    {
        var workingArea = Screen.FromControl(this).WorkingArea;
        MinimumSize = new Size(Math.Min(MinimumSize.Width, workingArea.Width), Math.Min(MinimumSize.Height, workingArea.Height));
        Size = new Size(Math.Min(Width, workingArea.Width), Math.Min(Height, workingArea.Height));
        base.OnLoad(e);
    }

    private static void AddField(TableLayoutPanel layout, string label, Control input, int row, bool span = true)
    {
        var caption = new Label { Text = label, AutoSize = true, Margin = new Padding(0, 0, 0, 5) };
        layout.Controls.Add(caption, 0, row);
        layout.SetColumnSpan(caption, 2);
        input.Margin = new Padding(0, 0, 0, 14);
        layout.Controls.Add(input, 0, row + 1);
        if (span) layout.SetColumnSpan(input, 2);
    }

    public void LoadSettings(AgentSettings settings)
    {
        gateway.Text = settings.GatewayUrl;
        token.Text = settings.Token;
        name.Text = settings.Name;
        var candidates = AgentSettings.DiscoverCodexExecutables();
        codex.Items.Clear();
        codex.Items.AddRange(candidates.Cast<object>().ToArray());
        codex.Text = string.IsNullOrWhiteSpace(settings.CodexExecutable) && candidates.Count == 1
            ? candidates[0] : settings.CodexExecutable;
        discovery.Text = candidates.Count switch
        {
            0 => "未检测到 Codex，请浏览或输入 EXE、CMD、BAT 的完整路径。",
            1 => "已检测到一个 Codex；也可浏览或输入其他路径。",
            _ => $"检测到 {candidates.Count} 个 Codex，请选择一个，或输入其他路径。"
        };
        startup.Checked = LoginStartup.Enabled;
    }

    public void SetStatus(AgentStatus value)
    {
        status.Text = value.Message.Length == 0 ? value.Label : $"{value.Label}：{value.Message}";
        status.ForeColor = value.State == "error" ? Color.Firebrick : SystemColors.GrayText;
    }

    private void BrowseCodex()
    {
        using var dialog = new OpenFileDialog
        {
            Title = "选择已安装的 Codex", Filter = "Codex 启动文件 (*.exe;*.cmd;*.bat)|*.exe;*.cmd;*.bat",
            CheckFileExists = true, FileName = AgentSettings.ExpandExecutablePath(codex.Text)
        };
        if (dialog.ShowDialog(this) == DialogResult.OK) codex.Text = dialog.FileName;
    }

    private async Task SaveAsync()
    {
        try
        {
            var settings = new AgentSettings(gateway.Text, token.Text, name.Text, codex.Text).Validate();
            save.Enabled = false;
            if (await apply(settings, startup.Checked)) Hide();
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "无法保存设置", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally { if (!IsDisposed) save.Enabled = true; }
    }
}

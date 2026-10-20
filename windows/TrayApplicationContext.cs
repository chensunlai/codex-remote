using System.Diagnostics;
using Microsoft.Win32;

namespace CodexRemote.Windows;

internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly Control dispatcher = new();
    private readonly AgentController controller = new();
    private readonly NotifyIcon tray;
    private readonly Icon icon;
    private readonly ToolStripMenuItem state = new("已停止") { Enabled = false };
    private readonly ToolStripMenuItem start = new("启动 Agent");
    private readonly ToolStripMenuItem stop = new("停止 Agent") { Enabled = false };
    private readonly ToolStripMenuItem login = new("登录后自动启动") { CheckOnClick = false };
    private SettingsForm? settings;
    private UpdateForm? updates;
    private bool exiting;
    private bool busy;

    public TrayApplicationContext(bool autostart)
    {
        _ = dispatcher.Handle;
        icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? (Icon)SystemIcons.Application.Clone();
        var menu = new ContextMenuStrip();
        menu.Items.Add(state);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("连接设置…", null, (_, _) => ShowSettings());
        menu.Items.Add("检查更新…", null, (_, _) => ShowUpdates());
        start.Click += async (_, _) => await StartAsync();
        stop.Click += async (_, _) => await StopAsync(confirm: true);
        menu.Items.Add(start);
        menu.Items.Add(stop);
        menu.Items.Add("打开日志目录", null, (_, _) =>
        {
            try { OpenLogs(); }
            catch (Exception error) { ShowError(error.Message); }
        });
        login.Checked = LoginStartup.Enabled;
        login.Click += (_, _) =>
        {
            try
            {
                LoginStartup.SetEnabled(!login.Checked);
                login.Checked = LoginStartup.Enabled;
            }
            catch (Exception error) { ShowError(error.Message); }
        };
        menu.Items.Add(login);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, async (_, _) => await ExitAsync(confirm: true));
        tray = new NotifyIcon { Icon = icon, Text = "Codex Remote · 已停止", ContextMenuStrip = menu, Visible = true };
        tray.DoubleClick += (_, _) => ShowSettings();
        controller.StatusChanged += value => Dispatch(() => UpdateStatus(value));
        SystemEvents.SessionEnding += SessionEnding;
        _ = InitializeAsync(autostart);
    }

    private async Task InitializeAsync(bool autostart)
    {
        try { AgentSettings.ImportLegacyConfig(); }
        catch (Exception error) { ShowError($"无法导入旧配置：{error.Message}"); }
        if (!File.Exists(AgentSettings.ConfigPath))
        {
            if (!autostart) ShowSettings();
            else ShowError("尚未配置连接，请打开托盘菜单中的连接设置。");
            return;
        }
        await StartAsync();
        if (controller.Status.State == "error" && !autostart) ShowSettings();
    }

    public void ShowSettings()
    {
        Dispatch(() =>
        {
            if (exiting) return;
            try
            {
                if (settings is null || settings.IsDisposed)
                {
                    settings = new SettingsForm(icon, ApplySettingsAsync);
                    settings.UpdateRequested += ShowUpdates;
                    settings.LoadSettings(AgentSettings.Load(AgentSettings.ConfigPath));
                }
                else if (!settings.Visible)
                {
                    settings.LoadSettings(AgentSettings.Load(AgentSettings.ConfigPath));
                }
                settings.SetStatus(controller.Status);
                settings.Show();
                settings.WindowState = FormWindowState.Normal;
                settings.Activate();
            }
            catch (Exception error) { ShowError(error.Message); }
        });
    }

    private void ShowUpdates()
    {
        if (exiting) return;
        if (updates is null || updates.IsDisposed) updates = new UpdateForm(icon, InstallUpdateAsync);
        updates.Show();
        updates.Activate();
    }

    private async Task<bool> InstallUpdateAsync(string path)
    {
        if (busy || exiting) return false;
        if (MessageBox.Show(updates,
            "安装更新会退出 Agent，正在运行的任务可能中断。配置和数据会保留，是否继续？",
            "安装更新", MessageBoxButtons.OKCancel, MessageBoxIcon.Question,
            MessageBoxDefaultButton.Button2) != DialogResult.OK) return false;
        busy = true;
        try
        {
            await controller.StopAsync();
            if (controller.IsRunning || controller.Status.State == "error")
                throw new InvalidOperationException("Agent 未能正常停止，请稍后重试。");
            var launch = new ProcessStartInfo(path) { UseShellExecute = true };
            launch.ArgumentList.Add("/DIR=" + AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar));
            launch.ArgumentList.Add("/NORESTART");
            // The standard installer waits for this tray process to release its files.
            launch.ArgumentList.Add("/WAITPID=" + Environment.ProcessId);
            if (Process.Start(launch) is null) throw new InvalidOperationException("无法启动安装程序。");
            exiting = true;
            ExitThread();
            return true;
        }
        finally { busy = false; UpdateStatus(controller.Status); }
    }

    private async Task<bool> ApplySettingsAsync(AgentSettings value, bool startOnLogin)
    {
        if (busy || exiting) return false;
        if (controller.IsRunning && !ConfirmInterruption("保存配置并重新连接")) return false;
        busy = true;
        try
        {
            await controller.StopAsync();
            if (exiting) return false;
            value.Save(AgentSettings.ConfigPath);
            LoginStartup.SetEnabled(startOnLogin);
            login.Checked = startOnLogin;
            await controller.StartAsync(value);
            return controller.Status.State != "error";
        }
        finally { busy = false; UpdateStatus(controller.Status); }
    }

    private async Task StartAsync()
    {
        if (busy || exiting) return;
        busy = true;
        try { await controller.StartAsync(AgentSettings.Load(AgentSettings.ConfigPath)); }
        catch (Exception error) { ShowError(error.Message); }
        finally { busy = false; UpdateStatus(controller.Status); }
    }

    private async Task StopAsync(bool confirm)
    {
        if (busy || exiting) return;
        if (confirm && controller.IsRunning && !ConfirmInterruption("停止 Agent")) return;
        busy = true;
        try { await controller.StopAsync(); }
        finally { busy = false; UpdateStatus(controller.Status); }
    }

    private async Task ExitAsync(bool confirm)
    {
        if (exiting) return;
        if (confirm && controller.IsRunning && !ConfirmInterruption("退出 Codex Remote")) return;
        exiting = true;
        tray.Text = "Codex Remote · 正在退出";
        await controller.StopAsync();
        ExitThread();
    }

    private bool ConfirmInterruption(string action) => MessageBox.Show(
        settings is { Visible: true } ? settings : null,
        $"{action}会停止本应用启动的 Codex 进程，正在运行的任务可能中断。是否继续？",
        "确认停止", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning,
        MessageBoxDefaultButton.Button2) == DialogResult.OK;

    private void UpdateStatus(AgentStatus value)
    {
        if (exiting) return;
        state.Text = value.Label;
        state.ToolTipText = value.Message;
        tray.Text = $"Codex Remote · {value.Label}";
        start.Enabled = !busy && !controller.IsRunning;
        stop.Enabled = !busy && controller.IsRunning;
        settings?.SetStatus(value);
        if (value.State == "error") ShowError(value.Message);
    }

    private void ShowError(string message)
    {
        if (exiting) return;
        tray.ShowBalloonTip(5000, "Codex Remote", message.Length > 240 ? message[..240] + "…" : message, ToolTipIcon.Warning);
    }

    private static void OpenLogs()
    {
        var directory = Path.Combine(AgentSettings.DataDirectory, "logs");
        Directory.CreateDirectory(directory);
        Process.Start(new ProcessStartInfo(directory) { UseShellExecute = true });
    }

    private void SessionEnding(object? sender, SessionEndingEventArgs e) => Dispatch(() => _ = ExitAsync(confirm: false));

    private void Dispatch(Action action)
    {
        if (dispatcher.IsDisposed || exiting) return;
        if (dispatcher.InvokeRequired) dispatcher.BeginInvoke(action);
        else action();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            SystemEvents.SessionEnding -= SessionEnding;
            controller.Dispose();
            tray.Visible = false;
            tray.Dispose();
            updates?.Dispose();
            if (settings is not null)
            {
                settings.AllowClose = true;
                settings.Dispose();
            }
            icon.Dispose();
            dispatcher.Dispose();
        }
        base.Dispose(disposing);
    }
}

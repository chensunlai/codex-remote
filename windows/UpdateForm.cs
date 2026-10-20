using System.Diagnostics;

namespace CodexRemote.Windows;

internal sealed class UpdateForm : Form
{
    private readonly Label status = new() { AutoSize = true, MaximumSize = new Size(440, 0) };
    private readonly ProgressBar progress = new() { Width = 440, Height = 18 };
    private readonly Button action = new() { Text = "检查更新", AutoSize = true };
    private readonly CancellationTokenSource cancellation = new();
    private readonly Func<string, Task<bool>> install;
    private AgentRelease? release;
    private bool busy;
    private bool installing;

    public UpdateForm(Icon icon, Func<string, Task<bool>> install)
    {
        this.install = install;
        Icon = icon;
        Text = "Codex Remote · 更新";
        Font = new Font("Segoe UI", 10);
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        var layout = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true,
            Padding = new Padding(24), Dock = DockStyle.Fill
        };
        layout.Controls.Add(new Label { Text = $"当前版本 {AgentUpdates.DisplayVersion}", AutoSize = true });
        status.Margin = new Padding(3, 12, 3, 12);
        layout.Controls.Add(status);
        layout.Controls.Add(progress);
        action.Margin = new Padding(3, 16, 3, 3);
        layout.Controls.Add(action);
        Controls.Add(layout);
        action.Click += async (_, _) => await RunAsync();
        Shown += async (_, _) => await RunAsync();
        FormClosing += (_, e) =>
        {
            if (installing) { e.Cancel = true; return; }
            cancellation.Cancel();
        };
    }

    private async Task RunAsync()
    {
        if (busy) return;
        busy = true;
        action.Enabled = false;
        try
        {
            if (release is null)
            {
                status.Text = "正在检查正式版本…";
                progress.Style = ProgressBarStyle.Marquee;
                release = await AgentUpdates.CheckAsync(cancellation.Token);
                if (IsDisposed) return;
                status.Text = release is null ? "当前已是最新版本。" : $"可更新至 {release.Version.ToString(3)} · {release.Size / 1048576d:F1} MiB\n更新 Windows Agent，不更新 Codex CLI。";
                action.Text = release is null ? "重新检查" : "下载并安装";
            }
            else
            {
                progress.Style = ProgressBarStyle.Continuous;
                progress.Value = 0;
                var timer = Stopwatch.StartNew();
                var last = TimeSpan.Zero;
                var report = new Progress<long>(bytes =>
                {
                    if (IsDisposed || (timer.Elapsed - last).TotalMilliseconds < 100 && bytes != release.Size) return;
                    last = timer.Elapsed;
                    progress.Value = (int)(bytes * 100 / release.Size);
                    status.Text = $"正在下载 {bytes / 1048576d:F1} / {release.Size / 1048576d:F1} MiB\n{bytes / 1048576d / Math.Max(0.1, timer.Elapsed.TotalSeconds):F1} MiB/s";
                });
                var path = await AgentUpdates.DownloadAsync(release, report, cancellation.Token);
                if (IsDisposed) return;
                status.Text = "校验通过。安装时会退出 Agent；取消安装后可重新启动原版本。";
                installing = true;
                try { await install(path); }
                finally { installing = false; }
            }
        }
        catch (OperationCanceledException) { if (!IsDisposed) status.Text = "检查或下载已取消 / 超时，请重试。"; }
        catch (Exception error) { if (!IsDisposed) status.Text = $"更新失败：{error.Message}"; }
        finally
        {
            busy = false;
            if (!IsDisposed) { action.Enabled = true; progress.Style = ProgressBarStyle.Continuous; }
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) cancellation.Cancel();
        base.Dispose(disposing);
    }
}

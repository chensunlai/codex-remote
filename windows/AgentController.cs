using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace CodexRemote.Windows;

internal sealed record AgentStatus(string State, string Message)
{
    public string Label => State switch
    {
        "connecting" => "正在连接", "connected" => "已连接", "reconnecting" => "正在重连",
        "stopping" => "正在停止", "error" => "运行失败", _ => "已停止"
    };
}

internal sealed class AgentController : IDisposable
{
    private readonly SemaphoreSlim lifecycle = new(1, 1);
    private readonly object logLock = new();
    private Process? process;
    private WindowsJob? job;
    private bool stopping;
    private bool disposed;
    private string token = "";
    public AgentStatus Status { get; private set; } = new("stopped", "");
    public bool IsRunning => process is { HasExited: false };
    public event Action<AgentStatus>? StatusChanged;

    public async Task StartAsync(AgentSettings settings)
    {
        await lifecycle.WaitAsync();
        try
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (IsRunning) return;
            settings = settings.Validate();
            token = settings.Token;
            Directory.CreateDirectory(AgentSettings.DataDirectory);
            var executable = Path.Combine(AppContext.BaseDirectory, "runtime", "node.exe");
            var entry = Path.Combine(AppContext.BaseDirectory, "app", "agent.mjs");
            if (!File.Exists(executable) || !File.Exists(entry))
                throw new InvalidOperationException("发行包不完整。请完整解压 ZIP 后启动，不要单独移动 CodexRemote.exe。");
            var start = new ProcessStartInfo(executable)
            {
                WorkingDirectory = AgentSettings.DataDirectory,
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
                StandardInputEncoding = new UTF8Encoding(false),
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
            };
            start.ArgumentList.Add(entry);
            start.ArgumentList.Add("--desktop-control");
            start.ArgumentList.Add("--config");
            start.ArgumentList.Add(AgentSettings.ConfigPath);
            // Saved settings take precedence over any unrelated CLI environment in the user's session.
            foreach (var key in new[] { "CODEX_REMOTE_GATEWAY", "CODEX_REMOTE_TOKEN", "CODEX_REMOTE_SERVICE_NAME", "CODEX_REMOTE_CODEX" })
                start.Environment.Remove(key);
            start.Environment["CODEX_REMOTE_NODE_PATH"] = executable;
            start.Environment["CODEX_REMOTE_WINDOWS_SUPERVISOR"] = Environment.ProcessPath!;
            stopping = false;
            SetStatus(new AgentStatus("connecting", ""));
            job = new WindowsJob();
            var child = Process.Start(start) ?? throw new InvalidOperationException("无法启动 Agent。");
            process = child;
            try
            {
                // Desktop Agent waits for this start command before opening connections or children.
                job.Assign(child);
                _ = ReadOutputAsync(child);
                _ = ReadErrorsAsync(child);
                _ = ObserveExitAsync(child);
                await child.StandardInput.WriteLineAsync("{\"type\":\"start\"}");
                await child.StandardInput.FlushAsync();
            }
            catch
            {
                try { child.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
                throw;
            }
        }
        catch (Exception error)
        {
            job?.Dispose();
            job = null;
            var message = Redact(error.Message);
            WriteLog(message);
            SetStatus(new AgentStatus("error", message));
        }
        finally { lifecycle.Release(); }
    }

    public async Task StopAsync()
    {
        await lifecycle.WaitAsync();
        try
        {
            stopping = true;
            if (process is { } child && !child.HasExited)
            {
                SetStatus(new AgentStatus("stopping", ""));
                try
                {
                    await child.StandardInput.WriteLineAsync("{\"type\":\"stop\"}");
                    await child.StandardInput.FlushAsync();
                    await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(8));
                }
                catch (Exception error) when (error is TimeoutException or IOException or InvalidOperationException)
                {
                    WriteLog("Agent 未及时退出，正在清理所属进程树。");
                }
            }
            var activeJob = job;
            job = null;
            try
            {
                if (activeJob is not null) await activeJob.TerminateAndWaitAsync();
            }
            finally { activeJob?.Dispose(); }
            if (process is { } remaining && !remaining.HasExited)
                await remaining.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3));
            SetStatus(new AgentStatus("stopped", ""));
        }
        catch (Exception error)
        {
            SetStatus(new AgentStatus("error", Redact(error.Message)));
        }
        finally { lifecycle.Release(); }
    }

    private async Task ReadOutputAsync(Process child)
    {
        try
        {
            while (await child.StandardOutput.ReadLineAsync() is { } line)
            {
                if (line.Length > 16_384) continue;
                try
                {
                    using var document = JsonDocument.Parse(line);
                    var root = document.RootElement;
                    if (root.TryGetProperty("type", out var type) && type.GetString() == "status"
                        && root.TryGetProperty("state", out var state))
                    {
                        var value = state.GetString();
                        if (value is "connecting" or "connected" or "reconnecting" or "stopped" or "error")
                        {
                            var message = root.TryGetProperty("message", out var detail) ? detail.GetString() ?? "" : "";
                            if (ReferenceEquals(process, child) && (!stopping || value == "stopped"))
                                SetStatus(new AgentStatus(value, Redact(message)));
                        }
                    }
                }
                catch (Exception error) when (error is JsonException or InvalidOperationException) { WriteLog(line); }
            }
        }
        catch (Exception error) when (error is IOException or ObjectDisposedException) { }
    }

    private async Task ReadErrorsAsync(Process child)
    {
        try
        {
            while (await child.StandardError.ReadLineAsync() is { } line) WriteLog(line);
        }
        catch (Exception error) when (error is IOException or ObjectDisposedException) { }
    }

    private async Task ObserveExitAsync(Process child)
    {
        await child.WaitForExitAsync();
        await lifecycle.WaitAsync();
        try
        {
            if (!ReferenceEquals(process, child)) { child.Dispose(); return; }
            var activeJob = job;
            job = null;
            try
            {
                if (activeJob is not null) await activeJob.TerminateAndWaitAsync();
            }
            catch (Exception error) { WriteLog($"进程树清理失败：{error.Message}"); }
            finally { activeJob?.Dispose(); }
            process = null;
            var exitCode = child.ExitCode;
            child.Dispose();
            if (stopping) SetStatus(new AgentStatus("stopped", ""));
            else SetStatus(new AgentStatus("error", $"Agent 已退出（{exitCode}）。请查看日志，修正后手动启动。"));
        }
        finally { lifecycle.Release(); }
    }

    private void SetStatus(AgentStatus value)
    {
        Status = value;
        StatusChanged?.Invoke(value);
    }

    private string Redact(string message) => token.Length == 0 ? message : message.Replace(token, "[已隐藏]", StringComparison.Ordinal);

    private void WriteLog(string message)
    {
        lock (logLock)
        {
            try
            {
                var directory = Path.Combine(AgentSettings.DataDirectory, "logs");
                Directory.CreateDirectory(directory);
                var path = Path.Combine(directory, "agent.log");
                if (File.Exists(path) && new FileInfo(path).Length >= 2 * 1024 * 1024)
                {
                    if (File.Exists(path + ".1")) File.Move(path + ".1", path + ".2", overwrite: true);
                    File.Move(path, path + ".1", overwrite: true);
                }
                var safe = Redact(message);
                if (safe.Length > 4096) safe = safe[..4096] + "…";
                File.AppendAllText(path, $"{DateTimeOffset.Now:O} {safe}{Environment.NewLine}");
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    public void Dispose()
    {
        disposed = true;
        job?.Dispose();
        // Do not dispose an active Process here: pending output/exit observers still use it.
    }
}

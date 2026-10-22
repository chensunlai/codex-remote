using System.Diagnostics;
using System.Text.Json.Nodes;

namespace CodexRemote.Windows;

// Runs on the actual Windows runtime in CI without Gateway credentials or a Codex login.
internal static class SelfTest
{
    public static async Task<int> Run()
    {
        using var output = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
        try
        {
            SettingsWindowLifecycle();
            await output.WriteLineAsync("PASS settings layout, small-window scrolling and close-to-tray behavior");
            await AgentUpdatesSelfTest.Run();
            await output.WriteLineAsync("PASS update versions, release URLs, SHA-256, download sizes, progress and cancellation");
            ConfigRoundTrip();
            await output.WriteLineAsync("PASS configuration validation, stable identity and UTF-8 paths");
            CodexPathDiscovery();
            await output.WriteLineAsync("PASS Codex discovery, multiple candidate selection and explicit path priority");
            await CodexScriptLauncherSelfTest.Run();
            await output.WriteLineAsync("PASS CMD/BAT arguments, Unicode paths, binary stdio, exit status and job cleanup");
            await JobOwnsOnlyItsTree();
            await output.WriteLineAsync("PASS job cleanup leaves independent processes running");
            await WatcherCleansDescendants(closeInput: false);
            await output.WriteLineAsync("PASS extension host exit cleans descendants before watcher stops");
            await WatcherCleansDescendants(closeInput: true);
            await output.WriteLineAsync("PASS watcher owner disconnect cleans its host tree");
            return 0;
        }
        catch (Exception error)
        {
            await output.WriteLineAsync($"FAIL {error}");
            return 1;
        }
    }

    private static void SettingsWindowLifecycle()
    {
        // Run before the first await: WinForms needs the entry point's STA thread.
        var previousContext = SynchronizationContext.Current;
        ApplicationConfiguration.Initialize();
        try
        {
            var saves = 0;
            using var form = new SettingsForm(SystemIcons.Application, (_, _) =>
            {
                saves++;
                return Task.FromResult(true);
            });
            form.Show();
            Application.DoEvents();
            form.PerformLayout();
            var updates = 0;
            form.UpdateRequested += () => updates++;
            var update = (Button)form.Controls.Find("checkUpdates", true).Single();
            update.PerformClick();
            Require(updates == 1 && saves == 0, "update button must not save settings or restart Agent");
            var save = (Control)form.AcceptButton!;
            RequireInsideClient(form, save, "save button at initial size");
            Require(form.Bounds.Height <= Screen.FromControl(form).WorkingArea.Height, "initial window fits screen");

            form.Size = form.MinimumSize;
            form.PerformLayout();
            form.ScrollControlIntoView(save);
            Application.DoEvents();
            RequireInsideClient(form, save, "save button remains reachable at minimum size");

            form.Close();
            Require(!form.Visible && !form.IsDisposed, "closing settings hides the same form");
            form.Show();
            Application.DoEvents();
            Require(form.Visible && !form.IsDisposed, "hidden settings can reopen");
            Require(saves == 0, "layout and close must not save or start Agent");
            form.LoadSettings(new AgentSettings("https://gateway.example.com", "unused-fixture-token", "fixture", Environment.ProcessPath!));
            form.ScrollControlIntoView(save);
            RequireInsideClient(form, save, "save button is visible before click");
            ((Button)save).PerformClick();
            Application.DoEvents();
            Require(saves == 1 && !form.Visible, "save button invokes only the supplied callback and hides settings");
            form.AllowClose = true;
            form.Close();
            Require(form.IsDisposed, "explicit application disposal can close settings");
        }
        finally
        {
            // Subsequent process tests have no message loop; don't capture the WinForms context.
            SynchronizationContext.SetSynchronizationContext(previousContext);
        }
    }

    private static void RequireInsideClient(Form form, Control control, string message)
    {
        var bounds = form.RectangleToClient(control.Parent!.RectangleToScreen(control.Bounds));
        Require(bounds.Width > 0 && bounds.Height > 0 && form.ClientRectangle.Contains(bounds),
            $"{message}: control={bounds}, client={form.ClientRectangle}");
    }

    private static void ConfigRoundTrip()
    {
        var directory = Path.Combine(Path.GetTempPath(), "Codex Remote 测试 " + Guid.NewGuid());
        Directory.CreateDirectory(directory);
        try
        {
            var executable = Path.Combine(directory, "Codex 测试.exe");
            File.WriteAllText(executable, "test");
            var path = Path.Combine(directory, "agent.json");
            var settings = new AgentSettings("gateway.example.com/", "never-log-this-token", "测试服务器", executable);
            settings.Save(path);
            var saved = AgentSettings.Load(path);
            Require(saved.GatewayUrl == "https://gateway.example.com", "Gateway URL normalization");
            Require(saved.CodexExecutable == executable && saved.Name == "测试服务器", "UTF-8 round trip");
            var document = JsonNode.Parse(File.ReadAllText(path))!.AsObject();
            var serviceId = document["serviceId"]!.GetValue<string>();
            document["futureField"] = "preserve";
            File.WriteAllText(path, document.ToJsonString());
            (saved with { Name = "renamed" }).Save(path);
            document = JsonNode.Parse(File.ReadAllText(path))!.AsObject();
            Require(document["serviceId"]!.GetValue<string>() == serviceId, "stable service ID");
            Require(document["futureField"]!.GetValue<string>() == "preserve", "preserve unknown fields");
            (saved with { GatewayUrl = "http://lan:6767/, gateway.example.com, http://lan:6767" }).Save(path);
            var routed = AgentSettings.Load(path);
            Require(routed.GatewayUrl == "http://lan:6767, https://gateway.example.com", "ordered Gateway addresses");
            document = JsonNode.Parse(File.ReadAllText(path))!.AsObject();
            Require(document["gatewayUrl"]!.GetValue<string>() == "http://lan:6767", "legacy primary address");
            Require(document["gatewayUrls"]!.AsArray().Count == 2, "deduplicated address list");
            Require(document["serviceId"]!.GetValue<string>() == serviceId, "routing keeps service identity");
            (routed with { GatewayUrl = "new.example" }).Save(path);
            Require(AgentSettings.Load(path).GatewayUrl == "https://new.example", "single address replaces saved list");
            document = JsonNode.Parse(File.ReadAllText(path))!.AsObject();
            document.Remove("gatewayUrls");
            File.WriteAllText(path, document.ToJsonString());
            Require(AgentSettings.Load(path).GatewayUrl == "https://new.example", "legacy config remains readable");
            foreach (var url in new[] { "", "http://lan,", "ftp://example.com", "https://user:secret@example.com", "https://example.com?token=a" })
                Reject(() => (saved with { GatewayUrl = url }).Validate());
            Reject(() => (saved with { CodexExecutable = @"relative\custom.cmd" }).Validate());
            Reject(() => (saved with { CodexExecutable = Path.Combine(directory, "missing.exe") }).Validate());
            foreach (var extension in new[] { ".cmd", ".bat" })
            {
                var script = Path.Combine(directory, "Codex 测试" + extension);
                File.WriteAllText(script, "@echo off\r\n");
                Require((saved with { CodexExecutable = script }).Validate().CodexExecutable == script,
                    "explicit script entry accepted");
            }
            Reject(() => (saved with { Token = " " }).Validate());
            File.WriteAllText(path, "{\"version\":2}");
            Reject(() => AgentSettings.Load(path));
        }
        finally { Directory.Delete(directory, recursive: true); }
    }

    private static void CodexPathDiscovery()
    {
        var directory = Path.Combine(Path.GetTempPath(), "Codex PATH 测试 " + Guid.NewGuid());
        var previousDirectory = Environment.CurrentDirectory;
        const string pathVariable = "CODEX_REMOTE_PATH_DISCOVERY_TEST";
        var previousVariable = Environment.GetEnvironmentVariable(pathVariable);
        try
        {
            var first = Directory.CreateDirectory(Path.Combine(directory, "first 空格")).FullName;
            var second = Directory.CreateDirectory(Path.Combine(directory, "second")).FullName;
            var fallback = Directory.CreateDirectory(Path.Combine(directory, "default")).FullName;
            var relative = Directory.CreateDirectory(Path.Combine(directory, "relative")).FullName;
            var executable = Path.Combine(first, "codex.exe");
            File.WriteAllText(executable, "first");
            var command = Path.Combine(first, "codex.cmd");
            var batch = Path.Combine(second, "codex.bat");
            var fallbackExecutable = Path.Combine(fallback, "codex.exe");
            File.WriteAllText(command, "command");
            File.WriteAllText(batch, "batch");
            File.WriteAllText(fallbackExecutable, "default");
            Directory.CreateDirectory(Path.Combine(second, "codex.exe"));
            File.WriteAllText(Path.Combine(relative, "codex.exe"), "must not use cwd");
            Environment.CurrentDirectory = directory;
            var candidates = AgentSettings.DiscoverCodexExecutables(
                $";relative;\"{first}\";{first.ToUpperInvariant()};{second}", new[] { first, fallback });
            Require(candidates.SequenceEqual(new[] { executable, command, batch, fallbackExecutable }),
                "PATH order, quoted UTF-8 paths, case-insensitive deduplication, defaults and directory exclusion");
            var automatic = new AgentSettings("https://gateway.example.com", "unused-fixture-token", "fixture", "");
            foreach (var input in new[] { "", "  ", "codex", "CODEX.EXE", "codex.cmd", "codex.bat" })
            {
                Require(AgentSettings.ResolveCodexExecutable(input, new[] { executable }) == executable,
                    "a single match is selected automatically");
                Reject(() => AgentSettings.ResolveCodexExecutable(input, candidates));
                Reject(() => AgentSettings.ResolveCodexExecutable(input, Array.Empty<string>()));
            }
            Require(AgentSettings.ResolveCodexExecutable(batch, candidates) == batch,
                "explicit choice takes priority over other discoveries");
            var manual = Path.Combine(directory, "自定义 Codex.cmd");
            File.WriteAllText(manual, "manual");
            Require(AgentSettings.ResolveCodexExecutable($"\"{manual}\"", candidates) == manual,
                "manual quoted path need not appear in discoveries");
            Environment.SetEnvironmentVariable(pathVariable, directory);
            var expandedManual = $"\"%{pathVariable}%\\自定义 Codex.cmd\"";
            Require(AgentSettings.ResolveCodexExecutable(expandedManual, candidates) == manual,
                "manual paths expand environment variables before validation");
            Require(AgentSettings.DiscoverCodexExecutables($"\"%{pathVariable}%\\first 空格\";{first}",
                new[] { $"%{pathVariable}%\\default" }).SequenceEqual(new[] { executable, command, fallbackExecutable }),
                "PATH and default directories expand variables and deduplicate resolved paths");
            Environment.SetEnvironmentVariable(pathVariable, "relative");
            Require(AgentSettings.DiscoverCodexExecutables($"%{pathVariable}%", Array.Empty<string>()).Count == 0,
                "expanded relative paths must not search the working directory");
            Reject(() => AgentSettings.ResolveCodexExecutable(Path.Combine(directory, "missing.exe"), candidates));
            Reject(() => AgentSettings.ResolveCodexExecutable(Path.Combine(second, "codex.exe"), candidates));
            Require(AgentSettings.DiscoverCodexExecutables(";relative;", new[] { fallback }).SequenceEqual(new[] { fallbackExecutable }),
                "default directories are scanned after PATH");
            Require(AgentSettings.DiscoverCodexExecutables(";relative;", Array.Empty<string>()).Count == 0,
                "missing entries do not invent a default executable or use cwd");
            var configPath = Path.Combine(directory, "agent.json");
            Require(AgentSettings.Load(configPath).CodexExecutable == "", "new configuration enables automatic lookup");
            (automatic with { CodexExecutable = batch }).Save(configPath);
            Require(AgentSettings.Load(configPath).CodexExecutable == batch, "save records the selected absolute script path");
        }
        finally
        {
            Environment.CurrentDirectory = previousDirectory;
            Environment.SetEnvironmentVariable(pathVariable, previousVariable);
            Directory.Delete(directory, recursive: true);
        }
    }

    private static async Task JobOwnsOnlyItsTree()
    {
        using var independent = StartWorker("leaf");
        using var host = StartWorker("parent");
        using var job = new WindowsJob();
        try
        {
            job.Assign(host);
            using var descendant = await StartDescendant(host);
            job.Dispose();
            await Exited(host);
            await Exited(descendant);
            Require(!independent.HasExited, "independent process must survive");
        }
        finally { Kill(host); Kill(independent); }
    }

    private static async Task WatcherCleansDescendants(bool closeInput)
    {
        using var independent = StartWorker("leaf");
        using var host = StartWorker("parent");
        using var outerJob = new WindowsJob();
        outerJob.Assign(host);
        using var watcher = Start("--watch-host", host.Id.ToString());
        outerJob.Assign(watcher);
        try
        {
            var ready = await watcher.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(10));
            Require(ready == "{\"type\":\"ready\"}", $"watcher handshake: {ready}");
            using var descendant = await StartDescendant(host);
            if (closeInput) watcher.StandardInput.Close();
            else host.Kill(entireProcessTree: false);
            var stopped = await watcher.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(10));
            Require(stopped == "{\"type\":\"stopped\"}", $"watcher stopped acknowledgement: {stopped}");
            await Exited(watcher);
            await Exited(host);
            await Exited(descendant);
            Require(!independent.HasExited, "watcher must not kill unrelated processes");
        }
        finally { Kill(watcher); Kill(host); Kill(independent); }
    }

    private static async Task<Process> StartDescendant(Process parent)
    {
        await parent.StandardInput.WriteLineAsync("start");
        await parent.StandardInput.FlushAsync();
        var line = await parent.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(10));
        Require(int.TryParse(line, out var pid), $"worker child PID: {line}");
        var child = Process.GetProcessById(pid);
        _ = child.Handle;
        return child;
    }

    internal static int RunWorker(string mode)
    {
        using var output = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
        if (mode == "parent")
        {
            using var input = new StreamReader(Console.OpenStandardInput());
            if (input.ReadLine() != "start") return 1;
            using var child = StartWorker("leaf");
            output.WriteLine(child.Id);
        }
        Thread.Sleep(Timeout.Infinite);
        return 0;
    }

    private static Process StartWorker(string mode) => Start("--self-test-worker", mode);

    private static Process Start(params string[] args)
    {
        var start = new ProcessStartInfo(Environment.ProcessPath!)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        foreach (var argument in args) start.ArgumentList.Add(argument);
        return Process.Start(start) ?? throw new InvalidOperationException("Unable to start self-test worker.");
    }

    private static async Task Exited(Process process) => await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
    private static void Kill(Process process)
    {
        try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
        catch (InvalidOperationException) { }
    }

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private static void Reject(Action action)
    {
        try { action(); }
        catch (InvalidOperationException) { return; }
        throw new InvalidOperationException("Expected invalid settings to be rejected.");
    }
}

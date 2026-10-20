using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace CodexRemote.Windows;

internal static class CodexScriptLauncherSelfTest
{
    internal static async Task Run()
    {
        var directory = Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "Codex 脚本 空格 & (测试) " + Guid.NewGuid())).FullName;
        try
        {
            // Script text is ASCII; Unicode paths and arguments must not depend on the CMD codepage.
            foreach (var extension in new[] { ".bat", ".cmd", "-npm.cmd" })
            {
                var script = Path.Combine(directory, "codex 测试 & (shim)" + extension);
                var contents = extension == "-npm.cmd"
                    ? "@echo off\r\nsetlocal\r\nset \"_prog=%CODEX_REMOTE_SCRIPT_TEST_EXE%\"\r\nendlocal & goto #_undefined_# 2>NUL || title %COMSPEC% & \"%_prog%\" --self-test-codex-script-worker %*\r\n"
                    : "@echo off\r\nsetlocal DisableDelayedExpansion\r\n\"%CODEX_REMOTE_SCRIPT_TEST_EXE%\" --self-test-codex-script-worker %*\r\nexit /b %errorlevel%\r\n";
                await File.WriteAllTextAsync(script, contents, Encoding.ASCII);
                var arguments = new[] { "app-server", "--config", "model=\"中文 & (配置)^%!\"", "", "中文 空格", "()[]", "%PATH%", "!UNCHANGED!", "caret^", "a\"b", "trailing\\", "\\\"", "\"(foo|bar>baz)\"" };
                var report = Path.Combine(directory, "arguments.json");
                using var launcher = Start(script, report, arguments);
                using var job = new WindowsJob();
                job.Assign(launcher);
                var bytes = new byte[65_537];
                for (var i = 0; i < bytes.Length; i++) bytes[i] = (byte)i;
                using var stdout = new MemoryStream();
                using var stderr = new MemoryStream();
                var output = launcher.StandardOutput.BaseStream.CopyToAsync(stdout);
                var errors = launcher.StandardError.BaseStream.CopyToAsync(stderr);
                await launcher.StandardInput.BaseStream.WriteAsync(bytes);
                launcher.StandardInput.Close();
                await launcher.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(15));
                await Task.WhenAll(output, errors);
                Require(launcher.ExitCode == 29, $"{extension} exit code {launcher.ExitCode}, stderr: {Encoding.UTF8.GetString(stderr.ToArray())}");
                var actual = JsonSerializer.Deserialize<string[]>(await File.ReadAllTextAsync(report))!;
                Require(actual.SequenceEqual(arguments), $"{extension} arguments: {JsonSerializer.Serialize(actual)}");
                Require(stdout.ToArray().SequenceEqual(bytes), $"{extension} binary stdin/stdout round trip");
                Require(stderr.ToArray().SequenceEqual(new byte[] { 0, 255, 128, 13, 10 }), $"{extension} binary stderr");
            }

            var earlyScript = Path.Combine(directory, "version.cmd");
            await File.WriteAllTextAsync(earlyScript, "@echo off\r\nexit /b 17\r\n", Encoding.ASCII);
            using (var early = Start(earlyScript, Path.Combine(directory, "unused.json"), ["--version"]))
            {
                // --version must exit even while the caller still holds its input pipe open.
                await early.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
                Require(early.ExitCode == 17, "version exit before stdin EOF");
            }

            var waitScript = Path.Combine(directory, "wait.cmd");
            await File.WriteAllTextAsync(waitScript, "@echo off\r\n\"%CODEX_REMOTE_SCRIPT_TEST_EXE%\" --self-test-codex-script-worker %*\r\n", Encoding.ASCII);
            foreach (var killLauncherOnly in new[] { false, true })
            {
                var waitReport = Path.Combine(directory, $"processes-{killLauncherOnly}.json");
                using var waiting = Start(waitScript, waitReport, ["wait-for-stop"]);
                using var waitingJob = new WindowsJob();
                waitingJob.Assign(waiting);
                await waiting.StandardInput.WriteLineAsync("start");
                await waiting.StandardInput.FlushAsync();
                var deadline = Stopwatch.StartNew();
                while (!File.Exists(waitReport))
                {
                    if (deadline.Elapsed > TimeSpan.FromSeconds(10)) throw new TimeoutException("Script worker did not start.");
                    await Task.Delay(25);
                }
                var pids = JsonSerializer.Deserialize<int[]>(await File.ReadAllTextAsync(waitReport))!;
                using var worker = Process.GetProcessById(pids[0]);
                using var descendant = Process.GetProcessById(pids[1]);
                _ = worker.Handle;
                _ = descendant.Handle;
                if (killLauncherOnly) waiting.Kill(entireProcessTree: false);
                else await waitingJob.TerminateAndWaitAsync();
                await Task.WhenAll(waiting.WaitForExitAsync(), worker.WaitForExitAsync(), descendant.WaitForExitAsync())
                    .WaitAsync(TimeSpan.FromSeconds(10));
            }
        }
        finally { Directory.Delete(directory, recursive: true); }
    }

    internal static int RunWorker(string[] args)
    {
        var report = Environment.GetEnvironmentVariable("CODEX_REMOTE_SCRIPT_TEST_REPORT")!;
        if (args.SequenceEqual(new[] { "wait-for-stop" }))
        {
            using var input = new StreamReader(Console.OpenStandardInput());
            if (input.ReadLine() != "start") return 2;
            var start = new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = false, CreateNoWindow = true };
            start.ArgumentList.Add("--self-test-worker");
            start.ArgumentList.Add("leaf");
            using var child = Process.Start(start)!;
            File.WriteAllText(report + ".tmp", JsonSerializer.Serialize(new[] { Environment.ProcessId, child.Id }));
            File.Move(report + ".tmp", report);
            Thread.Sleep(Timeout.Infinite);
        }
        File.WriteAllText(report, JsonSerializer.Serialize(args));
        using var stdin = Console.OpenStandardInput();
        using var stdout = Console.OpenStandardOutput();
        using var stderr = Console.OpenStandardError();
        stdin.CopyTo(stdout);
        stderr.Write(new byte[] { 0, 255, 128, 13, 10 });
        return 29;
    }

    private static Process Start(string script, string report, string[] args)
    {
        var start = new ProcessStartInfo(Environment.ProcessPath!)
        {
            WorkingDirectory = Path.GetDirectoryName(script)!,
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        start.Environment["CODEX_REMOTE_CODEX_SCRIPT"] = script;
        start.Environment["CODEX_REMOTE_SCRIPT_TEST_EXE"] = Environment.ProcessPath!;
        start.Environment["CODEX_REMOTE_SCRIPT_TEST_REPORT"] = report;
        foreach (var argument in args) start.ArgumentList.Add(argument);
        return Process.Start(start) ?? throw new InvalidOperationException("Unable to start script self-test.");
    }

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }
}

using System.Diagnostics;
using System.Text;

namespace CodexRemote.Windows;

// Presents a real EXE to the unchanged official extension when the selected CLI is a CMD/BAT shim.
internal static class CodexScriptLauncher
{
    private const string MetaCharacters = "()[]%!^\"`<>&|;, *?";
    // Keep this handle rooted until process teardown: this job owns the launcher itself.
    // Closing it on normal return would terminate the launcher before Main returns its exit code.
    private static WindowsJob? processLifetimeJob;

    internal static async Task<int> Run(string[] args)
    {
        try
        {
            var script = Environment.GetEnvironmentVariable("CODEX_REMOTE_CODEX_SCRIPT") ?? "";
            var extension = Path.GetExtension(script);
            if (!Path.IsPathFullyQualified(script) || !File.Exists(script)
                || !new[] { ".bat", ".cmd" }.Contains(extension, StringComparer.OrdinalIgnoreCase)
                || script.IndexOfAny(['\0', '\r', '\n', '"']) >= 0)
                throw new InvalidOperationException("Codex 脚本路径无效，请重新选择 BAT 或 CMD 文件。");
            if (args.Any(argument => argument.IndexOfAny(['\0', '\r', '\n']) >= 0))
                throw new InvalidOperationException("Codex 脚本参数包含不支持的控制字符。");

            // Raw Arguments is intentional: ArgumentList would apply CRT quoting to cmd's shell syntax.
            // Escape each shell parse, including the common shim's second %* expansion into its EXE call.
            var command = EscapeMeta(script) + string.Concat(args.Select(argument => " " + EscapeMeta(EscapeMeta(QuoteArgument(argument)))));
            if (command.Length > 8000)
                throw new InvalidOperationException("Codex 脚本参数超过 Windows 命令解释器长度限制，请改用 EXE。");
            var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "cmd.exe"))
            {
                Arguments = "/d /s /v:off /c \"" + command + "\"",
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
            };
            processLifetimeJob = new WindowsJob();
            using (var owner = Process.GetCurrentProcess()) processLifetimeJob.Assign(owner);
            // Children inherit the job from birth, including when only our PID is killed by Node.
            using var child = Process.Start(start) ?? throw new InvalidOperationException("无法启动 Codex 脚本。");
            using var output = Console.OpenStandardOutput();
            using var errors = Console.OpenStandardError();
            var stdout = child.StandardOutput.BaseStream.CopyToAsync(output);
            var stderr = child.StandardError.BaseStream.CopyToAsync(errors);
            // A CLI may exit before its caller closes stdin (--version); do not await that read on exit.
            _ = ForwardInput(child);
            await child.WaitForExitAsync();
            await Task.WhenAll(stdout, stderr);
            return child.ExitCode;
        }
        catch (Exception error)
        {
            using var errors = new StreamWriter(Console.OpenStandardError(), new UTF8Encoding(false)) { AutoFlush = true };
            await errors.WriteLineAsync($"Codex 脚本启动失败：{error.Message}");
            return 1;
        }
    }

    private static async Task ForwardInput(Process child)
    {
        try
        {
            using var input = Console.OpenStandardInput();
            await input.CopyToAsync(child.StandardInput.BaseStream);
            child.StandardInput.Close();
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or ObjectDisposedException) { }
    }

    private static string QuoteArgument(string argument)
    {
        var result = new StringBuilder("\"");
        var backslashes = 0;
        foreach (var character in argument)
        {
            if (character == '\\') { backslashes++; continue; }
            result.Append('\\', character == '"' ? backslashes * 2 + 1 : backslashes);
            result.Append(character);
            backslashes = 0;
        }
        return result.Append('\\', backslashes * 2).Append('"').ToString();
    }

    private static string EscapeMeta(string text)
    {
        var result = new StringBuilder();
        foreach (var character in text)
        {
            if (MetaCharacters.Contains(character)) result.Append('^');
            result.Append(character);
        }
        return result.ToString();
    }
}

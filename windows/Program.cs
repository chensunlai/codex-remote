using System.IO.Pipes;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;

namespace CodexRemote.Windows;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--watch-host" && int.TryParse(args[1], out var pid))
            return HostWatcher.Run(pid).GetAwaiter().GetResult();
        if (args.SequenceEqual(new[] { "--self-test" }))
            return SelfTest.Run().GetAwaiter().GetResult();
        if (args.Length == 2 && args[0] == "--self-test-worker")
            return SelfTest.RunWorker(args[1]);
        if (args.Length >= 1 && args[0] == "--self-test-codex-script-worker")
            return CodexScriptLauncherSelfTest.RunWorker(args[1..]);

        // Set only on Codex child invocations. Supervisor/control modes above always take priority.
        if (!string.IsNullOrEmpty(Environment.GetEnvironmentVariable("CODEX_REMOTE_CODEX_SCRIPT")))
            return CodexScriptLauncher.Run(args).GetAwaiter().GetResult();

        ApplicationConfiguration.Initialize();
        var user = WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        var identity = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(user)))[..24];
        var pipeName = $"CodexRemote-{identity}";
        using var mutex = new Mutex(initiallyOwned: true, $@"Local\{pipeName}", out var first);
        if (!first)
        {
            try
            {
                using var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.Out);
                pipe.Connect(3000);
                using var writer = new StreamWriter(pipe) { AutoFlush = true };
                writer.WriteLine("show");
            }
            catch (Exception error) when (error is TimeoutException or IOException)
            {
                MessageBox.Show("Codex Remote 已在运行，请从右下角托盘打开。", "Codex Remote");
            }
            return 0;
        }

        using var cancellation = new CancellationTokenSource();
        // Inno Setup checks this mutex before replacing or uninstalling program files.
        using var setupMutex = new Mutex(false, @"Local\CodexRemote.Setup");
        try
        {
            using var application = new TrayApplicationContext(args.Contains("--autostart", StringComparer.Ordinal));
            _ = ListenForActivation(pipeName, application.ShowSettings, cancellation.Token);
            Application.Run(application);
            return 0;
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "Codex Remote 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        finally
        {
            cancellation.Cancel();
            mutex.ReleaseMutex();
        }
    }

    private static async Task ListenForActivation(string name, Action activate, CancellationToken cancellation)
    {
        while (!cancellation.IsCancellationRequested)
        {
            try
            {
                using var pipe = new NamedPipeServerStream(name, PipeDirection.In, 1,
                    PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await pipe.WaitForConnectionAsync(cancellation);
                using var reader = new StreamReader(pipe);
                if (await reader.ReadLineAsync(cancellation) == "show") activate();
            }
            catch (OperationCanceledException) { return; }
            catch (IOException) { }
        }
    }
}

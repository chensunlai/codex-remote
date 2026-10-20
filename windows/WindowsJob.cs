using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace CodexRemote.Windows;

// Each owner holds the only job handle. Closing it kills only its assigned process tree.
internal sealed class WindowsJob : IDisposable
{
    private readonly SafeFileHandle handle;

    public WindowsJob()
    {
        handle = CreateJobObject(IntPtr.Zero, null);
        if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
        var information = new ExtendedLimitInformation
        {
            BasicLimitInformation = new BasicLimitInformation { LimitFlags = 0x00002000 }
        };
        if (!SetInformationJobObject(handle, 9, ref information, (uint)Marshal.SizeOf<ExtendedLimitInformation>()))
        {
            var error = Marshal.GetLastWin32Error();
            handle.Dispose();
            throw new Win32Exception(error);
        }
    }

    public void Assign(Process process)
    {
        if (!AssignProcessToJobObject(handle, process.Handle))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "无法托管 Agent 进程树。");
    }

    public void Dispose() => handle.Dispose();

    public async Task TerminateAndWaitAsync()
    {
        if (handle.IsClosed) return;
        if (!TerminateJobObject(handle, 1)) throw new Win32Exception(Marshal.GetLastWin32Error());
        var elapsed = Stopwatch.StartNew();
        while (true)
        {
            if (!QueryInformationJobObject(handle, 1, out var information,
                (uint)Marshal.SizeOf<BasicAccountingInformation>(), IntPtr.Zero))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            if (information.ActiveProcesses == 0) return;
            if (elapsed.Elapsed > TimeSpan.FromSeconds(5))
                throw new TimeoutException("进程树未及时退出。");
            await Task.Delay(25);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicAccountingInformation
    {
        public long TotalUserTime;
        public long TotalKernelTime;
        public long ThisPeriodTotalUserTime;
        public long ThisPeriodTotalKernelTime;
        public uint TotalPageFaultCount;
        public uint TotalProcesses;
        public uint ActiveProcesses;
        public uint TotalTerminatedProcesses;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimitInformation
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimitInformation
    {
        public BasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateJobObject(IntPtr attributes, string? name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(
        SafeFileHandle job, int informationClass, ref ExtendedLimitInformation information, uint length);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(SafeFileHandle job, IntPtr process);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool TerminateJobObject(SafeFileHandle job, uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryInformationJobObject(SafeFileHandle job, int informationClass,
        out BasicAccountingInformation information, uint length, IntPtr returnLength);
}

internal static class HostWatcher
{
    public static async Task<int> Run(int processId)
    {
        using var output = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
        try
        {
            using var host = Process.GetProcessById(processId);
            using (var job = new WindowsJob())
            {
                job.Assign(host);
                await output.WriteLineAsync("{\"type\":\"ready\"}");
                await Task.WhenAny(host.WaitForExitAsync(), WaitForOwnerExit());
                await job.TerminateAndWaitAsync();
            }
            // Replacement hosts may start only after all processes in this host's job have exited.
            await output.WriteLineAsync("{\"type\":\"stopped\"}");
            return 0;
        }
        catch (Exception error)
        {
            await output.WriteLineAsync(System.Text.Json.JsonSerializer.Serialize(new
            {
                type = "error", message = error.Message
            }));
            return 1;
        }
    }

    private static async Task WaitForOwnerExit()
    {
        using var input = new StreamReader(Console.OpenStandardInput());
        while (await input.ReadLineAsync() is { } line)
            if (line == "stop") return;
    }
}

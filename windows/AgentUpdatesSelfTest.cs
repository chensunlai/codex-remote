using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CodexRemote.Windows;

internal static class AgentUpdatesSelfTest
{
    public static async Task Run()
    {
        const string baseUrl = "https://github.com/chensunlai/codex-remote/releases/download/v2.5.0/";
        var json = JsonSerializer.Serialize(new
        {
            tag_name = "v2.5.0", draft = false, prerelease = false,
            assets = new[] {
                new { name = AgentUpdates.InstallerName, browser_download_url = baseUrl + AgentUpdates.InstallerName, size = 10 },
                new { name = "SHA256SUMS", browser_download_url = baseUrl + "SHA256SUMS", size = 100 },
            }
        });
        var release = AgentUpdates.ParseRelease(json, new Version(2, 4, 0, 0))
            ?? throw new Exception("Newer version was ignored");
        Require(AgentUpdates.ParseRelease(json, new Version(2, 5, 0, 0)) is null, "equal version");
        Require(AgentUpdates.ParseRelease(json, new Version(2, 6, 0, 0)) is null, "no downgrade");
        Reject(() => AgentUpdates.ParseRelease(json.Replace("https://github.com/", "https://example.com/"), new Version(2, 4, 0)));
        Reject(() => AgentUpdates.ParseRelease(json.Replace(AgentUpdates.InstallerName, "other.exe"), new Version(2, 4, 0)));
        Reject(() => AgentUpdates.ReadChecksum("not-a-hash  " + AgentUpdates.InstallerName));

        var directory = Path.Combine(Path.GetTempPath(), "更新 测试-" + Guid.NewGuid());
        var bytes = Encoding.UTF8.GetBytes("fixture 安装程序");
        var checksum = Convert.ToHexString(SHA256.HashData(bytes));
        using var handler = new FixtureHandler(bytes, $"{checksum}  {AgentUpdates.InstallerName}\n");
        using var http = new HttpClient(handler);
        release = release with { Size = bytes.Length };
        try
        {
            var progress = new InlineProgress();
            var path = await AgentUpdates.DownloadAsync(release, progress, CancellationToken.None, http, directory);
            Require(File.ReadAllBytes(path).SequenceEqual(bytes), "verified content");
            Require(progress.Bytes == bytes.Length, "download progress");
            foreach (var size in new[] { bytes.Length - 1, bytes.Length + 1 })
            {
                await RejectAsync(() => AgentUpdates.DownloadAsync(release with { Size = size }, progress, CancellationToken.None, http, directory));
                Require(File.ReadAllBytes(path).SequenceEqual(bytes), "failed download preserves previous installer");
                Require(!File.Exists(path + ".part"), "failed download removes temporary file");
            }
            handler.Checksums = new string('0', 64) + "  " + AgentUpdates.InstallerName;
            await RejectAsync(() => AgentUpdates.DownloadAsync(release, progress, CancellationToken.None, http, directory));
            Require(File.ReadAllBytes(path).SequenceEqual(bytes), "bad hash must not replace verified installer");
            Require(!File.Exists(path + ".part"), "bad hash cleanup");
            using var cancelled = new CancellationTokenSource();
            cancelled.Cancel();
            try
            {
                await AgentUpdates.DownloadAsync(release, progress, cancelled.Token, http, directory);
                throw new Exception("Cancellation was ignored");
            }
            catch (OperationCanceledException) { }
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true); }
    }

    private static void Require(bool value, string message) { if (!value) throw new Exception(message); }
    private static void Reject(Action action)
    {
        try { action(); }
        catch (InvalidOperationException) { return; }
        throw new Exception("Invalid release was accepted");
    }
    private static async Task RejectAsync(Func<Task<string>> action)
    {
        try { await action(); }
        catch (InvalidOperationException) { return; }
        throw new Exception("Invalid installer was accepted");
    }
    private sealed class InlineProgress : IProgress<long>
    {
        public long Bytes;
        public void Report(long value) => Bytes = value;
    }
    private sealed class FixtureHandler(byte[] bytes, string checksums) : HttpMessageHandler
    {
        public string Checksums = checksums;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellation)
        {
            cancellation.ThrowIfCancellationRequested();
            HttpContent content = request.RequestUri!.AbsolutePath.EndsWith("SHA256SUMS")
                ? new StringContent(Checksums) : new ByteArrayContent(bytes);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }
}

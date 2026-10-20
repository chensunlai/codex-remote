using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;

namespace CodexRemote.Windows;

internal sealed record AgentRelease(Version Version, Uri Installer, Uri Checksums, long Size);

internal static class AgentUpdates
{
    internal const string InstallerName = "codex-remote-windows-x64-setup.exe";
    internal static readonly Uri LatestRelease = new("https://api.github.com/repos/chensunlai/codex-remote/releases/latest");
    public static Version CurrentVersion => typeof(AgentUpdates).Assembly.GetName().Version ?? new Version(0, 0, 0);
    public static string DisplayVersion => CurrentVersion.ToString(3);
    private static readonly HttpClient client = CreateClient();

    private static HttpClient CreateClient()
    {
        var result = new HttpClient { Timeout = TimeSpan.FromMinutes(15) };
        result.DefaultRequestHeaders.UserAgent.ParseAdd("CodexRemote-Windows/" + DisplayVersion);
        return result;
    }

    public static async Task<AgentRelease?> CheckAsync(CancellationToken cancellation)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        var json = await client.GetStringAsync(LatestRelease, timeout.Token);
        return ParseRelease(json, CurrentVersion);
    }

    internal static AgentRelease? ParseRelease(string json, Version current)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        if (root.GetProperty("draft").GetBoolean() || root.GetProperty("prerelease").GetBoolean())
            throw new InvalidOperationException("未找到可用的正式版本。");
        var tag = root.GetProperty("tag_name").GetString() ?? "";
        if (!Version.TryParse(tag.TrimStart('v'), out var version))
            throw new InvalidOperationException("发布版本号无效。");
        if (new Version(version.Major, version.Minor, Math.Max(0, version.Build)) <=
            new Version(current.Major, current.Minor, Math.Max(0, current.Build))) return null;
        var assets = root.GetProperty("assets").EnumerateArray().ToArray();
        var installer = assets.FirstOrDefault(item => item.GetProperty("name").GetString() == InstallerName);
        var checksums = assets.FirstOrDefault(item => item.GetProperty("name").GetString() == "SHA256SUMS");
        if (installer.ValueKind == JsonValueKind.Undefined || checksums.ValueKind == JsonValueKind.Undefined)
            throw new InvalidOperationException("新版本尚未提供 Windows 安装包，请稍后重试。");
        var size = installer.GetProperty("size").GetInt64();
        if (size <= 0 || size > 512L * 1024 * 1024)
            throw new InvalidOperationException("安装包大小无效。");
        return new AgentRelease(version, AssetUri(installer, tag, InstallerName), AssetUri(checksums, tag, "SHA256SUMS"), size);
    }

    private static Uri AssetUri(JsonElement asset, string tag, string name)
    {
        var uri = new Uri(asset.GetProperty("browser_download_url").GetString()!);
        var expected = $"/chensunlai/codex-remote/releases/download/{tag}/{name}";
        if (uri.Scheme != "https" || uri.Host != "github.com" || uri.UserInfo.Length != 0
            || Uri.UnescapeDataString(uri.AbsolutePath) != expected || uri.Query.Length != 0 || uri.Fragment.Length != 0)
            throw new InvalidOperationException("发布文件地址无效。");
        return uri;
    }

    internal static string ReadChecksum(string manifest)
    {
        var matches = manifest.Split('\n').Select(line => line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries))
            .Where(parts => parts.Length == 2 && parts[1].TrimStart('*') == InstallerName).ToArray();
        if (matches.Length != 1 || matches[0][0].Length != 64 || !matches[0][0].All(Uri.IsHexDigit))
            throw new InvalidOperationException("发布文件缺少有效的 SHA-256 校验值。");
        return matches[0][0];
    }

    public static Task<string> DownloadAsync(AgentRelease release, IProgress<long> progress, CancellationToken cancellation) =>
        DownloadAsync(release, progress, cancellation, client, Path.Combine(AgentSettings.DataDirectory, "updates"));

    internal static async Task<string> DownloadAsync(AgentRelease release, IProgress<long> progress, CancellationToken cancellation,
        HttpClient http, string directory)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromMinutes(15));
        cancellation = timeout.Token;
        var checksum = ReadChecksum(await http.GetStringAsync(release.Checksums, cancellation));
        Directory.CreateDirectory(directory);
        var target = Path.Combine(directory, InstallerName);
        var temporary = target + ".part";
        try
        {
            using var response = await http.GetAsync(release.Installer, HttpCompletionOption.ResponseHeadersRead, cancellation);
            response.EnsureSuccessStatusCode();
            await using var input = await response.Content.ReadAsStreamAsync(cancellation);
            await using (var output = File.Create(temporary))
            {
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                var buffer = new byte[128 * 1024];
                long received = 0;
                int count;
                while ((count = await input.ReadAsync(buffer, cancellation)) > 0)
                {
                    received += count;
                    if (received > release.Size) throw new InvalidOperationException("安装包大小与发布信息不符。");
                    hash.AppendData(buffer, 0, count);
                    await output.WriteAsync(buffer.AsMemory(0, count), cancellation);
                    progress.Report(received);
                }
                if (received != release.Size || !Convert.ToHexString(hash.GetHashAndReset()).Equals(checksum, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("安装包校验失败，请重新下载。");
            }
            File.Move(temporary, target, overwrite: true);
            return target;
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}

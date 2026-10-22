using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Win32;

namespace CodexRemote.Windows;

internal sealed record AgentSettings(string GatewayUrl, string Token, string Name, string CodexExecutable)
{
    public static string DataDirectory => Path.Combine(AppContext.BaseDirectory, "data");
    public static string ConfigPath => Path.Combine(DataDirectory, "agent.json");

    public static void ImportLegacyConfig()
    {
        var legacy = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CodexRemote", "agent.json");
        if (File.Exists(ConfigPath) || !File.Exists(legacy)) return;
        // Preserve the existing service ID and token without rewriting or deleting the old file.
        ReadDocument(legacy);
        Directory.CreateDirectory(DataDirectory);
        File.Copy(legacy, ConfigPath, overwrite: false);
    }

    public static AgentSettings Load(string path)
    {
        var document = ReadDocument(path);
        return new AgentSettings(
            document["gatewayUrls"] is JsonArray endpoints
                ? string.Join(", ", endpoints.Select(value => value?.GetValue<string>() ?? ""))
                : document["gatewayUrl"]?.GetValue<string>() ?? "",
            document["token"]?.GetValue<string>() ?? "",
            document["name"]?.GetValue<string>() ?? Environment.MachineName,
            document["codexExecutable"]?.GetValue<string>() ?? "");
    }

    public AgentSettings Validate()
    {
        var endpoints = NormalizeGatewayUrls(GatewayUrl);
        if (Token.Trim().Length == 0) throw new InvalidOperationException("请填写访问令牌。");
        if (Name.Trim().Length == 0) throw new InvalidOperationException("请填写服务器名称。");
        var executable = ResolveCodexExecutable(CodexExecutable);
        return this with
        {
            GatewayUrl = string.Join(", ", endpoints), Token = Token.Trim(),
            Name = Name.Trim(), CodexExecutable = executable
        };
    }

    public static string[] NormalizeGatewayUrls(string value)
    {
        return value.Split(new[] { ',', '\n' }).Select(entry =>
        {
            var input = entry.Trim();
            if (input.Length == 0) throw new InvalidOperationException("Gateway 地址不能为空。");
            if (!input.Contains("://", StringComparison.Ordinal)) input = "https://" + input;
            if (!Uri.TryCreate(input, UriKind.Absolute, out var url)
                || (url.Scheme != "https" && url.Scheme != "http")
                || url.UserInfo.Length != 0 || url.Query.Length != 0 || url.Fragment.Length != 0)
                throw new InvalidOperationException("Gateway 地址仅支持 HTTP 或 HTTPS，不能包含账号、查询参数或片段。");
            return url.AbsoluteUri.TrimEnd('/');
        }).Distinct(StringComparer.Ordinal).ToArray();
    }

    public static string ResolveCodexExecutable(string input, IReadOnlyList<string>? candidates = null)
    {
        var executable = ExpandExecutablePath(input);
        if (executable.Length == 0 || new[] { "codex", "codex.exe", "codex.cmd", "codex.bat" }
            .Contains(executable, StringComparer.OrdinalIgnoreCase))
        {
            candidates ??= DiscoverCodexExecutables();
            if (candidates.Count == 0)
                throw new InvalidOperationException("未找到 Codex。请在 PATH 或常见安装目录中安装，或选择、输入 EXE、CMD 或 BAT 文件的完整路径。");
            if (candidates.Count > 1)
                throw new InvalidOperationException("检测到多个 Codex，请从列表中选择一个，或输入其他 EXE、CMD 或 BAT 文件的完整路径。");
            executable = candidates[0];
        }
        if (!Path.IsPathFullyQualified(executable)
            || !new[] { ".exe", ".cmd", ".bat" }.Contains(Path.GetExtension(executable), StringComparer.OrdinalIgnoreCase)
            || !File.Exists(executable))
            throw new InvalidOperationException("Codex 路径无效。请选择存在的 EXE、CMD 或 BAT 文件，或输入其完整路径。");
        return Path.GetFullPath(executable);
    }

    public static IReadOnlyList<string> DiscoverCodexExecutables(string? searchPath = null, IEnumerable<string>? defaultDirectories = null)
    {
        var candidates = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var directories = (searchPath ?? Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator)
            .Concat(defaultDirectories ?? DefaultCodexDirectories());
        foreach (var entry in directories)
        {
            var directory = ExpandExecutablePath(entry);
            if (!Path.IsPathFullyQualified(directory)) continue;
            foreach (var file in new[] { "codex.exe", "codex.cmd", "codex.bat" })
            {
                try
                {
                    var candidate = Path.GetFullPath(Path.Combine(directory, file));
                    if (File.Exists(candidate) && seen.Add(candidate)) candidates.Add(candidate);
                }
                catch (ArgumentException) { }
                catch (NotSupportedException) { }
            }
        }
        return candidates;
    }

    internal static string ExpandExecutablePath(string value) =>
        Environment.ExpandEnvironmentVariables(value.Trim().Trim('"'));

    private static IEnumerable<string> DefaultCodexDirectories()
    {
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs", "OpenAI", "Codex", "bin");
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "npm");
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "nodejs");
    }

    public void Save(string path)
    {
        var settings = Validate();
        var document = ReadDocument(path);
        document["version"] = 1;
        var endpoints = NormalizeGatewayUrls(settings.GatewayUrl);
        document["gatewayUrl"] = endpoints[0];
        document["gatewayUrls"] = new JsonArray(endpoints.Select(value => (JsonNode?)JsonValue.Create(value)).ToArray());
        document["token"] = settings.Token;
        document["name"] = settings.Name;
        document["codexExecutable"] = settings.CodexExecutable;
        if (string.IsNullOrWhiteSpace(document["serviceId"]?.GetValue<string>()))
            document["serviceId"] = Guid.NewGuid().ToString();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + ".tmp";
        File.WriteAllText(temporary, document.ToJsonString(new JsonSerializerOptions { WriteIndented = true }) + "\n");
        File.Move(temporary, path, overwrite: true);
    }

    private static JsonObject ReadDocument(string path)
    {
        if (!File.Exists(path)) return new JsonObject();
        var document = JsonNode.Parse(File.ReadAllText(path)) as JsonObject
            ?? throw new InvalidOperationException("Agent 配置必须是 JSON 对象。");
        if (document["version"]?.GetValue<int>() != 1)
            throw new InvalidOperationException("不支持此 Agent 配置版本。请检查 agent.json。");
        return document;
    }
}

internal static class LoginStartup
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "CodexRemote";
    public static bool Enabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
    }

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue(ValueName, $"\"{Environment.ProcessPath}\" --autostart");
        else key.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}

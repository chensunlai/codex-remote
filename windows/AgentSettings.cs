using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Win32;

namespace CodexRemote.Windows;

internal sealed record AgentSettings(string GatewayUrl, string Token, string Name, string CodexExecutable)
{
    public static string DataDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CodexRemote");
    public static string ConfigPath => Path.Combine(DataDirectory, "agent.json");
    public static string DefaultCodexPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Programs", "OpenAI", "Codex", "bin", "codex.exe");

    public static AgentSettings Load(string path)
    {
        var document = ReadDocument(path);
        return new AgentSettings(
            document["gatewayUrl"]?.GetValue<string>() ?? "",
            document["token"]?.GetValue<string>() ?? "",
            document["name"]?.GetValue<string>() ?? Environment.MachineName,
            document["codexExecutable"]?.GetValue<string>() ?? DefaultCodexPath);
    }

    public AgentSettings Validate()
    {
        var input = GatewayUrl.Trim();
        if (!input.Contains("://", StringComparison.Ordinal)) input = "https://" + input;
        if (!Uri.TryCreate(input, UriKind.Absolute, out var url)
            || (url.Scheme != "https" && url.Scheme != "http")
            || url.UserInfo.Length != 0 || url.Query.Length != 0 || url.Fragment.Length != 0)
            throw new InvalidOperationException("Gateway 地址仅支持 HTTP 或 HTTPS，不能包含账号、查询参数或片段。");
        if (Token.Trim().Length == 0) throw new InvalidOperationException("请填写访问令牌。");
        if (Name.Trim().Length == 0) throw new InvalidOperationException("请填写服务器名称。");
        var executable = CodexExecutable.Trim();
        if (!Path.IsPathFullyQualified(executable)
            || !string.Equals(Path.GetExtension(executable), ".exe", StringComparison.OrdinalIgnoreCase)
            || !File.Exists(executable))
            throw new InvalidOperationException("请选择已安装的 Codex EXE 文件；不支持 CMD 或 BAT 入口。");
        return this with
        {
            GatewayUrl = url.AbsoluteUri.TrimEnd('/'), Token = Token.Trim(),
            Name = Name.Trim(), CodexExecutable = Path.GetFullPath(executable)
        };
    }

    public void Save(string path)
    {
        var settings = Validate();
        var document = ReadDocument(path);
        document["version"] = 1;
        document["gatewayUrl"] = settings.GatewayUrl;
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

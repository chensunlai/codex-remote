# Windows Agent

Windows x64 首版使用原生 Codex，不依赖 WSL。Gateway 与 Linux 共用同一套服务，手机端需要支持 Windows 的新版 App。

## 准备 Codex

使用 [OpenAI 官方独立版安装器](https://learn.chatgpt.com/docs/enterprise/windows-deployment) 按当前 Windows 用户安装 Codex，先完成登录和首次运行。默认命令目录：

```text
%LOCALAPPDATA%\Programs\OpenAI\Codex\bin\codex.exe
```

保留官方安装器生成的完整运行文件。Agent 不会复制 Codex，也不会读取其内部安装布局；如果自定义了安装目录，在配置中选择实际的 `codex.exe`。首版不接受 npm 的 `.cmd`、`.bat` 包装入口。

## 启动与配置

1. 下载 `codex-remote-windows-x64.zip`，完整解压到固定位置。
2. 双击 `CodexRemote.exe`，填写 Gateway 地址、Token 和服务器名称。
3. 确认检测到的 Codex 路径，保存后启动 Agent。
4. 窗口关闭后收起到右下角托盘；在手机 App 中选择这台服务器。

ZIP 已包含 Node.js 和 .NET 运行环境，无需额外安装。连接配置、日志、官方插件缓存保存在 `%LOCALAPPDATA%\CodexRemote`，升级 ZIP 时保留该目录即可延续服务 ID 和配置。

托盘可修改配置、启动或停止 Agent、查看连接状态、打开日志目录，并选择“登录后自动启动”。自启动默认关闭，使用当前用户登录后的身份，不安装系统服务。改变配置需要停止当前 Agent；停止或退出会中断它正在执行的任务。

## 手机使用

支持聊天、选择 Windows 工作目录、文件上传、预览和下载。Windows 服务器的“终端”保留在底部导航并置灰；切回 Linux 服务器后恢复可用。Windows 不提供 Agent 或 Codex 的自动更新；停止 Agent 后替换完整 ZIP，Codex 由用户通过原安装方式更新。官方插件仍可在 App 中检查、下载并确认应用更新。

运行期间网络中断不会主动结束已接受的任务；网络恢复后沿用现有重连逻辑。注销、关机、睡眠或停止所属进程会影响运行，睡眠期间无法继续执行。

## 验证边界

分支自动测试验证 Windows 路径、进程托管、Node IPC、插件激活和传输链路。托盘交互、自启动、睡眠唤醒，以及与真实 Windows VS Code 同看活跃会话仍需要在实际 Windows 桌面验收；未完成验收前不宣称同会话同步已验证。首版不混用 WSL 中的登录状态、路径或会话。

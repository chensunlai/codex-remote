# Windows Agent

Windows x64 首版使用原生 Codex，不依赖 WSL。Gateway 与 Linux 共用同一套服务，手机端需要支持 Windows 的新版 App。

## 准备 Codex

通过 [OpenAI 官方独立版安装器](https://learn.chatgpt.com/docs/enterprise/windows-deployment) 或其他官方支持的安装方式准备 Codex，先完成登录和首次运行。保留安装方式生成的完整运行文件。Agent 不会复制 Codex，也不会读取 VS Code 或其他软件的内部安装布局。

配置窗口依次扫描当前进程的 PATH，以及几个常见命令目录：`%LOCALAPPDATA%\Programs\OpenAI\Codex\bin`、`%APPDATA%\npm`、`%ProgramFiles%\nodejs`，查找 `codex.exe`、`codex.cmd` 和 `codex.bat` 并去重。只找到一个时自动填入；找到多个时需要从列表选择一个，也可以输入其他启动文件的完整路径；没有找到时要求手动选择或输入。已保存的手动路径始终优先，失效时提示修正，不静默换用其他安装。保存后记录所选文件的绝对路径。

EXE 直接运行；CMD、BAT 通过系统命令解释器启动。自定义脚本必须将参数、标准输入输出和退出码原样交给 Codex，不能向 App Server 的标准输出写入欢迎文字等额外内容。

如果在托盘程序运行期间修改了 PATH，请退出并重新启动托盘程序，使其取得新的环境变量。

## 启动与配置

1. 运行 `codex-remote-windows-x64-setup.exe`，按向导安装。默认目录为 `C:\Program Files\CodexRemote`，可修改；安装时需要管理员授权。也可下载 `codex-remote-windows-x64.zip`，完整解压到自己有写入权限的固定位置。
2. 双击 `CodexRemote.exe`，填写 Gateway 地址、Token 和服务器名称。
3. 选择检测到的 Codex，或浏览、输入 EXE、CMD、BAT 的完整路径，保存后启动 Agent。
4. 窗口关闭后收起到右下角托盘；在手机 App 中选择这台服务器。

安装包和 ZIP 均包含 Node.js 和 .NET 运行环境，无需额外安装。连接配置为安装目录下的 `data\agent.json`，日志、官方插件缓存及更新下载也在 `data` 内。安装器为启动安装的用户设置此数据目录的写入权限，程序本身无需以管理员身份运行。

升级安装保留 `data`，卸载也保留配置和数据；不再需要时可自行删除。旧版 `%LOCALAPPDATA%\CodexRemote\agent.json` 会在首次启动且本地没有配置时复制到新目录，保留服务 ID 和令牌，原文件不删除；旧插件缓存不会搬运。

托盘可修改配置、启动或停止 Agent、查看连接状态、打开日志目录，并选择“登录后自动启动”。自启动默认关闭，使用当前用户登录后的身份，不安装系统服务。改变配置需要停止当前 Agent；停止或退出会中断它正在执行的任务。

## 手机使用

支持聊天、选择 Windows 工作目录、文件上传、预览和下载。Windows 服务器的“终端”保留在底部导航并置灰；切回 Linux 服务器后恢复可用。

## 更新

在 Windows 设置窗口或托盘菜单点击“检查更新”，检查 GitHub 最新正式版本。找到新版后，点击“下载并安装”，可查看进度、已下载大小和速度；下载完成并通过 SHA-256 校验后，再确认退出 Agent，交给 Inno Setup 安装器更新原目录。下载失败或取消下载不会停止 Agent；安装会停止所属任务，取消安装后可重新启动原版本。

更新按钮只更新 Windows Agent。Codex CLI 由用户通过原安装方式更新；官方插件仍可在 App 中检查、下载并确认应用更新。便携版也可使用更新按钮，在当前目录安装并加入系统卸载列表；若需保持纯便携使用，退出程序后用新版 ZIP 覆盖程序文件，保留 `data`。

运行期间网络中断不会主动结束已接受的任务；网络恢复后沿用现有重连逻辑。注销、关机、睡眠或停止所属进程会影响运行，睡眠期间无法继续执行。

## 验证边界

分支自动测试验证 Windows 路径、进程托管、Node IPC、插件激活和传输链路。托盘交互、自启动、睡眠唤醒，以及与真实 Windows VS Code 同看活跃会话仍需要在实际 Windows 桌面验收；未完成验收前不宣称同会话同步已验证。首版不混用 WSL 中的登录状态、路径或会话。

# 从源码构建

[返回项目首页](../README.md)

以下命令从仓库根目录执行。

开发与打包环境使用 Node.js 24.18.0+：

```bash
npm ci
npm test
npm run typecheck
npm run build
npm run package:binaries
```

打包产物位于 `release/`。Agent 打包过程会下载并校验对应架构的 Node.js 运行时，输出独立的 `codex-remote-agent-runtime-linux-<架构>.tar.gz`；部署时需与 Agent 二进制一起安装。开发环境可用 `CODEX_REMOTE_NODE_PATH` 指定宿主 Node.js。

构建 Android Debug APK，需要 JDK 17 或更高版本，以及 Android SDK（当前编译目标为 API 37）：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

产物为 `android/app/build/outputs/apk/debug/app-debug.apk`。连接设备或模拟器后，可运行 `./gradlew :app:connectedDebugAndroidTest` 执行设备测试。

验证完整官方扩展与真实页面，需要安装 Playwright，也可用 `CODEX_PLAYWRIGHT_MODULE` 指定其入口：

```bash
npm run frontend:smoke -- --extension /path/to/openai.chatgpt-extension
```

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 原生 App |
| `server/` | Gateway |
| `agent/` | Agent、官方扩展宿主与服务器能力 |
| `extension-webview-host/` | 当前 WebView 桥接与主题适配 |
| `scripts/` | 打包、资源同步和验证脚本 |
| `frontend-host/` | 旧链路回归代码，不打包进当前 Android App |

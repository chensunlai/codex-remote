import { randomBytes } from "node:crypto";
import { constants } from "node:fs";
import { access, chmod, mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const signingDirectory = resolve(root, "signing");
const keystorePath = resolve(signingDirectory, "codex-remote-release.p12");
const propertiesPath = resolve(signingDirectory, "keystore.properties");
const alias = "codex-remote";
const distinguishedName = "CN=chensunlai";

for (const path of [keystorePath, propertiesPath]) {
  if (await exists(path)) {
    throw new Error(`签名文件已存在，未覆盖: ${path}`);
  }
}

await mkdir(signingDirectory, { recursive: true, mode: 0o700 });
await chmod(signingDirectory, 0o700);

const password = randomBytes(32).toString("base64url");
await run("keytool", [
  "-genkeypair",
  "-keystore", keystorePath,
  "-storetype", "PKCS12",
  "-storepass", password,
  "-keypass", password,
  "-alias", alias,
  "-keyalg", "RSA",
  "-keysize", "4096",
  "-sigalg", "SHA256withRSA",
  "-validity", "36500",
  "-dname", distinguishedName,
]);

await chmod(keystorePath, 0o600);
await writeFile(
  propertiesPath,
  [
    "storeFile=codex-remote-release.p12",
    `storePassword=${password}`,
    `keyAlias=${alias}`,
    `keyPassword=${password}`,
    "storeType=PKCS12",
    "",
  ].join("\n"),
  { mode: 0o600 },
);
await chmod(propertiesPath, 0o600);

process.stdout.write(
  [
    `Android 发布密钥已创建: ${keystorePath}`,
    `签名配置已创建: ${propertiesPath}`,
    `证书主体: ${distinguishedName}`,
    "请离线备份 signing 目录；丢失密钥后将不能为同一应用签署更新。",
    "",
  ].join("\n"),
);

async function exists(path) {
  return access(path, constants.F_OK).then(() => true, () => false);
}

function run(command, args) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { cwd: root, stdio: "inherit" });
    child.on("error", reject);
    child.on("exit", (code, signal) => {
      if (code === 0) resolvePromise();
      else reject(new Error(`${command} 退出: code=${String(code)}, signal=${String(signal)}`));
    });
  });
}

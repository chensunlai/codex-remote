import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, expect, test, vi } from "vitest";
import { GatewayRuntimeControl } from "./gateway-runtime-control.js";

const directories: string[] = [];
afterEach(async () => { await Promise.all(directories.splice(0).map((path) => rm(path, { recursive: true, force: true }))); });

async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), "gateway-runtime-test-"));
  directories.push(directory);
  const dataDirectory = join(directory, "data"), controlDirectory = join(directory, "control");
  const executable = join(directory, "codex-remote-gateway");
  await mkdir(dataDirectory);
  await mkdir(join(controlDirectory, "requests"), { recursive: true });
  const installation = { dataDirectory, executable, updateSupported: true };
  await writeFile(join(controlDirectory, "installation.json"), JSON.stringify(installation));
  const options = { controlDirectory, executable, currentVersion: "2.0.1", managedService: true, latestVersion: async () => "2.1.0" };
  return { dataDirectory, controlDirectory, installation, options, control: new GatewayRuntimeControl(dataDirectory, options) };
}

test("checks stable updates independently of remote maintenance support", async () => {
  const f = await fixture();
  const control = new GatewayRuntimeControl(f.dataDirectory, { ...f.options, managedService: false });
  expect(await control.status(true)).toMatchObject({ currentVersion: "2.0.1", latestVersion: "2.1.0", updateAvailable: true, updateSupported: false, restartSupported: false });
  await expect(control.prepare("restart", true)).rejects.toThrow("systemd");
  await writeFile(join(f.controlDirectory, "installation.json"), JSON.stringify({ ...f.installation, executable: "/another/gateway" }));
  expect(await f.control.status()).toMatchObject({ restartSupported: false });
});

test("requires confirmation and dispatches only after the response activation callback", async () => {
  const f = await fixture();
  expect((await f.control.prepare("update", false)).status.confirmationRequired).toBe(true);
  expect(await readdir(join(f.controlDirectory, "requests"))).toEqual([]);
  const prepared = await f.control.prepare("restart", true);
  expect(prepared.status.operation).toMatchObject({ state: "queued", type: "restart" });
  expect(await readdir(join(f.controlDirectory, "requests"))).toEqual([]);
  await prepared.activate();
  await prepared.activate();
  expect(await readdir(join(f.controlDirectory, "requests"))).toEqual([`restart-${prepared.status.operation!.id}`]);
  expect(await readFile(join(f.controlDirectory, "requests", `restart-${prepared.status.operation!.id}`), "utf8")).toBe("");
});

test("concurrent requests and reconstructed controllers cannot queue duplicate maintenance", async () => {
  const f = await fixture();
  const results = await Promise.allSettled([f.control.prepare("update", true), f.control.prepare("restart", true)]);
  expect(results.filter(({ status }) => status === "fulfilled")).toHaveLength(1);
  const restored = new GatewayRuntimeControl(f.dataDirectory, f.options);
  await expect(restored.prepare("restart", true)).rejects.toThrow("正在进行");
});

test("root helper result survives Gateway restart and allows a later operation", async () => {
  const f = await fixture();
  const prepared = await f.control.prepare("update", true);
  await prepared.activate();
  const result = { ...prepared.status.operation, state: "succeeded", output: "更新完成" };
  await writeFile(join(f.controlDirectory, "operation.json"), JSON.stringify(result));
  const restored = new GatewayRuntimeControl(f.dataDirectory, f.options);
  expect((await restored.status()).operation).toMatchObject(result);
  expect((await restored.prepare("restart", true)).status.operation!.id).not.toBe(result.id);
});

test("reports dispatch failures instead of leaving a permanently queued operation", async () => {
  const f = await fixture();
  const prepared = await f.control.prepare("restart", true);
  await rm(join(f.controlDirectory, "requests"), { recursive: true });
  await expect(prepared.activate()).rejects.toThrow();
  expect((await f.control.status()).operation).toMatchObject({ state: "failed", output: "无法启动维护操作" });
});

test("disabled maintenance path times out and removes its stale queue request", async () => {
  const f = await fixture();
  const prepared = await f.control.prepare("restart", true);
  await prepared.activate();
  await writeFile(join(f.dataDirectory, "gateway-runtime-operation.json"), JSON.stringify({ ...prepared.status.operation, createdAt: Date.now() - 40_000 }));
  expect((await f.control.status()).operation).toMatchObject({ state: "failed", output: "维护操作超时" });
  expect(await readdir(join(f.controlDirectory, "requests"))).toEqual([]);
});

test("custom repository installation permits restart but never replaces it with official binaries", async () => {
  const f = await fixture();
  await writeFile(join(f.controlDirectory, "installation.json"), JSON.stringify({ ...f.installation, updateSupported: false }));
  expect(await f.control.status()).toMatchObject({ updateSupported: false, restartSupported: true });
  await expect(f.control.prepare("update", true)).rejects.toThrow("自定义仓库");
  expect((await f.control.prepare("restart", true)).status.operation!.type).toBe("restart");
});

test("concurrent checks share one request and malformed release versions surface an error", async () => {
  const f = await fixture();
  let resolveVersion!: (value: string) => void;
  const latestVersion = vi.fn(() => new Promise<string>((resolve) => { resolveVersion = resolve; }));
  const control = new GatewayRuntimeControl(f.dataDirectory, { ...f.options, latestVersion });
  const checking = Promise.all([control.status(true), control.status(true)]);
  resolveVersion("2.2.0");
  expect(await checking).toMatchObject([{ latestVersion: "2.2.0" }, { latestVersion: "2.2.0" }]);
  expect(latestVersion).toHaveBeenCalledTimes(1);
  const invalid = new GatewayRuntimeControl(f.dataDirectory, { ...f.options, latestVersion: async () => "999999999999999999999.1.0" });
  expect(await invalid.status(true)).toMatchObject({ latestVersion: null, updateAvailable: null, checkError: expect.stringContaining("格式无效") });
});

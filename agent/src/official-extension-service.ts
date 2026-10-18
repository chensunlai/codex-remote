import { createHash, randomUUID } from "node:crypto";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdir, realpath, stat } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import { OfficialExtensionStore, type OfficialExtensionPackage } from "./official-extension-store.js";
import { resolveExtensionHostDirectory, resolveNodeExecutable } from "./node-runtime.js";

export interface ExtensionViewFrame {
  viewId: string;
  epoch: string;
  sequence: number;
  type: "html" | "message" | "host-request" | "status" | "surface";
  surfaceId?: string;
  payload: unknown;
}
export interface ExtensionSurface {
  id: string;
  title: string;
  kind: "panel";
  html: string;
}
interface SavedFrame { frame: ExtensionViewFrame; bytes: number }
interface NativeRequest { id: string; method: string; params: unknown; surfaceId: string }
interface ExtensionView {
  viewId: string;
  epoch: string;
  rendererId: string;
  attached: boolean;
  workspace: string;
  version: string;
  packageSha256: string;
  origin: string;
  html: string;
  surfaces: Map<string, ExtensionSurface>;
  sequence: number;
  dropped: number;
  frames: SavedFrame[];
  bytes: number;
  child: ChildProcess;
  started: Promise<void>;
  deliveries: Map<string, string>;
  pending: Map<string, NativeRequest>;
  processes: Map<number, string | null>;
  stopping: boolean;
}
const maxFramesBytes = 8 * 1024 * 1024;
const maxMessageBytes = 12 * 1024 * 1024;

/** Keeps the official extension alive independently of Gateway/mobile sockets. */
export class OfficialExtensionService {
  readonly store: OfficialExtensionStore;
  private readonly views = new Map<string, ExtensionView>();
  private closed = false;
  private changing = false;
  private transition: Promise<void> = Promise.resolve();

  constructor(
    private readonly directory: string,
    private readonly executable: string,
    private readonly emit: (frame: ExtensionViewFrame) => void,
    store?: OfficialExtensionStore,
  ) { this.store = store ?? new OfficialExtensionStore(join(directory, "packages")); }

  async request(method: string, params: Record<string, unknown>): Promise<unknown> {
    if (method === "extension.view.attach" || method === "extension.apply") {
      const operation = this.transition.then(() => this.handleRequest(method, params));
      this.transition = operation.then(() => {}, () => {});
      return operation;
    }
    return this.handleRequest(method, params);
  }

  private async handleRequest(method: string, params: Record<string, unknown>): Promise<unknown> {
    if (this.closed) throw new Error("官方扩展宿主已关闭");
    switch (method) {
      case "extension.status": return this.status();
      case "extension.update": await this.store.update(); return this.status();
      case "extension.apply": {
        const state = await this.store.status();
        if (!state.candidate) return { confirmationRequired: false, ...await this.status() };
        if (this.views.size && params.confirmed !== true) return { confirmationRequired: true, ...await this.status() };
        this.changing = true;
        try {
          // Exercise only the published activation/provider lifecycle before
          // replacing the current package or stopping any live conversation.
          await mkdir(this.directory, { recursive: true, mode: 0o700 });
          const probe = await this.open("verify-" + randomUUID(), "verification", this.directory, state.candidate);
          try { await probe.started; } finally { await this.stop(probe); }
          await this.store.activate(state.candidate.sha256);
          await this.stopAll("正在应用官方前端更新");
          return { confirmationRequired: false, ...await this.status() };
        } finally { this.changing = false; }
      }
      case "extension.resource": return this.store.resource(text(params.version, "version"), text(params.path, "path"), {
        ...(params.acceptEncoding === "gzip" ? { acceptEncoding: "gzip" } : {}),
        ...(params.packageSha256 === undefined ? {} : { packageSha256: text(params.packageSha256, "packageSha256") }),
      });
      case "extension.view.attach": return this.attach(params);
      case "extension.view.detach": {
        const view = this.owned(params);
        view.attached = false;
        await this.send(view, { type: "visibility", visible: false });
        return {};
      }
      case "extension.view.send": {
        const view = this.owned(params);
        if (params.epoch !== view.epoch) throw new Error("页面宿主已更新，请重新连接");
        const id = text(params.messageId, "messageId");
        const surfaceId = this.surface(view, params.surfaceId);
        const serialized = JSON.stringify(params.payload);
        if (serialized === undefined || Buffer.byteLength(serialized) > maxMessageBytes) throw new Error("页面消息过大或无效");
        const digest = createHash("sha256").update(surfaceId).update("\0").update(serialized).digest("hex");
        const previous = view.deliveries.get(id);
        if (previous !== undefined) {
          if (previous !== digest) throw new Error("重复消息的内容不同");
          return { accepted: true };
        }
        await this.send(view, { type: "message", surfaceId, payload: params.payload });
        view.deliveries.set(id, digest);
        while (view.deliveries.size > 1024) view.deliveries.delete(view.deliveries.keys().next().value!);
        return { accepted: true };
      }
      case "extension.view.surface.close": {
        const view = this.owned(params);
        const surfaceId = text(params.surfaceId, "surfaceId");
        if (surfaceId === "main") throw new Error("主聊天页面不能作为面板关闭");
        if (!view.surfaces.has(surfaceId)) return {};
        await this.send(view, { type: "close-surface", surfaceId });
        return {};
      }
      case "extension.view.ack": {
        const view = this.owned(params);
        const sequence = counter(params.sequence);
        if (sequence > view.sequence) throw new Error("无效的页面确认位置");
        while (view.frames[0] && view.frames[0].frame.sequence <= sequence) {
          const frame = view.frames.shift()!;
          view.bytes -= frame.bytes;
          view.dropped = frame.frame.sequence;
        }
        return {};
      }
      case "extension.view.respond": {
        const view = this.owned(params);
        const id = text(params.id, "id");
        if (!view.pending.has(id)) return {};
        view.pending.delete(id);
        await this.send(view, { type: "host-response", id, result: params.result, error: params.error });
        return {};
      }
      case "extension.view.visibility": {
        const view = this.owned(params);
        await this.send(view, { type: "visibility", visible: params.visible === true });
        return {};
      }
      default: throw new Error(`不支持的扩展宿主方法: ${method}`);
    }
  }

  async status() {
    const state = await this.store.status();
    return {
      version: state.active?.version ?? null,
      stagedVersion: state.candidate?.version ?? null,
      updating: state.operation?.state === "running",
      progress: state.operation,
      ...(state.operation?.error ? { error: state.operation.error } : {}),
      hosts: this.views.size,
    };
  }

  runtimeStatus(): Array<{ channelId: string; version: string | null; active: boolean; pendingRequests: number }> {
    return [...this.views.values()].flatMap(view => [...view.processes.entries()].map(([pid, version]) => ({
      channelId: view.viewId + ":" + pid, version,
      // We don't inspect private plugin state to guess whether a process is idle.
      active: true, pendingRequests: 0,
    })));
  }

  async restart(): Promise<void> {
    const operation = this.transition.then(async () => {
      if (this.closed) return;
      if (this.changing) throw new Error("官方扩展宿主正在更新");
      this.changing = true;
      try { await this.stopAll("Codex 已重启，请重新连接"); } finally { this.changing = false; }
    });
    this.transition = operation.then(() => {}, () => {});
    await operation;
  }

  disconnected(): void {
    // The extension and app-server belong to Agent, never to this connection.
    for (const view of this.views.values()) {
      view.attached = false;
      void this.send(view, { type: "visibility", visible: false }).catch(() => {});
    }
  }

  close(): void {
    this.closed = true;
    void this.stopAll("Agent 已停止");
  }

  private async attach(params: Record<string, unknown>): Promise<unknown> {
    if (this.changing) throw new Error("官方扩展宿主正在更新，请稍后连接");
    const viewId = text(params.viewId, "viewId");
    if (!/^[a-zA-Z0-9_-]{1,128}$/.test(viewId)) throw new Error("无效的页面 ID");
    const rendererId = text(params.rendererId, "rendererId");
    const workspace = text(params.workspace, "workspace");
    if (!isAbsolute(workspace)) throw new Error("工作目录必须是绝对路径");
    if (!(await stat(workspace)).isDirectory()) throw new Error("工作目录不存在");
    const canonicalWorkspace = await realpath(workspace);
    let view = this.views.get(viewId);
    if (!view) {
      const extension = await this.store.active();
      if (!extension) throw new Error("尚未安装官方前端，请点击检查并更新前端");
      view = await this.open(viewId, rendererId, canonicalWorkspace, extension);
    }
    if (view.workspace !== canonicalWorkspace) throw new Error("页面的工作目录发生变化，请重新打开");
    const sameRenderer = view.rendererId === rendererId;
    const after = counter(params.after ?? 0);
    const resume = sameRenderer && params.epoch === view.epoch && after >= view.dropped && after <= view.sequence;
    if (!sameRenderer && view.rendererId) {
      for (const id of view.pending.keys()) {
        await this.send(view, { type: "host-response", id, error: "页面已重建，原生操作已取消" });
      }
      view.pending.clear();
      view.deliveries.clear();
    }
    view.rendererId = rendererId;
    view.attached = true;
    await view.started;
    await this.send(view, { type: "visibility", visible: true });
    return {
      viewId, epoch: view.epoch, sequence: view.sequence, version: view.version, packageSha256: view.packageSha256, origin: view.origin, html: view.html,
      frames: resume ? view.frames.filter(({ frame }) => {
        if (frame.sequence <= after || frame.type === "html") return false;
        // The snapshot owns current panel titles/HTML. Only closes are needed
        // to dismiss panels retained by an existing renderer while disconnected.
        if (frame.type === "surface") return (frame.payload as { action?: string })?.action === "close";
        if (frame.type === "host-request" && !view.pending.has((frame.payload as NativeRequest).id)) return false;
        // A closed panel has no recipient for old dialogs or replies.
        return !frame.surfaceId || frame.surfaceId === "main" || view.surfaces.has(frame.surfaceId);
      }).map(item => item.frame) : [],
      surfaces: [...view.surfaces.values()].map(surface => ({ ...surface })),
      pendingHostRequests: [...view.pending.values()],
      resyncRequired: sameRenderer && !!params.epoch && !resume,
    };
  }

  private async open(viewId: string, rendererId: string, workspace: string, extension: OfficialExtensionPackage): Promise<ExtensionView> {
    const [node, hostDirectory] = await Promise.all([resolveNodeExecutable(), resolveExtensionHostDirectory()]);
    const stateDirectory = join(this.directory, "state", viewId);
    await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
    if (this.closed) throw new Error("官方扩展宿主已关闭");
    const origin = "https://v" + viewId.slice(0, 40) + ".codex-remote.invalid";
    const child = spawn(node, [join(hostDirectory, "runtime.cjs")], {
      cwd: workspace, stdio: ["ignore", "pipe", "pipe", "ipc"], env: process.env, detached: true,
    });
    let resolveStart!: () => void, rejectStart!: (error: Error) => void;
    const started = new Promise<void>((resolve, reject) => { resolveStart = resolve; rejectStart = reject; });
    // Prevent an early child failure from becoming an unhandled rejection before attach awaits it.
    void started.catch(() => {});
    const view: ExtensionView = {
      viewId, epoch: randomUUID(), rendererId, attached: true, workspace, version: extension.version, packageSha256: extension.sha256, origin, html: "", surfaces: new Map(), sequence: 0,
      dropped: 0, frames: [], bytes: 0, child, started, deliveries: new Map(), pending: new Map(), processes: new Map(), stopping: false,
    };
    this.views.set(viewId, view);
    const timer = setTimeout(() => { rejectStart(new Error("官方扩展启动超时")); void this.stop(view); }, 30_000);
    child.on("message", raw => {
      const event = raw as { type: string; surfaceId?: string; payload?: unknown; message?: string; fatal?: boolean; pid?: number; version?: string | null };
      if (event.type === "started") { clearTimeout(timer); resolveStart(); }
      else if (event.type === "process-start" && event.pid) view.processes.set(event.pid, event.version ?? null);
      else if (event.type === "process-exit" && event.pid) view.processes.delete(event.pid);
      else if (event.type === "error") {
        if (event.fatal) rejectStart(new Error(event.message));
        this.frame(view, "status", { state: "error", message: event.message, retryable: false, reloadRequired: false });
      } else if (event.type === "surface") {
        const change = event.payload as { action?: string; id?: string; title?: string; html?: string };
        const id = change?.id;
        if (!id || id === "main") return;
        const previous = view.surfaces.get(id);
        if (change.action === "open") {
          view.surfaces.set(id, { id, title: change.title ?? previous?.title ?? "Codex", kind: "panel", html: change.html ?? previous?.html ?? "" });
        } else if (change.action === "update" && previous) {
          if (change.title !== undefined) previous.title = change.title;
          if (change.html !== undefined) previous.html = change.html;
        } else if (change.action === "close" && previous) {
          view.surfaces.delete(id);
          for (const [requestId, request] of view.pending) {
            if (request.surfaceId === id) view.pending.delete(requestId);
          }
        } else return;
        this.frame(view, "surface", event.payload, id);
      } else if (event.type === "html" || event.type === "host-request" || event.type === "message") {
        const surfaceId = event.surfaceId ?? "main";
        const surface = view.surfaces.get(surfaceId);
        if (surfaceId !== "main" && !surface) return;
        if (event.type === "html") {
          const html = String((event.payload as { html?: string })?.html ?? "");
          if (surface) surface.html = html; else view.html = html;
          this.frame(view, "html", event.payload, surfaceId);
        } else if (event.type === "host-request") {
          const request = { ...event.payload as Omit<NativeRequest, "surfaceId">, surfaceId };
          view.pending.set(request.id, request);
          this.frame(view, "host-request", request, surfaceId);
        } else this.frame(view, "message", event.payload, surfaceId);
      }
    });
    child.stdout?.resume();
    // Keep diagnostics bounded and out of the public event journal.
    child.stderr?.on("data", () => {});
    child.once("error", error => { clearTimeout(timer); rejectStart(error); });
    child.once("exit", (code, signal) => {
      clearTimeout(timer);
      // The host may have exited before a child honored SIGTERM. Its isolated
      // process group must be gone before a replacement can acquire writers.
      try { if (child.pid) process.kill(-child.pid, "SIGKILL"); } catch { /* Already exited. */ }
      rejectStart(new Error(`官方扩展已退出 (${String(code ?? signal)})`));
      if (this.views.get(viewId) === view) this.views.delete(viewId);
      if (!view.stopping) this.frame(view, "status", { state: "error", message: "官方扩展已退出，请重新连接", retryable: true, reloadRequired: true });
    });
    await this.send(view, { type: "start", options: {
      extensionDirectory: extension.directory, main: extension.manifest.main, stateDirectory, workspace,
      clientId: viewId, hostId: view.epoch, origin, cliExecutable: this.executable,
    } });
    return view;
  }

  private frame(view: ExtensionView, type: ExtensionViewFrame["type"], payload: unknown, surfaceId?: string): void {
    const frame: ExtensionViewFrame = { viewId: view.viewId, epoch: view.epoch, sequence: ++view.sequence, type, payload,
      ...(surfaceId !== undefined ? { surfaceId } : {}) };
    const bytes = Buffer.byteLength(JSON.stringify(frame));
    view.frames.push({ frame, bytes }); view.bytes += bytes;
    while (view.bytes > maxFramesBytes && view.frames.length) {
      const removed = view.frames.shift()!; view.bytes -= removed.bytes; view.dropped = removed.frame.sequence;
    }
    if (view.attached) this.emit(frame);
  }

  private surface(view: ExtensionView, value: unknown): string {
    const id = value === undefined ? "main" : text(value, "surfaceId");
    if (id !== "main" && !view.surfaces.has(id)) throw new Error("官方扩展面板不存在或已关闭");
    return id;
  }

  private owned(params: Record<string, unknown>): ExtensionView {
    const view = this.views.get(text(params.viewId, "viewId"));
    if (!view || view.stopping) throw new Error("官方扩展页面不存在，请重新连接");
    if (!view.attached || view.rendererId !== params.rendererId) throw new Error("页面连接已由新的客户端接管");
    return view;
  }

  private send(view: ExtensionView, message: unknown): Promise<void> {
    return new Promise((resolve, reject) => {
      if (!view.child.connected) { reject(new Error("官方扩展进程已断开")); return; }
      view.child.send(message as object, error => error ? reject(error) : resolve());
    });
  }

  private async stopAll(message: string): Promise<void> {
    await Promise.all([...this.views.values()].map(async view => {
      this.frame(view, "status", { state: "reload", message });
      await this.stop(view);
    }));
  }

  private async stop(view: ExtensionView): Promise<void> {
    if (view.stopping) return;
    view.stopping = true;
    if (this.views.get(view.viewId) === view) this.views.delete(view.viewId);
    await new Promise<void>(resolve => {
      if (view.child.exitCode !== null || view.child.signalCode !== null) { resolve(); return; }
      const timer = setTimeout(() => {
        // Each host has its own process group. A stuck deactivation must not
        // leave its app-server holding a conversation's writer after restart.
        try { if (view.child.pid) process.kill(-view.child.pid, "SIGKILL"); } catch { view.child.kill("SIGKILL"); }
        resolve();
      }, 5_000);
      view.child.once("exit", () => { clearTimeout(timer); resolve(); });
      void this.send(view, { type: "stop" }).catch(() => view.child.kill("SIGTERM"));
    });
  }
}

function text(value: unknown, name: string): string {
  if (typeof value !== "string" || !value || value.length > 4096) throw new Error(`${name} 无效`);
  return value;
}
function counter(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw new Error("页面消息序号无效");
  return value as number;
}

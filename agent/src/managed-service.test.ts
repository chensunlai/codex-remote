import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { chmod } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, readFile, writeFile, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, it, vi } from 'vitest';
const { serviceFiles, runMaintenance, enqueue, status, prepareUpdate } = createRequire(import.meta.url)('../maintenance/service.cjs');
const roots: string[]=[];
afterEach(async()=>{vi.unstubAllEnvs();vi.unstubAllGlobals();for(const root of roots.splice(0))await rm(root,{recursive:true,force:true});});
async function fixture(){
 const root=await mkdtemp(join(tmpdir(),'agent-managed 中文 '));roots.push(root);
 const executable=join(root,'agent'),control=join(root,'agent-maintenance');
 await mkdir(join(root,'runtime'),{recursive:true});await writeFile(executable,'old');await writeFile(join(root,'runtime/node'),'old node');
 await mkdir(join(control,'requests'),{recursive:true});
 await writeFile(join(control,'installation.json'),JSON.stringify({executable,config:join(root,'agent.json'),manager:'systemd',updateSupported:true}));
 await writeFile(join(control,'running.json'),JSON.stringify({version:'3.0.4'}));
 await writeFile(join(root,'agent.json'),'secret config');
 vi.stubEnv('CODEX_REMOTE_MANAGED_AGENT',control);
 const calls:string[]=[];
 const dependencies={
  latestVersion:async()=> '3.1.0',
  prepareUpdate:async(stage:string)=>{await writeFile(join(stage,'agent'),'new');await mkdir(join(stage,'runtime'));await writeFile(join(stage,'runtime/node'),'new node');},
  manage:async(_:unknown,action:string)=>{calls.push(action);},
  healthy:async()=>{},
 };
 return {root,executable,control,calls,dependencies};
}
it('generates quoted systemd units and escaped launchd plists with independent updater ownership',()=>{
 const files=serviceFiles('/Users/测试 $user%/Agent & Co/agent','/Users/测试/config.json','/Users/测试',{PATH:'/opt/homebrew/bin:/usr/bin',CODEX_REMOTE_TOKEN:'never-copy'});
 expect(files.systemd['codex-remote-agent.service']).toContain('Restart=always');
 expect(files.systemd['codex-remote-agent.service']).toContain('WantedBy=default.target');
 expect(files.systemd['codex-remote-agent.service']).toContain('$user%%');
 expect(files.systemd['codex-remote-agent-maintenance.service']).not.toContain('PartOf=');
 expect(files.launchd['dev.codexremote.agent.plist']).toContain('Agent &amp; Co');
 expect(files.launchd['dev.codexremote.agent.plist']).toContain('<key>KeepAlive</key><true/>');
 expect(files.launchd['dev.codexremote.agent.maintenance.plist']).toContain('<key>QueueDirectories</key>');
 expect(JSON.stringify(files)).not.toContain('never-copy');
});
it('requires managed installation and confirmation, queues once, preserves configuration and replaces both artifacts',async()=>{
 const f=await fixture();
 expect(await enqueue(f.executable,'3.0.4',false)).toMatchObject({confirmationRequired:true});
 await expect(access(join(f.control,'requests/update.json'))).rejects.toThrow();
 vi.stubEnv('CODEX_REMOTE_MANAGED_AGENT','');
 await expect(enqueue(f.executable,'3.0.4',true)).rejects.toThrow('安装器');
 vi.stubEnv('CODEX_REMOTE_MANAGED_AGENT',f.control);
 await enqueue(f.executable,'3.0.4',true);
 await expect(enqueue(f.executable,'3.0.4',true)).rejects.toThrow('正在进行');
 await runMaintenance(f.control,f.dependencies);
 expect(f.calls).toEqual(['stop','start']);
 expect(await readFile(f.executable,'utf8')).toBe('new');
 expect(await readFile(join(f.root,'runtime/node'),'utf8')).toBe('new node');
 expect(await readFile(join(f.root,'agent.json'),'utf8')).toBe('secret config');
 expect(await status(f.executable,'3.1.0')).toMatchObject({operation:{state:'succeeded'}});
});
it('leaves running files untouched on checksum failure',async()=>{
 const f=await fixture();await enqueue(f.executable,'3.0.4',true);
 await runMaintenance(f.control,{...f.dependencies,prepareUpdate:async()=>{throw new Error('SHA-256 mismatch');}});
 expect(f.calls).toEqual([]);expect(await readFile(f.executable,'utf8')).toBe('old');
 expect(await status(f.executable,'3.0.4')).toMatchObject({operation:{state:'failed',output:'SHA-256 mismatch'}});
});
it('restores both binary and runtime when the new process fails health verification',async()=>{
 const f=await fixture();await enqueue(f.executable,'3.0.4',true);
 await runMaintenance(f.control,{...f.dependencies,healthy:async(_:unknown,__:unknown,version:string)=>{if(version==='3.1.0')throw new Error('startup failed');}});
 expect(f.calls).toEqual(['stop','start','stop','start']);
 expect(await readFile(f.executable,'utf8')).toBe('old');expect(await readFile(join(f.root,'runtime/node'),'utf8')).toBe('old node');
 expect(await status(f.executable,'3.0.4')).toMatchObject({operation:{state:'failed'}});
});
it('recovers a replacement interrupted after moving the old binary, without downloading again',async()=>{
 const f=await fixture();await enqueue(f.executable,'3.0.4',true);
 const stage=join(f.control,'stage');await mkdir(stage);await writeFile(join(stage,'previous-agent'),'old');await writeFile(f.executable,'new');
 await writeFile(join(f.control,'replacement.json'),JSON.stringify({oldVersion:'3.0.4'}));
 await runMaintenance(f.control,{...f.dependencies,latestVersion:async()=>{throw new Error('must not download');}});
 expect(await readFile(f.executable,'utf8')).toBe('old');
 expect(await status(f.executable,'3.0.4')).toMatchObject({operation:{state:'failed',output:'上次更新中断，已恢复原版本'}});
});
it('does not downgrade or stop the current process when release is older',async()=>{
 const f=await fixture();await enqueue(f.executable,'3.0.4',true);
 await runMaintenance(f.control,{...f.dependencies,latestVersion:async()=> '3.0.3'});
 expect(f.calls).toEqual([]);expect(await readFile(f.executable,'utf8')).toBe('old');
});

const execute = promisify(execFile);
it.skipIf(process.platform !== 'linux')('systemd accepts generated units with spaces, dollar signs and percent signs', async () => {
 const f = await fixture();
 const root = join(f.root, '$user% services');await mkdir(join(root, 'runtime'), { recursive:true });
 for (const name of ['agent', 'runtime/node']) {
  await writeFile(join(root,name), '#!/bin/sh\nexit 0\n');await chmod(join(root,name),0o755);
 }
 const units = serviceFiles(join(root,'agent'),join(root,'config'),root).systemd;
 for (const [name,content] of Object.entries(units)) await writeFile(join(root,name), content as string);
 await execute('systemd-analyze', ['verify',...Object.keys(units).map(name=>join(root,name))]);
});
it.skipIf(!['linux','darwin'].includes(process.platform))('installs user units through the platform manager without exposing configuration secrets', async () => {
 const f = await fixture();
 await mkdir(join(f.root,'runtime/maintenance'), {recursive:true});await writeFile(join(f.root,'runtime/maintenance/main.cjs'),'');
 const commands = join(f.root,'commands');await mkdir(commands);
 const calls = join(f.root,'calls');
 for (const name of ['systemctl','loginctl','launchctl']) {
  await writeFile(join(commands,name), '#!/bin/sh\nprintf "%s\\n" "$*" >> "$FIXTURE_CALLS"\n[ "$1" != show-user ] || echo yes\nexit 0\n');
  await chmod(join(commands,name),0o755);
 }
 const modulePath = fileURLToPath(new URL('../maintenance/service.cjs', import.meta.url));
 await execute(process.execPath, ['-e', 'require(process.argv[1]).installService(process.argv[2],process.argv[3]).catch(e=>{console.error(e);process.exit(1)})', modulePath, f.executable, join(f.root,'agent.json')],
  {env:{...process.env, HOME:f.root, PATH:`${commands}:${process.env.PATH}`, FIXTURE_CALLS:calls, CODEX_REMOTE_REPO:'chensunlai/codex-remote'}});
 const recorded = await readFile(calls,'utf8');
 expect(recorded).not.toContain('secret config');
 if (process.platform === 'linux') {
  expect(recorded).toContain('--user enable --now codex-remote-agent-maintenance.path');
  expect(recorded).toContain('--user restart codex-remote-agent.service');
 } else {
  expect(recorded).toContain('bootstrap gui/');
  // installService runs real plutil -lint on these generated files on macOS.
  expect(recorded).toContain('dev.codexremote.agent.maintenance.plist');
 }
});
it.skipIf(!['linux','darwin'].includes(process.platform))('verifies downloaded bytes and rejects corrupt release artifacts before executing them', async () => {
 const f = await fixture();
 const source = join(f.root,'source');await mkdir(join(source,'runtime/extension-host'),{recursive:true});
 await mkdir(join(source,'runtime/maintenance'));await writeFile(join(source,'runtime/extension-host/runtime.cjs'),'');
 await writeFile(join(source,'runtime/maintenance/main.cjs'),'');
 await writeFile(join(source,'runtime/node'),'#!/bin/sh\necho v24.18.0\n');await chmod(join(source,'runtime/node'),0o755);
 const runtimeName = `codex-remote-agent-runtime-${process.platform}-${process.arch}.tar.gz`;
 const archive = join(f.root,'runtime.tar.gz');
 await execute('tar',['-czf',archive,'-C',source,'runtime']);
 const binaryName = `codex-remote-agent-${process.platform}-${process.arch}`;
 const assets: Record<string,Buffer> = {
  [binaryName]:Buffer.from('#!/bin/sh\necho 3.1.0\n'),[runtimeName]:await readFile(archive),
 };
 assets.SHA256SUMS=Buffer.from(Object.entries(assets).map(([name,bytes])=>`${createHash('sha256').update(bytes).digest('hex')}  ${name}`).join('\n'));
 vi.stubGlobal('fetch',async(url:string)=>new Response(new Uint8Array(assets[url.split('/').at(-1)!]!)));
 const stage=join(f.root,'verified');await mkdir(stage);
 await prepareUpdate(stage,'3.1.0');
 expect(await readFile(join(stage,'agent'),'utf8')).toContain('3.1.0');
 assets[binaryName]=Buffer.from('corrupt');
 const failedStage=join(f.root,'corrupt');await mkdir(failedStage);
 await expect(prepareUpdate(failedStage,'3.1.0')).rejects.toThrow('SHA-256');
 await expect(access(join(failedStage,'agent'))).rejects.toThrow();
});

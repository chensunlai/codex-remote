'use strict';
const fs = require('node:fs/promises');
const { createReadStream } = require('node:fs');
const { join, dirname, resolve } = require('node:path');
const { homedir } = require('node:os');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { createHash, randomUUID } = require('node:crypto');
const execute = promisify(execFile);
const UNIT = 'codex-remote-agent';
const LABEL = 'dev.codexremote.agent';
const REPO = 'chensunlai/codex-remote';
const run = (cmd, args) => execute(cmd, args, { timeout: 60000, maxBuffer: 1024 * 1024 });
const wait = ms => new Promise(done => setTimeout(done, ms));
async function json(path) { try { return JSON.parse(await fs.readFile(path, 'utf8')); } catch (e) { if (e.code === 'ENOENT') return null; throw e; } }
async function save(path, value) {
  const tmp = `${path}.${randomUUID()}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(value) + '\n', { mode: 0o600 });
  await fs.rename(tmp, path);
}
function controlPath(executable) { return join(dirname(executable), 'agent-maintenance'); }
function quote(value) {
  if (/[\r\n\0]/.test(value)) throw new Error('服务路径或环境变量包含无效字符');
  value = value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/%/g, '%%');
  return '"' + value + '"';
}
function unitPath(value) {
  if (/[\r\n\0]/.test(value) || value.trimEnd() !== value) throw new Error('服务路径包含无效字符');
  return value.replace(/%/g, '%%');
}
function xml(value) { return String(value).replace(/[<>&"']/g, c => ({ '<':'&lt;', '>':'&gt;', '&':'&amp;', '"':'&quot;', "'":'&apos;' })[c]); }
function plist(label, args, env, extra) {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict>
<key>Label</key><string>${xml(label)}</string>
<key>ProgramArguments</key><array>${args.map(v => `<string>${xml(v)}</string>`).join('')}</array>
<key>EnvironmentVariables</key><dict>${Object.entries(env).map(([k,v]) => `<key>${xml(k)}</key><string>${xml(v)}</string>`).join('')}</dict>
${extra}</dict></plist>\n`;
}
function serviceFiles(executable, config, userHome = homedir(), env = process.env) {
  const directory = dirname(executable), control = controlPath(executable);
  const serviceEnv = { PATH: env.PATH || '/usr/local/bin:/usr/bin:/bin', HOME: userHome, CODEX_REMOTE_MANAGED_AGENT: control };
  // Keep the user's login/proxy configuration without copying tokens into commands or logs.
  for (const key of ['HTTPS_PROXY','HTTP_PROXY','ALL_PROXY','NO_PROXY','https_proxy','http_proxy','all_proxy','no_proxy','CODEX_HOME','CLAUDE_CONFIG_DIR']) {
    if (env[key]) serviceEnv[key] = env[key];
  }
  const helper = [join(directory, 'runtime/node'), join(directory, 'runtime/maintenance/main.cjs'), control];
  const environment = Object.entries(serviceEnv).map(([k,v]) => `Environment=${quote(`${k}=${v}`)}`).join('\n');
  return {
    systemd: {
      [`${UNIT}.service`]: `[Unit]\nDescription=Codex Remote Agent\nStartLimitIntervalSec=0\n[Service]\nExecStart=:${quote(executable)} --config ${quote(config)}\nWorkingDirectory=${unitPath(userHome)}\n${environment}\nEnvironmentFile=-${unitPath(join(directory, 'agent.env'))}\nRestart=always\nRestartSec=5\nTimeoutStopSec=20\nKillMode=control-group\nUMask=0077\n[Install]\nWantedBy=default.target\n`,
      [`${UNIT}-maintenance.path`]: `[Unit]\nDescription=Codex Remote Agent update requests\n[Path]\nDirectoryNotEmpty=${unitPath(join(control, 'requests'))}\nUnit=${UNIT}-maintenance.service\n[Install]\nWantedBy=default.target\n`,
      [`${UNIT}-maintenance.service`]: `[Unit]\nDescription=Codex Remote Agent updater\nStartLimitIntervalSec=0\n[Service]\nType=oneshot\nExecStart=:${helper.map(quote).join(' ')}\n${environment}\nEnvironmentFile=-${unitPath(join(directory, 'agent.env'))}\nTimeoutStartSec=20min\nRestart=on-failure\nRestartSec=5\nUMask=0077\n`,
    },
    launchd: {
      [`${LABEL}.plist`]: plist(LABEL, [executable, '--config', config], serviceEnv,
        `<key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>ThrottleInterval</key><integer>5</integer><key>ExitTimeOut</key><integer>20</integer><key>WorkingDirectory</key><string>${xml(userHome)}</string><key>StandardOutPath</key><string>${xml(join(control,'agent.log'))}</string><key>StandardErrorPath</key><string>${xml(join(control,'agent.log'))}</string>`),
      [`${LABEL}.maintenance.plist`]: plist(`${LABEL}.maintenance`, helper, serviceEnv,
        `<key>RunAtLoad</key><true/><key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict><key>QueueDirectories</key><array><string>${xml(join(control,'requests'))}</string></array><key>ThrottleInterval</key><integer>5</integer><key>StandardOutPath</key><string>${xml(join(control,'maintenance.log'))}</string><key>StandardErrorPath</key><string>${xml(join(control,'maintenance.log'))}</string>`),
    },
  };
}
async function installService(executable, config) {
  if (!['linux','darwin'].includes(process.platform)) throw new Error('该平台请使用 Windows 托盘管理');
  executable = resolve(executable); config = resolve(config);
  const control = controlPath(executable);
  const previous = await json(join(control,'installation.json'));
  if (previous && previous.config !== config) throw new Error('此安装已管理另一配置，请使用原配置文件');
  const files = serviceFiles(executable, config);
  await fs.access(join(dirname(executable),'runtime/maintenance/main.cjs'));
  await fs.mkdir(join(control,'requests'), { recursive:true, mode:0o700 });
  if (process.platform === 'linux') {
    await run('systemctl', ['--user','show-environment']);
    // Linger is what makes a user unit start at boot and survive logout.
    const user = String(process.getuid());
    let linger = await run('loginctl',['show-user',user,'--property=Linger','--value']);
    if (linger.stdout.trim() !== 'yes') {
      await run('loginctl',['enable-linger',user]).catch(() => {});
      linger = await run('loginctl',['show-user',user,'--property=Linger','--value']);
      if (linger.stdout.trim() !== 'yes') throw new Error(`请先执行 sudo loginctl enable-linger ${user}，再重新运行安装器，以启用开机自启和退出登录后运行`);
    }
    const units = join(homedir(),'.config/systemd/user');
    await fs.mkdir(units,{ recursive:true });
    for (const [name,content] of Object.entries(files.systemd)) await fs.writeFile(join(units,name),content,{mode:0o600});
    await save(join(control,'installation.json'),{ executable,config,manager:'systemd',uid:process.getuid(),updateSupported:!process.env.CODEX_REMOTE_REPO || process.env.CODEX_REMOTE_REPO === REPO });
    await run('systemctl',['--user','daemon-reload']);
    await run('systemctl',['--user','enable','--now',`${UNIT}-maintenance.path`]);
    await run('systemctl',['--user','enable',`${UNIT}.service`]);
    await run('systemctl',['--user','restart',`${UNIT}.service`]);
    await run('systemctl',['--user','is-active',`${UNIT}.service`]);
  } else {
    const domain = `gui/${process.getuid()}`;
    await run('launchctl',['print',domain]);
    const units = join(homedir(),'Library/LaunchAgents');
    await fs.mkdir(units,{recursive:true});
    await save(join(control,'installation.json'),{executable,config,manager:'launchd',uid:process.getuid(),updateSupported:!process.env.CODEX_REMOTE_REPO || process.env.CODEX_REMOTE_REPO === REPO});
    for (const [name,content] of Object.entries(files.launchd)) {
      const path = join(units,name), label = name.replace(/\.plist$/,'');
      await fs.writeFile(path,content,{mode:0o600});
      await run('plutil',['-lint',path]);
      await run('launchctl',['bootout',`${domain}/${label}`]).catch(() => {});
      await run('launchctl',['enable',`${domain}/${label}`]);
      await run('launchctl',['bootstrap',domain,path]);
    }
  }
  console.log(process.platform === 'linux' ? 'Agent 已交由用户 systemd 管理，已启用开机自启、退出登录后运行和异常重启。' : 'Agent 已交由 launchd 管理，已启用当前用户登录自启和异常重启。');
}
async function status(executable, version) {
  const control = controlPath(executable), installed = await json(join(control,'installation.json'));
  const supported = ['linux','darwin'].includes(process.platform) && process.env.CODEX_REMOTE_MANAGED_AGENT === control
    && installed?.executable === executable && installed.updateSupported === true;
  const request = await json(join(control,'requests/update.json'));
  const operation = await json(join(control,'operation.json'));
  return { currentVersion:version, updateSupported:supported, manager:installed?.manager ?? null,
    unavailableReason:supported ? null : '请先在服务器以原用户重新运行安装器，启用 systemd / launchd 托管',
    operation:request && request.id !== operation?.id ? {...request,state:'queued',output:'等待独立维护服务执行'} : operation };
}
async function enqueue(executable, version, confirmed) {
  const current = await status(executable,version);
  if (!confirmed) return {...current, confirmationRequired:true};
  if (!current.updateSupported) throw new Error(current.unavailableReason);
  const control = controlPath(executable);
  // Atomic singleton request survives both Agent exit and App disconnect.
  const request = {id:randomUUID(),createdAt:Date.now()};
  const pending = join(control, `${request.id}.request`);
  await fs.writeFile(pending, JSON.stringify(request), { mode:0o600 });
  try {
    // Publish only complete JSON; the path watcher can fire immediately. A hard
    // link also provides exclusive creation without overwriting an active request.
    await fs.link(pending, join(control,'requests/update.json'));
  } catch(e) {
    if(e.code === 'EEXIST') throw new Error('Agent 更新正在进行');
    throw e;
  } finally { await fs.unlink(pending); }
  return {...current,operation:{...request,state:'queued',output:'等待独立维护服务执行'}};
}
async function markStarted(executable, version) {
  const control = controlPath(executable);
  if (process.env.CODEX_REMOTE_MANAGED_AGENT !== control) return;
  await save(join(control,'running.json'),{version,pid:process.pid,startedAt:Date.now()});
}
async function hash(path) { const h=createHash('sha256'); for await(const chunk of createReadStream(path)) h.update(chunk); return h.digest('hex'); }
function newer(candidate,current) {
  if(!/^\d+\.\d+\.\d+$/.test(candidate) || !/^\d+\.\d+\.\d+(?:[-+].*)?$/.test(current)) throw new Error('版本无法识别，拒绝替换');
  const a=candidate.split('.').map(Number),b=current.split(/[.+-]/).slice(0,3).map(Number);
  for(let i=0;i<3;i++) {if(a[i]!==b[i])return a[i]>b[i];} return current.includes('-');
}
async function download(url,path,limit) {
  const response=await fetch(url,{signal:AbortSignal.timeout(5*60_000)});
  if(!response.ok || !response.body)throw new Error(`下载失败 HTTP ${response.status}`);
  const file=await fs.open(path,'wx',0o600);let size=0;
  try {for await(const chunk of response.body){size+=chunk.length;if(size>limit)throw new Error('下载文件超出上限');await file.writeFile(chunk);}} finally {await file.close();}
}
async function latestVersion() {
  const response=await fetch(`https://api.github.com/repos/${REPO}/releases/latest`,{signal:AbortSignal.timeout(15000),headers:{Accept:'application/vnd.github+json'}});
  if(!response.ok)throw new Error(`检查更新失败 HTTP ${response.status}`);
  const value=await response.json();
  if(value.draft||value.prerelease||!/^v\d+\.\d+\.\d+$/.test(value.tag_name))throw new Error('正式版本信息无效');
  return value.tag_name.slice(1);
}
async function prepareUpdate(stage,version,platform=process.platform,arch=process.arch) {
  const asset=`codex-remote-agent-${platform}-${arch}`,runtime=`codex-remote-agent-runtime-${platform}-${arch}.tar.gz`;
  const base=`https://github.com/${REPO}/releases/download/v${version}`;
  await download(`${base}/SHA256SUMS`,join(stage,'SHA256SUMS'),1024*1024);
  const sums=(await fs.readFile(join(stage,'SHA256SUMS'),'utf8')).trim().split(/\r?\n/);
  for(const name of [asset,runtime]) {
    const matches=sums.map(s=>s.trim().split(/\s+/)).filter(s=>s[1]===name||s[1]===`*${name}`);
    if(matches.length!==1||!/^[a-f0-9]{64}$/i.test(matches[0][0]))throw new Error('Release 校验清单不完整');
    await download(`${base}/${name}`,join(stage,name),160*1024*1024);
    if(await hash(join(stage,name))!==matches[0][0].toLowerCase())throw new Error(`${name} SHA-256 校验失败`);
  }
  await fs.rename(join(stage,asset),join(stage,'agent'));
  await fs.chmod(join(stage,'agent'),0o755);
  const {stdout}=await run('tar',['-tzf',join(stage,runtime)]);
  if(stdout.trim().split('\n').some(p=>!p.startsWith('runtime/')||p.split('/').includes('..')))throw new Error('运行时包路径无效');
  await run('tar',['-xzf',join(stage,runtime),'-C',stage,'--no-same-owner','--no-same-permissions']);
  await rejectLinks(join(stage,'runtime'));
  await fs.access(join(stage,'runtime/extension-host/runtime.cjs'));
  await fs.access(join(stage,'runtime/maintenance/main.cjs'));
  const node=await run(join(stage,'runtime/node'),['--version']);
  if(!/^v(?:2[4-9]|[3-9]\d)\./.test(node.stdout.trim()))throw new Error('Node 运行时无效');
  const binary=await run(join(stage,'agent'),['--version']);
  if(binary.stdout.trim()!==version)throw new Error('Agent 版本与 Release 不一致');
}
async function rejectLinks(directory) {
  for(const item of await fs.readdir(directory,{withFileTypes:true})){
    const path=join(directory,item.name);
    if(item.isSymbolicLink())throw new Error('运行时包不允许符号链接');
    if(item.isDirectory())await rejectLinks(path);else if(!item.isFile())throw new Error('运行时包文件类型无效');
  }
}
async function manage(installed,action) {
  if(installed.manager==='systemd')return run('systemctl',['--user',action,`${UNIT}.service`]);
  const domain=`gui/${installed.uid}`,target=`${domain}/${LABEL}`;
  if(action==='stop')return run('launchctl',['bootout',target]);
  return run('launchctl',['bootstrap',domain,join(homedir(),'Library/LaunchAgents',`${LABEL}.plist`)]);
}
async function healthy(control,installed,version,since) {
  for(let i=0;i<30;i++) {
    await wait(1000);
    const running=await json(join(control,'running.json'));
    if(running?.version===version&&running.startedAt>=since) {
      try {process.kill(running.pid,0);await wait(2000);process.kill(running.pid,0);return;}catch{}
    }
  }
  throw new Error('Agent 新版本启动检查失败');
}
async function runMaintenance(control, dependencies={}) {
  const installed=await json(join(control,'installation.json'));
  const request=await json(join(control,'requests/update.json'));
  if(!installed||!request)return;
  const stage=join(control,'stage'),journalPath=join(control,'replacement.json');
  const destination=installed.executable,runtime=join(dirname(destination),'runtime');
  const operation={...request,state:'running',output:'正在检查更新'};
  const report=async(state,output)=>save(join(control,'operation.json'),{...operation,state,output});
  const controlService=dependencies.manage??manage;
  const verify=dependencies.healthy??healthy;
  async function rollback() {
    const journal=await json(journalPath);if(!journal)return;
    await controlService(installed,'stop').catch(()=>{});
    if(await fs.stat(join(stage,'previous-agent')).catch(()=>null))await fs.rename(join(stage,'previous-agent'),destination);
    if(await fs.stat(join(stage,'previous-runtime')).catch(()=>null)){
      await fs.rm(runtime,{recursive:true,force:true});await fs.rename(join(stage,'previous-runtime'),runtime);
    }
    const since=Date.now();await controlService(installed,'start');await verify(control,installed,journal.oldVersion,since);
    await fs.unlink(journalPath);
  }
  try {
    if(installed.updateSupported !== true) throw new Error('此安装不支持官方 Release 更新');
    if(await json(journalPath)){await rollback();throw new Error('上次更新中断，已恢复原版本');}
    const current=await json(join(control,'running.json'));
    if(!current?.version)throw new Error('无法确认当前运行版本');
    const version=await (dependencies.latestVersion??latestVersion)();
    if(!newer(version,current.version)){await report('succeeded','已是当前或更新版本');return;}
    await fs.rm(stage,{recursive:true,force:true});await fs.mkdir(stage,{mode:0o700});
    await report('running',`正在下载并校验 ${version}`);
    await (dependencies.prepareUpdate??prepareUpdate)(stage,version);
    await report('running','正在替换并重启 Agent');
    await save(journalPath,{oldVersion:current.version});
    await controlService(installed,'stop');
    await fs.rename(destination,join(stage,'previous-agent'));
    await fs.rename(runtime,join(stage,'previous-runtime'));
    await fs.rename(join(stage,'agent'),destination);
    await fs.rename(join(stage,'runtime'),runtime);
    const since=Date.now();await controlService(installed,'start');
    await verify(control,installed,version,since);
    await fs.unlink(journalPath);
    await report('succeeded',`Agent 已更新至 ${version}`);
  } catch(e) {
    let message=e.message;
    try {await rollback();}catch(rollbackError){message+=`; 恢复原版本失败：${rollbackError.message}`;}
    await report('failed',message);
  } finally {
    // Preserve recovery files if rollback itself failed.
    if(!await json(journalPath))await fs.rm(stage,{recursive:true,force:true});
    await fs.unlink(join(control,'requests/update.json')).catch(()=>{});
  }
}
module.exports={installService,serviceFiles,status,enqueue,markStarted,runMaintenance,prepareUpdate,newer};

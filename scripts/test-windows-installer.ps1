$ErrorActionPreference = 'Stop'
$installer = Join-Path $PWD 'release/windows/codex-remote-windows-x64-setup.exe'
$installDir = Join-Path $env:ProgramFiles 'Codex Remote 安装测试'
$data = Join-Path $installDir 'data'
$log = Join-Path $env:RUNNER_TEMP 'codex-remote-installer.log'

function Test-InstallingUser {
    # CI normally runs elevated, which hid the original user's inability to
    # write inside Setup's elevated temp directory. Exercise a different user.
    $name = 'crsetup' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
    $password = ConvertTo-SecureString ('Cr!' + [Guid]::NewGuid().ToString('N')) -AsPlainText -Force
    $privateDir = Join-Path $env:RUNNER_TEMP $name
    $server = $null
    $client = $null
    try {
        $user = New-LocalUser -Name $name -Password $password
        Add-LocalGroupMember -Group (Get-LocalGroup -SID 'S-1-5-32-545') -Member $user
        New-Item -ItemType Directory -Path $privateDir | Out-Null
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls.exe $privateDir /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create protected test directory' }
        $output = Join-Path $privateDir 'owner.txt'
        $helper = Join-Path $PWD 'windows/installer-user.ps1'
        $powershell = Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
        $server = Start-Process $powershell -ArgumentList @(
            '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
            '-File', "`"$helper`"", '-PipeName', $name, '-OutputFile', "`"$output`""
        ) -PassThru
        $script = @'
$ErrorActionPreference = 'Stop'
try {
    try { [IO.File]::WriteAllText('__OUTPUT__', 'must fail'); exit 2 }
    catch [UnauthorizedAccessException] { }
    $pipe = [IO.Pipes.NamedPipeClientStream]::new('.', '__PIPE__',
        [IO.Pipes.PipeDirection]::InOut, [IO.Pipes.PipeOptions]::Asynchronous,
        [Security.Principal.TokenImpersonationLevel]::Impersonation)
    $pipe.Connect(30000)
    $pipe.WriteByte(1)
    $pipe.Flush()
    $bytes = New-Object byte[] 1
    $read = $pipe.ReadAsync($bytes, 0, 1)
    if (!$read.Wait(30000) -or $read.Result -ne 1 -or $bytes[0] -ne 1) { exit 3 }
} catch { exit 4 } finally { if ($pipe) { $pipe.Dispose() } }
'@
        $script = $script.Replace('__PIPE__', $name).Replace('__OUTPUT__', $output.Replace("'", "''"))
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
        $credential = [Management.Automation.PSCredential]::new("$env:COMPUTERNAME\$name", $password)
        $client = Start-Process $powershell -Credential $credential -WorkingDirectory $env:WINDIR `
            -ArgumentList '-NoProfile', '-NonInteractive', '-EncodedCommand', $encoded -PassThru
        if (!$client.WaitForExit(45000)) { throw 'Original-user client timed out' }
        if ($client.ExitCode -ne 0) { throw "Original-user client failed: $($client.ExitCode)" }
        if (!$server.WaitForExit(10000) -or $server.ExitCode -ne 0) {
            if (Test-Path ($output + '.error')) { Get-Content ($output + '.error') }
            throw 'User identity helper failed'
        }
        if ([IO.File]::ReadAllText($output) -ne $user.SID.Value) {
            throw 'Installer identified the administrator instead of the original user'
        }
        Write-Host 'PASS original non-admin user identification with a protected installer temp directory'
    } finally {
        foreach ($process in @($client, $server)) {
            if ($process -and !$process.HasExited) { $process.Kill(); $process.WaitForExit() }
        }
        if (Get-LocalUser -Name $name -ErrorAction SilentlyContinue) { Remove-LocalUser -Name $name }
        if (Test-Path $privateDir) { Remove-Item $privateDir -Recurse -Force }
    }
}

function Run-Installer {
    $process = Start-Process -FilePath $installer -ArgumentList @(
        '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART',
        "/DIR=`"$installDir`"", "/LOG=`"$log`""
    ) -PassThru -Wait
    if ($process.ExitCode -ne 0) {
        if (Test-Path $log) { Get-Content $log -Tail 60 }
        throw "Installer failed: $($process.ExitCode)"
    }
}

try {
    Test-InstallingUser
    Run-Installer
    foreach ($relative in @('CodexRemote.exe', 'runtime/node.exe', 'app/agent.mjs', 'unins000.exe')) {
        if (!(Test-Path (Join-Path $installDir $relative))) { throw "Missing installed file: $relative" }
    }
    $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl $data
    $rule = $acl.Access | Where-Object {
        $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -eq $currentSid.Value -and
        ($_.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::Modify)
    }
    if (!$acl.AreAccessRulesProtected -or !$rule) { throw 'Data directory must grant the original user write access' }
    $users = $acl.Access | Where-Object {
        $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -in @('S-1-1-0', 'S-1-5-32-545', 'S-1-5-11')
    }
    if ($users) { throw 'Data directory must not grant access to all users' }

    $config = Join-Path $data 'agent.json'
    $fixture = '{"version":1,"serviceId":"installer-test","token":"fixture-not-a-secret"}'
    [System.IO.File]::WriteAllText($config, $fixture)
    Run-Installer
    if ([System.IO.File]::ReadAllText($config) -ne $fixture) { throw 'Upgrade changed configuration' }
    $before = Join-Path $PWD 'release/windows/CodexRemote/CodexRemote.exe'
    if ((Get-FileHash $before).Hash -ne (Get-FileHash (Join-Path $installDir 'CodexRemote.exe')).Hash) {
        throw 'Installed executable differs from packaged executable'
    }
    $uninstall = Start-Process -FilePath (Join-Path $installDir 'unins000.exe') `
        -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
    if ($uninstall.ExitCode -ne 0) { throw 'Uninstall failed' }
    if (Test-Path (Join-Path $installDir 'CodexRemote.exe')) { throw 'Uninstall left the program executable' }
    if ([System.IO.File]::ReadAllText($config) -ne $fixture) { throw 'Uninstall removed configuration' }
    Write-Host 'PASS Program Files install, custom Unicode path, data ACL, upgrade and uninstall preservation'
} finally {
    # This is only the isolated CI installation, never an existing user installation.
    if (Test-Path $installDir) { Remove-Item $installDir -Recurse -Force }
}

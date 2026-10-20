$ErrorActionPreference = 'Stop'
$installer = Join-Path $PWD 'release/windows/codex-remote-windows-x64-setup.exe'
$installDir = Join-Path $env:ProgramFiles 'Codex Remote 安装测试'
$data = Join-Path $installDir 'data'
$log = Join-Path $env:RUNNER_TEMP 'codex-remote-installer.log'

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

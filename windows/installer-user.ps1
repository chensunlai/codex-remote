# Runs elevated. The original (possibly different, non-admin) user connects over
# a local pipe; it never needs access to Setup's protected temporary directory.
param([Parameter(Mandatory)][string]$PipeName, [Parameter(Mandatory)][string]$OutputFile)
$ErrorActionPreference = 'Stop'
$pipe = $null
try {
    $security = New-Object System.IO.Pipes.PipeSecurity
    $users = New-Object System.Security.Principal.SecurityIdentifier('S-1-5-11')
    $security.AddAccessRule((New-Object System.IO.Pipes.PipeAccessRule(
        $users, [System.IO.Pipes.PipeAccessRights]::ReadWrite,
        [System.Security.AccessControl.AccessControlType]::Allow)))
    $pipe = New-Object System.IO.Pipes.NamedPipeServerStream(
        $PipeName, [System.IO.Pipes.PipeDirection]::InOut, 1,
        [System.IO.Pipes.PipeTransmissionMode]::Byte, [System.IO.Pipes.PipeOptions]::Asynchronous,
        4096, 4096, $security)
    $connection = $pipe.WaitForConnectionAsync()
    if (!$connection.Wait(30000)) { throw 'Timed out waiting for the installing user' }
    $read = $pipe.ReadAsync((New-Object byte[] 1), 0, 1)
    if (!$read.Wait(10000) -or $read.Result -ne 1) { throw 'Installing user disconnected' }
    # Obtain the authenticated Windows identity, not a SID supplied by the client.
    $script:ownerSid = $null
    $pipe.RunAsClient({ $script:ownerSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value })
    if (!$script:ownerSid) { throw 'Installing user has no SID' }
    [System.IO.File]::WriteAllText($OutputFile, $script:ownerSid, [System.Text.Encoding]::ASCII)
    $pipe.WriteByte(1)
    $pipe.Flush()
} catch {
    [System.IO.File]::WriteAllText($OutputFile + '.error', $_.Exception.Message)
    exit 1
} finally {
    if ($pipe) { $pipe.Dispose() }
}

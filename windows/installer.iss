; Build with Inno Setup 6. The payload is prepared by scripts/package-windows.mjs.
#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef StageDir
  #error StageDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif

[Setup]
AppId={{4D30F86F-17AB-4471-AD08-315162419095}
AppName=Codex Remote
AppVersion={#AppVersion}
AppPublisher=chensunlai
AppPublisherURL=https://github.com/chensunlai/codex-remote
DefaultDirName={autopf}\CodexRemote
DisableDirPage=no
DefaultGroupName=Codex Remote
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
MinVersion=10.0
OutputDir={#OutputDir}
OutputBaseFilename=codex-remote-windows-x64-setup
SetupIconFile=Assets\CodexRemote.ico
UninstallDisplayIcon={app}\CodexRemote.exe
AppMutex=Local\CodexRemote.Setup
CloseApplications=no
RestartApplications=no
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; Flags: unchecked

[Dirs]
Name: "{app}\data"; Flags: uninsneveruninstall

[Files]
Source: "installer-user.ps1"; Flags: dontcopy
Source: "{#StageDir}\*"; DestDir: "{app}"; Excludes: "data\*,*.pdb"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Codex Remote"; Filename: "{app}\CodexRemote.exe"
Name: "{autodesktop}\Codex Remote"; Filename: "{app}\CodexRemote.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\CodexRemote.exe"; Description: "Launch Codex Remote"; Flags: nowait postinstall skipifsilent runasoriginaluser

[Code]
function OpenProcess(Access: LongWord; Inherit: Boolean; PID: LongWord): THandle;
  external 'OpenProcess@kernel32.dll stdcall';
function WaitForSingleObject(Handle: THandle; Milliseconds: LongWord): LongWord;
  external 'WaitForSingleObject@kernel32.dll stdcall';
function CloseHandle(Handle: THandle): Boolean;
  external 'CloseHandle@kernel32.dll stdcall';

function InitializeSetup(): Boolean;
var
  PID: Integer;
  Handle: THandle;
begin
  Result := True;
  PID := StrToIntDef(ExpandConstant('{param:WAITPID|0}'), 0);
  if PID <> 0 then begin
    Handle := OpenProcess($00100000, False, PID);
    if Handle <> 0 then begin
      Result := WaitForSingleObject(Handle, 30000) = 0;
      CloseHandle(Handle);
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  DataDir, SIDFile, Script, SID, PipeName, PowerShell: String;
  SIDText: AnsiString;
  Code, I: Integer;
begin
  Result := '';
  DataDir := ExpandConstant('{app}\data');
  { Grant write access only to the user who launched Setup, even if UAC used
    another administrator's credentials. Program files retain their normal ACL. }
  SIDFile := ExpandConstant('{tmp}\data-owner.txt');
  DeleteFile(SIDFile);
  DeleteFile(SIDFile + '.error');
  PipeName := 'CodexRemote-Setup-' + ExtractFileName(ExpandConstant('{tmp}'));
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  ExtractTemporaryFile('installer-user.ps1');
  { Only the elevated helper touches {tmp}. Authenticate the original user
    through a local pipe instead of making Setup's temporary files writable. }
  if not Exec(PowerShell,
      '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' +
      ExpandConstant('{tmp}\installer-user.ps1') + '" -PipeName "' + PipeName +
      '" -OutputFile "' + SIDFile + '"',
      ExpandConstant('{sys}'), SW_HIDE, ewNoWait, Code) then begin
    Result := 'Cannot start user identification helper: ' + SysErrorMessage(Code);
    Exit;
  end;
  Script := '$ErrorActionPreference = ''Stop''; ' +
    '$p = New-Object System.IO.Pipes.NamedPipeClientStream(''.'', ''' + PipeName +
    ''', [System.IO.Pipes.PipeDirection]::InOut, [System.IO.Pipes.PipeOptions]::Asynchronous, ' +
    '[System.Security.Principal.TokenImpersonationLevel]::Impersonation); ' +
    'try { $p.Connect(30000); $p.WriteByte(1); $p.Flush(); ' +
    '$b = New-Object byte[] 1; $r = $p.ReadAsync($b, 0, 1); ' +
    'if (!$r.Wait(30000) -or $r.Result -ne 1 -or $b[0] -ne 1) { exit 1 } ' +
    '} finally { $p.Dispose() }';
  if not ExecAsOriginalUser(PowerShell,
      '-NoProfile -NonInteractive -Command "' + Script + '"',
      ExpandConstant('{sys}'), SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) or
      not LoadStringFromFile(SIDFile, SIDText) then begin
    Log('Original-user identification failed, code ' + IntToStr(Code));
    if LoadStringFromFile(SIDFile + '.error', SIDText) then
      Log('User identification helper: ' + String(SIDText));
    Result := 'Cannot identify the installing user. Please retry Setup with /LOG and check the installation log.';
    Exit;
  end;
  SID := Trim(String(SIDText));
  if Pos('S-1-', SID) <> 1 then begin
    Result := 'Invalid user SID.';
    Exit;
  end;
  for I := 3 to Length(SID) do
    if not ((SID[I] >= '0') and (SID[I] <= '9')) and (SID[I] <> '-') then begin
      Result := 'Invalid user SID.';
      Exit;
    end;
  if not ForceDirectories(DataDir) or
      not Exec(ExpandConstant('{sys}\icacls.exe'),
        '"' + DataDir + '" /inheritance:r /grant:r "*' + SID +
        ':(OI)(CI)M" "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F"',
        '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
    Result := 'Cannot prepare the local data directory.';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Command: String;
begin
  if CurUninstallStep = usUninstall then
    if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run',
        'CodexRemote', Command) then
      if CompareText(Command, '"' + ExpandConstant('{app}\CodexRemote.exe') + '" --autostart') = 0 then
        RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'CodexRemote');
end;

; GameGrab installer. Compile with Inno Setup 6+.
;   iscc installer.iss
; Output: dist\GameGrab-Setup.exe

#define AppName         "GameGrab"
#define AppVersion      "1.0.0"
#define AppPublisher    "GameGrab"
#define AppExe          "GameGrab.exe"
#define SourceDir       "bin\publish"

[Setup]
AppId={{8F7A2D0E-6E0C-4AF1-9BBB-7B3EFEF6B541}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
AppVerName={#AppName} {#AppVersion}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=GameGrab-Setup
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
SetupIconFile=src\GameGrab\Assets\app.ico

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop icon"; GroupDescription: "Additional icons:"; Flags: unchecked
Name: "autostart"; Description: "Run on Windows startup (minimized)"; GroupDescription: "Additional options:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
Source: "README.md";             DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}";                    Filename: "{app}\{#AppExe}"
Name: "{group}\Uninstall {#AppName}";          Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}";              Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
  ValueType: string; ValueName: "{#AppName}"; \
  ValueData: """{app}\{#AppExe}"" --minimized"; \
  Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\{#AppExe}"; Description: "Launch {#AppName}"; Flags: nowait postinstall skipifsilent

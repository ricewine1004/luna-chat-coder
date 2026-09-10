#define MyAppName "MyDesk AI"
#define MyAppVersion "0.3.9"
#define MyAppPublisher "MyDesk AI"
#define MyAppExeName "MyDeskAI.exe"

[Setup]
AppId={{A55F9B7A-7B64-4D34-B2D9-0EBC72CFA033}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\MyDesk AI
DefaultGroupName=MyDesk AI
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=MyDesk_AI_PC_AutoUpdate_0.3.9
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\Assets\mydesk.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
UsePreviousAppDir=yes
VersionInfoVersion=0.3.9.0
VersionInfoProductName=MyDesk AI
VersionInfoDescription=MyDesk AI Windows + Cloudflare Common Auto Updater

[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\MyDesk AI"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\MyDesk AI"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "MyDesk AI 실행 및 공통 업데이트 확인"; Flags: nowait postinstall skipifsilent

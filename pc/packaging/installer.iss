; Inno Setup script for HDR Studio (Windows).
; Build the app folder first:  python -m PyInstaller packaging/hdrstudio.spec --noconfirm
; Then:                        ISCC /DAppVer=1.0.0 packaging\installer.iss
;
; Per-user install (no administrator rights). The app builds its .onnx models and downloads the
; optional large weights into its own folder on first use, so that folder must stay writable.

#ifndef AppVer
  #define AppVer "0.0.0"
#endif

[Setup]
AppId={{6B2F3C0E-8E1D-4B7A-9C55-48445253544F}
AppName=HDR Studio
AppVersion={#AppVer}
AppPublisher=HDR Studio
AppPublisherURL=https://github.com/6thd/HdrStudio
AppSupportURL=https://github.com/6thd/HdrStudio/issues
DefaultDirName={localappdata}\Programs\HDR Studio
DefaultGroupName=HDR Studio
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
WizardStyle=modern
SetupIconFile=hdrstudio.ico
UninstallDisplayIcon={app}\HDR Studio.exe
OutputDir=..\dist
OutputBaseFilename=HDRStudio-Setup-{#AppVer}
Compression=lzma2/max
SolidCompression=yes
LZMAUseSeparateProcess=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "ffmpeg"; Description: "Install FFmpeg with winget (required to open and export video; accepts its package terms)"; GroupDescription: "Components:"; Check: FfmpegMissing

[Files]
Source: "..\dist\HDR Studio\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\HDR Studio"; Filename: "{app}\HDR Studio.exe"
Name: "{group}\Uninstall HDR Studio"; Filename: "{uninstallexe}"
Name: "{userdesktop}\HDR Studio"; Filename: "{app}\HDR Studio.exe"; Tasks: desktopicon

[Run]
Filename: "{cmd}"; Parameters: "/c winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements"; StatusMsg: "Installing FFmpeg (a large download, this can take a few minutes)..."; Tasks: ffmpeg; Flags: runhidden waituntilterminated
Filename: "{app}\HDR Studio.exe"; Description: "Start HDR Studio"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; models built or downloaded after installation
Type: filesandordirs; Name: "{app}\_internal\models"

[Code]
function FfmpegMissing: Boolean;
begin
  Result := (FileSearch('ffmpeg.exe', GetEnv('PATH')) = '')
    and not FileExists(ExpandConstant('{localappdata}\Microsoft\WinGet\Links\ffmpeg.exe'))
    and not FileExists(ExpandConstant('{app}\_internal\bin\ffmpeg.exe'));
end;

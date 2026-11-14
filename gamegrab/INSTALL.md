# GameGrab — install & build

Three ways to get it running. Pick one.

---

## 1. "Zbuduj mi" — CI (zero setup on your machine)

GitHub Actions already builds it. Nothing to install locally.

1. Open: `https://github.com/patryk75676/e/actions/workflows/gamegrab.yml`
2. Hit **Run workflow** → pick the `claude/ollie-operating-spec-ypsitm` branch → **Run**.
   (The workflow also runs on every push to `gamegrab/**`, so if you pushed, it's already building.)
3. Wait ~4–6 min. Click into the run.
4. Scroll to **Artifacts** at the bottom → download **GameGrab-windows-x64**.
   Zip contains:
   - `GameGrab.exe` — portable single-file, double-click and go
   - `GameGrab-Setup.exe` — Inno Setup installer (Start-menu shortcut, uninstaller, optional desktop icon, optional "run on startup minimized")
   - `GameGrab-portable-x64.zip` — exe + README bundled

To cut a versioned release with download links on the repo's Releases page: push a tag `v1.0.0` — the workflow builds and attaches the files to the Release.

---

## 2. Build locally with the installer (PowerShell on Windows)

### Requirements

- Windows 10/11 x64
- [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0) (`dotnet --version` → `8.0.x`)
- [Inno Setup 6](https://jrsoftware.org/isdl.php) (only for the installer, not the exe)

### Steps

```powershell
cd gamegrab

# 1. Build the single-file self-contained exe
powershell -ExecutionPolicy Bypass -File .\build.ps1
# -> bin\publish\GameGrab.exe   (self-contained, no .NET install needed on target)

# 2. (optional) Compile the installer
& "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer.iss
# -> dist\GameGrab-Setup.exe
```

Run either output. The installer puts it under `%LocalAppData%\Programs\GameGrab\` by default (no admin required). The raw exe is portable — copy it anywhere.

---

## 3. Build with no installer at all

Just the raw exe:

```powershell
cd gamegrab
powershell -ExecutionPolicy Bypass -File .\build.ps1
.\bin\publish\GameGrab.exe
```

---

## First-run checklist

- Firewall will prompt on first magnet — allow it, or BT peers won't reach you.
- Windows SmartScreen may complain the exe is unsigned. Click **More info** → **Run anyway**.
- Settings live in `%AppData%\GameGrab\settings.json`, cache in `%LocalAppData%\GameGrab\cache`.
- **Library+** needs a Steam loader (GreenLuma or SteamTools) installed next to `steam.exe` — the page's InfoBar tells you which is detected.

---

## Troubleshooting

- `dotnet: command not found` — install the .NET 8 SDK and reopen the shell.
- `ISCC: command not found` — install Inno Setup 6.
- Cert warnings on build servers behind corporate proxies — set `DOTNET_SYSTEM_NET_HTTP_USESOCKETSHTTPHANDLER=1` or configure the Nuget proxy.
- Build complains about `wpf-ui`/`MonoTorrent` version mismatch — delete `obj\` and `bin\`, re-run `dotnet restore`.
- "Icon file is corrupt" at Inno compile — rebuild the ico by running `src/GameGrab/Assets/app.ico`'s source steps (see repo history) or use any other `.ico` and point `SetupIconFile` at it.

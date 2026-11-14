# GameGrab

Native Windows desktop app. One `.exe`, no runtime to install.

Searches eight piracy indexes in parallel, aggregates magnets, `.torrent`
files and direct HTTP mirrors, and downloads torrents in-process via a
managed BitTorrent engine (DHT + LSD + uTP + PEX). Direct-mirror links
from filehosts open in your default browser — there is no honest way to
drive 1fichier / Pixeldrain / Rapidgator from inside an app without a
premium debrid account.

## Sources wired

| Source      | Kind                        | Notes                                |
| ----------- | --------------------------- | ------------------------------------ |
| 1337x       | magnet                      | auto-picks a live mirror             |
| TPB         | magnet                      | apibay JSON, cat 400 (games)         |
| RuTracker   | magnet + .torrent           | optional login in Settings for 100%  |
| FitGirl     | magnet + direct mirrors     |                                      |
| DODI        | magnet + direct mirrors     |                                      |
| SteamRIP    | direct mirrors              | pre-installed, DRM stripped          |
| Online-Fix  | direct mirrors + magnets    | multiplayer cracks                   |
| GOG-Games   | magnet + mirrors            | GOG rips                             |

Add more by implementing `ISource` in `src/GameGrab/Sources/` and
registering it in `Services/ServiceHost.cs`.

## Build (Windows, PowerShell)

Requires the .NET 8 SDK. Nothing else.

    powershell -ExecutionPolicy Bypass -File .\build.ps1

Output:

    bin\publish\GameGrab.exe       # one file, ~80-100 MB, self-contained x64

Portable: copy it anywhere, run it. Settings live in
`%AppData%\GameGrab\settings.json`; cache in
`%LocalAppData%\GameGrab\cache`.

## Usage

- Type the game name, press Enter.
- Results pour in from every source in parallel, ranked by seeders.
- Double-click a row, or `Download selected`, to resolve + queue.
- `Paste link` picks up a magnet or `.torrent` URL from clipboard.
- `Add .torrent` for local files.
- The transfers grid shows live progress, speed, ETA, peers.

## Notes

- First magnet add waits on DHT for metadata (10-60 s is normal).
- Firewall: inbound TCP/UDP on port `6881` (configurable in the JSON
  settings file) improves peer discovery; the engine works without it
  too.
- The `.torrent` dispatcher is MonoTorrent 3.x. The engine survives
  restart via its own fast-resume cache.
- Direct-mirror downloading from 1fichier/Rapidgator/etc. is left to
  your browser or JDownloader deliberately — those hosts require captcha
  and per-host paid-account handling that does not belong in the app.

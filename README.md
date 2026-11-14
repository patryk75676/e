# GameGrab

Native Windows app. Finds and downloads PC game releases across 10 piracy
indexes in parallel, drives torrents in-process (MonoTorrent 3.x), and
adds any Steam AppID to the Steam library via GreenLuma / SteamTools
plugins.

Project tree lives in [`gamegrab/`](./gamegrab). See
[`gamegrab/INSTALL.md`](./gamegrab/INSTALL.md) for build + install paths.

CI builds every push on Windows runners and uploads the installer, raw
exe and portable zip as artifacts of the
[GameGrab build](https://github.com/patryk75676/e/actions/workflows/gamegrab.yml)
workflow.

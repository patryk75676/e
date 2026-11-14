#requires -Version 5
<#
.SYNOPSIS
    Build GameGrab as a single-file self-contained Windows executable.
.DESCRIPTION
    Produces: bin\publish\GameGrab.exe (x64, win10+, .NET 8 bundled)
    Run from the repo root in PowerShell:
        powershell -ExecutionPolicy Bypass -File .\build.ps1
.PARAMETER Configuration
    Release (default) or Debug.
#>
param(
    [string] $Configuration = "Release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $root
try {
    if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
        throw "dotnet SDK 8.x is required. Install from https://dotnet.microsoft.com/download"
    }

    dotnet --version | Out-Host

    Write-Host "==> restore" -ForegroundColor Cyan
    dotnet restore .\GameGrab.sln

    Write-Host "==> publish single-file x64" -ForegroundColor Cyan
    $out = Join-Path $root "bin\publish"
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    dotnet publish .\src\GameGrab\GameGrab.csproj `
        -c $Configuration `
        -r win-x64 `
        --self-contained true `
        -p:PublishSingleFile=true `
        -p:EnableCompressionInSingleFile=true `
        -p:IncludeNativeLibrariesForSelfExtract=true `
        -p:DebugType=embedded `
        -o $out

    $exe = Join-Path $out "GameGrab.exe"
    if (-not (Test-Path $exe)) { throw "build produced no exe" }
    Write-Host ""
    Write-Host "built: $exe" -ForegroundColor Green
    Write-Host "size:  $([math]::Round((Get-Item $exe).Length / 1MB, 2)) MB"
}
finally {
    Pop-Location
}

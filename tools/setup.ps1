# One-time (and idempotent) setup for a fresh clone:
#   - a portable JDK 25 in .jdk\ (Minecraft 26.x needs Java 25; nothing is installed system-wide)
#   - the void world in run\saves\PortalCraft
#   - the Portal plugin in Portal\portal\addons (builds it if Visual Studio is present, else uses the prebuilt DLL)
# Usage: powershell -ExecutionPolicy Bypass -File tools\setup.ps1 [-Portal "D:\path\to\Portal"]
param([string]$Portal = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# --- JDK 25 ---------------------------------------------------------------------------------
$jdk = Get-ChildItem -Path (Join-Path $root ".jdk") -Directory -Filter "jdk-25*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) {
	Write-Host "Downloading a portable JDK 25 (about 140 MB) into .jdk ..."
	New-Item -ItemType Directory -Force (Join-Path $root ".jdk") | Out-Null
	$zip = Join-Path $root ".jdk\jdk25.zip"
	Invoke-WebRequest -UseBasicParsing -Uri "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile $zip
	Expand-Archive -Path $zip -DestinationPath (Join-Path $root ".jdk") -Force
	Remove-Item $zip
	$jdk = Get-ChildItem -Path (Join-Path $root ".jdk") -Directory -Filter "jdk-25*" | Select-Object -First 1
}
Write-Host "JDK: $($jdk.FullName)"

# --- void world -------------------------------------------------------------------------------
$world = Join-Path $root "run\saves\PortalCraft"
if (-not (Test-Path (Join-Path $world "level.dat"))) {
	New-Item -ItemType Directory -Force $world | Out-Null
	Copy-Item (Join-Path $root "worlds\PortalCraft\level.dat") $world
	Write-Host "Created the PortalCraft world (void, fall damage off)"
}

# --- Portal plugin ----------------------------------------------------------------------------
if (-not $Portal) { $Portal = & (Join-Path $PSScriptRoot "find-portal.ps1") }
if (-not $Portal) {
	Write-Warning "Steam Portal not found. Install it, or pass -Portal 'path\to\Portal'. Skipping the plugin."
	exit 0
}
Write-Host "Portal: $Portal"

$dll = Join-Path $root "host\portal\build\portalcraft.dll"
$vcvars = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat"
if (Test-Path $vcvars) {
	cmd /c (Join-Path $root "host\portal\build.cmd") | Out-Host
}
if (-not (Test-Path $dll)) {
	$dll = Join-Path $root "host\portal\prebuilt\portalcraft.dll"
	Write-Host "Using the prebuilt plugin (no Visual Studio Build Tools here)"
}
$addons = Join-Path $Portal "portal\addons"
if (Get-Process hl2 -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Portal*" }) {
	Write-Host "Portal is running, so its plugin is in use: leaving the installed copy alone (restart Portal to update it)"
	exit 0
}
New-Item -ItemType Directory -Force $addons | Out-Null
Copy-Item $dll (Join-Path $addons "portalcraft.dll") -Force
Set-Content -Path (Join-Path $addons "portalcraft.vdf") -Encoding ascii -Value "`"Plugin`"`r`n{`r`n`t`"file`"`t`"addons/portalcraft`"`r`n}"
Write-Host "Plugin installed to $addons"
# The plugin starts Minecraft with Portal (host\portal\src\launcher.cpp). An existing ini is kept.
$ini = Join-Path $addons "portalcraft.ini"
if (-not (Test-Path $ini)) {
	$launcher = Join-Path $root "gradle.cmd"
	Set-Content -Path $ini -Encoding ascii -Value @(
		"[Minecraft]",
		"start_with_portal=1",
		"launcher=$launcher",
		'arguments=runClient --no-configuration-cache --args="--quickPlaySingleplayer PortalCraft"',
		"directory=$root"
	)
	Write-Host "Wrote $ini (Portal starts Minecraft by itself)"
}

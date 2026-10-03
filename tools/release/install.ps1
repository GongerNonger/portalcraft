# Installs a PortalCraft release (run "Install PortalCraft.cmd" next to this file):
#   - the Portal plugin into Steam Portal's portal\addons (with portalcraft.ini: what to start);
#   - the Minecraft it starts into %LOCALAPPDATA%\PortalCraft: a portable Prism Launcher with the
#     PortalCraft instance (Minecraft 26.3, Fabric, Fabric API, PortalCraft) and its void world.
# Installing over an older release keeps the Prism sign-in, the world and Prism's settings; the
# instance's setup and its PortalCraft and Fabric API jars are replaced.
#   -Portal <folder>   Portal's install folder, if Steam's library list doesn't find it
param([string]$Portal)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot

$portal = if ($Portal) { $Portal } else { & (Join-Path $here "find-portal.ps1") }
if ($portal -and -not (Test-Path (Join-Path $portal "portal\gameinfo.txt"))) {
	Write-Host "$portal doesn't look like Portal's folder (no portal\gameinfo.txt)."
	exit 1
}
if (-not $portal) {
	Write-Host "Steam Portal wasn't found in any Steam library. Install Portal from Steam first."
	exit 1
}
Write-Host "Portal: $portal"
if (Get-Process hl2 -ErrorAction SilentlyContinue) {
	Write-Host "Portal is running. Close it, then run this again."
	exit 1
}

# --- Minecraft ---------------------------------------------------------------------------------
$dest = Join-Path $env:LOCALAPPDATA "PortalCraft"
$prism = Join-Path $dest "Prism"
$instance = Join-Path $prism "instances\PortalCraft"
$bundle = Join-Path $here "minecraft"
if (Get-Process prismlauncher -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$prism*" }) {
	Write-Host "PortalCraft's Minecraft (Prism Launcher) is running. Close it, then run this again."
	exit 1
}
if (-not (Test-Path (Join-Path $prism "prismlauncher.exe"))) {
	New-Item -ItemType Directory -Force $dest | Out-Null
	Copy-Item -Recurse -Force (Join-Path $bundle "Prism") $dest
	Write-Host "Installed Prism Launcher and the PortalCraft instance to $prism"
} else {
	# An update: Prism itself, the instance's setup and mods; never accounts, saves or settings
	# (the bundle has none of those, and nothing is deleted but the two jars it replaces).
	Get-ChildItem (Join-Path $bundle "Prism") -File | Copy-Item -Destination $prism -Force
	foreach ($dir in Get-ChildItem (Join-Path $bundle "Prism") -Directory | Where-Object { $_.Name -ne "instances" }) {
		Copy-Item -Recurse -Force $dir.FullName $prism
	}
	$mods = Join-Path $instance ".minecraft\mods"
	if (Test-Path $mods) {
		Get-ChildItem $mods -Filter "portalcraft-*.jar" | Remove-Item -Force
		Get-ChildItem $mods -Filter "fabric-api-*.jar" | Remove-Item -Force
	}
	Copy-Item -Recurse -Force (Join-Path $bundle "Prism\instances\PortalCraft") (Join-Path $prism "instances")
	Write-Host "Updated $prism (kept your sign-in, settings and world)"
}
$cfg = Join-Path $prism "prismlauncher.cfg"
if (-not (Test-Path $cfg)) {
	Copy-Item (Join-Path $bundle "defaults\prismlauncher.cfg") $cfg
}
$world = Join-Path $instance ".minecraft\saves\PortalCraft"
if (-not (Test-Path (Join-Path $world "level.dat"))) {
	New-Item -ItemType Directory -Force $world | Out-Null
	Copy-Item (Join-Path $bundle "world\level.dat") $world
	Write-Host "Created the PortalCraft world"
}

# --- Portal plugin -----------------------------------------------------------------------------
$addons = Join-Path $portal "portal\addons"
New-Item -ItemType Directory -Force $addons | Out-Null
Copy-Item (Join-Path $here "plugin\portalcraft.dll") (Join-Path $addons "portalcraft.dll") -Force
Set-Content -Path (Join-Path $addons "portalcraft.vdf") -Encoding ascii -Value "`"Plugin`"`r`n{`r`n`t`"file`"`t`"addons/portalcraft`"`r`n}"
Set-Content -Path (Join-Path $addons "portalcraft.ini") -Encoding ascii -Value @(
	"[Minecraft]",
	"; 0: start PortalCraft's Minecraft yourself (Prism Launcher, the PortalCraft instance)",
	"start_with_portal=1",
	"launcher=$(Join-Path $prism 'prismlauncher.exe')",
	"arguments=--launch PortalCraft --world PortalCraft",
	"directory=$prism",
	"log=$(Join-Path $instance '.minecraft\logs\latest.log')"
)
Write-Host "Installed the plugin to $addons"
Write-Host ""
Write-Host "Done. Start Portal from Steam. The first time, Prism Launcher opens and asks you to sign in"
Write-Host "with the Microsoft account that owns Minecraft: Java Edition, then downloads Minecraft (a few"
Write-Host "minutes). After that, Minecraft starts hidden with Portal by itself."

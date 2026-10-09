# Installs a PortalCraft release (PortalCraft-Setup.exe and "Install PortalCraft.cmd" both run this):
#   - the Portal plugin into Steam Portal's portal\addons (with portalcraft.ini: what to start);
#   - the Minecraft it starts into %LOCALAPPDATA%\PortalCraft: a portable Prism Launcher with the
#     PortalCraft instance (Minecraft 26.3, Fabric, Fabric API, PortalCraft, the voxel portal gun)
#     and its void world;
#   - the launcher (PortalCraft.ps1) beside it and a "PortalCraft" shortcut on the desktop and in
#     the Start menu: it looks for a newer release, installs it, and starts Portal.
# Installing over an older release keeps the Prism sign-in, the world and every setting; the
# instance's setup, its PortalCraft and Fabric API jars and the gun's resource pack are replaced.
#   -Portal <folder>   Portal's install folder, if Steam's library list doesn't find it
#   -Quiet             no closing notes (the launcher updating itself)
#   -NoShortcuts       no desktop or Start menu shortcut
# PORTALCRAFT_HOME, if set, is where Minecraft goes instead of %LOCALAPPDATA%\PortalCraft, and
# PORTALCRAFT_PORTAL the Portal folder (tests: a release installed without touching the real ones).
param([string]$Portal, [switch]$Quiet, [switch]$NoShortcuts)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot

$portal = if ($Portal) { $Portal } elseif ($env:PORTALCRAFT_PORTAL) { $env:PORTALCRAFT_PORTAL } else { & (Join-Path $here "find-portal.ps1") }
if ($portal -and -not (Test-Path (Join-Path $portal "portal\gameinfo.txt"))) {
	Write-Host "$portal doesn't look like Portal's folder (no portal\gameinfo.txt)."
	exit 1
}
if (-not $portal) {
	Write-Host "Steam Portal wasn't found in any Steam library. Install Portal from Steam first, then run this again."
	exit 1
}
Write-Host "Portal: $portal"
if (Get-Process hl2 -ErrorAction SilentlyContinue) {
	Write-Host "Portal is running. Close it, then run this again."
	exit 1
}

# --- Minecraft ---------------------------------------------------------------------------------
$dest = if ($env:PORTALCRAFT_HOME) { $env:PORTALCRAFT_HOME } else { Join-Path $env:LOCALAPPDATA "PortalCraft" }
$prism = Join-Path $dest "Prism"
$instance = Join-Path $prism "instances\PortalCraft"
$bundle = Join-Path $here "minecraft"
if (Get-Process prismlauncher -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$prism*" }) {
	Write-Host "PortalCraft's Minecraft (Prism Launcher) is running. Close it, then run this again."
	exit 1
}
$fresh = -not (Test-Path (Join-Path $prism "prismlauncher.exe"))
if ($fresh) {
	New-Item -ItemType Directory -Force $dest | Out-Null
	Copy-Item -Recurse -Force (Join-Path $bundle "Prism") $dest
	Write-Host "Installed Prism Launcher and the PortalCraft instance to $prism"
} else {
	# An update: Prism itself, the instance's setup, mods and the gun's pack; never accounts, saves
	# or settings (the bundle has none of those, and nothing is deleted but what it replaces).
	Get-ChildItem (Join-Path $bundle "Prism") -File | Copy-Item -Destination $prism -Force
	foreach ($dir in Get-ChildItem (Join-Path $bundle "Prism") -Directory | Where-Object { $_.Name -ne "instances" }) {
		Copy-Item -Recurse -Force $dir.FullName $prism
	}
	$mods = Join-Path $instance ".minecraft\mods"
	if (Test-Path $mods) {
		Get-ChildItem $mods -Filter "portalcraft-*.jar" | Remove-Item -Force
		Get-ChildItem $mods -Filter "fabric-api-*.jar" | Remove-Item -Force
	}
	$pack = Join-Path $instance ".minecraft\resourcepacks\portalcraft-voxel-gun"
	if (Test-Path $pack) {
		Remove-Item -Recurse -Force $pack
	}
	Copy-Item -Recurse -Force (Join-Path $bundle "Prism\instances\PortalCraft") (Join-Path $prism "instances")
	Write-Host "Updated $prism (kept your sign-in, settings and world)"
}
$cfg = Join-Path $prism "prismlauncher.cfg"
if (-not (Test-Path $cfg)) {
	Copy-Item (Join-Path $bundle "defaults\prismlauncher.cfg") $cfg
}
# Minecraft's own settings, the first time only: the voxel portal gun's resource pack switched on.
# (Minecraft fills in every other setting itself. An update leaves the player's file alone.)
$options = Join-Path $instance ".minecraft\options.txt"
if (-not (Test-Path $options)) {
	Copy-Item (Join-Path $bundle "defaults\options.txt") $options
}
$world = Join-Path $instance ".minecraft\saves\PortalCraft"
if (-not (Test-Path (Join-Path $world "level.dat"))) {
	New-Item -ItemType Directory -Force $world | Out-Null
	# level.dat and data\minecraft (the generator settings and game rules: without them Minecraft
	# 26.3 refuses the world, "Overworld settings missing")
	Copy-Item (Join-Path $bundle "world\*") $world -Recurse
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

# --- The launcher, which keeps all of this up to date --------------------------------------------
Copy-Item (Join-Path $here "PortalCraft.ps1") $dest -Force
Copy-Item (Join-Path $here "find-portal.ps1") $dest -Force
Copy-Item (Join-Path $here "README.txt") $dest -Force
Copy-Item (Join-Path $bundle "bundle-version.txt") (Join-Path $dest "version.txt") -Force
if (-not $NoShortcuts) {
	$shell = New-Object -ComObject WScript.Shell
	$targets = @([Environment]::GetFolderPath("Desktop"), [Environment]::GetFolderPath("Programs"))
	foreach ($folder in $targets) {
		$link = $shell.CreateShortcut((Join-Path $folder "PortalCraft.lnk"))
		$link.TargetPath = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
		$link.Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$(Join-Path $dest 'PortalCraft.ps1')`""
		$link.WorkingDirectory = $dest
		$link.IconLocation = "$(Join-Path $portal 'hl2.exe'),0"
		$link.Description = "Play Portal as Steve (checks for a newer PortalCraft first)"
		$link.Save()
	}
	Write-Host "Put a PortalCraft shortcut on the desktop and in the Start menu"
}
if (-not $Quiet) {
	Write-Host ""
	Write-Host "Done. Start it with the PortalCraft shortcut on your desktop: it checks for a newer"
	Write-Host "version, then starts Portal. The first time, Prism Launcher opens and asks you to sign in"
	Write-Host "with the Microsoft account that owns Minecraft: Java Edition, then downloads Minecraft (a few"
	Write-Host "minutes). After that, Minecraft starts hidden with Portal by itself."
}

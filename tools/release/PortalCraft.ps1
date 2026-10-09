# The PortalCraft shortcut runs this (from %LOCALAPPDATA%\PortalCraft): if GitHub has a newer
# release than the one installed, it is downloaded and installed first (the sign-in, the world and
# every setting are kept), and then Portal is started through Steam. No network, or GitHub not
# answering in a few seconds: Portal starts with what is installed.
#   -NoStart   check and update only (tests)
# PORTALCRAFT_REPO, if set, is the GitHub repository to ask instead (tests).
param([switch]$NoStart)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$here = $PSScriptRoot
$repo = if ($env:PORTALCRAFT_REPO) { $env:PORTALCRAFT_REPO } else { "GongerNonger/portalcraft" }
$log = Join-Path $here "launcher.log"
function Note([string]$text) {
	Add-Content -Path $log -Value ("{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $text) -ErrorAction SilentlyContinue
}

# "PortalCraft 0.2.0, Prism Launcher ..." -> 0.2.0
function Get-Installed {
	$file = Join-Path $here "version.txt"
	if (Test-Path $file) {
		$m = [regex]::Match((Get-Content $file -Raw), 'PortalCraft\s+(\d+(\.\d+)+)')
		if ($m.Success) { return [version]$m.Groups[1].Value }
	}
	return [version]"0.0"
}

# A small window while an update goes in: the shortcut runs hidden, and a download of this size
# with nothing on screen looks like nothing happening.
function Show-Updating([string]$text) {
	try {
		Add-Type -AssemblyName System.Windows.Forms
		$form = New-Object System.Windows.Forms.Form
		$form.Text = "PortalCraft"
		$form.Width = 420; $form.Height = 130
		$form.StartPosition = "CenterScreen"
		$form.FormBorderStyle = "FixedDialog"; $form.MaximizeBox = $false; $form.MinimizeBox = $false
		$form.TopMost = $true
		$label = New-Object System.Windows.Forms.Label
		$label.Text = $text
		$label.Dock = "Fill"; $label.TextAlign = "MiddleCenter"
		$form.Controls.Add($label)
		$form.Show(); $form.Refresh()
		return $form
	} catch { return $null }
}

function Update-PortalCraft {
	$installed = Get-Installed
	[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
	$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" -TimeoutSec 6 -Headers @{ "User-Agent" = "PortalCraft-launcher" }
	$m = [regex]::Match([string]$release.tag_name, '(\d+(\.\d+)+)')
	if (-not $m.Success) { Note "latest release has no version in its tag ($($release.tag_name))"; return }
	$latest = [version]$m.Groups[1].Value
	if ($latest -le $installed) { Note "up to date ($installed)"; return }
	$asset = $release.assets | Where-Object { $_.name -like "PortalCraft-*.zip" } | Select-Object -First 1
	if (-not $asset) { Note "release $latest has no PortalCraft-*.zip"; return }
	if (Get-Process hl2 -ErrorAction SilentlyContinue) { Note "Portal is running: $latest waits for the next start"; return }

	Note "updating $installed -> $latest"
	$window = Show-Updating "Updating PortalCraft to $latest ...`nThis takes a minute. Portal starts when it is done."
	try {
		$work = Join-Path $env:TEMP "PortalCraft-update"
		if (Test-Path $work) { Remove-Item -Recurse -Force $work }
		New-Item -ItemType Directory -Force $work | Out-Null
		$zip = Join-Path $work $asset.name
		Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zip -UseBasicParsing -TimeoutSec 600 -Headers @{ "User-Agent" = "PortalCraft-launcher" }
		Expand-Archive -Path $zip -DestinationPath $work -Force
		$installer = Get-ChildItem $work -Recurse -Filter "install.ps1" | Select-Object -First 1
		if (-not $installer) { throw "the release has no install.ps1" }
		$out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer.FullName -Quiet 2>&1
		$out | ForEach-Object { Note "  $_" }
		if ($LASTEXITCODE) { throw "the installer stopped (see above)" }
		Note "now $(Get-Installed)"
		Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
	} finally {
		if ($window) { $window.Close() }
	}
}

try { Update-PortalCraft } catch { Note "update check failed: $($_.Exception.Message)" }
if ($NoStart) { return }

# Portal, through Steam (app 400), with the plugin allowed to load. Already running: nothing to do.
if (Get-Process hl2 -ErrorAction SilentlyContinue) { return }
$steam = (Get-ItemProperty -Path "HKCU:\Software\Valve\Steam" -Name SteamExe -ErrorAction SilentlyContinue).SteamExe
if ($steam -and (Test-Path $steam)) {
	Start-Process -FilePath $steam -ArgumentList "-applaunch", "400", "-insecure", "-novid"
} else {
	Start-Process "steam://rungameid/400"
}
Note "started Portal"

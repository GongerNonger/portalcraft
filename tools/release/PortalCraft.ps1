# The PortalCraft shortcut runs this (from %LOCALAPPDATA%\PortalCraft): if GitHub has a newer
# release than the one installed, it is downloaded and installed first (the sign-in, the world and
# every setting are kept), and then Portal is started through Steam. No network, or GitHub not
# answering in a few seconds: Portal starts with what is installed.
#   -Friends   first ask how to play: alone, hosting for friends, or joining a friend's address
#              (the "PortalCraft with friends" shortcut); the answer is kept for every later start
#   -NoStart   check and update only (tests)
# PORTALCRAFT_REPO, if set, is the GitHub repository to ask instead (tests).
param([switch]$Friends, [switch]$NoStart)
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

# --- Playing with friends ---------------------------------------------------------------------
# friends.txt beside this script holds the choice: "alone", "host <port>" or "join <address>". It is
# written into Portal's portalcraft.ini ([Friends] host_port= / join=) before every start, since an
# update rewrites that file. Hosting opens this player's Minecraft world to the network; a friend who
# joins is in that same Minecraft world, and their own Portal follows the host's from map to map.
$friendsFile = Join-Path $here "friends.txt"
$defaultPort = 25590
function Get-FriendsChoice {
	if (Test-Path $friendsFile) { return (Get-Content $friendsFile -Raw).Trim() }
	return "alone"
}

function Get-LocalAddresses {
	try {
		return @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
			Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } | Select-Object -ExpandProperty IPAddress)
	} catch { return @() }
}

function Show-FriendsDialog {
	Add-Type -AssemblyName System.Windows.Forms
	$now = Get-FriendsChoice
	$form = New-Object System.Windows.Forms.Form
	$form.Text = "PortalCraft with friends"
	$form.Width = 470; $form.Height = 330
	$form.StartPosition = "CenterScreen"
	$form.FormBorderStyle = "FixedDialog"; $form.MaximizeBox = $false; $form.MinimizeBox = $false
	$alone = New-Object System.Windows.Forms.RadioButton
	$alone.Text = "Play alone"; $alone.Left = 20; $alone.Top = 15; $alone.Width = 400
	$host_ = New-Object System.Windows.Forms.RadioButton
	$host_.Text = "Host: friends join my world"; $host_.Left = 20; $host_.Top = 45; $host_.Width = 400
	$hostNote = New-Object System.Windows.Forms.Label
	$mine = (Get-LocalAddresses | ForEach-Object { "$($_):$defaultPort" }) -join "   or   "
	$hostNote.Text = "On the same network, friends join:  $mine`nOver the internet they need a public address for port $defaultPort (TCP): a tunnel such as playit.gg, or a port forward on your router."
	$hostNote.Left = 40; $hostNote.Top = 70; $hostNote.Width = 400; $hostNote.Height = 62
	$join = New-Object System.Windows.Forms.RadioButton
	$join.Text = "Join a friend. Their address:"; $join.Left = 20; $join.Top = 140; $join.Width = 400
	$address = New-Object System.Windows.Forms.TextBox
	$address.Left = 40; $address.Top = 166; $address.Width = 300
	$joinNote = New-Object System.Windows.Forms.Label
	$joinNote.Text = "Like 192.168.1.20:$defaultPort. Start after your friend has: your game goes to whatever chamber theirs is in."
	$joinNote.Left = 40; $joinNote.Top = 192; $joinNote.Width = 400; $joinNote.Height = 34
	if ($now -like "host*") { $host_.Checked = $true }
	elseif ($now -like "join *") { $join.Checked = $true; $address.Text = $now.Substring(5).Trim() }
	else { $alone.Checked = $true }
	$ok = New-Object System.Windows.Forms.Button
	$ok.Text = "Play"; $ok.Left = 250; $ok.Top = 245; $ok.Width = 90; $ok.DialogResult = "OK"
	$cancel = New-Object System.Windows.Forms.Button
	$cancel.Text = "Cancel"; $cancel.Left = 350; $cancel.Top = 245; $cancel.Width = 90; $cancel.DialogResult = "Cancel"
	$form.AcceptButton = $ok; $form.CancelButton = $cancel
	$form.Controls.AddRange(@($alone, $host_, $hostNote, $join, $address, $joinNote, $ok, $cancel))
	if ($form.ShowDialog() -ne "OK") { return $false }
	$choice = "alone"
	if ($host_.Checked) { $choice = "host $defaultPort" }
	elseif ($join.Checked) {
		$a = $address.Text.Trim()
		if ($a -notmatch '^[A-Za-z0-9.\-]+(:\d{1,5})?$') {
			[System.Windows.Forms.MessageBox]::Show("That doesn't look like an address. It should be like 192.168.1.20:$defaultPort.", "PortalCraft") | Out-Null
			return $false
		}
		if ($a -notmatch ':\d+$') { $a = "$($a):$defaultPort" }
		$choice = "join $a"
	}
	Set-Content -Path $friendsFile -Value $choice -Encoding ascii
	Note "friends: $choice"
	return $true
}

# The choice into Portal's portalcraft.ini: its [Friends] section rewritten, the rest left as it is.
function Set-FriendsInIni {
	$portal = if ($env:PORTALCRAFT_PORTAL) { $env:PORTALCRAFT_PORTAL } else { & (Join-Path $here "find-portal.ps1") }
	if (-not $portal) { return }
	$ini = Join-Path $portal "portal\addons\portalcraft.ini"
	if (-not (Test-Path $ini)) { return }
	$kept = @(); $inFriends = $false
	foreach ($line in Get-Content $ini) {
		if ($line -match '^\s*\[(.+)\]\s*$') { $inFriends = $Matches[1] -eq "Friends" }
		if (-not $inFriends) { $kept += $line }
	}
	$choice = Get-FriendsChoice
	if ($choice -match '^host\s+(\d+)$') { $kept += "[Friends]", "host_port=$($Matches[1])" }
	elseif ($choice -match '^join\s+(\S+)$') { $kept += "[Friends]", "join=$($Matches[1])" }
	Set-Content -Path $ini -Value $kept -Encoding ascii
}

if ($Friends -and -not (Show-FriendsDialog)) { return }
try { Update-PortalCraft } catch { Note "update check failed: $($_.Exception.Message)" }
try { Set-FriendsInIni } catch { Note "couldn't write the friends setting: $($_.Exception.Message)" }
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

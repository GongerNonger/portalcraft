# Prints the Steam Portal install folder (the one holding hl2.exe and portal\), or nothing.
# Looks in every Steam library listed in libraryfolders.vdf.
$steam = (Get-ItemProperty -Path "HKCU:\Software\Valve\Steam" -Name SteamPath -ErrorAction SilentlyContinue).SteamPath
$libraries = @()
if ($steam) {
	$libraries += $steam
	$vdf = Join-Path $steam "steamapps\libraryfolders.vdf"
	if (Test-Path $vdf) {
		foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) {
			$libraries += $m.Groups[1].Value -replace '\\\\', '\'
		}
	}
}
foreach ($lib in ($libraries | Select-Object -Unique)) {
	$candidate = Join-Path $lib "steamapps\common\Portal"
	if (Test-Path (Join-Path $candidate "portal\gameinfo.txt")) {
		return (Resolve-Path $candidate).Path
	}
}

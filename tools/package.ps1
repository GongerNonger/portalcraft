# Builds PortalCraft and packs a release for friends into dist\ (as SkyCraft's tools\package.ps1):
#   PortalCraft-<version>.zip   unzip anywhere, run "Install PortalCraft.cmd" (tools\release\install.ps1):
#     plugin\portalcraft.dll    the Portal plugin
#     minecraft\Prism\          a portable Prism Launcher with the PortalCraft instance (Minecraft 26.3,
#                               Fabric, Fabric API, PortalCraft); Prism asks the player to sign in once,
#                               then downloads Minecraft and Java itself
#     minecraft\world\          the void world
#   portalcraft-<version>.jar   the Minecraft mod on its own (for your own launcher)
#
#   powershell -ExecutionPolicy Bypass -File tools\package.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path -Parent $PSScriptRoot
$props = Get-Content (Join-Path $root "gradle.properties") -Raw
$version = [regex]::Match($props, '(?m)^version=(.+)$').Groups[1].Value.Trim()
$fabricApi = [regex]::Match($props, '(?m)^fabric_api_version=(.+)$').Groups[1].Value.Trim()

# Pinned downloads (checked against these hashes; the same files SkyCraft 0.1.2 ships).
$prismVersion = "11.1.1"
$prismZip = "PrismLauncher-Windows-MSVC-Portable-$prismVersion.zip"
$prismUrl = "https://github.com/PrismLauncher/PrismLauncher/releases/download/$prismVersion/$prismZip"
$prismSha256 = "ab35a770fb06d89d2ccc098079db5db329fb4e68f42b72babd8b095efde3d2d7"
$prismLicenseUrl = "https://raw.githubusercontent.com/PrismLauncher/PrismLauncher/$prismVersion/LICENSE"
$fabricApiJar = "fabric-api-0.161.0+26.3.jar"
$fabricApiUrl = "https://cdn.modrinth.com/data/P7dR8mSH/versions/bNnaTiuM/fabric-api-0.161.0%2B26.3.jar"
$fabricApiSha512 = "ed6b2586d6fde11fde8472f5a527c51e99b67026e46f94d4bfd85e7e28ce5ee299173ee16ad576ceb51f39f98d30a811086a6deb1a86a524859cc16e12da109d"
if ($fabricApiJar -ne "fabric-api-$fabricApi.jar") {
	throw "gradle.properties builds against Fabric API $fabricApi, but this script bundles ${fabricApiJar}: update the pin"
}

function Get-Pinned([string]$url, [string]$path, [string]$algorithm, [string]$hash) {
	if (-not (Test-Path $path)) {
		New-Item -ItemType Directory (Split-Path $path) -Force | Out-Null
		Invoke-WebRequest -Uri $url -OutFile $path -UseBasicParsing
	}
	if ($hash -and (Get-FileHash $path -Algorithm $algorithm).Hash -ne $hash.ToUpper()) {
		Remove-Item $path
		throw "$path doesn't match its pinned $algorithm hash"
	}
}

# Zip entries named with forward slashes, as the zip format expects (Windows PowerShell's own
# zipping writes backslashes).
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
function New-ZipFromFolder([string]$path, [string]$folder, [string]$prefix) {
	$zip = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
	try {
		$base = (Resolve-Path $folder).Path.TrimEnd('\') + '\'
		Get-ChildItem $folder -Recurse -File | Sort-Object FullName | ForEach-Object {
			$name = $prefix + $_.FullName.Substring($base.Length).Replace('\', '/')
			[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $name, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
		}
	} finally { $zip.Dispose() }
}

if (-not $NoBuild) {
	cmd /c (Join-Path $root "host\portal\build.cmd")
	if ($LASTEXITCODE -and -not (Test-Path (Join-Path $root "host\portal\build\portalcraft.dll"))) { throw "the Portal plugin didn't build" }
	cmd /c (Join-Path $root "gradle.cmd") build --no-configuration-cache
	if ($LASTEXITCODE) { throw "the Fabric mod didn't build" }
}
$dll = Join-Path $root "host\portal\build\portalcraft.dll"
$jar = Join-Path $root "build\libs\portalcraft-$version.jar"
foreach ($f in @($dll, $jar)) {
	if (-not (Test-Path $f)) { throw "missing $f (build first, or drop -NoBuild)" }
}

$cache = Join-Path $root ".tools\prism"
Get-Pinned $prismUrl (Join-Path $cache $prismZip) SHA256 $prismSha256
Get-Pinned $fabricApiUrl (Join-Path $cache $fabricApiJar) SHA512 $fabricApiSha512
Get-Pinned $prismLicenseUrl (Join-Path $cache "PrismLauncher-$prismVersion-LICENSE.txt") "" ""

$dist = Join-Path $root "dist"
New-Item -ItemType Directory $dist -Force | Out-Null
Get-ChildItem $dist | Remove-Item -Recurse -Force
$stage = Join-Path $dist "stage"
New-Item -ItemType Directory $stage -Force | Out-Null

# The installer and its notes.
Copy-Item (Join-Path $root "tools\release\*") $stage
Copy-Item (Join-Path $root "tools\find-portal.ps1") $stage
Copy-Item (Join-Path $root "THIRD-PARTY-NOTICES.md") $stage
Copy-Item (Join-Path $root "LICENSE") (Join-Path $stage "LICENSE.txt")
New-Item -ItemType Directory (Join-Path $stage "plugin") -Force | Out-Null
Copy-Item $dll (Join-Path $stage "plugin\portalcraft.dll")

# The bundled Minecraft: Prism (portable), the PortalCraft instance and its mods, Prism's defaults,
# the void world.
$mc = Join-Path $stage "minecraft"
Copy-Item -Recurse (Join-Path $root "tools\minecraft-bundle") $mc
Expand-Archive (Join-Path $cache $prismZip) (Join-Path $mc "Prism") -Force
Copy-Item (Join-Path $cache "PrismLauncher-$prismVersion-LICENSE.txt") (Join-Path $mc "Prism\LICENSE-PrismLauncher.txt")
$mods = Join-Path $mc "Prism\instances\PortalCraft\.minecraft\mods"
New-Item -ItemType Directory $mods -Force | Out-Null
Copy-Item (Join-Path $cache $fabricApiJar) $mods
Copy-Item $jar (Join-Path $mods "portalcraft-$version.jar")
# The voxel portal gun: a resource pack, switched on by the first install's options.txt.
$packs = Join-Path $mc "Prism\instances\PortalCraft\.minecraft\resourcepacks"
New-Item -ItemType Directory $packs -Force | Out-Null
Copy-Item -Recurse (Join-Path $root "packs\portalcraft-voxel-gun") $packs
# The whole seed world: level.dat and data\minecraft (without the generator settings and game rules
# in there Minecraft 26.3 refuses the world, "Overworld settings missing").
Copy-Item -Recurse (Join-Path $root "worlds\PortalCraft") (Join-Path $mc "world")
Set-Content (Join-Path $mc "bundle-version.txt") "PortalCraft $version, Prism Launcher $prismVersion, $fabricApiJar" -NoNewline

$zipPath = Join-Path $dist "PortalCraft-$version.zip"
New-ZipFromFolder $zipPath $stage "PortalCraft-$version/"
Remove-Item -Recurse -Force $stage
Copy-Item $jar (Join-Path $dist "portalcraft-$version.jar")

# PortalCraft-Setup.exe: the same zip inside a self-extracting program (Windows' own IExpress), so
# installing is one download and a double click. It unpacks the zip to a temporary folder and runs
# its install.ps1 in a window that stays open to say what happened.
$sfx = Join-Path $dist "sfx"
New-Item -ItemType Directory $sfx -Force | Out-Null
Copy-Item $zipPath (Join-Path $sfx "payload.zip")
Set-Content -Path (Join-Path $sfx "setup.cmd") -Encoding ascii -Value @(
	'@echo off',
	'title PortalCraft setup',
	'echo Installing PortalCraft...',
	'powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference=''Stop''; $w=Join-Path $env:TEMP ''PortalCraft-setup''; if (Test-Path $w) { Remove-Item -Recurse -Force $w }; Expand-Archive -Path ''%~dp0payload.zip'' -DestinationPath $w -Force; $i=Get-ChildItem $w -Recurse -Filter install.ps1 | Select-Object -First 1; & powershell -NoProfile -ExecutionPolicy Bypass -File $i.FullName; Remove-Item -Recurse -Force $w -ErrorAction SilentlyContinue"',
	'echo.',
	'pause'
)
$setupExe = Join-Path $dist "PortalCraft-Setup.exe"
$sed = Join-Path $sfx "setup.sed"
Set-Content -Path $sed -Encoding ascii -Value @(
	'[Version]', 'Class=IEXPRESS', 'SEDVersion=3',
	'[Options]', 'PackagePurpose=InstallApp', 'ShowInstallProgramWindow=1', 'HideExtractAnimation=1', 'UseLongFileName=1',
	'InsideCompressed=0', 'CAB_FixedSize=0', 'CAB_ResvCodeSigning=0', 'RebootMode=N', 'InstallPrompt=', 'DisplayLicense=',
	'FinishMessage=', "TargetName=$setupExe", "FriendlyName=PortalCraft $version setup", 'AppLaunched=cmd /c setup.cmd',
	'PostInstallCmd=<None>', 'AdminQuietInstCmd=', 'UserQuietInstCmd=', 'SourceFiles=SourceFiles',
	'[SourceFiles]', "SourceFiles0=$sfx\",
	'[SourceFiles0]', 'payload.zip=', 'setup.cmd='
)
& (Join-Path $env:SystemRoot "System32\iexpress.exe") /N /Q $sed | Out-Null
for ($i = 0; $i -lt 120 -and -not (Test-Path $setupExe); $i++) { Start-Sleep -Milliseconds 500 }
Remove-Item -Recurse -Force $sfx
if (-not (Test-Path $setupExe)) { throw "IExpress didn't write $setupExe" }
Get-ChildItem $dist | ForEach-Object { "{0,-32} {1,14:N0} bytes" -f $_.Name, $_.Length }

@echo off
rem Starts Steam Portal (with the PortalCraft plugin) and the PortalCraft Minecraft, linked.
rem Play in the Portal window; leave the Minecraft window open behind it.
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup.ps1 || exit /b 1
for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -File tools\find-portal.ps1`) do set "PORTAL=%%P"
if not defined PORTAL (
	echo Steam Portal not found.
	exit /b 1
)
set "PORTALCRAFT_MAPS=%PORTAL%\portal\maps"

tasklist /FI "IMAGENAME eq hl2.exe" | find /I "hl2.exe" >nul || start "" /D "%PORTAL%" "%PORTAL%\hl2.exe" -game portal -windowed -novid -w 1600 -h 900 -insecure +cl_updaterate 66 +cl_cmdrate 66 +cl_interp 0 +cl_interp_ratio 1
start "PortalCraft Minecraft" cmd /c gradle.cmd runClient --no-configuration-cache --args="--quickPlaySingleplayer PortalCraft"
echo Portal and Minecraft are starting. Click into the Portal window to play.

@echo off
rem Starts Steam Portal with the PortalCraft plugin. The plugin starts the PortalCraft Minecraft
rem itself, hidden (portal\addons\portalcraft.ini); it quits again when Portal closes.
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup.ps1 || exit /b 1
for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -File tools\find-portal.ps1`) do set "PORTAL=%%P"
if not defined PORTAL (
	echo Steam Portal not found.
	exit /b 1
)
set "PORTALCRAFT_MAPS=%PORTAL%\portal\maps"

tasklist /FI "IMAGENAME eq hl2.exe" | find /I "hl2.exe" >nul || start "" /D "%PORTAL%" "%PORTAL%\hl2.exe" -game portal -windowed -novid -w 1600 -h 900 -insecure +cl_updaterate 66 +cl_cmdrate 66 +cl_interp 0 +cl_interp_ratio 1 +mat_queue_mode 0 +engine_no_focus_sleep 0
echo Portal is starting; it starts Minecraft by itself (about a minute). Play in the Portal window.

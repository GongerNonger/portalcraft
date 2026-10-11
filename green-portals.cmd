@echo off
rem green-portals on|closed|open|off : both of Portal's portals as Rick and Morty's, a turning green spiral (on);
rem Portal's own swirl in green, not seen through (closed); green but seen through as usual (open);
rem or Portal's own blue and orange (off).
rem A recolour of Portal's own portal textures, made from this PC's Portal into Portal\portal\custom\pc-green-portals.
rem Portal reads that folder when it starts: close Portal first, or restart it after.
setlocal
cd /d "%~dp0"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -File tools\find-portal.ps1`) do set "PORTAL=%%P"
if not defined PORTAL (
	echo Steam Portal not found.
	exit /b 1
)
set "PACK=%PORTAL%\portal\custom\pc-green-portals"
if /i "%~1"=="open" set "MODE=open"
if /i "%~1"=="on" set "MODE=swirl"
if /i "%~1"=="closed" set "MODE=closed"
if defined MODE (
	if exist "%PACK%\materials\models\portals\portal-blue-color.vtf" rmdir /s /q "%PACK%"
	uv run -q --no-project --with vpk --with numpy --with srctools python tools\green-portals\make_green_portals.py "%PORTAL%" "%PACK%" %MODE% || exit /b 1
	echo Green portals are on. Restart Portal to see them.
	exit /b 0
)
if /i "%~1"=="off" (
	if exist "%PACK%\materials\models\portals\portal-blue-color.vtf" rmdir /s /q "%PACK%"
	echo Green portals are off. Restart Portal to get blue and orange back.
	exit /b 0
)
echo usage: green-portals on^|closed^|open^|off

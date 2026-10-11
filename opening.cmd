@echo off
rem opening on|off : chamber 00 opens with Steve dropping out of a green portal in the vault's ceiling (on),
rem or as Valve made it, waking up in the bed (off). Made from this PC's Portal (tools\intro\make_intro.py).
rem Portal reads it when it starts: close Portal first, or restart it after.
setlocal
cd /d "%~dp0"
for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -File tools\find-portal.ps1`) do set "PORTAL=%%P"
if not defined PORTAL (
	echo Steam Portal not found.
	exit /b 1
)
if /i not "%~1"=="on" if /i not "%~1"=="off" (
	echo usage: opening on^|off
	exit /b 1
)
uv run -q --no-project --with vpk --with numpy --with srctools python tools\intro\make_intro.py "%PORTAL%" %~1

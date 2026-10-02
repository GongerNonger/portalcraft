@echo off
rem Copies the plugin into Steam Portal's addons folder. Remove portal\addons\portalcraft.* to uninstall.
setlocal
set PORTAL=%~1
if "%PORTAL%"=="" set PORTAL=D:\SteamLibrary\steamapps\common\Portal
if not exist "%PORTAL%\portal\gameinfo.txt" (echo Portal not found at %PORTAL% & exit /b 1)
if not exist "%PORTAL%\portal\addons" mkdir "%PORTAL%\portal\addons"
copy /y "%~dp0build\portalcraft.dll" "%PORTAL%\portal\addons\portalcraft.dll" >nul || exit /b 1
> "%PORTAL%\portal\addons\portalcraft.vdf" (
	echo "Plugin"
	echo {
	echo 	"file"	"addons/portalcraft"
	echo }
)
rem The plugin starts Minecraft with Portal (src\launcher.cpp). Kept if it's already there: edit it to
rem change what starts, or set start_with_portal=0 to start Minecraft yourself.
for %%R in ("%~dp0..\..") do set "REPO=%%~fR"
set "INI=%PORTAL%\portal\addons\portalcraft.ini"
if not exist "%INI%" (
	> "%INI%" (
		echo [Minecraft]
		echo start_with_portal=1
		echo launcher=%REPO%\gradle.cmd
		echo arguments=runClient --no-configuration-cache --args="--quickPlaySingleplayer PortalCraft"
		echo directory=%REPO%
	)
	echo wrote %INI%
)
echo installed to %PORTAL%\portal\addons

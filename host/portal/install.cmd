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
echo installed to %PORTAL%\portal\addons

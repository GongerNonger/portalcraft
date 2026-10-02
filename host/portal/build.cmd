@echo off
rem Builds portalcraft.dll (Win32, static CRT) into host\portal\build\.
setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x86 >nul || exit /b 1
cd /d "%~dp0"
if not exist build mkdir build
cl /nologo /O2 /MT /EHsc /std:c++17 /W3 /D_CRT_SECURE_NO_WARNINGS /LD /Fobuild\ /Febuild\portalcraft.dll src\plugin.cpp src\overlay.cpp ws2_32.lib user32.lib d3d9.lib || exit /b 1
echo built %~dp0build\portalcraft.dll

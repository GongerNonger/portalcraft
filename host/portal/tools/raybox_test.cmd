@echo off
rem Builds and runs tools\raybox_test.cpp (needs VS 2022 Build Tools).
setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x86 >nul
cd /d "%~dp0"
if not exist "%TEMP%\portalcraft-raybox" mkdir "%TEMP%\portalcraft-raybox"
cl /nologo /EHsc /std:c++17 /Fo"%TEMP%\portalcraft-raybox\\" /Fe"%TEMP%\portalcraft-raybox\raybox_test.exe" raybox_test.cpp >nul || exit /b 1
"%TEMP%\portalcraft-raybox\raybox_test.exe"

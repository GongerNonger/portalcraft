@echo off
rem gradlew with the repo's portable JDK 25 (run tools\setup.ps1 once first). Example: gradle build
setlocal
for /d %%J in ("%~dp0.jdk\jdk-25*") do set "JAVA_HOME=%%~fJ"
if not defined JAVA_HOME (
	echo No JDK in .jdk - run: powershell -ExecutionPolicy Bypass -File tools\setup.ps1
	exit /b 1
)
call "%~dp0gradlew.bat" %*

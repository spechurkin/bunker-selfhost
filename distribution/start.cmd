@echo off
setlocal
java "-Djdk.net.unixdomain.tmpdir=%~dp0." -jar "%~dp0bunker-server.jar" %*
set "BUNKER_EXIT_CODE=%ERRORLEVEL%"
if not "%BUNKER_EXIT_CODE%"=="0" pause
exit /b %BUNKER_EXIT_CODE%

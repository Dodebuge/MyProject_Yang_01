@echo off
rem Run the Spring Boot version on Windows. First run downloads Gradle and dependencies (1-2 min).
rem   run.cmd                   -> http://localhost:8080
rem   set PORT=8000 ^& run.cmd  -> other port
rem .env (appKey/appSecret) is read from the repository root unless KB_DATA_DIR is set.
setlocal
if not defined KB_DATA_DIR set "KB_DATA_DIR=%~dp0.."
rem Full paths: cmd may not search the current folder (NoDefaultCurrentDirectoryInExePath).
call "%~dp0gradlew.bat" -q -p "%~dp0." bootJar || exit /b 1
java -jar "%~dp0build\libs\kb-openapi-spring.jar"

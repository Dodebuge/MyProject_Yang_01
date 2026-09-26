@echo off
rem Run the Spring Boot version on Windows. First run downloads Maven and dependencies (1-2 min).
rem   run.cmd                   -> http://localhost:8080
rem   set PORT=8000 ^& run.cmd  -> other port
rem .env (appKey/appSecret) is read from the repository root unless KB_DATA_DIR is set.
setlocal
if not defined KB_DATA_DIR set "KB_DATA_DIR=%~dp0.."
rem Full paths: cmd may not search the current folder (NoDefaultCurrentDirectoryInExePath).
call "%~dp0mvnw.cmd" -q -B -DskipTests -f "%~dp0pom.xml" package || exit /b 1
java -jar "%~dp0target\kb-openapi-spring.jar"

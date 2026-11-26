@echo off
rem Thin launcher (designs/stage-1-foundation.md 4.5): build tools quietly, then run "tools selftest".
setlocal
cd /d "%~dp0.." || exit /b 2
if not exist build\check mkdir build\check
call gradlew.bat -q :tools:installDist > build\check\tools-build.log 2>&1
if errorlevel 1 (
  echo CHECK FAIL tools did not build; see build/check/tools-build.log
  exit /b 2
)
call tools\build\install\tools\bin\tools.bat selftest %*
exit /b %ERRORLEVEL%

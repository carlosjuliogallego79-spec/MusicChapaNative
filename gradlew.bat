@echo off
REM Gradle wrapper placeholder
where gradle >nul 2>nul
if %ERRORLEVEL% equ 0 (
  gradle %*
) else (
  echo Gradle not found. Install Gradle or use Android Studio.
  exit /b 1
)

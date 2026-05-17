@echo off
setlocal enabledelayedexpansion
title DeCloud Builder
cd /d "%~dp0"

echo.
echo ================================================
echo   DeCloud Desktop - Electron Build
echo ================================================
echo.
echo Current directory: %CD%
echo.

:: Check Node.js
echo Checking for Node.js...
where node >nul 2>nul
if !ERRORLEVEL! NEQ 0 (
    echo.
    echo ========================================
    echo   ERROR: Node.js is NOT installed!
    echo ========================================
    echo.
    echo Please download and install Node.js from:
    echo   https://nodejs.org/
    echo.
    echo Choose the LTS version and install it.
    echo Then run this script again.
    echo.
    goto :end
)

echo [OK] Node.js found:
call node --version
echo.

:: Check npm
echo Checking for npm...
where npm >nul 2>nul
if !ERRORLEVEL! NEQ 0 (
    echo [ERROR] npm not found!
    goto :end
)
echo [OK] npm found
echo.

:: Install dependencies
if not exist "node_modules\electron" (
    echo ================================================
    echo   Installing dependencies...
    echo   This may take 2-5 minutes on first run.
    echo ================================================
    echo.

    :: Remove incomplete node_modules if exists
    if exist "node_modules" (
        echo Cleaning incomplete installation...
        rmdir /s /q node_modules 2>nul
    )

    call npm install
    if !ERRORLEVEL! NEQ 0 (
        echo.
        echo [ERROR] Failed to install dependencies!
        echo Check your internet connection.
        goto :end
    )
    echo.
    echo [OK] Dependencies installed
    echo.
)

echo ================================================
echo   Building DeCloud.exe ...
echo   Please wait...
echo ================================================
echo.

:: Build portable exe
call npm run build:portable

if !ERRORLEVEL! EQU 0 (
    echo.
    echo ================================================
    echo   BUILD SUCCESSFUL!
    echo ================================================
    echo.
    echo Your executable is ready:
    echo   %CD%\dist\DeCloud-Portable.exe
    echo.
    echo This is a standalone file - just double-click to run!
    echo.
    if exist "dist" start "" explorer "dist"
) else (
    echo.
    echo ================================================
    echo   BUILD FAILED
    echo ================================================
    echo.
    echo Check the error messages above.
    echo Common issues:
    echo   - No internet connection
    echo   - Antivirus blocking
    echo   - Missing permissions
)

:end
echo.
echo Press any key to close this window...
pause >nul

@echo off
echo.
echo ========================================
echo   DeCloud - System Check
echo ========================================
echo.

echo Checking Node.js...
where node
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [X] Node.js is NOT installed!
    echo.
    echo You need to install Node.js first:
    echo   1. Go to https://nodejs.org/
    echo   2. Download the LTS version
    echo   3. Install it (check "Add to PATH")
    echo   4. Restart your computer
    echo   5. Run this script again
    echo.
) else (
    echo [OK] Node.js is installed
    node --version
    echo.
    echo Checking npm...
    where npm
    if %ERRORLEVEL% NEQ 0 (
        echo [X] npm not found
    ) else (
        echo [OK] npm is installed
        call npm --version
    )
)

echo.
echo ========================================
echo.
pause

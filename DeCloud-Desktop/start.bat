@echo off
cd /d "%~dp0"

:: Install if needed
if not exist "node_modules" (
    echo Installing dependencies...
    call npm install
)

:: Run the app
npm start

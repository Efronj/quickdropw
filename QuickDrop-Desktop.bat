@echo off
title QuickDrop Desktop
cd /d "%~dp0desktop"

where node >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Node.js is not found in PATH!
    echo Please install Node.js from https://nodejs.org/
    pause
    exit /b 1
)

echo Starting QuickDrop Desktop...
node server.js
pause

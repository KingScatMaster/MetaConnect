@echo off
title MetaConnect — Ray-Ban Meta Glasses Server
color 0B

echo ==================================================
echo   MetaConnect — Ray-Ban Meta Glasses Server
echo ==================================================
echo.

:: Get local IP for display
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4" ^| findstr /v "127.0.0.1"') do (
    set LOCAL_IP=%%a
)
set LOCAL_IP=%LOCAL_IP: =%

echo   IMPORTANT: Start Claude AI Assistant FIRST!
echo   (Run START.bat in Claude AI Assistant folder)
echo.
echo   Then open this URL on your glasses:
echo   http://%LOCAL_IP%:5000
echo.
echo ==================================================
echo.

:: Check if bridge is reachable
echo   Checking bridge server...
python -c "import asyncio, websockets; asyncio.run(websockets.connect('ws://localhost:8765'))" 2>nul
if %errorlevel% neq 0 (
    echo   [WARNING] Bridge server not detected on port 8765
    echo   Start Claude AI Assistant bridge first for full functionality.
    echo.
) else (
    echo   [OK] Bridge server detected!
    echo.
)

:: Start glasses server
echo   Starting MetaConnect server on port 5000...
echo.
python "%~dp0server.py"

pause

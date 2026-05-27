@echo off
title MetaConnect Control Panel
color 0B
cd /d "%~dp0"

echo.
echo  ==================================================
echo   MetaConnect - Control Panel
echo  ==================================================
echo.

:: Get local IP
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4" ^| findstr /v "127.0.0.1"') do (
    set LOCAL_IP=%%a
)
set LOCAL_IP=%LOCAL_IP: =%

:: Step 1: Start Ollama
echo   [1/4] Starting Ollama...
tasklist /FI "IMAGENAME eq ollama.exe" | findstr /i ollama >nul 2>&1
if %errorlevel% neq 0 (
    start "" /B ollama serve >nul 2>&1
    timeout /t 3 >nul
)
echo         [OK] Ollama

:: Step 2: Start Bridge
echo   [2/4] Starting Bridge Server...
start "" /B python "C:\Users\KingScatMaster\Desktop\Claude AI Assistant\bridge\bridge_server.py" >nul 2>&1
timeout /t 3 >nul
echo         [OK] Bridge on port 8765

:: Step 3: Start ngrok
echo   [3/4] Starting ngrok tunnel...
tasklist /FI "IMAGENAME eq ngrok.exe" | findstr /i ngrok >nul 2>&1
if %errorlevel% neq 0 (
    start "" /B ngrok http 3000 >nul 2>&1
    timeout /t 5 >nul
)
echo         [OK] ngrok tunnel

:: Step 4: Start MetaConnect
echo   [4/4] Starting MetaConnect server...
echo.
echo  ==================================================
echo.
echo   LOCAL:  http://%LOCAL_IP%:3000
echo   PUBLIC: https://livestock-avatar-late.ngrok-free.dev
echo.
echo   Use the PUBLIC url in the phone app or glasses.
echo.
echo  ==================================================
echo   CLOSE THIS WINDOW TO STOP EVERYTHING
echo  ==================================================
echo.

python "%~dp0server.py"

:: Cleanup when window is closed
echo.
echo   Shutting down...
taskkill /IM ngrok.exe /F >nul 2>&1
echo   [OK] Stopped

pause

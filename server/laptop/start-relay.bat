@echo off
rem Double-click to run the Rider Intercom relay on this PC.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-relay.ps1"
pause

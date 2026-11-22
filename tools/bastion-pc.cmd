@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bastion-pc.ps1" %*
pause

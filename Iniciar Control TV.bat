@echo off
title Control TV
cd /d "%~dp0"
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" servidor.py
pause

@echo off
setlocal
set "ROOT=%~dp0"

start "CHZZK Collector" /B node "%ROOT%collector\collector.mjs"
pushd "%ROOT%dashboard"
start "CHZZK Dashboard" /B npm run dev
popd
timeout /t 3 /nobreak >nul
start "" "http://localhost:3000"

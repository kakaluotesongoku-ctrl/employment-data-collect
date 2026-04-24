@echo off
setlocal
pushd "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-service.ps1" %*
popd
endlocal
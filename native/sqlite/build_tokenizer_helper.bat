@echo off
setlocal
set "SOURCE=%~1"
set "INCLUDE_DIR=%~2"
set "OUTPUT=%~3"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
  echo Visual Studio locator not found: "%VSWHERE%" 1>&2
  exit /b 1
)
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -property installationPath`) do set "VSROOT=%%i"
if not defined VSROOT (
  echo Visual Studio installation not found 1>&2
  exit /b 1
)
call "%VSROOT%\Common7\Tools\VsDevCmd.bat" -arch=amd64
if errorlevel 1 exit /b %errorlevel%
cl /nologo /LD /I "%INCLUDE_DIR%" "%SOURCE%" /link /OUT:"%OUTPUT%"
exit /b %errorlevel%

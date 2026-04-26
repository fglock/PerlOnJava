@echo off
rem jcpan - CPAN Client for PerlOnJava (Windows wrapper)
rem Runs the standard cpan script with jperl
rem Supports --jobs N for parallel test execution:
rem   jcpan --jobs 4 -t Module::Name
set SCRIPT_DIR=%~dp0

rem Parse --jobs option for parallel test jobs
set "JCPAN_ARGS="
:parse_args
if "%~1"=="" goto run
if "%~1"=="--jobs" (
    set "HARNESS_OPTIONS=j%~2"
    shift
    shift
    goto parse_args
)
set "JCPAN_ARGS=%JCPAN_ARGS% %1"
shift
goto parse_args

:run
rem Set default per-test timeout (300s) to kill hanging tests
if not defined JPERL_TEST_TIMEOUT set "JPERL_TEST_TIMEOUT=300"
rem Expose jperl launcher for distroprefs (e.g. Moose.yml) to use as
rem prove --exec "%JPERL_BIN%".
set "JPERL_BIN=%SCRIPT_DIR%jperl.bat"
"%SCRIPT_DIR%jperl.bat" "%SCRIPT_DIR%src\main\perl\bin\cpan" %JCPAN_ARGS%

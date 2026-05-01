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
rem Enable orphan-exit watchdog in every jperl this run spawns — when
rem the parent jcpan dies, each child JVM self-exits within ~4s instead
rem of getting reparented to PID 1 and burning 100% CPU forever.
set "JPERL_ORPHAN_EXIT=1"
rem Expose jperl and jcpan launchers, and prepend SCRIPT_DIR to PATH so
rem shell-spawned subprocesses (distroprefs commandlines, prove --exec,
rem etc.) can find jperl/jcpan without tokens that don't expand in
rem POSIX sh. See src/main/perl/lib/CPAN/Config.pm (Moose.yml).
set "JPERL_BIN=%SCRIPT_DIR%jperl.bat"
set "JCPAN_BIN=%SCRIPT_DIR%jcpan.bat"
set "PATH=%SCRIPT_DIR%;%PATH%"
"%SCRIPT_DIR%jperl.bat" "%SCRIPT_DIR%src\main\perl\bin\cpan" %JCPAN_ARGS%

@echo off
rem jprove - Test Harness for PerlOnJava (Windows wrapper)
rem Runs the standard prove script with jperl
rem Repository: github.com/fglock/PerlOnJava

rem Get the directory where this script is located
set SCRIPT_DIR=%~dp0

rem Enable orphan-exit watchdog in every jperl this run spawns — when
rem the parent jprove dies, each child JVM self-exits within ~4s.
set "JPERL_ORPHAN_EXIT=1"

rem Run jperl with the prove script
call "%SCRIPT_DIR%jperl.bat" "%SCRIPT_DIR%src\main\perl\bin\prove" %*
